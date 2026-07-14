[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $Token,
    [int] $Port = 37828,
    [ValidateSet('main-menu', 'world', 'focus-baseline', 'focus-readback', 'shutdown')]
    [string] $Stage = 'main-menu',
    [string] $BaselinePath,
    [string] $EvidenceDirectory = (Join-Path $PSScriptRoot 'evidence'),
    [string] $LodestoneArtifact = (Join-Path $PSScriptRoot '..\hosts\neoforge\1.21.1\build\libs\lodestone-1.0.0.jar'),
    [string] $KeepFocusArtifact = (Join-Path $PSScriptRoot '..\..\KeepFocus\build\libs\keep_focus-1.0.0+1.21.1.jar'),
    [string] $ClientRunDirectory = (Join-Path $PSScriptRoot 'neoforge-artifact-client\run')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$reportPath = Join-Path $EvidenceDirectory "neoforge-keepfocus-flow-$Stage-$timestamp.json"
$script:requestId = 0
$script:sessionId = $null
$script:client = [System.Net.Http.HttpClient]::new()
$uri = "http://127.0.0.1:$Port/mcp"
$report = [ordered]@{
    formatVersion = 1
    benchmark = 'neoforge-1.21.1-keepfocus-flow'
    stage = $Stage
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    endpoint = $uri
    records = @()
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$artifactRows = foreach ($artifactPath in @($LodestoneArtifact, $KeepFocusArtifact)) {
    $resolved = (Resolve-Path -LiteralPath $artifactPath -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved
    [ordered]@{ path = $resolved; bytes = [int64] $item.Length; sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$report.provenance = [ordered]@{
    sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
    sourceDirtyPaths = @(& git -C $repoRoot status --short | Where-Object { $_ -notmatch 'verification/evidence/' -and $_ -notmatch 'run-artifact-client/' })
    lodestoneArtifact = $artifactRows[0]
    keepFocusArtifact = $artifactRows[1]
}

trap {
    $report.failure = [ordered]@{ message = $_.Exception.Message; stack = $_.ScriptStackTrace }
    $report.completedAtUtc = [DateTime]::UtcNow.ToString('o')
    $report | ConvertTo-Json -Depth 64 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    throw
}

function Invoke-Rpc {
    param([string] $Method, [System.Collections.IDictionary] $Params = @{})
    $script:requestId++
    $body = @{ jsonrpc = '2.0'; id = $script:requestId; method = $Method; params = $Params } |
        ConvertTo-Json -Depth 64 -Compress
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
    $message.Headers.Add('X-Lodestone-Token', $Token)
    if ($script:sessionId) {
        $message.Headers.Add('Mcp-Session-Id', $script:sessionId)
        $message.Headers.Add('MCP-Protocol-Version', '2025-11-25')
    }
    $message.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
    $response = $script:client.SendAsync($message).Result
    if ($response.Headers.Contains('Mcp-Session-Id')) {
        $script:sessionId = ($response.Headers.GetValues('Mcp-Session-Id') -join '')
    }
    $content = $response.Content.ReadAsStringAsync().Result
    [PSCustomObject]@{
        httpStatus = [int] $response.StatusCode
        json = if ($content) { $content | ConvertFrom-Json } else { $null }
    }
}

function Get-Envelope {
    param($Rpc)
    if ($Rpc.json.error) { return [ordered]@{ status = 'rpc-error'; error = $Rpc.json.error } }
    $structured = $Rpc.json.result.structuredContent
    if ($null -ne $structured) {
        if ($structured.status) {
            return [ordered]@{ status = [string] $structured.status; result = $structured.output; error = $structured.error }
        }
        return [ordered]@{ status = 'ok'; result = $structured; error = $null }
    }
    $content = @($Rpc.json.result.content)
    if ($content.Count -eq 0 -or $content[0].type -ne 'text') {
        return [ordered]@{ status = 'empty-response' }
    }
    try {
        $parsed = $content[0].text | ConvertFrom-Json
        if ($parsed.status) {
            return [ordered]@{ status = [string] $parsed.status; result = $parsed.output; error = $parsed.error }
        }
        return [ordered]@{ status = 'ok'; result = $parsed; error = $null }
    } catch {
        return [ordered]@{ status = 'non-json-response'; text = [string] $content[0].text }
    }
}

function Invoke-RpcWithRateRetry {
    param([string] $Method, [System.Collections.IDictionary] $Params = @{})
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $rpc = Invoke-Rpc $Method $Params
        $envelope = Get-Envelope $rpc
        if ($envelope.error.code -ne 'RATE_LIMIT_EXCEEDED') { return $rpc }
        Start-Sleep -Milliseconds 650
    }
    return $rpc
}

function Add-Record {
    param([string] $Operation, [System.Collections.IDictionary] $InvocationInput, $Rpc, [string] $Expectation = '',
          [string[]] $ExpectedErrorCodes = @(), [string[]] $ExpectedStatuses = @('ok'))
    $envelope = Get-Envelope $Rpc
    $errorCode = if ($envelope.error) { [string] $envelope.error.code } else { $null }
    $asserted = ($ExpectedStatuses -contains [string] $envelope.status) -or ($errorCode -and $ExpectedErrorCodes -contains $errorCode)
    $null = $report.records += [ordered]@{
        operation = $Operation
        input = $InvocationInput
        expectation = $Expectation
        expectedStatuses = @($ExpectedStatuses)
        expectedErrorCodes = @($ExpectedErrorCodes)
        asserted = [bool] $asserted
        httpStatus = $Rpc.httpStatus
        status = [string] $envelope.status
        result = $envelope.result
        error = $envelope.error
    }
    if (-not $asserted) {
        throw "Unexpected benchmark outcome for $Operation`: status=$($envelope.status), errorCode=$errorCode, expectation=$Expectation"
    }
    return $envelope
}

function Invoke-Tool {
    param([string] $Name, [System.Collections.IDictionary] $Arguments = @{}, [string] $Expectation = '',
          [string[]] $ExpectedErrorCodes = @(), [string[]] $ExpectedStatuses = @('ok'))
    $rpc = Invoke-RpcWithRateRetry 'tools/call' @{ name = $Name; arguments = $Arguments }
    return Add-Record "tool:$Name" $Arguments $rpc $Expectation $ExpectedErrorCodes $ExpectedStatuses
}

function Invoke-Capability {
    param([string] $Id, [string] $Version, [System.Collections.IDictionary] $CapabilityInput = @{}, [bool] $DryRun = $true,
          [string] $Expectation = '', [string[]] $ExpectedErrorCodes = @(), [string[]] $ExpectedStatuses = @('ok'))
    $arguments = @{ capability = $Id; capabilityVersion = $Version; input = $CapabilityInput; dryRun = $DryRun }
    $rpc = Invoke-RpcWithRateRetry 'tools/call' @{ name = 'lodestone_capability_invoke'; arguments = $arguments }
    return Add-Record "capability:$Id@$Version" $arguments $rpc $Expectation $ExpectedErrorCodes $ExpectedStatuses
}

function Get-UiState {
    $envelope = Invoke-Tool 'ui_state' @{} 'live UI snapshot'
    if ($envelope.status -ne 'ok') { throw "ui_state failed: $($envelope.error.code)" }
    return $envelope.result
}

function Wait-For {
    param([string] $Until, [int] $TimeoutMs = 30000)
    return Invoke-Tool 'ui_wait' @{ until = $Until; timeoutMs = $TimeoutMs; pollIntervalMs = 100 } "wait for $Until"
}

function Press-Escape {
    Invoke-Capability 'minecraft.ui.key' '1.0' @{ key = 256; scanCode = 0; modifiers = 0 } $false 'escape current UI screen' | Out-Null
    Start-Sleep -Milliseconds 350
}

function Navigate-Back {
    param([string] $Target, [string[]] $ExpectedErrorCodes = @())
    $navigation = Invoke-Tool 'ui_navigate' @{ target = $Target } "navigate $Target" $ExpectedErrorCodes
    if ($navigation.status -ne 'ok') { return }
    Start-Sleep -Milliseconds 400
    Get-UiState | Out-Null
    Press-Escape
    Get-UiState | Out-Null
}

function Invoke-UiClick {
    param($State, [System.Collections.IDictionary] $Selector, [string] $Expectation)
    $input = [ordered]@{
        screenToken = [string] $State.screenToken
        snapshotRevision = [string] $State.snapshotRevision
    }
    foreach ($entry in $Selector.GetEnumerator()) { $input[$entry.Key] = $entry.Value }
    return Invoke-Capability 'minecraft.ui.click' '2.0' $input $false $Expectation
}

function Get-TitleWidget {
    param($State, [string] $Label)
    $widget = @($State.widgets | Where-Object { $_.label -eq $Label -and $_.actions -contains 'click' }) | Select-Object -First 1
    if (-not $widget) {
        $labels = @($State.widgets | ForEach-Object { [string] $_.label } | Where-Object { $_ }) -join ' | '
        throw "title widget not found: $Label; current screen=$($State.screenClass); labels=$labels"
    }
    return $widget
}

function Invoke-ExpectedUnavailableCoverage {
    param($Capabilities)
    foreach ($capability in $Capabilities) {
        $input = [ordered]@{}
        # This invokes every negotiated capability once under dry-run. Detailed option coverage is exercised
        # by its real-state flow below; this pass supplies explicit evidence for unavailable/degraded rows.
        if ($capability.id -eq 'lodestone.ui.wait') { $input.until = 'screen_open'; $input.timeoutMs = 1; $input.pollIntervalMs = 100 }
        Invoke-Capability $capability.id $capability.version $input $true 'discovery dry-run; unavailable states are expected where contextual prerequisites are absent' @('CAPABILITY_UNAVAILABLE', 'DRY_RUN_UNSUPPORTED', '-32602') | Out-Null
    }
}

$initialize = Invoke-Rpc 'initialize' @{ protocolVersion = '2025-11-25'; clientInfo = @{ name = 'lodestone-flow-benchmark'; version = '1.0.0' } }
if ($initialize.httpStatus -ne 200 -or -not $script:sessionId) { throw 'Authenticated MCP initialize failed.' }
$null = Invoke-Rpc 'notifications/initialized' @{}
$report.sessionId = $script:sessionId

switch ($Stage) {
    'main-menu' {
        Invoke-Tool 'lodestone_status' @{} 'authenticated MCP status' | Out-Null
        $capabilities = (Invoke-Tool 'lodestone_capabilities_list' @{} 'typed capability discovery').result.capabilities
        $report.capabilities = @($capabilities | ForEach-Object {
            [ordered]@{ id = $_.id; version = $_.version; availability = $_.availability; reason = $_.reason }
        })
        # capabilities/list is authoritative typed descriptor coverage. Read a bounded sample
        # through the separate descriptor tool; avoid flooding its own declared rate bucket.
        foreach ($capability in @($capabilities | Select-Object -First 3)) {
            Invoke-Tool 'lodestone_capability_get' @{ capability = $capability.id } 'descriptor readback' | Out-Null
        }
        Invoke-Tool 'lodestone_capability_search' @{ query = 'ui' } 'capability search query' | Out-Null
        Invoke-Tool 'get_server_info' @{} 'disconnected client is an expected server-info state' @('CAPABILITY_UNAVAILABLE') | Out-Null
        Invoke-Tool 'search_minecraft_item' @{ query = 'diamond'; limit = 3 } 'registry search' | Out-Null
        Invoke-Tool 'capture_screenshot' @{ maxWidth = 320; maxHeight = 180 } 'title-screen capture' | Out-Null

        $subscription = Invoke-Tool 'lodestone_events_subscribe' @{ eventPrefix = 'minecraft.ui.'; bufferLimit = 32 } 'UI event subscription'
        $subscriptionId = $subscription.result.id
        if ([string]::IsNullOrWhiteSpace([string] $subscriptionId)) { throw 'Event subscription returned no subscription id.' }
        Invoke-ExpectedUnavailableCoverage $capabilities

        # All menu navigation targets that return safely to title. Contextual targets are covered later.
        # Multiplayer's first-run warning is intentionally deferred: its warning screen has no ordinary
        # title-menu back target, so forcing Escape would distort a stateful benchmark.
        foreach ($target in @('mods', 'options', 'language', 'accessibility', 'singleplayer')) {
            Navigate-Back $target
        }
        # Vanilla menu builds may omit Credits. Keep that explicit adapter absence
        # in evidence rather than allowing it to look like a green navigation step.
        Navigate-Back 'credits' @('ADAPTER_FAILURE')

        # Exercise each guarded UI click selector against the same harmless Options button, returning after each.
        foreach ($selectorKind in @('nodeId', 'path', 'label', 'coordinates')) {
            $state = Get-UiState
            $options = Get-TitleWidget $state 'Options...'
            switch ($selectorKind) {
                'nodeId' { $selector = @{ nodeId = [string] $options.nodeId } }
                'path' { $selector = @{ path = @($options.path) } }
                'label' { $selector = @{ label = 'Options...' } }
                default { $selector = @{ x = [double] $options.bounds.x + ([double] $options.bounds.width / 2); y = [double] $options.bounds.y + ([double] $options.bounds.height / 2) } }
            }
            Invoke-UiClick $state $selector "guarded UI click selector: $selectorKind" | Out-Null
            Start-Sleep -Milliseconds 300
            Press-Escape
        }

        # Both key and mouse input shapes are tested at title, followed by mandatory release-all cleanup.
        Invoke-Capability 'minecraft.input.key.set' '1.0' @{ key = 'key.forward'; down = $true } $false 'key down' | Out-Null
        Invoke-Capability 'minecraft.input.key.set' '1.0' @{ key = 'key.forward'; down = $false } $false 'key up' | Out-Null
        Invoke-Capability 'minecraft.input.mouse.set' '1.0' @{ button = 0; down = $true } $false 'mouse button selector' | Out-Null
        Invoke-Capability 'minecraft.input.mouse.set' '1.0' @{ button = 0; down = $false } $false 'mouse release selector' | Out-Null
        Invoke-Capability 'minecraft.input.mouse.set' '1.0' @{ key = 'key.attack'; down = $false } $false 'mouse key selector' | Out-Null
        Invoke-Capability 'minecraft.input.release-all' '1.0' @{} $false 'mandatory input cleanup' | Out-Null

        # Fresh-world path: singleplayer -> creation screen -> text insertion -> actual Create New World.
        Invoke-Tool 'ui_navigate' @{ target = 'singleplayer' } 'open singleplayer' | Out-Null
        Start-Sleep -Milliseconds 400
        Invoke-Tool 'ui_navigate' @{ target = 'create_new_world' } 'open new-world screen' | Out-Null
        Start-Sleep -Milliseconds 400
        Get-UiState | Out-Null
        Invoke-Capability 'minecraft.ui.text.insert' '1.0' @{ text = 'Lodestone KeepFocus Flow' } $false 'insert fresh-world name text' | Out-Null
        Invoke-Tool 'ui_navigate' @{ target = 'create_world' } 'create fresh world' | Out-Null
        Wait-For 'in_world' 60000 | Out-Null
        Invoke-Tool 'lodestone_events_poll' @{ subscriptionId = $subscriptionId; maxEvents = 32 } 'UI event readback' | Out-Null
        Invoke-Tool 'lodestone_events_unsubscribe' @{ subscriptionId = $subscriptionId } 'event cleanup' | Out-Null
    }
    'world' {
        Wait-For 'in_world' 5000 | Out-Null
        $playerBefore = (Invoke-Capability 'minecraft.player.state.read' '1.0' @{} $false 'player-state baseline').result
        Invoke-Capability 'minecraft.player.context.read' '1.0' @{ reach = 4 } $false 'player context' | Out-Null
        Invoke-Capability 'minecraft.server.info.read' '1.0' @{} $false 'integrated-server info' | Out-Null
        Invoke-Capability 'minecraft.registry.item.search' '1.0' @{ query = 'diamond'; namespace = 'minecraft'; limit = 3 } $false 'registry read' | Out-Null
        Invoke-Capability 'minecraft.inventory.read' '1.0' @{} $false 'inventory read' | Out-Null
        Invoke-Capability 'minecraft.entity.list' '1.0' @{ limit = 8; includePlayers = $true } $false 'entity list' | Out-Null
        Invoke-Capability 'minecraft.entity.nearby.read' '1.0' @{ radius = 8; limit = 8; includePlayers = $true } $false 'nearby entities' | Out-Null

        $x = [math]::Floor([double] $playerBefore.position.x)
        $y = [math]::Floor([double] $playerBefore.position.y)
        $z = [math]::Floor([double] $playerBefore.position.z)
        Invoke-Capability 'minecraft.world.heightmap.read' '1.0' @{ x = $x; z = $z; sizeX = 2; sizeZ = 2; includeSurfaceBlocks = $true } $false 'heightmap read' | Out-Null
        Invoke-Capability 'minecraft.world.light.analyze' '1.0' @{ x = $x; y = $y; z = $z; sizeX = 2; sizeY = 2; sizeZ = 2; resolution = 1; darkSpotLimit = 4; lightSourceLimit = 4 } $false 'light analysis' | Out-Null
        Invoke-Capability 'minecraft.world.blocks.read' '1.0' @{ dimension = 'minecraft:overworld'; x = $x; y = $y + 3; z = $z; sizeX = 1; sizeY = 1; sizeZ = 1 } $false 'block baseline read' | Out-Null

        $mutationY = $y + 4
        $mutationBaseline = Invoke-Capability 'minecraft.world.blocks.read' '1.0' @{ dimension = 'minecraft:overworld'; x = $x; y = $mutationY; z = $z; sizeX = 1; sizeY = 1; sizeZ = 1 } $false 'mutation target baseline read'
        if ($mutationBaseline.status -ne 'ok' -or $mutationBaseline.result.blocks[0].block -ne 'minecraft:air') {
            throw "Refusing mutation: target ($x,$mutationY,$z) baseline is not known air."
        }

        # The only real world mutation is a known-air block above the player. It is dry-run validated,
        # written, read back, restored to air, and read back again.
        $mutation = @{ dimension = 'minecraft:overworld'; changes = @(@{ x = $x; y = $mutationY; z = $z; block = 'minecraft:gold_block' }); dryRun = $true }
        $dryRunWrite = Invoke-Capability 'minecraft.world.blocks.write' '1.0' $mutation $true 'validate reversible mutation'
        if (-not $dryRunWrite.result.validated -or -not $dryRunWrite.result.dryRun) { throw 'Dry-run block write invariant failed.' }
        $mutation.dryRun = $false
        $write = Invoke-Capability 'minecraft.world.blocks.write' '1.0' $mutation $false 'write marker block'
        if (-not $write.result.validated -or $write.result.dryRun -or [int] $write.result.changedCount -ne 1) { throw 'Real block write invariant failed.' }
        $marker = Invoke-Capability 'minecraft.world.blocks.read' '1.0' @{ dimension = 'minecraft:overworld'; x = $x; y = $mutationY; z = $z; sizeX = 1; sizeY = 1; sizeZ = 1 } $false 'marker readback'
        if ($marker.result.blocks[0].block -ne 'minecraft:gold_block') { throw 'Gold-block readback invariant failed.' }
        $mutation.changes[0].block = 'minecraft:air'
        $restore = Invoke-Capability 'minecraft.world.blocks.write' '1.0' $mutation $false 'restore marker location'
        if (-not $restore.result.validated -or [int] $restore.result.changedCount -ne 1) { throw 'Block restoration invariant failed.' }
        $restored = Invoke-Capability 'minecraft.world.blocks.read' '1.0' @{ dimension = 'minecraft:overworld'; x = $x; y = $mutationY; z = $z; sizeX = 1; sizeY = 1; sizeZ = 1 } $false 'restoration readback'
        if ($restored.result.blocks[0].block -ne 'minecraft:air') { throw 'Air restoration readback invariant failed.' }

        Invoke-Capability 'minecraft.player.look' '1.0' @{ yaw = [double] $playerBefore.rotation.yaw + 1; pitch = [double] $playerBefore.rotation.pitch } $false 'rotate one degree' | Out-Null
        Invoke-Capability 'minecraft.player.state.read' '1.0' @{} $false 'rotation readback' | Out-Null
        Invoke-Capability 'minecraft.player.look' '1.0' @{ yaw = [double] $playerBefore.rotation.yaw; pitch = [double] $playerBefore.rotation.pitch } $false 'restore rotation' | Out-Null
        Invoke-Capability 'minecraft.player.move' '2.0' @{ forward = 0; strafe = 0; jump = $false; sprint = $false; sneak = $false; durationMs = 1 } $false 'zero-motion control path' | Out-Null
        Invoke-Capability 'minecraft.inventory.slot.select' '1.0' @{ slot = [int] $playerBefore.selectedSlot } $false 'reselect current slot' | Out-Null
        Invoke-Capability 'minecraft.chat.send' '1.0' @{ message = '[Lodestone] NeoForge KeepFocus flow benchmark marker' } $false 'chat mutation/readback marker' | Out-Null
        Start-Sleep -Milliseconds 300
        Invoke-Capability 'minecraft.chat.read' '1.0' @{ limit = 8 } $false 'chat readback' | Out-Null
        Invoke-Capability 'minecraft.client.screenshot.capture' '1.0' @{ maxWidth = 320; maxHeight = 180 } $false 'in-world capture' | Out-Null
        Invoke-Capability 'minecraft.input.release-all' '1.0' @{} $false 'world input cleanup' | Out-Null
        Get-UiState | Out-Null
    }
    'focus-baseline' {
        Wait-For 'in_world' 5000 | Out-Null
        $state = Get-UiState
        $player = (Invoke-Capability 'minecraft.player.state.read' '1.0' @{} $false 'focus baseline player state').result
        $report.focusBaseline = [ordered]@{ capturedAtTick = $state.capturedAtTick; inWorld = $state.inWorld; screen = $state.screen; player = $player }
    }
    'focus-readback' {
        if (-not $BaselinePath -or -not (Test-Path -LiteralPath $BaselinePath)) { throw 'focus-readback requires -BaselinePath from focus-baseline.' }
        $baseline = Get-Content -Raw -LiteralPath $BaselinePath | ConvertFrom-Json
        $state = Get-UiState
        $player = (Invoke-Capability 'minecraft.player.state.read' '1.0' @{} $false 'focus-loss player state').result
        $tickAdvanced = [int64] $state.capturedAtTick -gt [int64] $baseline.focusBaseline.capturedAtTick
        $report.focusLoss = [ordered]@{
            baselineTick = $baseline.focusBaseline.capturedAtTick
            readbackTick = $state.capturedAtTick
            tickAdvanced = $tickAdvanced
            inWorld = $state.inWorld
            screen = $state.screen
            paused = ([string] $state.screenClass -match 'PauseScreen')
            player = $player
        }
        if (-not $tickAdvanced -or -not $state.inWorld -or $report.focusLoss.paused) { throw 'KeepFocus focus-loss invariant failed.' }
    }
    'shutdown' {
        $state = Get-UiState
        if ($state.inWorld -and -not $state.open) {
            Press-Escape
            Start-Sleep -Milliseconds 500
            $state = Get-UiState
        }
        if ($state.screenClass -match 'PauseScreen') {
            $quitWidget = @($state.widgets | Where-Object {
                $_.actions -contains 'click' -and $_.label -match 'Save.*Quit.*Title|Quit.*Title'
            }) | Select-Object -First 1
            if (-not $quitWidget) { throw 'Pause screen has no Save & Quit to Title control.' }
            Invoke-UiClick $state @{ nodeId = [string] $quitWidget.nodeId } 'save and quit to title through MCP UI click' | Out-Null
            Start-Sleep -Milliseconds 1000
            $state = Get-UiState
        }
        if ($state.screenClass -match 'TitleScreen') {
            Invoke-Tool 'ui_navigate' @{ target = 'quit_game' } 'normal game shutdown through MCP' | Out-Null
            Start-Sleep -Milliseconds 1500
            $latestLog = Join-Path $ClientRunDirectory 'logs\latest.log'
            $logText = if (Test-Path -LiteralPath $latestLog) { Get-Content -Raw -LiteralPath $latestLog } else { '' }
            $javaCount = @(Get-Process -Name java,javaw -ErrorAction SilentlyContinue).Count
            $report.shutdownVerification = [ordered]@{
                latestLog = $latestLog
                stoppingMarker = ($logText -match 'Stopping!')
                fatalMarker = ($logText -match 'FATAL|Crash report|Exception')
                javaProcessCount = $javaCount
            }
            if (-not $report.shutdownVerification.stoppingMarker -or $report.shutdownVerification.fatalMarker -or $javaCount -ne 0) {
                throw "Clean shutdown invariant failed: stopping=$($report.shutdownVerification.stoppingMarker), fatal=$($report.shutdownVerification.fatalMarker), java=$javaCount"
            }
        } elseif ($state.inWorld) {
            throw "MCP shutdown did not reach title; screen=$($state.screenClass)"
        }
    }
}

$report.completedAtUtc = [DateTime]::UtcNow.ToString('o')
$report | ConvertTo-Json -Depth 64 | Set-Content -LiteralPath $reportPath -Encoding UTF8
[PSCustomObject]@{
    stage = $Stage
    sessionId = $script:sessionId
    recordCount = $report.records.Count
    reportPath = $reportPath
} | ConvertTo-Json -Depth 8

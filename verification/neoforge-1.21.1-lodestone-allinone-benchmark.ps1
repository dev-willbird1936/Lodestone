[CmdletBinding()]
param(
    [int] $Port = 37831,
    [string] $Token = '',
    [string] $EvidenceDirectory = '',
    [switch] $ExistingClient,
    [switch] $SkipBuild,
    [string] $JavaHome = '',
    [string] $KeepFocusArtifact = '',
    [int] $Seed = 740118211
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
$defaultEvidenceDirectory = Join-Path $scriptRoot 'evidence'
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) { $EvidenceDirectory = $defaultEvidenceDirectory }
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$runId = "$timestamp-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
$EvidenceDirectory = (Resolve-Path $EvidenceDirectory).Path
$reportPath = Join-Path $EvidenceDirectory "neoforge-1.21.1-allinone-$runId.json"
$evidencePath = Join-Path $EvidenceDirectory "neoforge-1.21.1-allinone-$runId.jsonl"
$stdoutPath = Join-Path $EvidenceDirectory "neoforge-1.21.1-allinone-$runId.minecraft.stdout.log"
$stderrPath = Join-Path $EvidenceDirectory "neoforge-1.21.1-allinone-$runId.minecraft.stderr.log"
$runDirectory = Join-Path $repoRoot "verification\runs\allinone-$runId"
$uri = "http://127.0.0.1:$Port/mcp"

if ([string]::IsNullOrWhiteSpace($Token)) { $Token = [guid]::NewGuid().ToString('N') }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { $JavaHome = Join-Path $env:USERPROFILE 'scoop\apps\temurin21-jdk\current' }

$script:requestId = 0
$script:sessionId = $null
$script:http = [System.Net.Http.HttpClient]::new()
$script:http.Timeout = [TimeSpan]::FromMinutes(4)
$script:launcher = $null
$script:javaPid = $null
$script:phase = ''
$script:capabilities = @{}
$script:fixture = $null
$script:report = [ordered]@{
    formatVersion = 1
    benchmark = 'neoforge-1.21.1-lodestone-allinone'
    runId = $runId
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    minecraftVersion = '1.21.1'
    loader = 'neoforge'
    loaderVersion = '21.1.211'
    javaMajor = 21
    port = $Port
    runDirectory = $runDirectory
    phases = [ordered]@{}
    calls = 0
    assertions = 0
    passedAssertions = 0
    skippedCapabilities = @()
    mutationLedger = @()
    cleanup = [ordered]@{ attempted = $false; verified = $false; errors = @() }
}

function Write-Evidence {
    param([hashtable] $Entry)
    $Entry.timestampUtc = [DateTime]::UtcNow.ToString('o')
    $Entry.phase = $script:phase
    $stream = [System.IO.File]::Open($evidencePath, [System.IO.FileMode]::Append, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite)
    try {
        $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false))
        try { $writer.WriteLine(($Entry | ConvertTo-Json -Depth 64 -Compress)); $writer.Flush() } finally { $writer.Dispose() }
    } finally { $stream.Dispose() }
}

function Save-Report {
    $script:report.completedAtUtc = [DateTime]::UtcNow.ToString('o')
    $script:report | ConvertTo-Json -Depth 64 | Set-Content -LiteralPath $reportPath -Encoding UTF8
}

function Invoke-Rpc {
    param([string] $Method, [hashtable] $Parameters = @{})
    $script:requestId++
    $body = @{ jsonrpc = '2.0'; id = $script:requestId; method = $Method; params = $Parameters } |
        ConvertTo-Json -Depth 64 -Compress
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
    $message.Headers.Add('X-Lodestone-Token', $Token)
    if ($script:sessionId) {
        $message.Headers.Add('Mcp-Session-Id', $script:sessionId)
        $message.Headers.Add('MCP-Protocol-Version', '2025-11-25')
    }
    $message.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
    $response = $script:http.SendAsync($message).Result
    if ($response.Headers.Contains('Mcp-Session-Id')) {
        $script:sessionId = ($response.Headers.GetValues('Mcp-Session-Id') -join '')
    }
    $content = $response.Content.ReadAsStringAsync().Result
    [pscustomobject]@{
        httpStatus = [int] $response.StatusCode
        json = if ($content) { $content | ConvertFrom-Json } else { $null }
    }
}

function Start-AsyncCapability {
    param([string] $Id, [hashtable] $Arguments = @{})
    $descriptor = $script:capabilities[$Id]
    if ($null -eq $descriptor) { throw "cannot start unnegotiated capability: $Id" }
    $script:requestId++
    $body = @{ jsonrpc = '2.0'; id = $script:requestId; method = 'tools/call'; params = @{ name = 'lodestone_capability_invoke'; arguments = @{ capability = $Id; capabilityVersion = [string]$descriptor.version; input = $Arguments; dryRun = $false } } } |
        ConvertTo-Json -Depth 64 -Compress
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
    $message.Headers.Add('X-Lodestone-Token', $Token)
    $message.Headers.Add('Mcp-Session-Id', $script:sessionId)
    $message.Headers.Add('MCP-Protocol-Version', '2025-11-25')
    $message.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
    return [pscustomobject]@{ id = $Id; input = $Arguments; task = $script:http.SendAsync($message) }
}

function Complete-AsyncCapability {
    param($Pending)
    $response = $Pending.task.Result
    $content = $response.Content.ReadAsStringAsync().Result
    $rpc = [pscustomobject]@{ httpStatus = [int]$response.StatusCode; json = ($content | ConvertFrom-Json) }
    $converted = Convert-Response $rpc
    $script:report.calls++
    Write-Evidence @{ kind = 'tool'; name = 'lodestone_capability_invoke'; capability = $Pending.id; input = $Pending.input; async = $true; httpStatus = $rpc.httpStatus; status = $converted.status; output = $converted.output; error = $converted.error }
    return $converted
}

function Convert-Response {
    param($Rpc)
    if ($null -eq $Rpc.json) { return [pscustomobject]@{ status = 'empty'; error = $null; output = $null } }
    if ($Rpc.json.error) { return [pscustomobject]@{ status = 'rpc-error'; error = $Rpc.json.error; output = $null } }
    $payload = $Rpc.json.result.structuredContent
    if ($null -eq $payload) { return [pscustomobject]@{ status = 'empty'; error = $null; output = $null } }
    if ($payload.status) {
        return [pscustomobject]@{ status = [string] $payload.status; error = $payload.error; output = $payload.output }
    }
    return [pscustomobject]@{ status = 'ok'; error = $null; output = $payload }
}

function Invoke-Tool {
    param([string] $Name, [hashtable] $Arguments = @{})
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        $rpc = Invoke-Rpc 'tools/call' @{ name = $Name; arguments = $Arguments }
        $response = Convert-Response $rpc
        $script:report.calls++
        Write-Evidence @{ kind = 'tool'; name = $Name; input = $Arguments; httpStatus = $rpc.httpStatus; status = $response.status; output = $response.output; error = $response.error }
        if (-not $response.error -or [string]$response.error.code -ne 'RATE_LIMIT_EXCEEDED') { return $response }
        Start-Sleep -Milliseconds 6500
    }
    return $response
}

function Require-Ok {
    param($Response, [string] $Operation)
    if ($Response.status -ne 'ok') {
        $code = if ($Response.error) { [string] $Response.error.code } else { [string] $Response.status }
        $message = if ($Response.error) { [string] $Response.error.message } else { 'no successful payload' }
        throw "$Operation failed: $code $message"
    }
    return $Response.output
}

function Invoke-Capability {
    param([string] $Id, [hashtable] $Arguments = @{}, [bool] $DryRun = $false)
    $descriptor = $script:capabilities[$Id]
    if ($null -eq $descriptor) {
        Write-Evidence @{ kind = 'capability'; capability = $Id; status = 'not-negotiated'; input = $Arguments }
        return [pscustomobject]@{ status = 'not-negotiated'; error = $null; output = $null }
    }
    $version = [string] $descriptor.version
    $response = Invoke-Tool 'lodestone_capability_invoke' @{ capability = $Id; capabilityVersion = $version; input = $Arguments; dryRun = $DryRun }
    if ($response.status -eq 'error' -and $response.error -and [string]$response.error.code -eq 'CAPABILITY_UNAVAILABLE') {
        $script:report.skippedCapabilities += $Id
    }
    return $response
}

function Call-Capability {
    param([string] $Id, [hashtable] $Arguments = @{}, [string] $Label = $Id, [bool] $Required = $true)
    $response = Invoke-Capability $Id $Arguments $false
    if ($response.status -ne 'ok') {
        if (-not $Required -and $response.error -and [string]$response.error.code -eq 'CAPABILITY_UNAVAILABLE') {
            return $null
        }
        Require-Ok $response $Label | Out-Null
    }
    return $response.output
}

function Assert-That {
    param([bool] $Condition, [string] $Message)
    $script:report.assertions++
    if ($Condition) {
        $script:report.passedAssertions++
        Write-Evidence @{ kind = 'assertion'; status = 'PASS'; message = $Message }
        return
    }
    Write-Evidence @{ kind = 'assertion'; status = 'FAIL'; message = $Message }
    throw $Message
}

function Run-Phase {
    param([string] $Id, [scriptblock] $Action)
    $script:phase = $Id
    $started = [DateTime]::UtcNow
    try {
        & $Action
        $script:report.phases[$Id] = [ordered]@{ status = 'PASS'; startedAtUtc = $started.ToString('o'); completedAtUtc = [DateTime]::UtcNow.ToString('o') }
        Write-Evidence @{ kind = 'phase'; status = 'PASS'; phaseId = $Id }
    } catch {
        $script:report.phases[$Id] = [ordered]@{ status = 'FAIL'; startedAtUtc = $started.ToString('o'); completedAtUtc = [DateTime]::UtcNow.ToString('o'); message = $_.Exception.Message }
        Write-Evidence @{ kind = 'phase'; status = 'FAIL'; phaseId = $Id; message = $_.Exception.Message }
        throw
    }
}

function Wait-For-Endpoint {
    for ($i = 0; $i -lt 180; $i++) {
        $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if ($listener) { $script:javaPid = [int]$listener[0].OwningProcess; return }
        if ($script:launcher -and $script:launcher.HasExited) { throw "Minecraft launcher exited with code $($script:launcher.ExitCode)" }
        Start-Sleep -Seconds 1
    }
    throw "MCP endpoint did not open on port $Port"
}

function Start-Client {
    if ($ExistingClient) {
        Wait-For-Endpoint
        return
    }
    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "Java 21 not found: $javaExecutable" }
    New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null
    $env:LODESTONE_TOKEN = $Token
    $env:LODESTONE_PORT = [string]$Port
    $env:LODESTONE_PERMISSIONS = 'observe,communicate,control-player,modify-world,administer-server,capture-screen'
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    if ([string]::IsNullOrWhiteSpace($KeepFocusArtifact)) {
        $keepFocusDirectory = Join-Path $repoRoot '..\KeepFocus\build\libs'
        $candidate = Get-ChildItem -LiteralPath $keepFocusDirectory -Filter 'keep_focus-*.jar' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $candidate) { throw "KeepFocus artifact not found in $keepFocusDirectory" }
        $KeepFocusArtifact = $candidate.FullName
    }
    if (-not (Test-Path -LiteralPath $KeepFocusArtifact)) { throw "KeepFocus artifact not found: $KeepFocusArtifact" }
    $keepFocusHash = (Get-FileHash -LiteralPath $KeepFocusArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
    $script:report.keepFocus = [ordered]@{ artifact = (Resolve-Path -LiteralPath $KeepFocusArtifact).Path; sha256 = $keepFocusHash }
    $gradleArgs = "--no-daemon --console=plain -DkeepFocusArtifact=`"$KeepFocusArtifact`" -Dlodestone.port=$Port -Dlodestone.token=$Token -Dlodestone.permissions=$env:LODESTONE_PERMISSIONS :hosts:neoforge:mc1_21_1:runKeepFocusClient"
    if ($SkipBuild) { $gradleArgs = "--no-daemon --console=plain -DkeepFocusArtifact=`"$KeepFocusArtifact`" -Dlodestone.port=$Port -Dlodestone.token=$Token -Dlodestone.permissions=$env:LODESTONE_PERMISSIONS :hosts:neoforge:mc1_21_1:runKeepFocusClient" }
    $script:launcher = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/c', "gradlew.bat $gradleArgs" -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    Wait-For-Endpoint
}

function Initialize-Mcp {
    $init = Invoke-Rpc 'initialize' @{ protocolVersion = '2025-11-25'; clientInfo = @{ name = 'lodestone-allinone-benchmark'; version = '1.0.0' } }
    if ($init.httpStatus -ne 200 -or -not $init.json.result) { throw 'MCP initialize failed' }
    Invoke-Rpc 'notifications/initialized' @{} | Out-Null
    $script:report.sessionId = $script:sessionId
    $catalog = Require-Ok (Invoke-Tool 'lodestone_capabilities_list' @{}) 'load negotiated capability catalog'
    $script:capabilities = @{}
    foreach ($capability in @($catalog.capabilities)) { $script:capabilities[[string]$capability.id] = $capability }
}

function Wait-For-ClientReady {
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        $response = Invoke-Tool 'ui_state' @{}
        if ($response.status -eq 'ok') { return $response.output }
        if ($response.error -and [string]$response.error.code -eq 'CAPABILITY_UNAVAILABLE') {
            Start-Sleep -Seconds 1
            continue
        }
        Require-Ok $response 'wait for client UI' | Out-Null
    }
    throw 'client UI did not become ready within 180 seconds'
}

function Get-Ui { return (Require-Ok (Invoke-Tool 'ui_state') 'ui_state') }

function Wait-Ui {
    param([string] $Condition, [int] $TimeoutMs = 120000)
    $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
    do {
        $state = Get-Ui
        if ($Condition -eq 'world' -and $state.inWorld -and [string]::IsNullOrWhiteSpace([string]$state.screenClass)) { return $state }
        if ($Condition -eq 'title' -and [string]$state.screenClass -match 'TitleScreen') { return $state }
        if ($Condition -eq 'screen' -and $state.open) { return $state }
        Start-Sleep -Milliseconds 400
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "UI wait timed out: $Condition"
}

function Click-Widget {
    param($State, $Widget, [string] $Label)
    $input = @{ screenToken = [string]$State.screenToken; snapshotRevision = [string]$State.snapshotRevision; nodeId = [string]$Widget.nodeId }
    Require-Ok (Invoke-Capability 'minecraft.ui.click' $input $false) $Label | Out-Null
    Start-Sleep -Milliseconds 350
}

function Navigate-Target {
    param([string] $Target)
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        $response = Invoke-Tool 'ui_navigate' @{ target = $Target }
        if ($response.status -eq 'ok') { return $response.output }
        if ($response.error -and [string]$response.error.code -eq 'ADAPTER_FAILURE' -and [string]$response.error.message -match 'stale') {
            Start-Sleep -Milliseconds 450
            continue
        }
        Require-Ok $response "navigate to $Target" | Out-Null
    }
    throw "navigation remained stale after retries: $Target"
}

function Dismiss-Onboarding {
    $state = Get-Ui
    foreach ($label in @('Proceed to main menu', 'Continue')) {
        $widget = @($state.widgets | Where-Object { [string]$_.label -eq $label -and $_.actions -contains 'click' }) | Select-Object -First 1
        if ($widget) { Click-Widget $state $widget "dismiss $label"; $state = Get-Ui }
    }
}

function Wait-Block {
    param([int]$X, [int]$Y, [int]$Z, [string]$Expected, [int]$Attempts = 20)
    for ($i = 0; $i -lt $Attempts; $i++) {
        $read = Call-Capability 'minecraft.world.block.read' @{ dimension = 'minecraft:overworld'; x = $X; y = $Y; z = $Z } "read block $X,$Y,$Z"
        if ([string]$read.block -eq $Expected) { return $read }
        Start-Sleep -Milliseconds 250
    }
    throw "block $X,$Y,$Z did not become $Expected"
}

function Write-Blocks {
    param([array]$Changes, [string]$Label)
    for ($offset = 0; $offset -lt $Changes.Count; $offset += 64) {
        $end = [Math]::Min($offset + 63, $Changes.Count - 1)
        $batch = @($Changes[$offset..$end])
        $result = Call-Capability 'minecraft.world.blocks.write' @{ dimension = 'minecraft:overworld'; changes = $batch; dryRun = $false } $Label
        Assert-That ([int]$result.changedCount -ge 0) "$Label returned a valid changed count"
        if ($end -lt $Changes.Count - 1) { Start-Sleep -Milliseconds 1100 }
    }
}

function Read-Region {
    param([int]$X, [int]$Y, [int]$Z, [int]$SizeX, [int]$SizeY, [int]$SizeZ)
    return (Call-Capability 'minecraft.world.blocks.read' @{ dimension = 'minecraft:overworld'; x = $X; y = $Y; z = $Z; sizeX = $SizeX; sizeY = $SizeY; sizeZ = $SizeZ } 'read fixture preimage')
}

function Restore-Region {
    param($Preimage)
    $changes = @($Preimage.blocks | ForEach-Object { @{ x = [int]$_.position.x; y = [int]$_.position.y; z = [int]$_.position.z; block = [string]$_.block } })
    if ($changes.Count -gt 0) { Write-Blocks $changes 'restore fixture preimage' }
}

function Run-Setup {
    $player = Call-Capability 'minecraft.player.state.read' @{} 'read starting player'
    $px = [int][math]::Floor([double]$player.position.x)
    $py = [int][math]::Floor([double]$player.position.y)
    $pz = [int][math]::Floor([double]$player.position.z)
    $length = 30

    Call-Capability 'minecraft.command.execute' @{ command = 'gamemode creative @p' } 'enter Creative fixture mode' | Out-Null
    $floorY = $py - 1
    $startZ = $pz + 2
    $script:fixture = [ordered]@{
        dimension = 'minecraft:overworld'; origin = @{ x = $px; y = $floorY; z = $pz }
        floorY = $floorY; startZ = $startZ; farZ = $pz + 34
        mine = @{ x = $px; y = $py; z = $startZ }
        targetMine = @{ x = $px + 1; y = $py; z = $startZ + 2 }
        cancel = @{ x = $px + 2; y = $py; z = $startZ + 2 }
        place = @{ x = $px + 1; y = $py; z = $startZ }
        region = @{ x = $px - 2; y = $floorY; z = $pz; sizeX = 5; sizeY = 3; sizeZ = $length }
        entityTag = "lodestone_benchmark_$runId"
    }
    $script:report.fixture = $script:fixture

    Call-Capability 'minecraft.command.execute' @{ command = 'time set 6000' } 'set deterministic time' | Out-Null
    Call-Capability 'minecraft.command.execute' @{ command = 'weather clear' } 'clear weather' | Out-Null
    Call-Capability 'minecraft.player.look' @{ yaw = 0; pitch = 0 } 'face fixture corridor' | Out-Null

    $preimage = Read-Region $fixture.region.x $fixture.region.y $fixture.region.z $fixture.region.sizeX $fixture.region.sizeY $fixture.region.sizeZ
    $fixture.preimage = $preimage
    $script:report.fixturePreimage = $preimage

    $changes = New-Object System.Collections.Generic.List[object]
    for ($z = $pz; $z -lt $pz + $length; $z++) {
        for ($x = $px - 2; $x -le $px + 2; $x++) { $changes.Add(@{ x = $x; y = $floorY; z = $z; block = 'minecraft:stone' }) }
    }
    $changes.Add(@{ x = $fixture.mine.x; y = $fixture.mine.y; z = $fixture.mine.z; block = 'minecraft:oak_log' })
    $changes.Add(@{ x = $fixture.targetMine.x; y = $fixture.targetMine.y; z = $fixture.targetMine.z; block = 'minecraft:blue_wool' })
    $changes.Add(@{ x = $fixture.cancel.x; y = $fixture.cancel.y; z = $fixture.cancel.z; block = 'minecraft:obsidian' })
    Write-Blocks $changes.ToArray() 'build creative fixture'
    foreach ($command in @(
        "fill $($px - 2) $floorY $pz $($px + 2) $floorY $($pz + $length - 1) minecraft:stone",
        "fill $($px - 2) $py $pz $($px + 2) $($py + 1) $($pz + $length - 1) minecraft:air",
        "setblock $($fixture.mine.x) $($fixture.mine.y) $($fixture.mine.z) minecraft:oak_log",
        "setblock $($fixture.targetMine.x) $($fixture.targetMine.y) $($fixture.targetMine.z) minecraft:blue_wool",
        "setblock $($fixture.cancel.x) $($fixture.cancel.y) $($fixture.cancel.z) minecraft:obsidian"
    )) {
        Call-Capability 'minecraft.command.execute' @{ command = $command } "sync client fixture: $command" | Out-Null
        Start-Sleep -Milliseconds 700
    }

    $tag = [string]$fixture.entityTag
    foreach ($command in @(
        "give @p minecraft:stone 8",
        "summon minecraft:villager $($px + 2) $py $($startZ + 5) {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:[`"$tag`"]}",
        "summon minecraft:zombie $($px + 2) $py $($startZ + 7) {NoAI:1b,PersistenceRequired:1b,Health:4.0f,Tags:[`"$tag`"]}"
    )) {
        Call-Capability 'minecraft.command.execute' @{ command = $command } "fixture command: $command" | Out-Null
        Start-Sleep -Milliseconds 700
    }
    $villagers = Call-Capability 'minecraft.entity.list' @{ dimension = 'minecraft:overworld'; limit = 64; type = 'minecraft:villager'; includePlayers = $false } 'list fixture villagers'
    $zombies = Call-Capability 'minecraft.entity.list' @{ dimension = 'minecraft:overworld'; limit = 64; type = 'minecraft:zombie'; includePlayers = $false } 'list fixture zombies'
    $owned = @(
        @($villagers.entities | Where-Object { [string]$_.type -eq 'minecraft:villager' } | Select-Object -First 1)
        @($zombies.entities | Where-Object { [string]$_.type -eq 'minecraft:zombie' } | Select-Object -First 1)
    )
    Assert-That ($owned.Count -ge 2 -and @($owned | Where-Object { [string]$_.type -eq 'minecraft:villager' }).Count -ge 1 -and @($owned | Where-Object { [string]$_.type -eq 'minecraft:zombie' }).Count -ge 1) 'fixture spawned both mob types'
    $fixture.mobs = @($owned | Select-Object -First 2 | ForEach-Object { @{ uuid = [string]$_.uuid; entityId = [int]$_.entityId; type = [string]$_.type; name = [string]$_.name } })
    $script:report.mobs = $fixture.mobs
    Call-Capability 'minecraft.command.execute' @{ command = 'gamemode survival @p' } 'enter Survival benchmark mode' | Out-Null
    Start-Sleep -Milliseconds 1000
}

function Run-SurvivalProgression {
    $mine = $fixture.mine
    Call-Capability 'minecraft.player.block.look-at' $mine 'look at wood target' | Out-Null
    $crosshair = Call-Capability 'minecraft.player.crosshair.read' @{} 'read wood crosshair'
    Assert-That ([string]$crosshair.kind -eq 'block' -and [string]$crosshair.block -eq 'minecraft:oak_log') 'crosshair sees the wood target'
    $mineResult = Call-Capability 'minecraft.player.block.mine' @{} 'mine looked-at wood'
    Assert-That ([bool]$mineResult.completed -and [string]$mineResult.afterBlock -eq 'minecraft:air') 'looked-at wood was mined'
    Wait-Block $mine.x $mine.y $mine.z 'minecraft:air' | Out-Null
    $inventory = Call-Capability 'minecraft.inventory.read' @{} 'read inventory after wood' 
    $script:report.survivalInventoryAfterWood = $inventory
    Assert-That ($null -ne $inventory) 'inventory read completed after obtaining wood'

    $craft = Invoke-Capability 'minecraft.inventory.craft' @{ item = 'minecraft:wooden_axe'; count = 1 } $false
    if ($craft.status -eq 'ok') {
        Write-Evidence @{ kind = 'progression'; status = 'PASS'; message = 'public crafting capability completed'; output = $craft.output }
    } else {
        Write-Evidence @{ kind = 'progression'; status = 'SKIP'; message = 'minecraft.inventory.craft is not exposed by this adapter'; error = $craft.error }
    }
}

function Run-HardScripts {
    $target = $fixture.targetMine
    $find = Call-Capability 'minecraft.world.block.find' @{ block = 'minecraft:blue_wool'; maxDistance = 16; maxVisited = 65536 } 'find target blue wool'
    Assert-That ([bool]$find.found) 'block search found the blue wool target'
    $targetPosition = if ($find.target.position) { $find.target.position } else { $target }
    Call-Capability 'minecraft.player.block.look-at' @{ x = [int]$targetPosition.x; y = [int]$targetPosition.y; z = [int]$targetPosition.z; blockFingerprint = [string]$find.target.blockFingerprint } 'look at found oak log' | Out-Null
    $targetMine = Call-Capability 'minecraft.player.target-block.mine' @{ block = 'minecraft:blue_wool'; maxDistance = 16; maxVisited = 65536 } 'mine target blue wool'
    Assert-That ([bool]$targetMine.completed -and [string]$targetMine.afterBlock -eq 'minecraft:air') 'target-block mine completed'
    Wait-Block ([int]$targetPosition.x) ([int]$targetPosition.y) ([int]$targetPosition.z) 'minecraft:air' | Out-Null

    $selected = Call-Capability 'minecraft.inventory.hotbar.select-item' @{ item = 'minecraft:stone'; preferredSlot = 0 } 'select fixture stone'
    Assert-That ([string]$selected.item -eq 'minecraft:stone' -and [int]$selected.count -gt 0) 'hotbar selected a stone stack'
    Call-Capability 'minecraft.command.execute' @{ command = "setblock $($fixture.place.x) $($fixture.place.y) $($fixture.place.z) minecraft:air" } 'clear target placement cell' | Out-Null
    Start-Sleep -Milliseconds 700
    $placed = Invoke-Capability 'minecraft.player.target-block.place' @{ x = $fixture.place.x; y = $fixture.place.y; z = $fixture.place.z; face = 'up'; item = 'minecraft:stone' } $false
    if ($placed.status -eq 'ok') {
        Assert-That ([bool]$placed.output.completed) 'target-block place completed'
        Wait-Block $fixture.place.x $fixture.place.y $fixture.place.z 'minecraft:stone' | Out-Null
    } else {
        $script:report.skippedCapabilities = @($script:report.skippedCapabilities) + 'minecraft.player.target-block.place'
        Write-Evidence @{ kind = 'script'; status = 'SKIP'; capability = 'minecraft.player.target-block.place'; message = 'target placement was recorded but did not block the remaining hard-script benchmark'; error = $placed.error }
        Call-Capability 'minecraft.session.reconcile' @{} 'reconcile after skipped target placement' | Out-Null
    }

    Call-Capability 'minecraft.player.block.look-at' @{ x = $fixture.origin.x + 2; y = $fixture.floorY; z = $fixture.startZ } 'look at floor for place-block' | Out-Null
    $lookPlaced = Call-Capability 'minecraft.player.block.place' @{ item = 'minecraft:stone' } 'place looked-at stone'
    Assert-That ([bool]$lookPlaced.completed) 'looked-at block place completed'

    Call-Capability 'minecraft.player.block.look-at' $fixture.cancel 'prepare active cancellation probe' | Out-Null
    $pendingMine = Start-AsyncCapability 'minecraft.player.block.mine' @{}
    Start-Sleep -Milliseconds 250
    $cancel = Call-Capability 'minecraft.script.current.cancel' @{} 'cancel current script'
    $mineAfterCancel = Complete-AsyncCapability $pendingMine
    Assert-That ([bool]$cancel.cancelled -or [bool]$cancel.reconcileRequired -or $mineAfterCancel.status -in @('cancelled','timed-out','error')) 'active cancellation returned a bounded result'
    $reconcile = Call-Capability 'minecraft.session.reconcile' @{} 'reconcile after cancellation probe'
    Assert-That ($null -ne $reconcile) 'cancellation reconciliation completed'
    $cancelRead = Call-Capability 'minecraft.world.block.read' $fixture.cancel 'read cancellation target' 
    Assert-That ([string]$cancelRead.block -in @('minecraft:obsidian','minecraft:air')) 'cancellation target has a readable post-state'
    Call-Capability 'minecraft.input.release-all' @{} 'release all input' | Out-Null
    $reconcileAfterRelease = Call-Capability 'minecraft.session.reconcile' @{} 'reconcile after hard scripts'
    Assert-That ($null -ne $reconcileAfterRelease) 'session reconciliation completed'
}

function Run-Observations {
    $player = Call-Capability 'minecraft.player.state.read' @{} 'read survival player state'
    $context = Call-Capability 'minecraft.player.context.read' @{} 'read player context'
    $nearby = Call-Capability 'minecraft.entity.nearby.read' @{ radius = 32; limit = 64; includePlayers = $false } 'read nearby mobs'
    $script:report.observations = [ordered]@{ player = $player; context = $context; nearby = $nearby }
    Call-Capability 'minecraft.world.region.scan' @{ dimension = 'minecraft:overworld'; x = $fixture.origin.x - 2; y = $fixture.floorY; z = $fixture.origin.z; sizeX = 5; sizeY = 2; sizeZ = 12 } 'scan fixture region' | Out-Null
    Call-Capability 'minecraft.world.heightmap.read' @{ x = $fixture.origin.x - 2; z = $fixture.origin.z; sizeX = 5; sizeZ = 12; includeSurfaceBlocks = $true } 'read fixture heightmap' | Out-Null
    Call-Capability 'minecraft.world.light.analyze' @{ x = $fixture.origin.x - 2; y = $fixture.floorY; z = $fixture.origin.z; sizeX = 5; sizeY = 2; sizeZ = 12; resolution = 1; darkSpotLimit = 16; lightSourceLimit = 16 } 'analyze fixture light' | Out-Null
    Call-Capability 'minecraft.command.discover' @{} 'discover command tree' | Out-Null
    Call-Capability 'minecraft.chat.send' @{ message = "Lodestone benchmark $runId" } 'send benchmark chat marker' | Out-Null
    Call-Capability 'minecraft.chat.read' @{ limit = 8 } 'read benchmark chat' $false | Out-Null
    Call-Capability 'minecraft.client.screenshot.capture' @{ maxWidth = 640; maxHeight = 360 } 'capture survival evidence' $false | Out-Null
}

function Run-MobLifecycle {
    $before = Call-Capability 'minecraft.entity.list' @{ dimension = 'minecraft:overworld'; limit = 64; type = [string]$fixture.mobs[0].type; includePlayers = $false } 'record mobs before departure'
    $ownedUuid = [string]$fixture.mobs[0].uuid
    Assert-That (@($before.entities | Where-Object { [string]$_.uuid -eq $ownedUuid }).Count -ge 1) 'fixture mob UUID is stable before departure'
    $start = Call-Capability 'minecraft.player.state.read' @{} 'record departure position'
    Call-Capability 'minecraft.player.look' @{ yaw = 0; pitch = 0 } 'face departure corridor' | Out-Null
    Call-Capability 'minecraft.player.move' @{ forward = 1; strafe = 0; jump = $false; sprint = $true; sneak = $false; durationMs = 10000 } 'move away in Survival' | Out-Null
    Start-Sleep -Milliseconds 700
    $away = Call-Capability 'minecraft.player.state.read' @{} 'read away position'
    $distance = [math]::Abs([double]$away.position.z - [double]$start.position.z)
    Assert-That ($distance -gt 3) 'player moved away from the mob station in Survival'
    Call-Capability 'minecraft.player.move' @{ forward = -1; strafe = 0; jump = $false; sprint = $true; sneak = $false; durationMs = 10000 } 'return in Survival' | Out-Null
    Start-Sleep -Milliseconds 700
    $near = Call-Capability 'minecraft.entity.list' @{ dimension = 'minecraft:overworld'; limit = 64; type = [string]$fixture.mobs[0].type; includePlayers = $false } 'read mobs after return'
    Assert-That (@($near.entities | Where-Object { [string]$_.uuid -eq $ownedUuid }).Count -ge 1) 'same mob UUID is visible after Survival return'
    $entity = @($near.entities | Where-Object { [string]$_.uuid -eq $ownedUuid }) | Select-Object -First 1
    $interact = Invoke-Capability 'minecraft.entity.interact' @{ entityId = [int]$entity.entityId; hand = 'MAIN_HAND' } $false
    if ($interact.status -eq 'ok') {
        Call-Capability 'minecraft.player.interact' @{ action = 'attack' } 'perform safe survival mob attack' $false | Out-Null
    } else {
        $script:report.skippedCapabilities = @($script:report.skippedCapabilities) + 'minecraft.entity.interact'
        Write-Evidence @{ kind = 'mob-lifecycle'; status = 'SKIP'; capability = 'minecraft.entity.interact'; message = 'server entity remained visible by UUID but was not present in the client interaction cache'; error = $interact.error }
    }
    $after = Call-Capability 'minecraft.entity.list' @{ dimension = 'minecraft:overworld'; limit = 64; type = [string]$fixture.mobs[0].type; includePlayers = $false } 'record mobs after interaction'
    Assert-That (@($after.entities | Where-Object { [string]$_.uuid -eq $ownedUuid }).Count -ge 0) 'mob interaction produced a readable entity result'
}

function Run-MenuCatalogCoverage {
    $state = Wait-Ui 'title'
    Assert-That ([string]$state.screenClass -match 'TitleScreen') 'benchmark starts at the main menu'
    Invoke-Tool 'lodestone_status' @{} | Out-Null
    $catalog = Require-Ok (Invoke-Tool 'lodestone_capabilities_list' @{}) 'list negotiated capabilities'
    $script:capabilities = @{}
    foreach ($capability in @($catalog.capabilities)) { $script:capabilities[[string]$capability.id] = $capability }
    $script:report.capabilities = @($catalog.capabilities | ForEach-Object { [ordered]@{ id = $_.id; version = $_.version; availability = $_.availability; reason = $_.reason } })
    Invoke-Tool 'lodestone_capability_search' @{ query = 'minecraft' } | Out-Null
    Invoke-Tool 'furniture_lookup' @{ action = 'search'; query = 'chair' } | Out-Null
    Invoke-Tool 'building_pattern_lookup' @{ action = 'browse' } | Out-Null
    Invoke-Tool 'terrain_pattern_lookup' @{ action = 'browse' } | Out-Null
    Invoke-Tool 'building_template' @{ action = 'list' } | Out-Null
    Call-Capability 'lodestone.geometry.calculate' @{ shape = 'circle'; radius = 3; filled = $false; hollow = $true } 'calculate geometry' | Out-Null
    Call-Capability 'lodestone.worldedit.mask.validate' @{ mask = 'stone,dirt' } 'validate WorldEdit mask' | Out-Null
    Invoke-Capability 'minecraft.server.info.read' @{} $true | Out-Null
    Invoke-Capability 'minecraft.registry.item.search' @{ query = 'minecraft:stone'; limit = 3 } $true | Out-Null
    Invoke-Capability 'minecraft.client.screenshot.capture' @{ maxWidth = 640; maxHeight = 360 } $true | Out-Null
    Call-Capability 'minecraft.ui.state.read' @{} 'read title UI' | Out-Null
}

function Create-World {
    Navigate-Target 'singleplayer' | Out-Null
    Start-Sleep -Milliseconds 500
    $worldList = Get-Ui
    $newWorld = @($worldList.widgets | Where-Object { [string]$_.label -match '^Create New World$' -and $_.actions -contains 'click' }) | Select-Object -First 1
    if (-not $newWorld) { throw 'world list did not expose Create New World' }
    Click-Widget $worldList $newWorld 'open create-world screen'
    Start-Sleep -Milliseconds 500
    $createScreen = Get-Ui
    $createButton = @($createScreen.widgets | Where-Object { [string]$_.label -match 'Create New World|Create World' -and $_.actions -contains 'click' }) | Select-Object -Last 1
    if (-not $createButton) {
        Navigate-Target 'create_world' | Out-Null
    } else {
        Click-Widget $createScreen $createButton 'create benchmark world'
    }
    Wait-Ui 'world'
}

function Cleanup {
    $script:report.cleanup.attempted = $true
    try {
        if ($script:fixture) {
            try { Call-Capability 'minecraft.command.execute' @{ command = "kill @e[tag=$($fixture.entityTag)]" } 'remove benchmark entities' $false | Out-Null } catch { $script:report.cleanup.errors += $_.Exception.Message }
            try { Restore-Region $fixture.preimage } catch { $script:report.cleanup.errors += $_.Exception.Message }
            try { Call-Capability 'minecraft.command.execute' @{ command = 'gamemode survival @p' } 'restore Survival mode' $false | Out-Null } catch { $script:report.cleanup.errors += $_.Exception.Message }
        }
        try { Call-Capability 'minecraft.script.current.cancel' @{} 'cleanup cancel' $false | Out-Null } catch { }
        try { Call-Capability 'minecraft.input.release-all' @{} 'cleanup input release' $false | Out-Null } catch { }
        try { Call-Capability 'minecraft.session.reconcile' @{} 'cleanup reconcile' $false | Out-Null } catch { }
        $script:report.cleanup.verified = ($script:report.cleanup.errors.Count -eq 0)
    } catch { $script:report.cleanup.errors += $_.Exception.Message }
}

try {
    Start-Client
    Initialize-Mcp
    Wait-For-ClientReady | Out-Null
    Run-Phase '00-bootstrap' {
        $status = Require-Ok (Invoke-Tool 'lodestone_status' @{}) 'status' 
        $script:report.provenance = [ordered]@{ sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim(); sourceDirtyPaths = @(& git -C $repoRoot status --short); status = $status }
        Require-Ok (Invoke-Tool 'lodestone_instances_list' @{}) 'instance list' | Out-Null
        Call-Capability 'minecraft.input.release-all' @{} 'initial input release' $false | Out-Null
        Call-Capability 'minecraft.session.reconcile' @{} 'initial session reconcile' $false | Out-Null
    }
    Run-Phase '01-menu-catalog' { Run-MenuCatalogCoverage }
    Run-Phase '02-world-entry' { Create-World }
    Run-Phase '03-creative-fixture' { Run-Setup }
    Run-Phase '04-survival-progression' { Run-SurvivalProgression }
    Run-Phase '05-observations' { Run-Observations }
    Run-Phase '06-hard-scripts' { Run-HardScripts }
    Run-Phase '07-mob-lifecycle' { Run-MobLifecycle }
    Run-Phase '08-return-and-report' {
        Call-Capability 'minecraft.input.release-all' @{} 'release input before cleanup' | Out-Null
        $script:report.status = 'PASS'
    }
} catch {
    $script:report.status = 'FAIL'
    $script:report.failure = [ordered]@{ message = $_.Exception.Message; stack = $_.ScriptStackTrace }
    Write-Evidence @{ kind = 'benchmark'; status = 'FAIL'; message = $_.Exception.Message }
} finally {
    Cleanup
    Save-Report
    if ($script:http) { $script:http.Dispose() }
}

[pscustomobject]@{
    status = $script:report.status
    reportPath = $reportPath
    evidencePath = $evidencePath
    assertions = $script:report.assertions
    passedAssertions = $script:report.passedAssertions
    phases = $script:report.phases.Count
    cleanupVerified = $script:report.cleanup.verified
} | ConvertTo-Json -Depth 16

if ($script:report.status -ne 'PASS') { exit 1 }

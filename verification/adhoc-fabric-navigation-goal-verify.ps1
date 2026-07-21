[CmdletBinding()]
param(
    [int] $Port = 37830,
    [string] $EvidenceDirectory = '',
    [string] $JavaHome = ''
)

# One-off live verification for the Fabric 1.21.1 port of minecraft.goal.navigation.safe-waypoint
# (see FabricNavigationGoal / FabricSafePathPlanner / FabricGoalSupervisor). Modeled directly on
# the proven verification/run-neoforge-navigation-benchmarks.ps1 pattern, but: (1) launches
# :hosts:fabric:mc1_21_1:runClient instead of the NeoForge KeepFocus client, (2) skips ffmpeg
# recording - per run-goal-benchmark-multiseed.ps1's own established convention, results are
# scored from goal-report telemetry alone, (3) uses a small engineered flat-strip fixture instead
# of the full lava/maze benchmark course, since this is a single functional proof, not a scored
# benchmark suite.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) { $EvidenceDirectory = Join-Path $scriptRoot 'evidence' }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { $JavaHome = Join-Path $env:USERPROFILE 'scoop\apps\temurin21-jdk\current' }
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
$EvidenceDirectory = (Resolve-Path $EvidenceDirectory).Path
$stem = "fabric-navigation-verify-$timestamp"
$reportPath = Join-Path $EvidenceDirectory "$stem.json"
$stdoutLog = Join-Path $EvidenceDirectory "$stem.minecraft.stdout.log"
$stderrLog = Join-Path $EvidenceDirectory "$stem.minecraft.stderr.log"
$token = [guid]::NewGuid().ToString('N')

$script:requestId = 0
$script:sessionId = $null
$script:http = [System.Net.Http.HttpClient]::new()
$script:http.Timeout = [TimeSpan]::FromSeconds(75)
$script:uri = "http://127.0.0.1:$Port/mcp"
$launcher = $null
$clientJavaPid = $null
$report = [ordered]@{
    formatVersion = 1
    evidenceKind = 'mcp-created-fabric-navigation-verify'
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
    minecraftVersion = '1.21.1'
    loader = 'fabric'
    worldName = 'fabric-nav-verify'
    setupMode = 'creative'
    goalMode = 'survival'
    mcpPort = $Port
    cases = @()
    setupCommands = @()
}

function Invoke-Rpc {
    param([string] $Method, [hashtable] $Parameters = @{})
    $script:requestId++
    $body = @{ jsonrpc = '2.0'; id = $script:requestId; method = $Method; params = $Parameters } |
        ConvertTo-Json -Depth 64 -Compress
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $script:uri)
    $message.Headers.Add('X-Lodestone-Token', $token)
    if ($script:sessionId) {
        $message.Headers.Add('Mcp-Session-Id', $script:sessionId)
        $message.Headers.Add('MCP-Protocol-Version', '2025-11-25')
    }
    $message.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
    $response = $script:http.SendAsync($message).Result
    if ($response.Headers.Contains('Mcp-Session-Id')) { $script:sessionId = ($response.Headers.GetValues('Mcp-Session-Id') -join '') }
    [pscustomobject]@{ httpStatus = [int] $response.StatusCode; json = ($response.Content.ReadAsStringAsync().Result | ConvertFrom-Json) }
}

function Convert-ToolResponse {
    param($Rpc)
    if ($Rpc.json.error) { return [pscustomobject]@{ status = 'rpc-error'; error = $Rpc.json.error; output = $null } }
    $payload = $Rpc.json.result.structuredContent
    if ($null -eq $payload) { return [pscustomobject]@{ status = 'empty'; error = $null; output = $null } }
    if ($payload.status -in @('ok', 'error', 'cancelled', 'timed-out', 'failed', 'indeterminate')) {
        return [pscustomobject]@{ status = [string] $payload.status; error = $payload.error; output = $payload.output }
    }
    [pscustomobject]@{ status = if ($payload.status) { [string] $payload.status } else { 'ok' }; error = $null; output = $payload }
}

function Invoke-Tool {
    param([string] $Name, [hashtable] $Arguments = @{})
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        $response = Convert-ToolResponse (Invoke-Rpc 'tools/call' @{ name = $Name; arguments = $Arguments })
        if (-not $response.error -or $response.error.code -ne 'RATE_LIMIT_EXCEEDED') { return $response }
        Start-Sleep -Milliseconds 750
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
    param([string] $Capability, [string] $Version, [hashtable] $Input, [bool] $DryRun = $false)
    return Require-Ok (Invoke-Tool 'lodestone_capability_invoke' @{
        capability = $Capability; capabilityVersion = $Version; input = $Input; dryRun = $DryRun
    }) "capability $Capability"
}

function Wait-Ui {
    param([string] $Pattern = '', [int] $TimeoutMs = 120000)
    $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
    do {
        $state = Require-Ok (Invoke-Tool 'ui_state') 'ui_state'
        if ([string]::IsNullOrWhiteSpace($Pattern) -or [string] $state.screenClass -match $Pattern) { return $state }
        Start-Sleep -Milliseconds 400
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "UI readiness timed out for $Pattern"
}

function Click-UiWidget {
    param($State, $Widget, [string] $Operation)
    Require-Ok (Invoke-Tool 'lodestone_capability_invoke' @{
        capability = 'minecraft.ui.click'; capabilityVersion = '2.0'; dryRun = $false
        input = @{ screenToken = [string] $State.screenToken; snapshotRevision = [string] $State.snapshotRevision; nodeId = [string] $Widget.nodeId }
    }) $Operation | Out-Null
    Start-Sleep -Milliseconds 350
}

function Ensure-CreativeVerifyWorld {
    $state = Wait-Ui
    if ($state.inWorld) { throw 'refusing to modify an already-loaded world; verification requires a new world' }
    if ([string] $state.screenClass -match 'AccessibilityOnboardingScreen') {
        $continue = @($state.widgets | Where-Object { [string] $_.label -eq 'Continue' }) | Select-Object -First 1
        if (-not $continue) { throw 'accessibility onboarding did not expose Continue' }
        Click-UiWidget $state $continue 'dismiss accessibility onboarding'
        $state = Wait-Ui
    }
    Require-Ok (Invoke-Tool 'ui_navigate' @{ target = 'singleplayer' }) 'open singleplayer' | Out-Null
    Wait-Ui 'SelectWorld|WorldSelection' | Out-Null
    Require-Ok (Invoke-Tool 'ui_navigate' @{ target = 'create_new_world' }) 'open create world' | Out-Null
    $state = Wait-Ui 'CreateWorld'
    $textWidget = @($state.widgets | Where-Object { [string] $_.type -match 'EditBox' }) | Select-Object -First 1
    if ($textWidget) {
        Click-UiWidget $state $textWidget 'focus world name'
        for ($i = 0; $i -lt 9; $i++) {
            Invoke-Capability 'minecraft.ui.key' '1.0' @{ key = 259; scanCode = 14; modifiers = 0 } | Out-Null
        }
        Start-Sleep -Milliseconds 1100
    }
    Require-Ok (Invoke-Tool 'lodestone_capability_invoke' @{
        capability = 'minecraft.ui.text.insert'; capabilityVersion = '1.0'; dryRun = $false
        input = @{ text = 'fabric-nav-verify' }
    }) 'name fabric-nav-verify world' | Out-Null
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $state = Wait-Ui 'CreateWorld'
        $mode = @($state.widgets | Where-Object { [string] $_.label -match '^Game Mode:' }) | Select-Object -First 1
        if (-not $mode) { throw 'create-world screen did not expose a game-mode control' }
        if ([string] $mode.label -match 'Creative') { break }
        Click-UiWidget $state $mode 'cycle game mode toward creative'
        if ($attempt -eq 3) { throw "could not select creative mode; final label was $($mode.label)" }
    }
    $state = Wait-Ui 'CreateWorld'
    $selected = @($state.widgets | Where-Object { [string] $_.label -match '^Game Mode: Creative' })
    if ($selected.Count -ne 1) { throw 'creative mode was not verified on the create-world screen' }
    Require-Ok (Invoke-Tool 'ui_navigate' @{ target = 'create_world' }) 'create verify world' | Out-Null
    $world = Wait-Ui '' 120000
    if (-not $world.inWorld -or -not [string]::IsNullOrWhiteSpace([string] $world.screenClass)) {
        throw "verify world did not become ready: inWorld=$($world.inWorld), screen=$($world.screenClass)"
    }
    return $world
}

function Add-BlockChange {
    param([System.Collections.Generic.List[object]] $Changes, [int] $X, [int] $Y, [int] $Z, [string] $Block)
    $Changes.Add(@{ x = $X; y = $Y; z = $Z; block = $Block })
}

function Write-BlockBatch {
    param([System.Collections.Generic.List[object]] $Changes, [string] $Label)
    for ($offset = 0; $offset -lt $Changes.Count; $offset += 64) {
        if ($offset -gt 0) { Start-Sleep -Milliseconds 1100 }
        $end = [Math]::Min($offset + 64, $Changes.Count)
        $batch = @($Changes[$offset..($end - 1)])
        $write = Invoke-Capability 'minecraft.world.blocks.write' '1.0' @{
            dimension = 'minecraft:overworld'; changes = $batch
        }
        if ([int] $write.changedCount -lt 0) {
            throw "$Label write returned an invalid changedCount: $($write.changedCount)"
        }
    }
}

function Build-Fixture {
    # A small engineered flat strip, not the full NeoForge lava/maze course: this is a single
    # functional proof for the Fabric port, not a scored multi-case benchmark. Clears a 1-wide,
    # 2-tall corridor over a solid stone floor from spawn straight out to the target so natural
    # terrain (trees, slopes) cannot produce a false negative unrelated to the port itself.
    $player = (Invoke-Capability 'minecraft.player.state.read' '1.0' @{}).result
    $ox = [Math]::Floor([double] $player.position.x)
    $oy = [Math]::Floor([double] $player.position.y)
    $oz = [Math]::Floor([double] $player.position.z)
    $changes = [System.Collections.Generic.List[object]]::new()
    for ($x = 0; $x -le 12; $x++) {
        Add-BlockChange $changes ($ox + $x) ($oy - 1) $oz 'minecraft:stone'
        Add-BlockChange $changes ($ox + $x) $oy $oz 'minecraft:air'
        Add-BlockChange $changes ($ox + $x) ($oy + 1) $oz 'minecraft:air'
    }
    Add-BlockChange $changes ($ox + 10) ($oy - 1) $oz 'minecraft:gold_block'
    Write-BlockBatch $changes 'verify fixture'
    $report.fixtureOrigin = @{ x = $ox; y = $oy; z = $oz }
    $report.fixtureTarget = @{ x = ($ox + 10); y = $oy; z = $oz }
}

function Setup-Player {
    param($Position)
    foreach ($command in @(
        'gamemode creative @p',
        "tp @p $($Position.x) $($Position.y) $($Position.z)",
        'gamemode survival @p'
    )) {
        Invoke-Capability 'minecraft.command.execute' '1.0' @{ command = $command } | Out-Null
        $report.setupCommands += $command
        Start-Sleep -Milliseconds 1100
    }
}

function Run-GoalCase {
    param([string] $Id, [string] $Intelligence, [string] $Safety, $Start, $Target)
    Setup-Player $Start
    $goal = "reach fabric verify waypoint x=$($Target.x) y=$($Target.y) z=$($Target.z)"
    $response = Invoke-Tool 'minecraft_goal' @{
        goal = $goal; taskId = 'navigation.safe-waypoint'; mode = 'script'; maxSteps = 8
        maxDurationMs = 45000; dryRun = $false; intelligence = $Intelligence; safety = $Safety
        observation = 'loaded-chunks'; combatPolicy = 'avoid'; allowBlockBreaking = $false; allowBlockPlacing = $false
        suppressInGameMessages = $true
    }
    $outcome = [ordered]@{
        id = $Id; intelligence = $Intelligence; safety = $Safety
        status = $response.status; error = if ($response.error) { $response.error.message } else { $null }
        output = $response.output
    }
    $report.cases += $outcome
    return $outcome
}

function Stop-TestClient {
    if (-not $launcher) { return }
    $root = Get-CimInstance Win32_Process -Filter "ProcessId=$($launcher.Id)" -ErrorAction SilentlyContinue
    if ($root -and [string] $root.CommandLine -match 'runClient') { & taskkill.exe /PID $launcher.Id /T /F | Out-Null }
}

try {
    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "Java 21 executable not found: $javaExecutable" }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "port $Port is already in use" }

    # A brand-new run directory shows a genuine first-launch AccessibilityOnboardingScreen instead
    # of going straight to the title screen; the existing hosts/fabric/1.21.1/run directory
    # already carries a known-good options.txt from prior interactive sessions, avoiding the
    # documented "fresh run-dir can't self-prime" pitfall. Force pauseOnLostFocus off so world
    # ticking (and the ordinary-input navigation actor) keeps running while this script's own
    # HTTP calls, not the game window, hold OS focus.
    $fabricRunDirectory = Join-Path $repoRoot 'hosts\fabric\1.21.1\run'
    $optionsPath = Join-Path $fabricRunDirectory 'options.txt'
    if (Test-Path -LiteralPath $optionsPath) {
        $optionsContent = Get-Content -LiteralPath $optionsPath -Raw
        if ($optionsContent -match 'pauseOnLostFocus:true') {
            $optionsContent = $optionsContent -replace 'pauseOnLostFocus:true', 'pauseOnLostFocus:false'
            [System.IO.File]::WriteAllText($optionsPath, $optionsContent, (New-Object System.Text.UTF8Encoding $false))
        }
    }

    $env:JAVA_HOME = $JavaHome; $env:Path = "$JavaHome\bin;$env:Path"; $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
    $permissions = 'observe,communicate,control-player,modify-world,administer-server,capture-screen'
    # Native cmd.exe file redirection (>, 2>) rather than PowerShell's own managed
    # -RedirectStandardOutput/-RedirectStandardError stream pumping: the latter has shown
    # unbounded memory growth in this harness against a long-running, high-log-volume child
    # (a dev-environment Minecraft client with verbose Mixin debug logging) - the parent
    # PowerShell process ballooned to multiple GB and never reached its own timeout/cleanup path.
    $command = ".\gradlew.bat --no-daemon --console=plain -PincludeFabric262=false -Dlodestone.port=$Port -Dlodestone.token=$token -Dlodestone.permissions=$permissions :hosts:fabric:mc1_21_1:runClient > `"$stdoutLog`" 2> `"$stderrLog`""
    $launcher = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/c', $command -WorkingDirectory $repoRoot -WindowStyle Normal -PassThru
    for ($attempt = 0; $attempt -lt 300; $attempt++) {
        $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if ($listener) { $clientJavaPid = [int] $listener.OwningProcess; break }
        if ($launcher.HasExited) { throw "verify client exited before MCP opened: $($launcher.ExitCode)" }
        Start-Sleep -Seconds 1
    }
    if (-not $clientJavaPid) { throw "verify MCP endpoint did not open on port $Port" }

    $initialize = Invoke-Rpc 'initialize' @{ protocolVersion = '2025-11-25'; clientInfo = @{ name = 'lodestone-fabric-navigation-verify'; version = '1.0' } }
    if ($initialize.json.error -or $null -eq $initialize.json.result) { throw 'verify MCP initialize failed' }
    Invoke-Rpc 'notifications/initialized' @{} | Out-Null
    Ensure-CreativeVerifyWorld | Out-Null
    Build-Fixture
    $report.setupVerified = $true
    Run-GoalCase 'flat-strip-guarded-balanced' 'guarded-v1' 'balanced' $report.fixtureOrigin $report.fixtureTarget
    Run-GoalCase 'flat-strip-raw-low' 'raw-v1' 'low' $report.fixtureOrigin $report.fixtureTarget
    $report.status = if (@($report.cases | Where-Object { $_.status -notin @('ok', 'SUCCEEDED') }).Count -eq 0) { 'PASS' } else { 'PARTIAL' }
} catch {
    $report.status = 'FAIL'
    $report.failure = @{ message = $_.Exception.Message; stack = $_.ScriptStackTrace }
    throw
} finally {
    try { Stop-TestClient } catch { $report.clientCleanupFailure = $_.Exception.Message }
    $report.completedAtUtc = [DateTime]::UtcNow.ToString('o')
    if (Test-Path -LiteralPath $stdoutLog) { $report.clientStdoutTail = @(Get-Content -LiteralPath $stdoutLog -Tail 30) }
    if (Test-Path -LiteralPath $stderrLog) { $report.clientStderrTail = @(Get-Content -LiteralPath $stderrLog -Tail 30) }
    $report.clientListenerAfterCleanup = [bool] (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    $report | ConvertTo-Json -Depth 64 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    $script:http.Dispose()
}

[pscustomobject]@{ status = $report.status; reportPath = $reportPath; cases = $report.cases } |
    ConvertTo-Json -Depth 20

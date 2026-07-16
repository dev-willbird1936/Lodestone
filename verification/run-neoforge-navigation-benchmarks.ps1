[CmdletBinding()]
param(
    [int] $Port = 37830,
    [string] $EvidenceDirectory = '',
    [string] $JavaHome = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) { $EvidenceDirectory = Join-Path $scriptRoot 'evidence' }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { $JavaHome = Join-Path $env:USERPROFILE 'scoop\apps\temurin21-jdk\current' }
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
$EvidenceDirectory = (Resolve-Path $EvidenceDirectory).Path
$stem = "neoforge-navigation-benchmark-$timestamp"
$runDirectory = "runs/navigation-benchmark-$timestamp"
$reportPath = Join-Path $EvidenceDirectory "$stem.json"
$videoPath = Join-Path $EvidenceDirectory "$stem.mp4"
$stdoutLog = Join-Path $EvidenceDirectory "$stem.minecraft.stdout.log"
$stderrLog = Join-Path $EvidenceDirectory "$stem.minecraft.stderr.log"
$ffmpegLog = Join-Path $EvidenceDirectory "$stem.ffmpeg.log"
$token = [guid]::NewGuid().ToString('N')

$script:requestId = 0
$script:sessionId = $null
$script:http = [System.Net.Http.HttpClient]::new()
$script:http.Timeout = [TimeSpan]::FromMinutes(4)
$script:uri = "http://127.0.0.1:$Port/mcp"
$launcher = $null
$clientJavaPid = $null
$recorder = $null
$recorderErrorTask = $null
$report = [ordered]@{
    formatVersion = 1
    evidenceKind = 'mcp-created-navigation-benchmark'
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
    minecraftVersion = '1.21.1'
    loader = 'neoforge'
    keepFocus = $true
    worldName = 'benchmark'
    setupMode = 'creative'
    goalMode = 'survival'
    mcpPort = $Port
    runDirectory = $runDirectory
    videoPath = $videoPath
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

function Ensure-CreativeBenchmarkWorld {
    $state = Wait-Ui
    if ($state.inWorld) { throw 'refusing to modify an already-loaded world; benchmark requires a new world' }
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
        input = @{ text = 'benchmark' }
    }) 'name benchmark world' | Out-Null
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
    Require-Ok (Invoke-Tool 'ui_navigate' @{ target = 'create_world' }) 'create benchmark world' | Out-Null
    $world = Wait-Ui '' 120000
    if (-not $world.inWorld -or -not [string]::IsNullOrWhiteSpace([string] $world.screenClass)) {
        throw "benchmark world did not become ready: inWorld=$($world.inWorld), screen=$($world.screenClass)"
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

function Build-Fixtures {
    $player = (Invoke-Capability 'minecraft.player.state.read' '1.0' @{}).result
    $ox = [Math]::Floor([double] $player.position.x)
    $base = [Math]::Floor([double] $player.position.y)
    $oz = [Math]::Floor([double] $player.position.z)
    $lavaTargetX = $ox + 14
    $mazeStartX = $ox + 20
    $mazeTargetX = $ox + 38
    $changes = [System.Collections.Generic.List[object]]::new()
    for ($x = 0; $x -le 42; $x++) {
        for ($z = -10; $z -le 10; $z++) { Add-BlockChange $changes ($ox + $x) ($base - 1) ($oz + $z) 'minecraft:stone' }
    }
    for ($x = 4; $x -le 11; $x++) {
        for ($z = -2; $z -le 2; $z++) { Add-BlockChange $changes ($ox + $x) $base ($oz + $z) 'minecraft:lava' }
    }
    Add-BlockChange $changes $lavaTargetX ($base - 1) $oz 'minecraft:gold_block'
    foreach ($wall in @(
        @{ x = $ox + 23; gap = 5; low = -8; high = 5 },
        @{ x = $ox + 28; gap = -5; low = -5; high = 8 },
        @{ x = $ox + 33; gap = 5; low = -8; high = 5 }
    )) {
        for ($z = $wall.low; $z -le $wall.high; $z++) {
            if ($z -ne $wall.gap) {
                Add-BlockChange $changes $wall.x $base ($oz + $z) 'minecraft:stone'
                Add-BlockChange $changes $wall.x ($base + 1) ($oz + $z) 'minecraft:stone'
            }
        }
    }
    Add-BlockChange $changes $mazeTargetX ($base - 1) $oz 'minecraft:diamond_block'
    Write-BlockBatch $changes 'benchmark fixture'
    $report.fixtureOrigin = @{ x = $ox; y = $base; z = $oz }
    $report.fixtures = [ordered]@{
        lava = @{ start = @{ x = $ox; y = $base; z = $oz }; target = @{ x = $lavaTargetX; y = $base; z = $oz }; lavaX = @($ox + 4, $ox + 11); lavaZ = @($oz - 2, $oz + 2) }
        maze = @{ start = @{ x = $mazeStartX; y = $base; z = $oz }; target = @{ x = $mazeTargetX; y = $base; z = $oz }; wallGaps = @(5, -5, 5) }
    }
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
    param([string] $Id, [string] $Mode, [string] $Intelligence, [string] $Safety, $Start, $Target)
    Setup-Player $Start
    $goal = "reach benchmark waypoint x=$($Target.x) y=$($Target.y) z=$($Target.z)"
    $response = Invoke-Tool 'minecraft_goal' @{
        goal = $goal; taskId = 'navigation.safe-waypoint'; mode = $Mode; maxSteps = 8
        maxDurationMs = 45000; dryRun = $false; intelligence = $Intelligence; safety = $Safety
        observation = 'loaded-chunks'; combatPolicy = 'avoid'; allowBlockBreaking = $false; allowBlockPlacing = $false
        suppressInGameMessages = $true
    }
    $outcome = [ordered]@{
        id = $Id; mode = $Mode; intelligence = $Intelligence; safety = $Safety
        status = $response.status; error = if ($response.error) { $response.error.message } else { $null }
        output = $response.output
    }
    $report.cases += $outcome
}

function Start-Recorder {
    param([string] $WindowTitle)
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = (Get-Command ffmpeg -ErrorAction Stop).Source
    $psi.Arguments = "-hide_banner -y -f gdigrab -framerate 30 -draw_mouse 0 -i `"title=$WindowTitle`" -c:v libx264 -preset ultrafast -crf 20 -pix_fmt yuv420p -movflags +faststart `"$videoPath`""
    $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true; $psi.RedirectStandardInput = $true; $psi.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new(); $process.StartInfo = $psi
    if (-not $process.Start()) { throw 'benchmark ffmpeg recorder did not start' }
    $script:recorderErrorTask = $process.StandardError.ReadToEndAsync()
    Start-Sleep -Seconds 2
    if ($process.HasExited) { throw "benchmark recorder exited: $($script:recorderErrorTask.Result)" }
    return $process
}

function Stop-Recorder {
    if (-not $script:recorder) { return }
    if (-not $script:recorder.HasExited) {
        $script:recorder.StandardInput.WriteLine('q'); $script:recorder.StandardInput.Flush()
        if (-not $script:recorder.WaitForExit(30000)) { Stop-Process -Id $script:recorder.Id -Force -ErrorAction SilentlyContinue }
    }
    if ($script:recorderErrorTask) { $script:recorderErrorTask.Result | Set-Content -LiteralPath $ffmpegLog -Encoding UTF8 }
}

function Stop-TestClient {
    if (-not $launcher) { return }
    $root = Get-CimInstance Win32_Process -Filter "ProcessId=$($launcher.Id)" -ErrorAction SilentlyContinue
    if ($root -and [string] $root.CommandLine -match 'runKeepFocusClient') { & taskkill.exe /PID $launcher.Id /T /F | Out-Null }
}

try {
    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    $keepFocus = Join-Path $repoRoot '..\KeepFocus\build\libs\keep_focus-1.0.0+1.21.1.jar'
    if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "Java 21 executable not found: $javaExecutable" }
    if (-not (Test-Path -LiteralPath $keepFocus)) { throw "KeepFocus artifact not found: $keepFocus" }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "port $Port is already in use" }
    $env:JAVA_HOME = $JavaHome; $env:Path = "$JavaHome\bin;$env:Path"; $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
    $permissions = 'observe,communicate,control-player,modify-world,administer-server,capture-screen'
    $command = "gradlew.bat --no-daemon --console=plain -PincludeFabric262=false -Dlodestone.runDirectory=$runDirectory -Dlodestone.port=$Port -Dlodestone.token=$token -Dlodestone.permissions=$permissions :hosts:neoforge:mc1_21_1:runKeepFocusClient"
    $launcher = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/c', $command -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru
    for ($attempt = 0; $attempt -lt 300; $attempt++) {
        $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if ($listener) { $clientJavaPid = [int] $listener.OwningProcess; break }
        if ($launcher.HasExited) { throw "benchmark client exited before MCP opened: $($launcher.ExitCode)" }
        Start-Sleep -Seconds 1
    }
    if (-not $clientJavaPid) { throw "benchmark MCP endpoint did not open on port $Port" }
    $windowTitle = ''
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        $client = Get-Process -Id $clientJavaPid -ErrorAction SilentlyContinue
        if ($client -and $client.MainWindowHandle -ne 0 -and -not [string]::IsNullOrWhiteSpace($client.MainWindowTitle)) { $windowTitle = [string] $client.MainWindowTitle; break }
        Start-Sleep -Seconds 1
    }
    if ([string]::IsNullOrWhiteSpace($windowTitle)) { throw 'benchmark Minecraft window did not become recordable' }
    $script:recorder = Start-Recorder $windowTitle
    $initialize = Invoke-Rpc 'initialize' @{ protocolVersion = '2025-11-25'; clientInfo = @{ name = 'lodestone-navigation-benchmark'; version = '1.0' } }
    if ($initialize.json.error -or $null -eq $initialize.json.result) { throw 'benchmark MCP initialize failed' }
    Invoke-Rpc 'notifications/initialized' @{} | Out-Null
    Ensure-CreativeBenchmarkWorld | Out-Null
    Build-Fixtures
    $report.setupVerified = $true
    $report.fixtureTests = 'lava-crossing plus three-gap maze'
    Run-GoalCase 'lava-guarded-high-realtime' 'realtime' 'guarded-v1' 'high' $report.fixtures.lava.start $report.fixtures.lava.target
    Run-GoalCase 'maze-raw-script' 'script' 'raw-v1' 'low' $report.fixtures.maze.start $report.fixtures.maze.target
    Run-GoalCase 'maze-guarded-high-realtime' 'realtime' 'guarded-v1' 'high' $report.fixtures.maze.start $report.fixtures.maze.target
    $report.status = if (@($report.cases | Where-Object { $_.status -notin @('ok', 'SUCCEEDED') }).Count -eq 0) { 'PASS' } else { 'PARTIAL' }
} catch {
    $report.status = 'FAIL'
    $report.failure = @{ message = $_.Exception.Message; stack = $_.ScriptStackTrace }
    throw
} finally {
    try { Stop-Recorder } catch { $report.recorderFailure = $_.Exception.Message }
    try { Stop-TestClient } catch { $report.clientCleanupFailure = $_.Exception.Message }
    $report.completedAtUtc = [DateTime]::UtcNow.ToString('o')
    if (Test-Path -LiteralPath $stdoutLog) { $report.clientStdoutTail = @(Get-Content -LiteralPath $stdoutLog -Tail 30) }
    if (Test-Path -LiteralPath $stderrLog) { $report.clientStderrTail = @(Get-Content -LiteralPath $stderrLog -Tail 30) }
    $report.clientListenerAfterCleanup = [bool] (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    $report | ConvertTo-Json -Depth 64 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    $script:http.Dispose()
}

[pscustomobject]@{ status = $report.status; reportPath = $reportPath; videoPath = $videoPath; cases = $report.cases } |
    ConvertTo-Json -Depth 20

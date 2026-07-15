[CmdletBinding()]
param(
    [int] $Port = 37828,
    [string] $EvidenceDirectory = '',
    [string] $KeepFocusArtifact = '',
    [string] $JavaHome = '',
    [switch] $ExistingClient,
    [switch] $StopExistingClient
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) { $EvidenceDirectory = Join-Path $scriptRoot 'evidence' }
if ([string]::IsNullOrWhiteSpace($KeepFocusArtifact)) {
    $KeepFocusArtifact = Join-Path $scriptRoot '..\..\KeepFocus\build\libs\keep_focus-1.0.0+1.21.1.jar'
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) { $JavaHome = Join-Path $env:USERPROFILE 'scoop\apps\temurin21-jdk\current' }

$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
$reportPath = Join-Path $EvidenceDirectory "neoforge-goal-benchmark-$timestamp.json"
$stdoutLog = Join-Path $env:TEMP "lodestone-neoforge-goal-$timestamp.stdout.log"
$stderrLog = Join-Path $env:TEMP "lodestone-neoforge-goal-$timestamp.stderr.log"
$token = [guid]::NewGuid().ToString('N')
$script:requestId = 0
$script:sessionId = $null
$script:http = [System.Net.Http.HttpClient]::new()
$script:http.Timeout = [TimeSpan]::FromSeconds(15)
$script:uri = "http://127.0.0.1:$Port/mcp"
$clientLauncher = $null
$clientPid = $null
$clientJavaPid = $null
$report = [ordered]@{
    formatVersion = 1
    benchmark = 'neoforge-1.21.1-keepfocus-goal-modes'
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
    minecraftVersion = '1.21.1'
    loader = 'neoforge'
    mod = 'KeepFocus'
    taskId = 'tools.inspect-nearby'
    goal = 'inspect the nearby world and entities'
    clientStdoutLog = $stdoutLog
    clientStderrLog = $stderrLog
    steps = [ordered]@{}
}

function Save-Report {
    $report.completedAtUtc = [DateTime]::UtcNow.ToString('o')
    $report | ConvertTo-Json -Depth 64 | Set-Content -LiteralPath $reportPath -Encoding UTF8
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
    if ($response.Headers.Contains('Mcp-Session-Id')) {
        $script:sessionId = ($response.Headers.GetValues('Mcp-Session-Id') -join '')
    }
    [pscustomobject]@{
        httpStatus = [int] $response.StatusCode
        json = ($response.Content.ReadAsStringAsync().Result | ConvertFrom-Json)
    }
}

function Convert-ToolResponse {
    param($Rpc)

    if ($Rpc.json.error) {
        return [pscustomobject]@{ status = 'rpc-error'; error = $Rpc.json.error; output = $null }
    }
    $payload = $Rpc.json.result.structuredContent
    if ($null -eq $payload) {
        return [pscustomobject]@{ status = 'empty'; error = $null; output = $null }
    }
    if ($payload.status -in @('ok', 'error', 'cancelled', 'timed-out')) {
        return [pscustomobject]@{ status = [string] $payload.status; error = $payload.error; output = $payload.output }
    }
    [pscustomobject]@{
        status = if ($payload.status) { [string] $payload.status } else { 'ok' }
        error = $null
        output = $payload
    }
}

function Invoke-Tool {
    param([string] $Name, [hashtable] $Arguments = @{})

    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $response = Convert-ToolResponse (Invoke-Rpc 'tools/call' @{ name = $Name; arguments = $Arguments })
        if ($response.error.code -ne 'RATE_LIMIT_EXCEEDED') { return $response }
        Start-Sleep -Milliseconds 700
    }
    return $response
}

function Require-Ok {
    param($Response, [string] $Operation)
    if ($Response.status -ne 'ok') {
        $code = if ($Response.error) { [string] $Response.error.code } else { 'UNKNOWN' }
        $message = if ($Response.error) { [string] $Response.error.message } else { 'no response payload' }
        throw "$Operation failed: $code $message"
    }
    return $Response.output
}

function Wait-ForWorld {
    $last = $null
    for ($attempt = 0; $attempt -lt 240; $attempt++) {
        $state = Require-Ok (Invoke-Tool 'ui_state') 'ui_state'
        $last = $state
        if ($state.inWorld -and [string]::IsNullOrWhiteSpace([string] $state.screenClass)) { return $state }
        Start-Sleep -Milliseconds 500
    }
    throw "world readiness timed out: screen=$($last.screenClass), inWorld=$($last.inWorld)"
}

function Wait-ForScreen {
    param([string] $Pattern)
    $last = $null
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        $last = Require-Ok (Invoke-Tool 'ui_state') 'ui_state'
        if ([string] $last.screenClass -match $Pattern) {
            Start-Sleep -Milliseconds 750
            return (Require-Ok (Invoke-Tool 'ui_state') 'settled ui_state')
        }
        Start-Sleep -Milliseconds 250
    }
    throw "screen transition timed out: expected $Pattern, got $($last.screenClass)"
}

function Invoke-Capability {
    param([string] $Capability, [string] $Version, [hashtable] $CapabilityInput)
    Require-Ok (Invoke-Tool 'lodestone_capability_invoke' @{
        capability = $Capability
        capabilityVersion = $Version
        input = $CapabilityInput
        dryRun = $false
    }) "capability $Capability"
}

function Stop-TestClient {
    $processes = @()
    if ($clientJavaPid) { $processes += [int] $clientJavaPid }
    if ($clientLauncher) { $processes += [int] $clientLauncher.Id }
    if ($clientPid) { $processes += [int] $clientPid }
    foreach ($processId in ($processes | Select-Object -Unique)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
        if ($process -and ([string] $process.CommandLine -match 'runKeepFocusClient|lodestone\.port=')) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        }
    }
}

try {
    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "Java 21 executable not found: $javaExecutable" }
    if (-not (Test-Path -LiteralPath $KeepFocusArtifact)) { throw "KeepFocus artifact not found: $KeepFocusArtifact" }

    if ($ExistingClient) {
        $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if (-not $listener) { throw "existing client is not listening on port $Port" }
        $clientJavaPid = [int] $listener.OwningProcess
        $javaProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$clientJavaPid"
        $existingToken = [regex]::Match([string] $javaProcess.CommandLine, '-Dlodestone.token=([^\s]+)').Groups[1].Value
        if ([string]::IsNullOrWhiteSpace($existingToken)) { throw 'existing client command line did not contain a Lodestone token' }
        $token = $existingToken
        $report.steps.clientBoot = [ordered]@{ status = 'PASS'; mode = 'existing'; javaPid = $clientJavaPid }
    } else {
        $env:JAVA_HOME = $JavaHome
        $env:Path = "$JavaHome\bin;$env:Path"
        $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
        $env:LODESTONE_PERMISSIONS = 'observe,communicate,control-player,modify-world,administer-server,capture-screen'
        $command = "gradlew.bat --no-daemon --console=plain -Dlodestone.port=$Port -Dlodestone.token=$token -Dlodestone.permissions=$env:LODESTONE_PERMISSIONS :hosts:neoforge:mc1_21_1:runKeepFocusClient"
        $clientLauncher = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/c', $command -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru

        for ($attempt = 0; $attempt -lt 120; $attempt++) {
            $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
            if ($listener) {
                $clientPid = [int] $clientLauncher.Id
                $clientJavaPid = [int] $listener.OwningProcess
                break
            }
            if ($clientLauncher.HasExited) { throw "client launcher exited before MCP endpoint opened: $($clientLauncher.ExitCode)" }
            Start-Sleep -Seconds 1
        }
        if (-not $clientPid) { throw "MCP endpoint did not open on port $Port" }
        $report.steps.clientBoot = [ordered]@{ status = 'PASS'; mode = 'launched'; launcherPid = $clientPid; javaPid = $clientJavaPid }
    }

    $initialize = Invoke-Rpc 'initialize' @{
        protocolVersion = '2025-11-25'
        clientInfo = @{ name = 'lodestone-recorded-goal-benchmark'; version = '1.0' }
    }
    if ($initialize.json.error -or $null -eq $initialize.json.result) {
        $message = if ($initialize.json.error) { $initialize.json.error.message } else { 'no initialize result payload' }
        throw "MCP initialize failed: $message"
    }
    $report.steps.mcpInitialize = [ordered]@{ status = 'PASS'; httpStatus = $initialize.httpStatus }
    Invoke-Rpc 'notifications/initialized' @{} | Out-Null

    $ui = Require-Ok (Invoke-Tool 'ui_state') 'initial ui_state'
    if (-not $ui.inWorld) {
        Require-Ok (Invoke-Tool 'ui_navigate' @{ target = 'singleplayer' }) 'open singleplayer' | Out-Null
        Wait-ForScreen 'SelectWorld|WorldSelection' | Out-Null
        Require-Ok (Invoke-Tool 'ui_navigate' @{ target = 'create_new_world' }) 'open create-world screen' | Out-Null
        Wait-ForScreen 'CreateWorld' | Out-Null
        Require-Ok (Invoke-Tool 'ui_navigate' @{ target = 'create_world' }) 'create fresh world' | Out-Null
        $ui = Wait-ForWorld
    }
    $report.steps.newWorld = [ordered]@{ status = 'PASS'; screenClass = [string] $ui.screenClass; inWorld = [bool] $ui.inWorld }

    $catalog = Require-Ok (Invoke-Tool 'minecraft_goal_tasks' @{ category = 'tools' }) 'minecraft_goal_tasks'
    $report.steps.taskCatalog = [ordered]@{ status = 'PASS'; taskCount = @($catalog.tasks).Count; taskId = $report.taskId }

    $goalArgs = @{
        goal = $report.goal
        taskId = $report.taskId
        maxSteps = 16
        maxDurationMs = 30000
        dryRun = $false
    }
    $scriptResponse = Invoke-Tool 'minecraft_goal' ($goalArgs + @{ mode = 'script' })
    $realtimeResponse = Invoke-Tool 'minecraft_goal' ($goalArgs + @{ mode = 'realtime' })
    $benchmarkResponse = Invoke-Tool 'minecraft_goal_benchmark' @{ taskIds = @($report.taskId); dryRun = $false }
    $benchmark = @($benchmarkResponse.output.results)[0]
    $report.steps.script = $scriptResponse.output
    $report.steps.realtime = $realtimeResponse.output
    $report.steps.benchmark = $benchmark
    $report.summary = [ordered]@{
        scriptStatus = [string] $scriptResponse.status
        realtimeStatus = [string] $realtimeResponse.status
        benchmarkStatus = [string] $benchmarkResponse.status
        benchmarkComparison = [string] $benchmark.comparison
        allGoalRunsSucceeded = ([string] $scriptResponse.status -eq 'SUCCEEDED' -and
            [string] $realtimeResponse.status -eq 'SUCCEEDED' -and
            [string] $benchmark.script.status -eq 'SUCCEEDED' -and
            [string] $benchmark.realtime.status -eq 'SUCCEEDED')
    }
    if (-not $report.summary.allGoalRunsSucceeded) { throw "one or more recorded goal runs failed" }

    # Request a normal MCP shutdown; the finally block still guarantees cleanup if the
    # dev launcher remains alive after the game returns to the title screen.
    try {
        $state = Require-Ok (Invoke-Tool 'ui_state') 'shutdown ui_state'
        if ($state.inWorld -and [string]::IsNullOrWhiteSpace([string] $state.screenClass)) {
            Invoke-Capability 'minecraft.ui.key' '1.0' @{ key = 256; scanCode = 0; modifiers = 0 } | Out-Null
            Start-Sleep -Milliseconds 500
            $state = Require-Ok (Invoke-Tool 'ui_state') 'pause ui_state'
        }
        if ([string] $state.screenClass -match 'PauseScreen') {
            $quit = @($state.widgets | Where-Object {
                $_.actions -contains 'click' -and $_.label -match 'Save.*Quit.*Title|Quit.*Title'
            }) | Select-Object -First 1
            if ($quit) {
                Invoke-Capability 'minecraft.ui.click' '2.0' @{
                    screenToken = [string] $state.screenToken
                    snapshotRevision = [string] $state.snapshotRevision
                    nodeId = [string] $quit.nodeId
                } | Out-Null
                Start-Sleep -Seconds 2
            }
        }
        $report.steps.shutdown = [ordered]@{ status = 'REQUESTED' }
    } catch {
        $report.steps.shutdown = [ordered]@{ status = 'REQUEST_FAILED'; message = $_.Exception.Message }
    }
    $report.status = 'PASS'
} catch {
    $report.status = 'FAIL'
    $report.failure = [ordered]@{ message = $_.Exception.Message; stack = $_.ScriptStackTrace }
    throw
} finally {
    try {
        if (Test-Path -LiteralPath $stdoutLog) {
            $report.clientStdoutTail = @(Get-Content -LiteralPath $stdoutLog -Tail 40)
        }
        if (Test-Path -LiteralPath $stderrLog) {
            $report.clientStderrTail = @(Get-Content -LiteralPath $stderrLog -Tail 40)
        }
        if ($clientPid -or ($ExistingClient -and $StopExistingClient)) { Stop-TestClient }
        $report.clientListenerAfterCleanup = [bool] (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    } finally {
        $script:http.Dispose()
        Save-Report
    }
}

[pscustomobject]@{
    status = $report.status
    reportPath = $reportPath
    scriptStatus = $report.summary.scriptStatus
    realtimeStatus = $report.summary.realtimeStatus
    benchmarkComparison = $report.summary.benchmarkComparison
} | ConvertTo-Json -Depth 8

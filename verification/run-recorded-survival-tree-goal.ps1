[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('script', 'realtime')]
    [string] $Mode,
    [int] $Port = 37828,
    [string] $EvidenceDirectory = '',
    [string] $JavaHome = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $EvidenceDirectory = Join-Path $scriptRoot 'evidence'
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = Join-Path $env:USERPROFILE 'scoop\apps\temurin21-jdk\current'
}

$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$stem = "minecraft-goal-survival-wooden-axe-$Mode-$timestamp"
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
$EvidenceDirectory = (Resolve-Path $EvidenceDirectory).Path
$videoPath = Join-Path $EvidenceDirectory "$stem.mp4"
$reportPath = Join-Path $EvidenceDirectory "$stem.json"
$stdoutLog = Join-Path $EvidenceDirectory "$stem.minecraft.stdout.log"
$stderrLog = Join-Path $EvidenceDirectory "$stem.minecraft.stderr.log"
$ffmpegLog = Join-Path $EvidenceDirectory "$stem.ffmpeg.log"
$goalText = 'load into survival, get a wooden axe and mine a full tree with it'
$token = [guid]::NewGuid().ToString('N')

$script:requestId = 0
$script:sessionId = $null
$script:http = [System.Net.Http.HttpClient]::new()
$script:http.Timeout = [TimeSpan]::FromMinutes(12)
$script:uri = "http://127.0.0.1:$Port/mcp"
$launcher = $null
$clientJavaPid = $null
$recorder = $null
$recorderErrorTask = $null
$goalInvocationCount = 0
$report = [ordered]@{
    formatVersion = 1
    evidenceKind = 'recorded-authentic-survival-goal'
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
    minecraftVersion = '1.21.1'
    loader = 'neoforge'
    keepFocus = $true
    mode = $Mode
    taskId = 'survival.wooden-axe-mine-tree'
    goal = $goalText
    videoPath = $videoPath
    clientStdoutLog = $stdoutLog
    clientStderrLog = $stderrLog
    recorderLog = $ffmpegLog
    mcpPort = $Port
    advisorCheckpoint = [ordered]@{
        status = 'UNAVAILABLE'
        reason = 'Required read-only checkpoint was attempted earlier, blocked by recursive subprocesses, and explicitly ordered not to be retried.'
    }
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

function Invoke-SetupTool {
    param([string] $Name, [hashtable] $Arguments = @{})

    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        $response = Convert-ToolResponse (Invoke-Rpc 'tools/call' @{ name = $Name; arguments = $Arguments })
        if ($response.error.code -ne 'RATE_LIMIT_EXCEEDED') { return $response }
        Start-Sleep -Milliseconds 750
    }
    return $response
}

function Require-SetupOk {
    param($Response, [string] $Operation)
    if ($Response.status -ne 'ok') {
        $code = if ($Response.error) { [string] $Response.error.code } else { [string] $Response.status }
        $message = if ($Response.error) { [string] $Response.error.message } else { 'no successful payload' }
        throw "$Operation failed: $code $message"
    }
    return $Response.output
}

function Start-Recorder {
    param([string] $WindowTitle)

    $ffmpeg = (Get-Command ffmpeg -ErrorAction Stop).Source
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $ffmpeg
    $psi.Arguments = "-hide_banner -y -f gdigrab -framerate 30 -draw_mouse 0 -i `"title=$WindowTitle`" -c:v libx264 -preset ultrafast -crf 20 -pix_fmt yuv420p -movflags +faststart `"$videoPath`""
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    if (-not $process.Start()) { throw 'ffmpeg recorder did not start' }
    $script:recorderErrorTask = $process.StandardError.ReadToEndAsync()
    Start-Sleep -Seconds 2
    if ($process.HasExited) {
        $stderr = $script:recorderErrorTask.Result
        $stderr | Set-Content -LiteralPath $ffmpegLog -Encoding UTF8
        throw "ffmpeg recorder exited before the run: $stderr"
    }
    return $process
}

function Stop-Recorder {
    if (-not $script:recorder) { return }
    if (-not $script:recorder.HasExited) {
        $script:recorder.StandardInput.WriteLine('q')
        $script:recorder.StandardInput.Flush()
        if (-not $script:recorder.WaitForExit(30000)) {
            Stop-Process -Id $script:recorder.Id -Force -ErrorAction SilentlyContinue
            throw 'ffmpeg did not close the MP4 within 30 seconds'
        }
    }
    if ($script:recorderErrorTask) {
        $script:recorderErrorTask.Result | Set-Content -LiteralPath $ffmpegLog -Encoding UTF8
    }
}

function Stop-TaskClient {
    if (-not $launcher) { return }
    $rootProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($launcher.Id)" -ErrorAction SilentlyContinue
    if ($rootProcess -and [string] $rootProcess.CommandLine -match 'runKeepFocusClient') {
        & taskkill.exe /PID $launcher.Id /T /F | Out-Null
    }
}

try {
    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    $keepFocus = Join-Path $repoRoot '..\KeepFocus\build\libs\keep_focus-1.0.0+1.21.1.jar'
    if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "Java 21 executable not found: $javaExecutable" }
    if (-not (Test-Path -LiteralPath $keepFocus)) { throw "KeepFocus artifact not found: $keepFocus" }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "port $Port already has a listener; refusing to attach to a non-task process"
    }

    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
    $permissions = 'observe,communicate,control-player,modify-world,administer-server,capture-screen'
    $command = "gradlew.bat --no-daemon --console=plain -PincludeFabric262=false -Dlodestone.port=$Port -Dlodestone.token=$token -Dlodestone.permissions=$permissions :hosts:neoforge:mc1_21_1:runKeepFocusClient"
    $report.launchCommand = $command -replace [regex]::Escape($token), '<redacted>'
    $launcher = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/c', $command -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru
    $report.launcherPid = [int] $launcher.Id

    for ($attempt = 0; $attempt -lt 300; $attempt++) {
        $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if ($listener) {
            $clientJavaPid = [int] $listener.OwningProcess
            break
        }
        if ($launcher.HasExited) { throw "client launcher exited before MCP opened: $($launcher.ExitCode)" }
        Start-Sleep -Seconds 1
    }
    if (-not $clientJavaPid) { throw "MCP endpoint did not open on port $Port within five minutes" }
    $report.clientJavaPid = $clientJavaPid

    $windowTitle = ''
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        $clientProcess = Get-Process -Id $clientJavaPid -ErrorAction SilentlyContinue
        if ($clientProcess -and $clientProcess.MainWindowHandle -ne 0 -and -not [string]::IsNullOrWhiteSpace($clientProcess.MainWindowTitle)) {
            $windowTitle = [string] $clientProcess.MainWindowTitle
            break
        }
        Start-Sleep -Seconds 1
    }
    if ([string]::IsNullOrWhiteSpace($windowTitle)) { throw 'Minecraft window did not become recordable' }
    $report.minecraftWindowTitle = $windowTitle
    $script:recorder = Start-Recorder -WindowTitle $windowTitle
    $report.recorderPid = [int] $script:recorder.Id

    $initialize = Invoke-Rpc 'initialize' @{
        protocolVersion = '2025-11-25'
        clientInfo = @{ name = 'lodestone-recorded-survival-goal'; version = '1.0' }
    }
    if ($initialize.json.error -or $null -eq $initialize.json.result) {
        $message = if ($initialize.json.error) { $initialize.json.error.message } else { 'no initialize result' }
        throw "MCP initialize failed: $message"
    }
    Invoke-Rpc 'notifications/initialized' @{} | Out-Null
    $report.mcpInitializeHttpStatus = $initialize.httpStatus

    $initialUi = $null
    $initialUiError = $null
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        $uiResponse = Invoke-SetupTool 'ui_state'
        if ($uiResponse.status -eq 'ok') {
            $initialUi = $uiResponse.output
            if (-not $initialUi.inWorld -and [string] $initialUi.screenClass -match 'TitleScreen') { break }
        } elseif ([string] $uiResponse.error.code -eq 'CAPABILITY_UNAVAILABLE') {
            $initialUiError = [string] $uiResponse.error.message
        } else {
            Require-SetupOk $uiResponse 'initial ui_state' | Out-Null
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $initialUi -or $initialUi.inWorld -or [string] $initialUi.screenClass -notmatch 'TitleScreen') {
        throw "fresh title-screen state not reached: inWorld=$($initialUi.inWorld), screen=$($initialUi.screenClass), lastTransient=$initialUiError"
    }
    $report.initialUi = [ordered]@{
        inWorld = [bool] $initialUi.inWorld
        screenClass = [string] $initialUi.screenClass
        screenToken = [string] $initialUi.screenToken
    }

    $tasks = Require-SetupOk (Invoke-SetupTool 'minecraft_goal_tasks' @{ category = 'survival' }) 'minecraft_goal_tasks'
    $task = @($tasks.tasks | Where-Object { $_.id -eq 'survival.wooden-axe-mine-tree' }) | Select-Object -First 1
    if (-not $task) { throw 'survival.wooden-axe-mine-tree is absent from the live task catalog' }
    if (@($task.requiredCapabilities) -notcontains 'minecraft.goal.survival.wooden-axe-tree') {
        throw 'survival task is missing its authentic NeoForge gameplay capability'
    }
    $report.taskCatalogEntry = [ordered]@{
        id = [string] $task.id
        category = [string] $task.category
        gameMode = [string] $task.gameMode
        requiredCapabilities = [string[]] @($task.requiredCapabilities)
        successContract = [string] $task.successContract
    }

    $goalInvocationCount++
    $goalRpc = Invoke-Rpc 'tools/call' @{
        name = 'minecraft_goal'
        arguments = @{
            goal = $goalText
            mode = $Mode
            taskId = 'survival.wooden-axe-mine-tree'
            maxSteps = 32
            maxDurationMs = 480000
            dryRun = $false
        }
    }
    $goalResponse = Convert-ToolResponse $goalRpc
    $report.goalInvocationCount = $goalInvocationCount
    $report.goalToolResponseStatus = $goalResponse.status
    $report.goalResult = $goalResponse.output

    if ($goalResponse.status -ne 'SUCCEEDED') {
        $detail = if ($goalResponse.error) { $goalResponse.error | ConvertTo-Json -Depth 12 -Compress } else { [string] $goalResponse.output.message }
        throw "single minecraft_goal call did not succeed: status=$($goalResponse.status), detail=$detail"
    }
    $state = $goalResponse.output.state.steps.'survival-workflow'
    if (-not $state) { throw 'goal result is missing state.steps.survival-workflow' }
    $report.goalPredicates = [ordered]@{
        survival = [bool] $state.survival
        freshWorld = [bool] $state.freshWorld
        handMinedLogs = [int] $state.handMinedLogs
        planksCrafted = [int] $state.planksCrafted
        sticksCrafted = [int] $state.sticksCrafted
        craftingTableCrafted = [bool] $state.craftingTableCrafted
        woodenAxeCrafted = [bool] $state.woodenAxeCrafted
        woodenAxeEquipped = [bool] $state.woodenAxeEquipped
        targetTreeInitialLogs = [int] $state.targetTreeInitialLogs
        targetTreeRemainingLogs = [int] $state.targetTreeRemainingLogs
        fullTreeMined = [bool] $state.fullTreeMined
        allTargetLogsMinedWithWoodenAxe = [bool] $state.allTargetLogsMinedWithWoodenAxe
        commandsUsed = [bool] $state.commandsUsed
        directMutationUsed = [bool] $state.directMutationUsed
    }
    $requiredPredicates = @(
        [bool] $state.survival,
        [bool] $state.freshWorld,
        ([int] $state.handMinedLogs -ge 3),
        [bool] $state.craftingTableCrafted,
        [bool] $state.woodenAxeCrafted,
        [bool] $state.woodenAxeEquipped,
        ([int] $state.targetTreeInitialLogs -ge 3),
        ([int] $state.targetTreeRemainingLogs -eq 0),
        [bool] $state.fullTreeMined,
        [bool] $state.allTargetLogsMinedWithWoodenAxe,
        (-not [bool] $state.commandsUsed),
        (-not [bool] $state.directMutationUsed)
    )
    if ($requiredPredicates -contains $false) {
        throw 'goal returned SUCCEEDED without every required survival/tree predicate'
    }

    Start-Sleep -Seconds 5
    Stop-Recorder
    $script:recorder = $null

    $probeJson = & ffprobe -v error -show_entries 'format=duration,format_name' -show_entries 'stream=codec_name,codec_type,width,height,nb_frames' -of json -- $videoPath
    if ($LASTEXITCODE -ne 0) { throw 'ffprobe could not parse the recorded MP4 (missing or unclosed moov atom)' }
    $probe = $probeJson | ConvertFrom-Json
    $videoStream = @($probe.streams | Where-Object { $_.codec_type -eq 'video' }) | Select-Object -First 1
    if (-not $videoStream -or $videoStream.codec_name -ne 'h264' -or [double] $probe.format.duration -le 0) {
        throw 'recorded evidence is not a nonzero H.264 video'
    }
    $report.ffprobe = $probe
    $report.status = 'PASS'
} catch {
    $report.status = 'FAIL'
    $report.failure = [ordered]@{ message = $_.Exception.Message; stack = $_.ScriptStackTrace }
    throw
} finally {
    try {
        if ($script:recorder) {
            try { Stop-Recorder } catch { $report.recorderCleanupFailure = $_.Exception.Message }
            $script:recorder = $null
        }
        if ($launcher) { Stop-TaskClient }
        Start-Sleep -Milliseconds 500
        $report.listenerAfterCleanup = [bool] (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        $report.goalInvocationCount = $goalInvocationCount
        $report.completedAtUtc = [DateTime]::UtcNow.ToString('o')
        if (Test-Path -LiteralPath $stdoutLog) { $report.clientStdoutTail = [string[]] @(Get-Content -LiteralPath $stdoutLog -Tail 80) }
        if (Test-Path -LiteralPath $stderrLog) { $report.clientStderrTail = [string[]] @(Get-Content -LiteralPath $stderrLog -Tail 80) }
        $report | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    } finally {
        $script:http.Dispose()
    }
}

[pscustomobject]@{
    status = $report.status
    mode = $Mode
    reportPath = $reportPath
    videoPath = $videoPath
    runId = [string] $report.goalResult.runId
    goalStatus = [string] $report.goalResult.status
    durationSeconds = [double] $report.ffprobe.format.duration
} | ConvertTo-Json -Depth 8

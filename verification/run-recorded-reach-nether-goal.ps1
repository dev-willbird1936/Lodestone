[CmdletBinding()]
param(
    [ValidateSet('script', 'realtime')]
    [string] $Mode = 'realtime',
    [int] $Port = 37829,
    [string] $EvidenceDirectory = '',
    [string] $JavaHome = '',
    [ValidateSet('gpt-5.4-mini')]
    [string] $ModelId = 'gpt-5.4-mini',
    [int] $ModelPort = 37842,
    [ValidateSet('raw-v1', 'guarded-v1', 'adaptive-v1')]
    [string] $Intelligence = 'adaptive-v1',
    [ValidateSet('low', 'balanced', 'high')]
    [string] $Safety = 'balanced'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) { $EvidenceDirectory = Join-Path $scriptRoot 'evidence' }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { $JavaHome = Join-Path $env:USERPROFILE 'scoop\apps\temurin21-jdk\current' }

$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$stem = "minecraft-goal-reach-nether-$Mode-$timestamp"
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
$EvidenceDirectory = (Resolve-Path $EvidenceDirectory).Path
$videoPath = Join-Path $EvidenceDirectory "$stem.mp4"
$reportPath = Join-Path $EvidenceDirectory "$stem.json"
$stdoutLog = Join-Path $EvidenceDirectory "$stem.minecraft.stdout.log"
$stderrLog = Join-Path $EvidenceDirectory "$stem.minecraft.stderr.log"
$ffmpegLog = Join-Path $EvidenceDirectory "$stem.ffmpeg.log"
$modelProxyLog = Join-Path $EvidenceDirectory "$stem.model-proxy.log"
$modelProxyStdoutLog = Join-Path $EvidenceDirectory "$stem.model-proxy.stdout.log"
$goalText = 'load into a fresh survival world and get to the Nether'
$taskId = 'survival.reach-nether'
$capability = 'minecraft.goal.survival.reach-nether'
$token = [guid]::NewGuid().ToString('N')

$script:requestId = 0
$script:sessionId = $null
$script:http = [System.Net.Http.HttpClient]::new()
$script:http.Timeout = [TimeSpan]::FromMinutes(12)
$script:uri = "http://127.0.0.1:$Port/mcp"
$launcher = $null
$clientJavaPid = $null
$script:recorder = $null
$script:recorderErrorTask = $null
$script:modelProxy = $null
$goalInvocationCount = 0
$report = [ordered]@{
    formatVersion = 1
    evidenceKind = 'recorded-reach-nether-goal'
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
    minecraftVersion = '1.21.1'
    loader = 'neoforge'
    keepFocus = $true
    mode = $Mode
    taskId = $taskId
    goal = $goalText
    videoPath = $videoPath
    clientStdoutLog = $stdoutLog
    clientStderrLog = $stderrLog
    recorderLog = $ffmpegLog
    modelProxyLog = $modelProxyLog
    modelProxyStdoutLog = $modelProxyStdoutLog
    mcpPort = $Port
    cleanGameplayRequested = $true
    requestedModel = $ModelId
    modelProxyPort = $ModelPort
    advisorCheckpoint = [ordered]@{
        status = 'CONSULTED'
        recommendation = 'adaptive-default; realtime-first for dynamic survival tasks'
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
    if ($response.Headers.Contains('Mcp-Session-Id')) { $script:sessionId = ($response.Headers.GetValues('Mcp-Session-Id') -join '') }
    [pscustomobject]@{ httpStatus = [int] $response.StatusCode; json = ($response.Content.ReadAsStringAsync().Result | ConvertFrom-Json) }
}

function Convert-ToolResponse {
    param($Rpc)
    if ($Rpc.json.error) { return [pscustomobject]@{ status = 'rpc-error'; error = $Rpc.json.error; output = $null } }
    $payload = $Rpc.json.result.structuredContent
    if ($null -eq $payload) { return [pscustomobject]@{ status = 'empty'; error = $null; output = $null } }
    if ($payload.status -in @('ok', 'error', 'cancelled', 'timed-out')) {
        return [pscustomobject]@{ status = [string] $payload.status; error = $payload.error; output = $payload.output }
    }
    [pscustomobject]@{ status = if ($payload.status) { [string] $payload.status } else { 'ok' }; error = $null; output = $payload }
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
    if ($script:recorderErrorTask) { $script:recorderErrorTask.Result | Set-Content -LiteralPath $ffmpegLog -Encoding UTF8 }
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
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "port $Port already has a listener" }
    if (Get-NetTCPConnection -LocalPort $ModelPort -State Listen -ErrorAction SilentlyContinue) { throw "model proxy port $ModelPort already has a listener" }

    $proxyPath = Join-Path $repoRoot 'verification\gpt54-mini-goal-model-proxy.py'
    if (-not (Test-Path -LiteralPath $proxyPath)) { throw "GPT-5.4 mini model proxy not found: $proxyPath" }
    $env:LODESTONE_PROXY_MODEL = $ModelId
    $env:LODESTONE_PROXY_LOG = $modelProxyLog
    $proxyPython = (Get-Command python -ErrorAction Stop).Source
    $script:modelProxy = Start-Process -FilePath $proxyPython -ArgumentList @((('"{0}"' -f $proxyPath)), [string] $ModelPort) `
        -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $modelProxyStdoutLog `
        -RedirectStandardError $modelProxyLog -PassThru
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (Get-NetTCPConnection -LocalPort $ModelPort -State Listen -ErrorAction SilentlyContinue) { break }
        if ($script:modelProxy.HasExited) { throw "GPT-5.4 mini model proxy exited: $($script:modelProxy.ExitCode)" }
        Start-Sleep -Milliseconds 250
    }
    if (-not (Get-NetTCPConnection -LocalPort $ModelPort -State Listen -ErrorAction SilentlyContinue)) {
        throw "GPT-5.4 mini model proxy did not open port $ModelPort"
    }
    $env:LODESTONE_GOAL_MODEL_URL = "http://127.0.0.1:$ModelPort/v1/chat/completions"
    $env:LODESTONE_GOAL_MODEL_ID = $ModelId
    $env:LODESTONE_GOAL_MODEL_P95_MS = '150'
    $env:LODESTONE_GOAL_MODEL_TIMEOUT_MS = '95000'

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
        if ($listener) { $clientJavaPid = [int] $listener.OwningProcess; break }
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

    $initialize = Invoke-Rpc 'initialize' @{ protocolVersion = '2025-11-25'; clientInfo = @{ name = 'lodestone-recorded-reach-nether-goal'; version = '1.0' } }
    if ($initialize.json.error -or $null -eq $initialize.json.result) { throw 'MCP initialize failed' }
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
        } else { Require-SetupOk $uiResponse 'initial ui_state' | Out-Null }
        Start-Sleep -Milliseconds 500
    }
    if (-not $initialUi -or $initialUi.inWorld -or [string] $initialUi.screenClass -notmatch 'TitleScreen') {
        throw "fresh title-screen state not reached: lastTransient=$initialUiError"
    }
    $report.initialUi = [ordered]@{ inWorld = [bool] $initialUi.inWorld; screenClass = [string] $initialUi.screenClass; screenToken = [string] $initialUi.screenToken }

    $tasks = Require-SetupOk (Invoke-SetupTool 'minecraft_goal_tasks' @{ category = 'survival' }) 'minecraft_goal_tasks'
    $task = @($tasks.tasks | Where-Object { $_.id -eq $taskId }) | Select-Object -First 1
    if (-not $task) { throw "$taskId is absent from the live task catalog" }
    if (@($task.requiredCapabilities) -notcontains $capability) { throw "task is missing $capability" }
    $report.taskCatalogEntry = [ordered]@{ id = [string] $task.id; category = [string] $task.category; gameMode = [string] $task.gameMode; requiredCapabilities = [string[]] @($task.requiredCapabilities); successContract = [string] $task.successContract }

    $goalInvocationCount++
    $goalRpc = Invoke-Rpc 'tools/call' @{ name = 'minecraft_goal'; arguments = @{ goal = $goalText; mode = $Mode; taskId = $taskId; maxSteps = 32; maxDurationMs = 480000; dryRun = $false; suppressInGameMessages = $true; intelligence = $Intelligence; safety = $Safety } }
    $goalResponse = Convert-ToolResponse $goalRpc
    $report.goalInvocationCount = $goalInvocationCount
    $report.goalToolResponseStatus = $goalResponse.status
    $report.goalResult = $goalResponse.output
    if ($goalResponse.status -ne 'SUCCEEDED') { throw "single minecraft_goal call did not succeed: status=$($goalResponse.status)" }

    $state = $goalResponse.output.state.steps.'nether-workflow'
    if (-not $state) { throw 'goal result is missing state.steps.nether-workflow' }
    $report.goalPredicates = [ordered]@{
        freshWorld = [bool] $state.freshWorld
        survival = [bool] $state.survival
        initialDimension = [string] $state.initialDimension
        finalDimension = [string] $state.finalDimension
        teleportedToBuildSite = [bool] $state.teleportedToBuildSite
        manualPortalBuilt = [bool] $state.manualPortalBuilt
        portalFrameBlocksPlaced = [int] $state.portalFrameBlocksPlaced
        portalLit = [bool] $state.portalLit
        portalBlocksObserved = [bool] $state.portalBlocksObserved
        enteredPortal = [bool] $state.enteredPortal
        reachedNether = [bool] $state.reachedNether
        playerAlive = [bool] $state.playerAlive
        setupCommandsUsed = [bool] $state.setupCommandsUsed
        setupCommandCount = [int] $state.setupCommandCount
        setupCommands = [string[]] @($state.setupCommands)
        commandFeedbackSuppressed = [bool] $state.commandFeedbackSuppressed
        suppressInGameMessages = [bool] $state.suppressInGameMessages
        inGameMessagesEmitted = [int] $state.inGameMessagesEmitted
        directMutationUsed = [bool] $state.directMutationUsed
        inputActions = [string[]] @($state.inputActions)
    }
    $required = @(
        [bool] $state.freshWorld, [bool] $state.survival,
        ([string] $state.initialDimension -eq 'minecraft:overworld'),
        ([string] $state.finalDimension -eq 'minecraft:the_nether'),
        (-not [bool] $state.teleportedToBuildSite), [bool] $state.manualPortalBuilt,
        ([int] $state.portalFrameBlocksPlaced -eq 10), [bool] $state.portalLit,
        [bool] $state.portalBlocksObserved, [bool] $state.enteredPortal, [bool] $state.reachedNether,
        [bool] $state.playerAlive, (-not [bool] $state.setupCommandsUsed),
        ([int] $state.setupCommandCount -eq 0), [bool] $state.commandFeedbackSuppressed,
        [bool] $state.suppressInGameMessages, ([int] $state.inGameMessagesEmitted -eq 0),
        (-not [bool] $state.directMutationUsed)
    )
    if ($required -contains $false) { throw 'goal returned SUCCEEDED without every Nether terminal predicate' }
    if (@($state.setupCommands | Where-Object { $_ -match 'setblock|fill|loot|replaceitem|data modify|summon' }).Count -ne 0) { throw 'setup trace contains direct world/entity mutation' }
    $naturalChestRoute = @($state.inputActions | Where-Object { $_ -like 'use:key.use-open-natural-portal-chest' }).Count -ge 1 -and
        @($state.inputActions | Where-Object { $_ -like 'container-click:loot-portal-chest-slot-*' }).Count -ge 1
    $survivalGatherRoute = @($state.inputActions | Where-Object { $_ -eq 'attack:key.attack-held-by-hand' }).Count -ge 1 -and
        @($state.inputActions | Where-Object { $_ -eq 'ui:key.inventory' }).Count -ge 1 -and
        @($state.inputActions | Where-Object { $_ -eq 'use:key.use-fill-lava-bucket' }).Count -ge 1
    if (-not ($naturalChestRoute -or $survivalGatherRoute)) { throw 'input trace does not prove either legitimate natural portal loot or genuine survival gathering' }
    if (@($state.inputActions | Where-Object { $_ -eq 'portal:key.use:flint-and-steel' }).Count -lt 1) { throw 'input trace does not prove portal ignition' }
    if (@($state.inputActions | Where-Object { $_ -eq 'move:key.forward-into-portal' }).Count -lt 1) { throw 'input trace does not prove forward portal entry' }

    $chatComponentLines = [string[]] @(Select-String -LiteralPath $stdoutLog -Pattern '\[minecraft/ChatComponent\]' | ForEach-Object { $_.Line })
    $report.chatComponentLines = $chatComponentLines
    $report.cleanGameplayLogVerified = $chatComponentLines.Count -eq 0
    if ($chatComponentLines.Count -ne 0) { throw 'clean gameplay was contaminated by client chat/status feedback' }

    Start-Sleep -Seconds 5
    Stop-Recorder
    $script:recorder = $null
    $probeJson = & ffprobe -v error -show_entries 'format=duration,format_name' -show_entries 'stream=codec_name,codec_type,width,height,nb_frames' -of json -- $videoPath
    if ($LASTEXITCODE -ne 0) { throw 'ffprobe could not parse the recorded MP4' }
    $probe = $probeJson | ConvertFrom-Json
    $videoStream = @($probe.streams | Where-Object { $_.codec_type -eq 'video' }) | Select-Object -First 1
    if (-not $videoStream -or $videoStream.codec_name -ne 'h264' -or [double] $probe.format.duration -le 0) { throw 'recorded evidence is not a nonzero H.264 video' }
    $report.ffprobe = $probe
    $report.status = 'PASS'
} catch {
    $report.status = 'FAIL'
    $report.failure = [ordered]@{ message = $_.Exception.Message; stack = $_.ScriptStackTrace }
    throw
} finally {
    try {
        if ($script:recorder) { try { Stop-Recorder } catch { $report.recorderCleanupFailure = $_.Exception.Message }; $script:recorder = $null }
        if ($launcher) { Stop-TaskClient }
        if ($script:modelProxy) {
            Stop-Process -Id $script:modelProxy.Id -Force -ErrorAction SilentlyContinue
            $script:modelProxy = $null
        }
        Remove-Item Env:LODESTONE_GOAL_MODEL_URL,Env:LODESTONE_GOAL_MODEL_ID,Env:LODESTONE_GOAL_MODEL_P95_MS,Env:LODESTONE_GOAL_MODEL_TIMEOUT_MS,Env:LODESTONE_PROXY_MODEL,Env:LODESTONE_PROXY_LOG -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
        $report.listenerAfterCleanup = [bool] (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        $report.goalInvocationCount = $goalInvocationCount
        $report.completedAtUtc = [DateTime]::UtcNow.ToString('o')
        if (Test-Path -LiteralPath $stdoutLog) { $report.clientStdoutTail = [string[]] @(Get-Content -LiteralPath $stdoutLog -Tail 80) }
        if (Test-Path -LiteralPath $stderrLog) { $report.clientStderrTail = [string[]] @(Get-Content -LiteralPath $stderrLog -Tail 80) }
        $report | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    } finally { $script:http.Dispose() }
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

[CmdletBinding()]
param(
    [int] $Port = 37829,
    [int] $ModelPort = 37842,
    [string] $Token = 'b6b13b39-82ec-4f4f-9305-21f09f0e3d47',
    [string] $JavaHome = "$env:USERPROFILE\scoop\apps\temurin21-jdk\current",
    [string] $Intelligence = 'adaptive-v1',
    [string] $Safety = 'balanced'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
$logDir = Join-Path $scriptRoot 'evidence\adhoc-realtime-mine-tree'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stdoutLog = Join-Path $logDir 'client.stdout.log'
$stderrLog = Join-Path $logDir 'client.stderr.log'
$modelProxyLog = Join-Path $logDir 'model-proxy.log'
$modelProxyStdoutLog = Join-Path $logDir 'model-proxy.stdout.log'
$resultPath = Join-Path $logDir 'result.json'

Write-Host "Launching gpt-5.4-mini model proxy on port $ModelPort..."
$proxyPath = Join-Path $repoRoot 'verification\gpt54-mini-goal-model-proxy.py'
if (-not (Test-Path -LiteralPath $proxyPath)) { throw "model proxy not found: $proxyPath" }
$env:LODESTONE_PROXY_MODEL = 'gpt-5.4-mini'
$env:LODESTONE_PROXY_LOG = $modelProxyLog
$proxyPython = (Get-Command python -ErrorAction Stop).Source
$modelProxy = Start-Process -FilePath $proxyPython -ArgumentList @((('"{0}"' -f $proxyPath)), [string] $ModelPort) `
    -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $modelProxyStdoutLog `
    -RedirectStandardError $modelProxyLog -PassThru
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    if (Get-NetTCPConnection -LocalPort $ModelPort -State Listen -ErrorAction SilentlyContinue) { break }
    if ($modelProxy.HasExited) { throw "model proxy exited: $($modelProxy.ExitCode)" }
    Start-Sleep -Milliseconds 250
}
if (-not (Get-NetTCPConnection -LocalPort $ModelPort -State Listen -ErrorAction SilentlyContinue)) {
    throw "model proxy did not open port $ModelPort"
}
Write-Host "Model proxy up."

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "port $Port already has a listener; refusing to attach to a non-task process"
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
$env:LODESTONE_GOAL_MODEL_URL = "http://127.0.0.1:$ModelPort/v1/chat/completions"
$env:LODESTONE_GOAL_MODEL_ID = 'gpt-5.4-mini'
$env:LODESTONE_GOAL_MODEL_P95_MS = '150'
$env:LODESTONE_GOAL_MODEL_TIMEOUT_MS = '95000'
$permissions = 'observe,communicate,control-player,modify-world,administer-server,capture-screen'
$command = ".\gradlew.bat --no-daemon --console=plain -PincludeFabric262=false -Dlodestone.port=$Port -Dlodestone.token=$Token -Dlodestone.permissions=$permissions :hosts:neoforge:mc1_21_1:runKeepFocusClient"
Write-Host "Launching NeoForge KeepFocus client on port $Port..."
$launcher = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/c', $command -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru

for ($attempt = 0; $attempt -lt 1500; $attempt++) {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) { break }
    if ($launcher.HasExited) { throw "client launcher exited before MCP opened: $($launcher.ExitCode)" }
    Start-Sleep -Seconds 1
}
if (-not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
    throw "MCP endpoint did not open on port $Port within 25 minutes"
}
Write-Host "Client MCP endpoint is up. Waiting for title screen..."

$script:requestId = 0
$script:sessionId = $null
$script:http = [System.Net.Http.HttpClient]::new()
$script:http.Timeout = [TimeSpan]::FromMinutes(12)
$script:uri = "http://127.0.0.1:$Port/mcp"

function Invoke-Rpc {
    param([string] $Method, [hashtable] $Parameters = @{})
    $script:requestId++
    $body = @{ jsonrpc = '2.0'; id = $script:requestId; method = $Method; params = $Parameters } |
        ConvertTo-Json -Depth 64 -Compress
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $script:uri)
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

$initialize = Invoke-Rpc 'initialize' @{ protocolVersion = '2025-11-25'; clientInfo = @{ name = 'lodestone-adhoc-realtime'; version = '1.0' } }
if ($initialize.json.error -or $null -eq $initialize.json.result) {
    $message = if ($initialize.json.error) { $initialize.json.error.message } else { 'no initialize result' }
    throw "MCP initialize failed: $message"
}
Invoke-Rpc 'notifications/initialized' @{} | Out-Null
Write-Host "MCP session initialized."

$initialUi = $null
for ($attempt = 0; $attempt -lt 180; $attempt++) {
    $uiRpc = Invoke-Rpc 'tools/call' @{ name = 'ui_state'; arguments = @{} }
    $uiResponse = Convert-ToolResponse $uiRpc
    if ($uiResponse.status -eq 'ok') {
        $initialUi = $uiResponse.output
        if (-not $initialUi.inWorld -and [string] $initialUi.screenClass -match 'TitleScreen') { break }
    }
    Start-Sleep -Milliseconds 500
}
if (-not $initialUi -or $initialUi.inWorld -or [string] $initialUi.screenClass -notmatch 'TitleScreen') {
    Write-Host "WARNING: did not confirm fresh title-screen state, proceeding anyway (screen=$($initialUi.screenClass))"
} else {
    Write-Host "Title screen confirmed."
}

Write-Host "Sending realtime minecraft_goal: mine a tree (intelligence=$Intelligence, safety=$Safety)..."
$goalRpc = Invoke-Rpc 'tools/call' @{
    name = 'minecraft_goal'
    arguments = @{
        goal = 'load into survival, get a wooden axe and mine a full tree with it'
        mode = 'realtime'
        taskId = 'survival.wooden-axe-mine-tree'
        maxSteps = 32
        maxDurationMs = 480000
        dryRun = $false
        suppressInGameMessages = $true
        intelligence = $Intelligence
        safety = $Safety
    }
}
$goalResponse = Convert-ToolResponse $goalRpc

$result = [ordered]@{
    status = $goalResponse.status
    error = $goalResponse.error
    output = $goalResponse.output
    clientPort = $Port
    modelProxyPort = $ModelPort
    token = $Token
}
$result | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath $resultPath -Encoding UTF8
Write-Host "Result written to $resultPath"
Write-Host "STATUS: $($goalResponse.status)"
if ($goalResponse.output) { Write-Host ($goalResponse.output | ConvertTo-Json -Depth 12 -Compress) }
if ($goalResponse.error) { Write-Host "ERROR: $($goalResponse.error | ConvertTo-Json -Depth 12 -Compress)" }

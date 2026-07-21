[CmdletBinding()]
param(
    [int] $Port = 37821,
    [string] $Token = '618bAuUetMm0zKhCmsM3rUNb-4fL3-speAZIlsq-SVo',
    [string] $Intelligence = 'guarded-v1',
    [string] $Safety = 'balanced'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $scriptRoot 'evidence\adhoc-goal-to-user-client'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$resultPath = Join-Path $logDir 'result.json'

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

Write-Host "Connecting to your running client on port $Port..."
$initialize = Invoke-Rpc 'initialize' @{ protocolVersion = '2025-11-25'; clientInfo = @{ name = 'lodestone-adhoc-user-client'; version = '1.0' } }
if ($initialize.json.error -or $null -eq $initialize.json.result) {
    $message = if ($initialize.json.error) { $initialize.json.error.message } else { 'no initialize result' }
    throw "MCP initialize failed: $message"
}
Invoke-Rpc 'notifications/initialized' @{} | Out-Null
Write-Host "MCP session initialized."

$uiRpc = Invoke-Rpc 'tools/call' @{ name = 'ui_state'; arguments = @{} }
$uiResponse = Convert-ToolResponse $uiRpc
Write-Host "Current UI state: $($uiResponse.output | ConvertTo-Json -Compress -Depth 6)"

Write-Host "Sending minecraft_goal: mine 5 cacti (intelligence=$Intelligence, safety=$Safety)..."
$goalRpc = Invoke-Rpc 'tools/call' @{
    name = 'minecraft_goal'
    arguments = @{
        goal = 'find a desert or wherever cactus grows, and mine (break and collect) exactly 5 cactus blocks'
        mode = 'script'
        maxSteps = 48
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
}
$result | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath $resultPath -Encoding UTF8
Write-Host "Result written to $resultPath"
Write-Host "STATUS: $($goalResponse.status)"
if ($goalResponse.output) { Write-Host ($goalResponse.output | ConvertTo-Json -Depth 12 -Compress) }
if ($goalResponse.error) { Write-Host "ERROR: $($goalResponse.error | ConvertTo-Json -Depth 12 -Compress)" }

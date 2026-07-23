[CmdletBinding()]
param(
    [int] $Port = 37891,
    [string] $ToolName = '',
    [string] $ArgsJson = '{}',
    [string] $ArgsFile = '',
    [switch] $ListTools,
    [int] $TimeoutSec = 300
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
$uri = "http://127.0.0.1:$Port/mcp"
$script:requestId = 0
$script:sessionId = $null

function Invoke-Rpc {
    param([string] $Method, $Parameters = @{})
    $script:requestId++
    $body = @{ jsonrpc = '2.0'; id = $script:requestId; method = $Method; params = $Parameters } | ConvertTo-Json -Depth 64 -Compress
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
    if ($script:sessionId) {
        $message.Headers.Add('Mcp-Session-Id', $script:sessionId)
        $message.Headers.Add('MCP-Protocol-Version', '2025-11-25')
    }
    $message.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
    $response = $http.SendAsync($message).Result
    if ($response.Headers.Contains('Mcp-Session-Id')) { $script:sessionId = ($response.Headers.GetValues('Mcp-Session-Id') -join '') }
    [pscustomobject]@{ http = [int]$response.StatusCode; json = ($response.Content.ReadAsStringAsync().Result | ConvertFrom-Json) }
}

Invoke-Rpc 'initialize' @{ protocolVersion = '2025-11-25'; clientInfo = @{ name = 'lodestone-agent-goal-worker'; version = '1.0' } } | Out-Null
Invoke-Rpc 'notifications/initialized' @{} | Out-Null

if ($ListTools) {
    $tools = Invoke-Rpc 'tools/list' @{}
    @{ tools = @($tools.json.result.tools | ForEach-Object { @{ name = $_.name; description = $_.description } }) } | ConvertTo-Json -Depth 8
    return
}

if (-not $ToolName) { throw 'ToolName is required unless -ListTools is used' }

$rawArgs = if ($ArgsFile) { Get-Content -LiteralPath $ArgsFile -Raw } else { $ArgsJson }
$toolArgs = if ($rawArgs -and $rawArgs.Trim() -ne '{}') { $rawArgs | ConvertFrom-Json } else { @{} }
$result = Invoke-Rpc 'tools/call' @{ name = $ToolName; arguments = $toolArgs }

$out = [ordered]@{ http = $result.http }
if ($result.json.error) { $out.rpcError = $result.json.error }
if ($null -ne $result.json.result) {
    if ($result.json.result.isError) { $out.isError = $true }
    if ($null -ne $result.json.result.structuredContent) {
        $out.result = $result.json.result.structuredContent
    } elseif ($null -ne $result.json.result.content) {
        $out.result = $result.json.result.content
    }
}
[pscustomobject]$out | ConvertTo-Json -Depth 40 -Compress

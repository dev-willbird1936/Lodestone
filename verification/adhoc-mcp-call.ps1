[CmdletBinding()]
param(
    [int] $Port = 37821,
    [string] $Token = '618bAuUetMm0zKhCmsM3rUNb-4fL3-speAZIlsq-SVo',
    [Parameter(Mandatory = $true)] [string] $ToolName,
    [string] $ArgsJson = '{}'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(60)
$uri = "http://127.0.0.1:$Port/mcp"
$script:requestId = 0
$script:sessionId = $null

function Invoke-Rpc {
    param([string] $Method, [hashtable] $Parameters = @{})
    $script:requestId++
    $body = @{ jsonrpc = '2.0'; id = $script:requestId; method = $Method; params = $Parameters } | ConvertTo-Json -Depth 64 -Compress
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
    $message.Headers.Add('X-Lodestone-Token', $Token)
    if ($script:sessionId) {
        $message.Headers.Add('Mcp-Session-Id', $script:sessionId)
        $message.Headers.Add('MCP-Protocol-Version', '2025-11-25')
    }
    $message.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
    $response = $http.SendAsync($message).Result
    if ($response.Headers.Contains('Mcp-Session-Id')) { $script:sessionId = ($response.Headers.GetValues('Mcp-Session-Id') -join '') }
    [pscustomobject]@{ json = ($response.Content.ReadAsStringAsync().Result | ConvertFrom-Json) }
}

Invoke-Rpc 'initialize' @{ protocolVersion = '2025-11-25'; clientInfo = @{ name = 'lodestone-adhoc-drive'; version = '1.0' } } | Out-Null
Invoke-Rpc 'notifications/initialized' @{} | Out-Null

$toolArgs = if ($ArgsJson -and $ArgsJson -ne '{}') { $ArgsJson | ConvertFrom-Json } else { @{} }
$result = Invoke-Rpc 'tools/call' @{ name = $ToolName; arguments = $toolArgs }
$result.json.result.structuredContent | ConvertTo-Json -Depth 20 -Compress

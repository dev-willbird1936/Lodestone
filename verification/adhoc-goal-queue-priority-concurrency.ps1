[CmdletBinding()]
param(
    [int] $Port = 37831,
    [string] $Token = 'd41f2b8a-6c3e-4a1a-9d7c-8b4e2a5f7c19',
    [string] $JavaHome = "$env:USERPROFILE\scoop\apps\temurin21-jdk\current"
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
$logDir = Join-Path $scriptRoot 'evidence\adhoc-goal-queue-priority-concurrency'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stdoutLog = Join-Path $logDir 'client.stdout.log'
$stderrLog = Join-Path $logDir 'client.stderr.log'
$resultPath = Join-Path $logDir 'result.json'

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "port $Port already has a listener; refusing to attach to a non-task process"
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
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
Write-Host "Client MCP endpoint is up."

$uri = "http://127.0.0.1:$Port/mcp"

function Invoke-Rpc {
    param([string] $Uri, [string] $Token, [ref] $SessionId, [ref] $RequestId, [string] $Method, [hashtable] $Parameters = @{})
    $RequestId.Value++
    $body = @{ jsonrpc = '2.0'; id = $RequestId.Value; method = $Method; params = $Parameters } | ConvertTo-Json -Depth 64 -Compress
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $Uri)
    $message.Headers.Add('X-Lodestone-Token', $Token)
    if ($SessionId.Value) {
        $message.Headers.Add('Mcp-Session-Id', $SessionId.Value)
        $message.Headers.Add('MCP-Protocol-Version', '2025-11-25')
    }
    $message.Content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, 'application/json')
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromMinutes(5)
    $response = $client.SendAsync($message).Result
    if ($response.Headers.Contains('Mcp-Session-Id')) {
        $SessionId.Value = ($response.Headers.GetValues('Mcp-Session-Id') -join '')
    }
    return ($response.Content.ReadAsStringAsync().Result | ConvertFrom-Json)
}

# Pre-establish three independent MCP sessions (each its own initialize handshake) BEFORE any timed
# request, so per-session handshake jitter can never distort which of the three concurrent
# minecraft_goal calls is actually sent first - only the later Start-Job dispatch order controls that.
function New-Session {
    param([string] $Label)
    $sessionId = $null
    $requestId = 0
    $init = Invoke-Rpc -Uri $uri -Token $Token -SessionId ([ref]$sessionId) -RequestId ([ref]$requestId) -Method 'initialize' `
        -Parameters @{ protocolVersion = '2025-11-25'; clientInfo = @{ name = "lodestone-queue-verify-$Label"; version = '1.0' } }
    if ($init.error) { throw "initialize failed for $Label`: $($init.error.message)" }
    Invoke-Rpc -Uri $uri -Token $Token -SessionId ([ref]$sessionId) -RequestId ([ref]$requestId) -Method 'notifications/initialized' -Parameters @{} | Out-Null
    return $sessionId
}

Write-Host "Establishing three independent MCP sessions..."
$sessionA = New-Session -Label 'A-plain'
$sessionB = New-Session -Label 'B-plain'
$sessionC = New-Session -Label 'C-priority'
Write-Host "Sessions ready: A=$sessionA B=$sessionB C=$sessionC"

# lodestone.ui.wait / until=screen_closed never matches at the title screen, so this deterministically
# holds the native goal actor for ~timeoutMs of real, observe-only (no click, no mutation) wall time -
# a safe, controllable way to guarantee genuine overlap without depending on menu transition timing.
function Hold-Plan {
    param([string] $Label, [int] $TimeoutMs)
    return @{
        id = "queue-verify-$Label"
        goal = "queue verification hold ($Label)"
        segments = @(
            @{
                id = 'hold'
                description = 'Deliberately hold the goal actor for a bounded, observe-only wait so concurrent minecraft_goal calls have a real window to queue behind it.'
                steps = @(
                    @{
                        id = 'hold-step'
                        kind = 'invoke'
                        capability = 'lodestone.ui.wait'
                        capabilityVersion = '1.0'
                        input = @{ until = 'screen_closed'; timeoutMs = $TimeoutMs; pollIntervalMs = 250 }
                        assertions = @(@{ path = 'steps.hold-step.timedOut'; operator = 'equals'; expected = $true })
                    }
                )
            }
        )
        metadata = @{ completionPredicateReady = $true }
    }
}

$goalScript = {
    param($Uri, $Token, $SessionId, $Label, $Priority, $TimeoutMs, $StaggerMs)
    Add-Type -AssemblyName System.Net.Http

    function Invoke-TimedRpc {
        param([string] $Uri, [string] $Token, [string] $SessionId, [hashtable] $Parameters)
        $body = @{ jsonrpc = '2.0'; id = 1; method = 'tools/call'; params = $Parameters } | ConvertTo-Json -Depth 64 -Compress
        $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $Uri)
        $message.Headers.Add('X-Lodestone-Token', $Token)
        $message.Headers.Add('Mcp-Session-Id', $SessionId)
        $message.Headers.Add('MCP-Protocol-Version', '2025-11-25')
        $message.Content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, 'application/json')
        $client = [System.Net.Http.HttpClient]::new()
        $client.Timeout = [TimeSpan]::FromMinutes(5)
        return $client.SendAsync($message).Result.Content.ReadAsStringAsync().Result | ConvertFrom-Json
    }

    $plan = @{
        id = "queue-verify-$Label"
        goal = "queue verification hold ($Label)"
        segments = @(
            @{
                id = 'hold'
                description = 'Deliberately hold the goal actor for a bounded, observe-only wait.'
                steps = @(
                    @{
                        id = 'hold-step'
                        kind = 'invoke'
                        capability = 'lodestone.ui.wait'
                        capabilityVersion = '1.0'
                        input = @{ until = 'screen_closed'; timeoutMs = $TimeoutMs; pollIntervalMs = 250 }
                        assertions = @(@{ path = 'steps.hold-step.timedOut'; operator = 'equals'; expected = $true })
                    }
                )
            }
        )
        metadata = @{ completionPredicateReady = $true }
    }

    if ($StaggerMs -gt 0) { Start-Sleep -Milliseconds $StaggerMs }

    $sendTime = Get-Date
    $goalRpc = Invoke-TimedRpc -Uri $Uri -Token $Token -SessionId $SessionId -Parameters @{
        name = 'minecraft_goal'
        arguments = @{
            goal = "queue verification hold ($Label)"
            mode = 'script'
            maxDurationMs = 60000
            dryRun = $false
            suppressInGameMessages = $true
            priority = [bool]$Priority
            plan = $plan
        }
    }
    $receiveTime = Get-Date

    $structured = $goalRpc.result.structuredContent
    [pscustomobject]@{
        label                   = $Label
        priority                = [bool]$Priority
        sendTime                = $sendTime
        receiveTime             = $receiveTime
        durationMs              = [math]::Round(($receiveTime - $sendTime).TotalMilliseconds)
        isError                 = $goalRpc.result.isError
        status                  = $structured.status
        message                 = $structured.message
        queuedMs                = $structured.state.queuedMs
        queuePositionAtEnqueue  = $structured.state.queuePositionAtEnqueue
        rpcError                = $goalRpc.error
    }
}

Write-Host "Firing 3 concurrent minecraft_goal calls: A (plain), B (plain) essentially together, then C (priority=true) ~300ms later..."
$jobA = Start-Job -ScriptBlock $goalScript -ArgumentList $uri, $Token, $sessionA, 'A-plain', $false, 3000, 0
$jobB = Start-Job -ScriptBlock $goalScript -ArgumentList $uri, $Token, $sessionB, 'B-plain', $false, 3000, 0
$jobC = Start-Job -ScriptBlock $goalScript -ArgumentList $uri, $Token, $sessionC, 'C-priority', $true, 3000, 300

Wait-Job $jobA, $jobB, $jobC | Out-Null
$resultA = Receive-Job $jobA -ErrorAction SilentlyContinue
$resultB = Receive-Job $jobB -ErrorAction SilentlyContinue
$resultC = Receive-Job $jobC -ErrorAction SilentlyContinue
Remove-Job $jobA, $jobB, $jobC -Force

$all = @($resultA, $resultB, $resultC) | Sort-Object sendTime
$all | Format-Table label, priority, sendTime, receiveTime, durationMs, isError, status, queuedMs, queuePositionAtEnqueue -AutoSize | Out-String | Write-Host

$all | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $resultPath -Encoding UTF8
Write-Host "Result written to $resultPath"

$bResult = $all | Where-Object { $_.label -eq 'B-plain' }
$cResult = $all | Where-Object { $_.label -eq 'C-priority' }
if ($cResult.receiveTime -lt $bResult.receiveTime) {
    Write-Host "PASS: C-priority (sent after B-plain) completed BEFORE B-plain - priority queue-jump confirmed."
} else {
    Write-Host "FAIL: C-priority did not complete before B-plain - priority queue-jump NOT observed."
}

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BenchmarkName,
    [Parameter(Mandatory = $true)]
    [string] $TaskId,
    [Parameter(Mandatory = $true)]
    [string] $Goal,
    [string] $SeedListPath = '',
    [string[]] $Seeds = @(),
    [int] $Limit = 0,
    [ValidateSet('script', 'realtime')]
    [string] $Mode = 'script',
    [ValidateSet('raw-v1', 'guarded-v1', 'adaptive-v1', 'deliberate-v1')]
    [string] $Intelligence = 'guarded-v1',
    [ValidateSet('low', 'balanced', 'high')]
    [string] $Safety = 'balanced',
    [int] $GoalMaxDurationMs = 480000,
    [int] $BenchmarkBudgetMs = 360000,
    [int] $Port = 37901,
    [string] $RunDirectory = 'runs/benchmark-multiseed',
    [string] $EvidenceDirectory = '',
    [string] $JavaHome = ''
)

# Telemetry-only multi-seed harness: no ffmpeg/video. Per the benchmark contract, every result
# is scored from goal-report telemetry alone; recording is reserved for single diagnostic runs
# via run-recorded-survival-tree-goal.ps1 when a failure needs to actually be watched.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($JavaHome)) { $JavaHome = Join-Path $env:USERPROFILE 'scoop\apps\temurin21-jdk\current' }
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) { $EvidenceDirectory = Join-Path $scriptRoot 'evidence' }

if ($Port -eq 37828 -or $RunDirectory -eq 'runs/keepfocus-client') {
    throw "refusing default port 37828 / run dir runs/keepfocus-client: this harness must stay isolated from any interactive KeepFocus session"
}

if ($Seeds.Count -eq 0) {
    if ([string]::IsNullOrWhiteSpace($SeedListPath)) { throw 'provide either -Seeds or -SeedListPath' }
    $Seeds = @(Get-Content -LiteralPath $SeedListPath | ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('#') })
}
if ($Limit -gt 0 -and $Limit -lt $Seeds.Count) { $Seeds = $Seeds[0..($Limit - 1)] }
if ($Seeds.Count -eq 0) { throw 'seed list resolved to zero seeds' }

$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$runDir = Join-Path $EvidenceDirectory "$BenchmarkName-$timestamp"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$runDir = (Resolve-Path $runDir).Path

function New-HttpClient {
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromMilliseconds($GoalMaxDurationMs + 30000)
    return $client
}

function Invoke-Rpc {
    param($Http, [string] $Uri, [ref] $SessionId, [string] $Token, [string] $Method, [hashtable] $Parameters = @{})
    if (-not $script:rpcId) { $script:rpcId = 0 }
    $script:rpcId = $script:rpcId + 1
    $body = @{ jsonrpc = '2.0'; id = $script:rpcId; method = $Method; params = $Parameters } |
        ConvertTo-Json -Depth 64 -Compress
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $Uri)
    $message.Headers.Add('X-Lodestone-Token', $Token)
    if ($SessionId.Value) {
        $message.Headers.Add('Mcp-Session-Id', $SessionId.Value)
        $message.Headers.Add('MCP-Protocol-Version', '2025-11-25')
    }
    $message.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
    $response = $Http.SendAsync($message).Result
    if ($response.Headers.Contains('Mcp-Session-Id')) {
        $SessionId.Value = ($response.Headers.GetValues('Mcp-Session-Id') -join '')
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

function Stop-BenchmarkClient {
    param($Launcher)
    if (-not $Launcher) { return }
    $rootProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($Launcher.Id)" -ErrorAction SilentlyContinue
    if ($rootProcess -and [string] $rootProcess.CommandLine -match 'runKeepFocusClient') {
        & taskkill.exe /PID $Launcher.Id /T /F | Out-Null
    }
}

# One seed's worth of work: launch a fresh isolated client, wait for the title screen, run the
# goal once, tear the client down. Returns a result record; never throws for a goal-level
# failure (that IS the recorded result) but does throw for infra faults so the caller can retry.
function Invoke-SeedRun {
    param([string] $Seed, [string] $StdoutLog, [string] $StderrLog)

    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "INFRA:java-missing: Java 21 executable not found: $javaExecutable" }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "INFRA:port-occupied: port $Port already has a listener; refusing to attach to a non-harness process"
    }

    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
    $token = [guid]::NewGuid().ToString('N')
    $permissions = 'observe,communicate,control-player,modify-world,administer-server,capture-screen'
    $command = ".\gradlew.bat --no-daemon --console=plain -PincludeFabric262=false -Dlodestone.port=$Port -Dlodestone.token=$token -Dlodestone.permissions=$permissions -Dlodestone.runDirectory=$RunDirectory `:hosts:neoforge:mc1_21_1:runKeepFocusClient"
    $launcher = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/c', $command -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $StdoutLog -RedirectStandardError $StderrLog -PassThru

    $result = [ordered]@{
        seed = $Seed; status = $null; failureCause = $null; elapsedMs = $null
        goalStatus = $null; goalOutput = $null; infra = $null
    }
    try {
        $clientJavaPid = $null
        for ($attempt = 0; $attempt -lt 1500; $attempt++) {
            $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
            if ($listener) { $clientJavaPid = [int] $listener.OwningProcess; break }
            if ($launcher.HasExited) { throw "INFRA:launcher-exited: client launcher exited before MCP opened: $($launcher.ExitCode)" }
            Start-Sleep -Seconds 1
        }
        if (-not $clientJavaPid) { throw "INFRA:mcp-endpoint-timeout: MCP endpoint did not open on port $Port within 25 minutes" }

        $http = New-HttpClient
        $sessionId = $null
        $uri = "http://127.0.0.1:$Port/mcp"
        try {
            $initialize = Invoke-Rpc $http $uri ([ref] $sessionId) $token 'initialize' @{
                protocolVersion = '2025-11-25'; clientInfo = @{ name = 'lodestone-multiseed-benchmark'; version = '1.0' }
            }
            if ($initialize.json.error -or $null -eq $initialize.json.result) {
                throw "INFRA:mcp-initialize-failed: $($initialize.json.error.message)"
            }
            Invoke-Rpc $http $uri ([ref] $sessionId) $token 'notifications/initialized' @{} | Out-Null

            $initialUi = $null
            for ($attempt = 0; $attempt -lt 180; $attempt++) {
                $uiResponse = Convert-ToolResponse (Invoke-Rpc $http $uri ([ref] $sessionId) $token 'tools/call' @{ name = 'ui_state'; arguments = @{} })
                if ($uiResponse.status -eq 'ok') {
                    $initialUi = $uiResponse.output
                    if (-not $initialUi.inWorld -and [string] $initialUi.screenClass -match 'TitleScreen') { break }
                }
                Start-Sleep -Milliseconds 500
            }
            if (-not $initialUi -or $initialUi.inWorld -or [string] $initialUi.screenClass -notmatch 'TitleScreen') {
                throw "INFRA:title-screen-timeout: fresh title screen not reached: inWorld=$($initialUi.inWorld), screen=$($initialUi.screenClass)"
            }

            $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
            $goalRpc = Invoke-Rpc $http $uri ([ref] $sessionId) $token 'tools/call' @{
                name = 'minecraft_goal'
                arguments = @{
                    goal = $Goal; mode = $Mode; taskId = $TaskId; maxSteps = 400
                    maxDurationMs = $GoalMaxDurationMs; dryRun = $false; suppressInGameMessages = $true
                    intelligence = $Intelligence; safety = $Safety; worldSeed = $Seed
                }
            }
            $stopwatch.Stop()
            $goalResponse = Convert-ToolResponse $goalRpc
            $result.elapsedMs = $stopwatch.ElapsedMilliseconds
            $result.goalStatus = [string] $goalResponse.status
            $result.goalOutput = $goalResponse.output
            $state = $goalResponse.output.state
            $result.failureCause = if ($state -and $state.failureCause) { [string] $state.failureCause } else { $null }

            if ($goalResponse.status -eq 'SUCCEEDED' -and $result.elapsedMs -gt $BenchmarkBudgetMs) {
                $result.status = 'OVER_BUDGET'
                $result.failureCause = "timeout:benchmark-budget"
            } elseif ($goalResponse.status -eq 'SUCCEEDED') {
                $result.status = 'SUCCEEDED'
            } else {
                $result.status = [string] $goalResponse.status
                if (-not $result.failureCause) {
                    $result.failureCause = if ($goalResponse.error.message) { [string] $goalResponse.error.message } else { 'error:unclassified' }
                }
            }
        } finally {
            $http.Dispose()
        }
    } catch {
        $message = $_.Exception.Message
        if ($message -match '^INFRA:([a-z-]+):') {
            $result.status = 'INFRA_FAILURE'
            $result.infra = $Matches[1]
            $result.failureCause = "infra:$($Matches[1])"
        } else {
            $result.status = 'HARNESS_ERROR'
            $result.failureCause = 'error:harness'
        }
        $result.harnessMessage = $message
    } finally {
        Stop-BenchmarkClient -Launcher $launcher
        Start-Sleep -Milliseconds 500
    }
    return [pscustomobject] $result
}

$results = @()
$seedIndex = 0
foreach ($seed in $Seeds) {
    $seedIndex++
    Write-Host "[$seedIndex/$($Seeds.Count)] seed=$seed ..."
    $stdoutLog = Join-Path $runDir "seed-$seed.stdout.log"
    $stderrLog = Join-Path $runDir "seed-$seed.stderr.log"

    $attempt = Invoke-SeedRun -Seed $seed -StdoutLog $stdoutLog -StderrLog $stderrLog
    if ($attempt.status -eq 'INFRA_FAILURE') {
        Write-Host "  infra failure ($($attempt.infra)) - retrying once"
        $attempt = Invoke-SeedRun -Seed $seed -StdoutLog $stdoutLog -StderrLog $stderrLog
    }

    Write-Host "  -> $($attempt.status) cause=$($attempt.failureCause) elapsedMs=$($attempt.elapsedMs)"
    $results += $attempt
    $seedReportPath = Join-Path $runDir "seed-$seed.json"
    $attempt | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $seedReportPath -Encoding UTF8
}

$scored = @($results | Where-Object { $_.status -ne 'INFRA_FAILURE' -and $_.status -ne 'HARNESS_ERROR' })
$succeeded = @($scored | Where-Object { $_.status -eq 'SUCCEEDED' })
$elapsedValues = @($succeeded | ForEach-Object { $_.elapsedMs } | Sort-Object)
$median = if ($elapsedValues.Count -eq 0) { $null }
    elseif ($elapsedValues.Count % 2 -eq 1) { $elapsedValues[[int](($elapsedValues.Count - 1) / 2)] }
    else { ($elapsedValues[$elapsedValues.Count / 2 - 1] + $elapsedValues[$elapsedValues.Count / 2]) / 2.0 }

$causeHistogram = @{}
foreach ($r in $results) {
    $key = if ($r.failureCause) { $r.failureCause } else { 'none' }
    if (-not $causeHistogram.ContainsKey($key)) { $causeHistogram[$key] = 0 }
    $causeHistogram[$key] = $causeHistogram[$key] + 1
}

$aggregate = [ordered]@{
    formatVersion = 1
    benchmarkName = $BenchmarkName
    taskId = $TaskId
    mode = $Mode
    intelligence = $Intelligence
    safety = $Safety
    sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
    startedAtUtc = $timestamp
    completedAtUtc = [DateTime]::UtcNow.ToString('o')
    seedCount = $Seeds.Count
    excludedInfraFailures = @($results | Where-Object { $_.status -eq 'INFRA_FAILURE' -or $_.status -eq 'HARNESS_ERROR' }).Count
    scoredCount = $scored.Count
    succeededCount = $succeeded.Count
    successRate = if ($scored.Count -gt 0) { [Math]::Round($succeeded.Count / $scored.Count, 4) } else { $null }
    deathCount = @($scored | Where-Object { [string] $_.failureCause -like 'died:*' }).Count
    stallCount = @($scored | Where-Object { [string] $_.failureCause -like 'stall:*' }).Count
    overBudgetCount = @($scored | Where-Object { $_.status -eq 'OVER_BUDGET' }).Count
    elapsedMsMedianSucceeded = $median
    elapsedMsMinSucceeded = if ($elapsedValues.Count -gt 0) { $elapsedValues[0] } else { $null }
    elapsedMsMaxSucceeded = if ($elapsedValues.Count -gt 0) { $elapsedValues[-1] } else { $null }
    failureCauseHistogram = $causeHistogram
    perSeed = $results
}
$aggregatePath = Join-Path $runDir 'aggregate.json'
$aggregate | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $aggregatePath -Encoding UTF8

Write-Host ""
Write-Host "Aggregate: $($succeeded.Count)/$($scored.Count) succeeded (excluded $($aggregate.excludedInfraFailures) infra failures), median elapsed $($median)ms"
Write-Host "Report: $aggregatePath"

[pscustomobject]@{
    aggregatePath = $aggregatePath
    runDir = $runDir
    succeededCount = $succeeded.Count
    scoredCount = $scored.Count
    successRate = $aggregate.successRate
} | ConvertTo-Json -Depth 8

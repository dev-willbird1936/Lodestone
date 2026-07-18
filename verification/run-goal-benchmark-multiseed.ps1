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
    [string] $JavaHome = '',
    # Realtime-only: launches verification\gpt54-mini-goal-model-proxy.py (the same proxy already
    # proven by run-recorded-reach-nether-goal.ps1) and wires the LODESTONE_GOAL_MODEL_* env vars
    # around the whole seed loop so -Mode realtime genuinely consults a low-latency model instead
    # of silently falling back to DeterministicGoalModelProvider. Ignored entirely in script mode.
    [int] $ModelPort = 37842,
    [ValidateSet('gpt-5.4-mini')]
    [string] $ModelId = 'gpt-5.4-mini'
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
    param([int] $TimeoutBaseMs = $GoalMaxDurationMs)
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromMilliseconds($TimeoutBaseMs + 30000)
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

# A brand-new isolated run directory has no options.txt, so a fresh client shows a genuine
# first-launch AccessibilityOnboardingScreen instead of going straight to the title screen - the
# title-screen wait below only recognizes TitleScreen and would otherwise time out waiting for a
# screen that never arrives. Seeding the same options.txt the proven interactive keepfocus-client
# run directory already carries (onboardAccessibility:false, tutorialStep:none, default
# keybindings that this adapter's simulated input depends on) skips that screen entirely. Written
# fresh before every launch so a run directory can never drift from this known-good state.
$knownGoodOptionsTxt = @'
version:3955
ao:true
biomeBlendRadius:2
enableVsync:true
entityDistanceScaling:1.0
entityShadows:true
forceUnicodeFont:false
japaneseGlyphVariants:false
fov:0.0
fovEffectScale:1.0
darknessEffectScale:1.0
glintSpeed:0.5
glintStrength:0.75
prioritizeChunkUpdates:0
fullscreen:false
gamma:0.5
graphicsMode:1
guiScale:0
maxFps:120
mipmapLevels:4
narrator:0
particles:0
reducedDebugInfo:false
renderClouds:"true"
renderDistance:12
simulationDistance:12
screenEffectScale:1.0
soundDevice:""
autoJump:false
operatorItemsTab:false
autoSuggestions:true
chatColors:true
chatLinks:true
chatLinksPrompt:true
discrete_mouse_scroll:false
invertYMouse:false
realmsNotifications:true
showSubtitles:false
directionalAudio:false
touchscreen:false
bobView:true
toggleCrouch:false
toggleSprint:false
darkMojangStudiosBackground:false
hideLightningFlashes:false
hideSplashTexts:false
mouseSensitivity:0.5
damageTiltStrength:1.0
highContrast:false
narratorHotkey:true
resourcePacks:[]
incompatibleResourcePacks:[]
lastServer:
lang:en_us
chatVisibility:0
chatOpacity:1.0
chatLineSpacing:0.0
textBackgroundOpacity:0.5
backgroundForChatOnly:true
hideServerAddress:false
advancedItemTooltips:false
pauseOnLostFocus:false
overrideWidth:0
overrideHeight:0
chatHeightFocused:1.0
chatDelay:0.0
chatHeightUnfocused:0.4375
chatScale:1.0
chatWidth:1.0
notificationDisplayTime:1.0
useNativeTransport:true
mainHand:"right"
attackIndicator:1
tutorialStep:none
mouseWheelSensitivity:1.0
rawMouseInput:true
glDebugVerbosity:1
skipMultiplayerWarning:false
hideMatchedNames:true
joinedFirstServer:false
hideBundleTutorial:false
syncChunkWrites:true
showAutosaveIndicator:true
allowServerListing:true
onlyShowSecureChat:false
panoramaScrollSpeed:1.0
telemetryOptInExtra:false
onboardAccessibility:false
menuBackgroundBlurriness:5
key_key.attack:key.mouse.left
key_key.use:key.mouse.right
key_key.forward:key.keyboard.w
key_key.left:key.keyboard.a
key_key.back:key.keyboard.s
key_key.right:key.keyboard.d
key_key.jump:key.keyboard.space
key_key.sneak:key.keyboard.left.shift
key_key.sprint:key.keyboard.left.control
key_key.drop:key.keyboard.q
key_key.inventory:key.keyboard.e
key_key.chat:key.keyboard.t
key_key.playerlist:key.keyboard.tab
key_key.pickItem:key.mouse.middle
key_key.command:key.keyboard.slash
key_key.socialInteractions:key.keyboard.p
key_key.screenshot:key.keyboard.f2
key_key.togglePerspective:key.keyboard.f5
key_key.smoothCamera:key.keyboard.unknown
key_key.fullscreen:key.keyboard.f11
key_key.spectatorOutlines:key.keyboard.unknown
key_key.swapOffhand:key.keyboard.f
key_key.saveToolbarActivator:key.keyboard.c
key_key.loadToolbarActivator:key.keyboard.x
key_key.advancements:key.keyboard.l
key_key.hotbar.1:key.keyboard.1
key_key.hotbar.2:key.keyboard.2
key_key.hotbar.3:key.keyboard.3
key_key.hotbar.4:key.keyboard.4
key_key.hotbar.5:key.keyboard.5
key_key.hotbar.6:key.keyboard.6
key_key.hotbar.7:key.keyboard.7
key_key.hotbar.8:key.keyboard.8
key_key.hotbar.9:key.keyboard.9
soundCategory_master:0.0
soundCategory_music:1.0
soundCategory_record:1.0
soundCategory_weather:1.0
soundCategory_block:1.0
soundCategory_hostile:1.0
soundCategory_neutral:1.0
soundCategory_player:1.0
soundCategory_ambient:1.0
soundCategory_voice:1.0
modelPart_cape:true
modelPart_jacket:true
modelPart_left_sleeve:true
modelPart_right_sleeve:true
modelPart_left_pants_leg:true
modelPart_right_pants_leg:true
modelPart_hat:true
'@

function Set-KnownGoodOptions {
    param([string] $TargetRunDirectory)
    $hostProjectDir = Join-Path $repoRoot 'hosts\neoforge\1.21.1'
    $resolvedRunDir = Join-Path $hostProjectDir $TargetRunDirectory
    New-Item -ItemType Directory -Force -Path $resolvedRunDir | Out-Null
    # Windows PowerShell 5.1's -Encoding UTF8 always emits a BOM, which the proven
    # keepfocus-client options.txt does not have; a leading BOM corrupts the first
    # "version:<n>" line and Minecraft silently falls back to defaults (onboarding screen and
    # all), so this writes plain BOM-less UTF-8 directly instead of using Set-Content -Encoding.
    $noBomUtf8 = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText((Join-Path $resolvedRunDir 'options.txt'), $knownGoodOptionsTxt, $noBomUtf8)
}

function Stop-BenchmarkClient {
    param($Launcher)
    if (-not $Launcher) { return }
    $rootProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($Launcher.Id)" -ErrorAction SilentlyContinue
    if ($rootProcess -and [string] $rootProcess.CommandLine -match 'runKeepFocusClient') {
        & taskkill.exe /PID $Launcher.Id /T /F | Out-Null
    }
}

# Mirrors the proven proxy-launch pattern in run-recorded-reach-nether-goal.ps1 (lines ~225-244):
# start the local codex-backed OpenAI-compatible bridge, wait for its port, then point
# HttpJsonGoalModelProvider.fromEnvironment() at it. Only ever called when -Mode realtime; script
# mode never calls this function, so script mode's process/env footprint is unchanged.
$script:modelProxy = $null
$script:modelProxyLog = Join-Path $runDir 'model-proxy.log'
$script:modelProxyStdoutLog = Join-Path $runDir 'model-proxy.stdout.log'

function Start-RealtimeModelProxy {
    if (Get-NetTCPConnection -LocalPort $ModelPort -State Listen -ErrorAction SilentlyContinue) {
        throw "INFRA:model-proxy-port-occupied: model proxy port $ModelPort already has a listener"
    }
    $proxyPath = Join-Path $repoRoot 'verification\gpt54-mini-goal-model-proxy.py'
    if (-not (Test-Path -LiteralPath $proxyPath)) { throw "INFRA:model-proxy-missing: $proxyPath not found" }
    $env:LODESTONE_PROXY_MODEL = $ModelId
    $env:LODESTONE_PROXY_LOG = $script:modelProxyLog
    $proxyPython = (Get-Command python -ErrorAction Stop).Source
    $script:modelProxy = Start-Process -FilePath $proxyPython -ArgumentList @((('"{0}"' -f $proxyPath)), [string] $ModelPort) `
        -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $script:modelProxyStdoutLog `
        -RedirectStandardError $script:modelProxyLog -PassThru
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (Get-NetTCPConnection -LocalPort $ModelPort -State Listen -ErrorAction SilentlyContinue) { break }
        if ($script:modelProxy.HasExited) { throw "INFRA:model-proxy-exited: realtime model proxy exited: $($script:modelProxy.ExitCode)" }
        Start-Sleep -Milliseconds 250
    }
    if (-not (Get-NetTCPConnection -LocalPort $ModelPort -State Listen -ErrorAction SilentlyContinue)) {
        throw "INFRA:model-proxy-timeout: realtime model proxy did not open port $ModelPort"
    }
    $env:LODESTONE_GOAL_MODEL_URL = "http://127.0.0.1:$ModelPort/v1/chat/completions"
    $env:LODESTONE_GOAL_MODEL_ID = $ModelId
    $env:LODESTONE_GOAL_MODEL_P95_MS = '150'
    $env:LODESTONE_GOAL_MODEL_TIMEOUT_MS = '95000'
}

function Stop-RealtimeModelProxy {
    if ($script:modelProxy) {
        Stop-Process -Id $script:modelProxy.Id -Force -ErrorAction SilentlyContinue
        $script:modelProxy = $null
    }
    Remove-Item Env:LODESTONE_GOAL_MODEL_URL, Env:LODESTONE_GOAL_MODEL_ID, Env:LODESTONE_GOAL_MODEL_P95_MS, `
        Env:LODESTONE_GOAL_MODEL_TIMEOUT_MS, Env:LODESTONE_PROXY_MODEL, Env:LODESTONE_PROXY_LOG -ErrorAction SilentlyContinue
}

# One seed's worth of work: launch a fresh isolated client, wait for the title screen, run the
# goal once, tear the client down. Returns a result record; never throws for a goal-level
# failure (that IS the recorded result) but does throw for infra faults so the caller can retry.
function Invoke-SeedRun {
    param([string] $Seed, [string] $StdoutLog, [string] $StderrLog, [int] $MaxDurationMsOverride = 0)
    $effectiveGoalMaxDurationMs = if ($MaxDurationMsOverride -gt 0) { $MaxDurationMsOverride } else { $GoalMaxDurationMs }

    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "INFRA:java-missing: Java 21 executable not found: $javaExecutable" }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "INFRA:port-occupied: port $Port already has a listener; refusing to attach to a non-harness process"
    }

    Set-KnownGoodOptions -TargetRunDirectory $RunDirectory
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
    $token = [guid]::NewGuid().ToString('N')
    $permissions = 'observe,communicate,control-player,modify-world,administer-server,capture-screen'
    $command = ".\gradlew.bat --no-daemon --console=plain -PincludeFabric262=false -Dlodestone.port=$Port -Dlodestone.token=$token -Dlodestone.permissions=$permissions -Dlodestone.runDirectory=$RunDirectory :hosts:neoforge:mc1_21_1:runKeepFocusClient"
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

        $http = New-HttpClient -TimeoutBaseMs $effectiveGoalMaxDurationMs
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

            $goalArguments = @{
                goal = $Goal; mode = $Mode; taskId = $TaskId; maxSteps = 400
                maxDurationMs = $effectiveGoalMaxDurationMs; dryRun = $false; suppressInGameMessages = $true
                intelligence = $Intelligence; safety = $Safety
            }
            # Omitted (not sent as null/empty) when priming: a genuinely absent worldSeed argument
            # keeps GoalSpec.worldSeed() null, so the plan takes its normal random-fresh-world path
            # instead of trying to type an empty string into the seed field.
            if (-not [string]::IsNullOrWhiteSpace($Seed)) { $goalArguments.worldSeed = $Seed }

            $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
            $goalRpc = Invoke-Rpc $http $uri ([ref] $sessionId) $token 'tools/call' @{
                name = 'minecraft_goal'
                arguments = $goalArguments
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

# The wooden-axe-mine-tree plan's "open singleplayer, then click through to create a world"
# navigation only works correctly when the save list is already non-empty (verified live: an
# empty list makes Singleplayer auto-advance straight past the list to the world-configuration
# screen, so the very next click - meant to open that screen - instead fires its confirm button
# immediately, before a requested worldSeed is ever entered; see task "Fix create_new_world/
# create_world label collision for fresh installs"). A brand-new isolated run directory always
# starts with an empty list, so prime it once with a throwaway, unseeded world before running any
# seeded attempt against it - cheap relative to a full benchmark, and avoids touching the shared
# navigation/plan code from this harness.
function Test-HasExistingSave {
    param([string] $TargetRunDirectory)
    $savesDir = Join-Path (Join-Path $repoRoot 'hosts\neoforge\1.21.1') (Join-Path $TargetRunDirectory 'saves')
    return (Test-Path -LiteralPath $savesDir) -and @(Get-ChildItem -LiteralPath $savesDir -Directory -ErrorAction SilentlyContinue).Count -gt 0
}

if ($Mode -eq 'realtime') {
    Write-Host "Starting realtime model proxy ($ModelId) on port $ModelPort ..."
    Start-RealtimeModelProxy
}
try {
    if (-not (Test-HasExistingSave -TargetRunDirectory $RunDirectory)) {
        Write-Host "Priming $RunDirectory with one throwaway unseeded world (save list is empty) ..."
        $priming = Invoke-SeedRun -Seed $null -StdoutLog (Join-Path $runDir 'priming.stdout.log') -StderrLog (Join-Path $runDir 'priming.stderr.log') -MaxDurationMsOverride 45000
        Write-Host "  priming run -> $($priming.status) cause=$($priming.failureCause) (discarded, not scored)"
        (@{ purpose = 'prime-save-list'; result = $priming } | ConvertTo-Json -Depth 32) |
            Set-Content -LiteralPath (Join-Path $runDir 'priming.json') -Encoding UTF8
    }

    $results = @()
    $seedIndex = 0
    foreach ($seed in $Seeds) {
        $seedIndex++
        Write-Host "[$seedIndex/$($Seeds.Count)] seed=$seed ..."

        $attempt = Invoke-SeedRun -Seed $seed -StdoutLog (Join-Path $runDir "seed-$seed.attempt1.stdout.log") -StderrLog (Join-Path $runDir "seed-$seed.attempt1.stderr.log")
        if ($attempt.status -eq 'INFRA_FAILURE') {
            Write-Host "  infra failure ($($attempt.infra)) - retrying once"
            $attempt = Invoke-SeedRun -Seed $seed -StdoutLog (Join-Path $runDir "seed-$seed.attempt2.stdout.log") -StderrLog (Join-Path $runDir "seed-$seed.attempt2.stderr.log")
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
    if ($Mode -eq 'realtime') {
        $aggregate.realtimeModelId = $ModelId
        $aggregate.realtimeModelPort = $ModelPort
        $aggregate.realtimeModelProxyLog = $script:modelProxyLog
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
} finally {
    if ($Mode -eq 'realtime') { Stop-RealtimeModelProxy }
}

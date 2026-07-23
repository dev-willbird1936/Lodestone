[CmdletBinding()]
param(
    [int] $Port = 37891,
    [string] $Tag = '',
    [string] $JavaHome = '',
    [switch] $SkipRecording
)
$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path (Join-Path $scriptRoot '..')).Path
if (!$JavaHome) { $JavaHome = Join-Path $env:USERPROFILE 'scoop\apps\temurin21-jdk\current' }
$evidence = Join-Path $scriptRoot 'evidence'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
if (!$Tag) { $Tag = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') }
$stem = "agent-goal-attempt-$Tag"

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "port $Port is already listening; refusing to collide with another client" }
if (!$SkipRecording -and (Get-Process obs64 -ErrorAction SilentlyContinue)) { throw 'OBS is already running; refusing to start a second recording instance' }

$keep = Get-ChildItem (Join-Path $root '..\KeepFocus\build\libs') -Filter 'keep_focus-*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$keep) { throw 'KeepFocus artifact not found under ..\KeepFocus\build\libs' }

$runDir = Join-Path $root "runs\$stem"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$stdoutPath = Join-Path $evidence "$stem.minecraft.stdout.log"
$stderrPath = Join-Path $evidence "$stem.minecraft.stderr.log"

# Recording starts BEFORE the game launch (proven ordering from the allinone recorded runner):
# OBS initializes while the machine is still quiet, and the game-capture source hooks the
# Minecraft window whenever it appears.
$obsPid = $null
$obsProfileDirectory = $null
$obsCollectionPath = $null
$videoPath = Join-Path $evidence "$stem.mp4"
if (!$SkipRecording) {
    $obsExecutable = 'C:\Program Files\obs-studio\bin\64bit\obs64.exe'
    $obsSourceScenes = Join-Path $env:APPDATA 'obs-studio\basic\scenes\Untitled.json'
    $obsSourceProfile = Join-Path $env:APPDATA 'obs-studio\basic\profiles\Untitled\basic.ini'
    if (-not (Test-Path -LiteralPath $obsExecutable)) { throw "OBS was not found: $obsExecutable" }
    if (-not (Test-Path -LiteralPath $obsSourceScenes)) { throw "OBS scene collection was not found: $obsSourceScenes" }
    if (-not (Test-Path -LiteralPath $obsSourceProfile)) { throw "OBS profile was not found: $obsSourceProfile" }

    # A prior force-killed OBS leaves an unclean-shutdown sentinel that opens a Safe Mode modal
    # BEFORE argument parsing; minimized and unattended, it blocks startup indefinitely
    # (observed: 42 minutes of nothing recorded). --disable-shutdown-check does not cover it.
    foreach ($configName in @('global.ini', 'user.ini')) {
        $configPath = Join-Path $env:APPDATA "obs-studio\$configName"
        if (Test-Path -LiteralPath $configPath) {
            $configText = Get-Content -LiteralPath $configPath -Raw
            if ($configText -match '(?m)^UncleanShutdown=true$') {
                $configText = $configText -replace '(?m)^UncleanShutdown=true$', 'UncleanShutdown=false'
                Set-Content -LiteralPath $configPath -Value $configText -Encoding UTF8
            }
        }
    }

    $obsConfigRoot = Join-Path $env:APPDATA 'obs-studio'
    $obsProfileDirectory = Join-Path $obsConfigRoot "basic\profiles\$stem"
    $obsCollectionPath = Join-Path $obsConfigRoot "basic\scenes\$stem.json"
    New-Item -ItemType Directory -Force -Path $obsProfileDirectory | Out-Null
    Copy-Item -LiteralPath $obsSourceProfile -Destination (Join-Path $obsProfileDirectory 'basic.ini')
    Copy-Item -LiteralPath $obsSourceScenes -Destination $obsCollectionPath

    $scene = Get-Content -LiteralPath $obsCollectionPath -Raw | ConvertFrom-Json
    $scene.sources[0].settings.window = 'Minecraft NeoForge* 1.21.1*:GLFW30:java.exe'
    $scene | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $obsCollectionPath -Encoding UTF8

    $profilePath = Join-Path $obsProfileDirectory 'basic.ini'
    $profileText = Get-Content -LiteralPath $profilePath -Raw
    $obsPathValue = $evidence -replace '\\', '\\\\'
    $profileText = $profileText -replace '(?m)^FilePath=.*$', "FilePath=$obsPathValue"
    $profileText = $profileText -replace '(?m)^RecFilePath=.*$', "RecFilePath=$obsPathValue"
    $profileText = $profileText -replace '(?m)^FilenameFormatting=.*$', "FilenameFormatting=$stem"
    Set-Content -LiteralPath $profilePath -Value $profileText -Encoding UTF8

    $obsWorkingDirectory = Split-Path -Parent $obsExecutable
    $obs = Start-Process -FilePath $obsExecutable -ArgumentList @('--profile', $stem, '--collection', $stem, '--scene', 'Scene', '--startrecording', '--disable-shutdown-check') -WorkingDirectory $obsWorkingDirectory -WindowStyle Minimized -PassThru
    $recordingFile = $null
    $lastLength = -1
    $grew = $false
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 1
        if ($obs.HasExited) { throw "OBS exited during startup with code $($obs.ExitCode)" }
        if (-not $recordingFile) {
            $recordingFile = Get-ChildItem -LiteralPath $evidence -Filter "$stem*" -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Extension -in @('.mp4', '.mkv', '.mov') } |
                Select-Object -First 1
        }
        if ($recordingFile) {
            $currentLength = (Get-Item -LiteralPath $recordingFile.FullName).Length
            if ($lastLength -ge 0 -and $currentLength -gt $lastLength) { $grew = $true; break }
            $lastLength = $currentLength
        }
    }
    if (-not $grew) {
        Stop-Process -Id $obs.Id -Force -ErrorAction SilentlyContinue
        throw 'OBS recording file did not appear and grow within 90 seconds; recording is not live'
    }
    $obsPid = $obs.Id
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
$permissions = 'observe,communicate,control-player,modify-world,capture-screen'
$env:LODESTONE_PORT = "$Port"
$env:LODESTONE_PERMISSIONS = $permissions

# Zero-config loopback endpoint (post-d35dbb4): no token configured on purpose so ad-hoc RPC
# helpers with stale default tokens still work against this client.
$cmd = '.\gradlew.bat --no-daemon --console=plain -DkeepFocusArtifact="' + $keep.FullName + '" -Dlodestone.runDirectory="' + $runDir + '" -Dlodestone.port=' + $Port + ' -Dlodestone.permissions=' + $permissions + ' :hosts:neoforge:mc1_21_1:runKeepFocusClient'
$launcher = Start-Process cmd.exe -ArgumentList '/d', '/c', $cmd -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru

for ($i = 0; $i -lt 1800; $i++) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { break }
    if ($launcher.HasExited) { throw "launcher exited with code $($launcher.ExitCode); see $stderrPath" }
    Start-Sleep 1
}
if (!(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { throw 'Lodestone MCP endpoint did not open within 30 minutes' }

$gameWindow = $null
for ($i = 0; $i -lt 300 -and -not $gameWindow; $i++) {
    $gameWindow = Get-Process -Name 'java' -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowTitle -like 'Minecraft NeoForge*' } |
        Select-Object -First 1
    if (-not $gameWindow) { Start-Sleep -Seconds 1 }
}
if (-not $gameWindow) { throw 'Minecraft NeoForge window did not appear within 5 minutes of the endpoint opening' }

Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class LodestoneAttemptFocus {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr hWnd, int x, int y, int w, int h, bool repaint);
}
'@
[LodestoneAttemptFocus]::ShowWindow($gameWindow.MainWindowHandle, 5) | Out-Null
# Fill the 1920x1080 OBS canvas; the default dev-client window is ~854x480 and would record tiny.
[LodestoneAttemptFocus]::MoveWindow($gameWindow.MainWindowHandle, 0, 0, 1920, 1080, $true) | Out-Null
[LodestoneAttemptFocus]::SetForegroundWindow($gameWindow.MainWindowHandle) | Out-Null

$state = [ordered]@{
    stem = $stem
    port = $Port
    runDirectory = $runDir
    launcherPid = $launcher.Id
    gamePid = $gameWindow.Id
    obsPid = $obsPid
    obsProfileDirectory = $obsProfileDirectory
    obsCollectionPath = $obsCollectionPath
    videoPath = $videoPath
    stdoutLog = $stdoutPath
    stderrLog = $stderrPath
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
}
$statePath = Join-Path $evidence "$stem.state.json"
$state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
[pscustomobject]$state | ConvertTo-Json

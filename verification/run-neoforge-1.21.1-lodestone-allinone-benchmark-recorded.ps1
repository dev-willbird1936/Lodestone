[CmdletBinding()]
param(
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
$evidenceDirectory = Join-Path $scriptRoot 'evidence'
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null

$runTag = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$videoStem = "neoforge-1.21.1-allinone-$runTag"
$videoPath = Join-Path $evidenceDirectory "$videoStem.mp4"
$benchmarkLauncher = Join-Path $scriptRoot 'run-neoforge-1.21.1-lodestone-allinone-benchmark.bat'
$obsExecutable = 'C:\Program Files\obs-studio\bin\64bit\obs64.exe'
$obsSourceScenes = Join-Path $env:APPDATA 'obs-studio\basic\scenes\Untitled.json'
$obsSourceProfile = Join-Path $env:APPDATA 'obs-studio\basic\profiles\Untitled\basic.ini'

if (-not (Test-Path -LiteralPath $obsExecutable)) { throw "OBS was not found: $obsExecutable" }
if (-not (Test-Path -LiteralPath $obsSourceScenes)) { throw "OBS scene collection was not found: $obsSourceScenes" }
if (-not (Test-Path -LiteralPath $obsSourceProfile)) { throw "OBS profile was not found: $obsSourceProfile" }

$obsProfileName = "LodestoneBenchmark-$runTag"
$obsCollectionName = "LodestoneBenchmark-$runTag"
$obsConfigRoot = Join-Path $env:APPDATA 'obs-studio'
$obsProfileDirectory = Join-Path $obsConfigRoot "basic\profiles\$obsProfileName"
$obsScenesDirectory = Join-Path $obsConfigRoot 'basic\scenes'
$obsCollectionPath = Join-Path $obsScenesDirectory "$obsCollectionName.json"
New-Item -ItemType Directory -Force -Path $obsProfileDirectory | Out-Null
Copy-Item -LiteralPath $obsSourceProfile -Destination (Join-Path $obsProfileDirectory 'basic.ini')
Copy-Item -LiteralPath $obsSourceScenes -Destination $obsCollectionPath

$scenePath = $obsCollectionPath
$scene = Get-Content -LiteralPath $scenePath -Raw | ConvertFrom-Json
$gameWindow = $null

$benchmarkArgs = '/d /c call "' + $benchmarkLauncher + '"'
if ($SkipBuild) { $benchmarkArgs += ' -SkipBuild' }
$benchmark = $null
$obs = $null
$report = $null
$reportPath = $null
$captureStartUtc = $null

function Stop-ObsRecording {
    if (-not $obs -or $obs.HasExited) { return }
    if (-not $obs.CloseMainWindow()) {
        Stop-Process -Id $obs.Id -Force -ErrorAction SilentlyContinue
        return
    }
    if (-not $obs.WaitForExit(60000)) {
        Stop-Process -Id $obs.Id -Force -ErrorAction SilentlyContinue
    }
}

try {
    $scene.sources[0].settings.window = 'Minecraft NeoForge* 1.21.1*:GLFW30:java.exe'
    $scene | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $scenePath -Encoding UTF8

    $profilePath = Join-Path $obsProfileDirectory 'basic.ini'
    $profileText = Get-Content -LiteralPath $profilePath -Raw
    $obsPathValue = $evidenceDirectory -replace '\\', '\\\\'
    $profileText = $profileText -replace '(?m)^FilePath=.*$', "FilePath=$obsPathValue"
    $profileText = $profileText -replace '(?m)^RecFilePath=.*$', "RecFilePath=$obsPathValue"
    $profileText = $profileText -replace '(?m)^FilenameFormatting=.*$', "FilenameFormatting=$videoStem"
    Set-Content -LiteralPath $profilePath -Value $profileText -Encoding UTF8

    $captureStartUtc = [DateTime]::UtcNow
    $obsWorkingDirectory = Split-Path -Parent $obsExecutable
    $obs = Start-Process -FilePath $obsExecutable -ArgumentList @('--profile',$obsProfileName,'--collection',$obsCollectionName,'--scene','Scene','--startrecording','--disable-shutdown-check') -WorkingDirectory $obsWorkingDirectory -WindowStyle Minimized -PassThru
    Start-Sleep -Seconds 5
    if ($obs.HasExited) { throw "OBS exited during startup with code $($obs.ExitCode)." }

    $benchmark = Start-Process -FilePath 'cmd.exe' -ArgumentList $benchmarkArgs -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru

    for ($attempt = 0; $attempt -lt 180 -and -not $gameWindow; $attempt++) {
        $gameWindow = Get-Process -Name 'java' -ErrorAction SilentlyContinue |
            Where-Object { $_.MainWindowTitle -like 'Minecraft NeoForge*' } |
            Select-Object -First 1
        if (-not $gameWindow) { Start-Sleep -Seconds 1 }
    }
    if (-not $gameWindow) { throw 'Minecraft window did not appear within 180 seconds.' }

    Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class LodestoneFocus {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
'@
    [LodestoneFocus]::ShowWindow($gameWindow.MainWindowHandle, 5) | Out-Null
    [LodestoneFocus]::SetForegroundWindow($gameWindow.MainWindowHandle) | Out-Null

    while (-not $benchmark.HasExited) {
        $candidate = Get-ChildItem -LiteralPath $evidenceDirectory -Filter 'neoforge-1.21.1-allinone-*.json' -File |
            Where-Object { $_.LastWriteTimeUtc -ge $captureStartUtc } |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
        if ($candidate) {
            try {
                $parsed = Get-Content -LiteralPath $candidate.FullName -Raw | ConvertFrom-Json
                if ($parsed.status) {
                    $report = $parsed
                    $reportPath = $candidate.FullName
                    break
                }
            } catch { }
        }
        Start-Sleep -Seconds 2
    }

    if (-not $report) {
        $candidate = Get-ChildItem -LiteralPath $evidenceDirectory -Filter 'neoforge-1.21.1-allinone-*.json' -File |
            Where-Object { $_.LastWriteTimeUtc -ge $captureStartUtc } |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
        if ($candidate) {
            $report = Get-Content -LiteralPath $candidate.FullName -Raw | ConvertFrom-Json
            $reportPath = $candidate.FullName
        }
    }
    if (-not $report) { throw "Benchmark exited without writing a report. Exit code: $($benchmark.ExitCode)" }

    Stop-ObsRecording
    Start-Sleep -Seconds 5

    $obsRecording = Get-ChildItem -LiteralPath $evidenceDirectory -Filter "$videoStem*" -File |
        Where-Object { $_.Extension -in @('.mp4','.mkv','.mov') } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if (-not $obsRecording) { throw "OBS did not write a recording for $videoStem." }
    if ($obsRecording.FullName -ne $videoPath) {
        Move-Item -LiteralPath $obsRecording.FullName -Destination $videoPath -Force
    }
} finally {
    if ($obs -and -not $obs.HasExited) {
        Stop-Process -Id $obs.Id -Force -ErrorAction SilentlyContinue
    }
    if ($obsProfileDirectory -and (Test-Path -LiteralPath $obsProfileDirectory)) {
        Remove-Item -LiteralPath $obsProfileDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($obsCollectionPath -and (Test-Path -LiteralPath $obsCollectionPath)) {
        Remove-Item -LiteralPath $obsCollectionPath -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Test-Path -LiteralPath $videoPath)) { throw "Recording not found: $videoPath" }
$probe = & ffprobe -v error -show_entries format=duration:stream=width,height,codec_name -of json $videoPath | ConvertFrom-Json
if (-not $probe.format.duration) { throw "Recording is not a valid video: $videoPath" }
$duration = [math]::Round([double]$probe.format.duration, 2)
$videoSizeMb = [math]::Round((Get-Item -LiteralPath $videoPath).Length / 1MB, 1)

[pscustomobject]@{
    status = $report.status
    report = $reportPath
    video = $videoPath
    durationSeconds = $duration
    sizeMb = $videoSizeMb
    benchmarkExitCode = $benchmark.ExitCode
} | ConvertTo-Json

if ($report.status -ne 'PASS') { exit 1 }

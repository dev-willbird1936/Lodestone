[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $StatePath
)
$ErrorActionPreference = 'Stop'
$state = Get-Content -LiteralPath $StatePath -Raw | ConvertFrom-Json
$evidence = Split-Path -Parent $StatePath
$result = [ordered]@{ stem = $state.stem; videoPath = $null; durationSeconds = $null; sizeMb = $null; portReleased = $false }

if ($state.obsPid) {
    $obs = Get-Process -Id $state.obsPid -ErrorAction SilentlyContinue
    if ($obs -and -not $obs.HasExited) {
        if (-not $obs.CloseMainWindow()) {
            Stop-Process -Id $obs.Id -Force -ErrorAction SilentlyContinue
        } elseif (-not $obs.WaitForExit(60000)) {
            Stop-Process -Id $obs.Id -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Seconds 5
    $recording = Get-ChildItem -LiteralPath $evidence -Filter "$($state.stem)*" -File |
        Where-Object { $_.Extension -in @('.mp4', '.mkv', '.mov') } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($recording) {
        if ($recording.FullName -ne $state.videoPath) {
            Move-Item -LiteralPath $recording.FullName -Destination $state.videoPath -Force
        }
        $result.videoPath = $state.videoPath
    }
}

$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
if ($state.launcherPid) { cmd /c "taskkill /PID $($state.launcherPid) /T /F >nul 2>&1" }
if ($state.gamePid) {
    $game = Get-Process -Id $state.gamePid -ErrorAction SilentlyContinue
    if ($game) { cmd /c "taskkill /PID $($state.gamePid) /T /F >nul 2>&1" }
}
$ErrorActionPreference = $previousEap
for ($i = 0; $i -lt 30; $i++) {
    if (!(Get-NetTCPConnection -LocalPort $state.port -State Listen -ErrorAction SilentlyContinue)) { $result.portReleased = $true; break }
    Start-Sleep 1
}

if ($state.obsProfileDirectory -and (Test-Path -LiteralPath $state.obsProfileDirectory)) {
    Remove-Item -LiteralPath $state.obsProfileDirectory -Recurse -Force -ErrorAction SilentlyContinue
}
if ($state.obsCollectionPath -and (Test-Path -LiteralPath $state.obsCollectionPath)) {
    Remove-Item -LiteralPath $state.obsCollectionPath -Force -ErrorAction SilentlyContinue
}

if ($result.videoPath -and (Test-Path -LiteralPath $result.videoPath)) {
    try {
        $probe = & ffprobe -v error -show_entries format=duration -of json $result.videoPath | ConvertFrom-Json
        if ($probe.format.duration) { $result.durationSeconds = [math]::Round([double]$probe.format.duration, 2) }
    } catch { }
    $result.sizeMb = [math]::Round((Get-Item -LiteralPath $result.videoPath).Length / 1MB, 1)
}

[pscustomobject]$result | ConvertTo-Json

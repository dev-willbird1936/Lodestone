# SPDX-License-Identifier: MIT
<#
    Finds currently running Lodestone-enabled Minecraft instances without already knowing which
    instance folder is running Lodestone. Each host writes a discovery-registry entry to a
    well-known, instance-independent location on startup (~/.lodestone/instances/<port>.json);
    this script reads every entry there, verifies the recorded PID is still an actually-running
    process (a registry file can outlive its process on a crash or a forceful kill), and reports
    the live ones.

    Stale entries are reported but left in place by default; pass -CleanupStale to delete them.
#>
[CmdletBinding()]
param(
    [switch]$CleanupStale
)
$ErrorActionPreference = 'Stop'

$registryDirectory = Join-Path (Join-Path $HOME '.lodestone') 'instances'
if (-not (Test-Path -LiteralPath $registryDirectory -PathType Container)) {
    Write-Host "No Lodestone instance registry found at $registryDirectory (nothing has started since the registry was introduced, or nothing is currently running)."
    return
}

$liveInstances = @()
$staleFiles = @()

Get-ChildItem -LiteralPath $registryDirectory -Filter '*.json' -File | ForEach-Object {
    $file = $_
    try {
        $entry = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
    } catch {
        Write-Warning "Skipping unreadable registry entry $($file.FullName): $($_.Exception.Message)"
        return
    }

    $process = Get-Process -Id ([int]$entry.pid) -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        $staleFiles += $file.FullName
        return
    }

    $liveInstances += [pscustomobject]@{
        port         = $entry.port
        workingDir   = $entry.workingDir
        tokenFile    = $entry.tokenFile
        pid          = $entry.pid
        modVersion   = $entry.modVersion
        startedAtUtc = $entry.startedAtUtc
        registryFile = $file.FullName
    }
}

if ($staleFiles.Count -gt 0) {
    $plural = if ($staleFiles.Count -eq 1) { 'entry' } else { 'entries' }
    if ($CleanupStale) {
        foreach ($staleFile in $staleFiles) {
            Remove-Item -LiteralPath $staleFile -Force -ErrorAction SilentlyContinue
        }
        Write-Host "Removed $($staleFiles.Count) stale registry $plural (process no longer running)."
    } else {
        Write-Host "Found $($staleFiles.Count) stale registry $plural (process no longer running); rerun with -CleanupStale to remove."
    }
}

if ($liveInstances.Count -eq 0) {
    Write-Host 'No running Lodestone instance found.'
} else {
    $plural = if ($liveInstances.Count -eq 1) { 'instance' } else { 'instances' }
    Write-Host "Found $($liveInstances.Count) running Lodestone $plural`:"
}

$liveInstances

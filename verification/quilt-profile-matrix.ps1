# SPDX-License-Identifier: MIT
<#
    Extracts the exact Lodestone host bytes from final Quilt CurseForge profile
    archives, verifies their frozen hashes, and runs the fresh-world Quilt
    acceptance cells without metadata rewriting.
#>
[CmdletBinding()]
param(
    [string]$Profile1201,
    [string]$Profile1211,
    [string]$ServerRoot = (Join-Path $env:TEMP 'lodestone-matrix'),
    [string]$ExtractionRoot = (Join-Path $env:TEMP 'lodestone-quilt-profile-matrix')
)
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Profile1201)) {
    $Profile1201 = Join-Path $projectRoot 'verification/curseforge-profiles/lodestone-quilt-1.20.1-local.zip'
}
if ([string]::IsNullOrWhiteSpace($Profile1211)) {
    $Profile1211 = Join-Path $projectRoot 'verification/curseforge-profiles/lodestone-quilt-1.21.1-local.zip'
}

function Assert([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Get-Sha256([string]$path) {
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Extract-ProfileHost([string]$profilePath, [string]$uploadFilename, [string]$version) {
    Assert (Test-Path -LiteralPath $profilePath -PathType Leaf) "Missing final Quilt profile: $profilePath"
    $evidencePath = Join-Path $projectRoot 'verification/evidence/release-conformance-v1.0.0.json'
    $evidence = Get-Content -Raw -LiteralPath $evidencePath | ConvertFrom-Json
    $artifact = @($evidence.artifacts | Where-Object { $_.uploadFilename -ceq $uploadFilename })
    Assert ($artifact.Count -eq 1) "Release evidence does not bind Quilt profile $uploadFilename."
    $profileItem = Get-Item -LiteralPath $profilePath
    $actualProfileHash = Get-Sha256 $profilePath
    Assert ($profileItem.Length -eq [long]$artifact[0].bytes -and $actualProfileHash -ceq [string]$artifact[0].sha256) `
        "Final Quilt profile bytes do not match release evidence: $uploadFilename"

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $directory = Join-Path $ExtractionRoot ("$version-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $output = Join-Path $directory 'lodestone-quilt.jar'
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $profilePath).Path)
    try {
        $entry = $archive.GetEntry('overrides/mods/lodestone-quilt.jar')
        Assert ($null -ne $entry) "$uploadFilename has no embedded Lodestone Quilt host."
        $input = $entry.Open()
        $stream = [IO.File]::Open($output, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try { $input.CopyTo($stream) } finally {
            $stream.Dispose()
            $input.Dispose()
        }
    } finally {
        $archive.Dispose()
    }
    Write-Host "Exact Quilt profile host: $uploadFilename SHA-256 $(Get-Sha256 $output)"
    return $output
}

$host1201 = Extract-ProfileHost $Profile1201 'lodestone-1.0.0-profile-quilt-mc-1.20.1-curseforge.zip' '1.20.1'
$host1211 = Extract-ProfileHost $Profile1211 'lodestone-1.0.0-profile-quilt-mc-1.21.1-curseforge.zip' '1.21.1'
& (Join-Path $PSScriptRoot 'packaged-server-matrix.ps1') -Root $ServerRoot `
    -Only @('Quilt 1.20.1 / Loader 0.29.2 via Fabric compatibility', 'Quilt 1.21.1 / Loader 0.29.2 via Fabric compatibility') `
    -Quilt1201HostJar $host1201 -Quilt1211HostJar $host1211

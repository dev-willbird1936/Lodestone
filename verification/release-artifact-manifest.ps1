# SPDX-License-Identifier: MIT
<#
    Freezes or verifies the exact production-source snapshot, release JARs/launchers,
    and CurseForge profile ZIPs used by the live compatibility matrix.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Freeze', 'Verify')]
    [string]$Mode,
    [string]$Manifest = (Join-Path $PSScriptRoot 'evidence/release-artifacts-2026-07-12.json')
)
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$artifactPaths = @(
    'hosts/fabric/1.18.2/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/fabric/1.19.2/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/fabric/1.20.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/fabric/1.21.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/fabric/26.2/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/neoforge/1.21.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/forge/1.16.5/build/reobfJar/output.jar',
    'hosts/forge/1.18.2/build/reobfJar/output.jar',
    'hosts/forge/1.19.2/build/reobfJar/output.jar',
    'hosts/forge/1.20.1/build/reobfJar/output.jar',
    'hosts/forge/1.21.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/forge/1.12.2/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/forge/1.7.10/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/forge/1.8.9/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/paper/1.21.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/spigot/1.21.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'hosts/folia/1.21.4/build/libs/lodestone-0.1.0-SNAPSHOT.jar',
    'gateway/rcon-launcher/build/distributions/rcon-launcher.zip',
    'gateway/legacy-bridge-launcher/build/distributions/legacy-bridge-launcher.zip'
)
$profilePaths = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'curseforge-profiles') `
        -Filter '*-local.zip' -File | Sort-Object Name | ForEach-Object {
            $_.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
        })
$allArtifactPaths = @($artifactPaths + $profilePaths)

function Get-RelativePath([string]$path) {
    return ([IO.Path]::GetFullPath($path)).Substring($projectRoot.Length + 1).Replace('\', '/')
}

function Get-SourceSnapshot {
    $extensions = @('.java', '.json', '.toml', '.yml', '.yaml', '.properties', '.gradle', '.kts')
    $files = @(Get-ChildItem -LiteralPath @(
                (Join-Path $projectRoot 'common'),
                (Join-Path $projectRoot 'adapters'),
                (Join-Path $projectRoot 'gateway'),
                (Join-Path $projectRoot 'hosts'),
                (Join-Path $projectRoot 'protocol')) -Recurse -File |
            Where-Object {
                $_.Extension.ToLowerInvariant() -in $extensions -and
                $_.FullName -notmatch '[\\/](build|runs|\.gradle)[\\/]'
            })
    foreach ($rootInput in @('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties')) {
        $candidate = Join-Path $projectRoot $rootInput
        if (Test-Path -LiteralPath $candidate) { $files += Get-Item -LiteralPath $candidate }
    }
    $rows = @($files | Sort-Object FullName -Unique | ForEach-Object {
            $relative = Get-RelativePath $_.FullName
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$relative`t$hash"
        })
    $bytes = [Text.Encoding]::UTF8.GetBytes(($rows -join "`n"))
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $digest = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    return [ordered]@{ fileCount = $rows.Count; sha256 = $digest }
}

function Get-ArtifactRows {
    return @($allArtifactPaths | ForEach-Object {
            $full = Join-Path $projectRoot $_
            if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "Missing release artifact: $_" }
            $item = Get-Item -LiteralPath $full
            [ordered]@{
                path = $_.Replace('\', '/')
                bytes = $item.Length
                sha256 = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        })
}

$snapshot = Get-SourceSnapshot
$artifacts = Get-ArtifactRows
if ($Mode -eq 'Freeze') {
    $payload = [ordered]@{
        formatVersion = 1
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        sourceSnapshot = $snapshot
        artifacts = $artifacts
    }
    $parent = Split-Path -Parent $Manifest
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $temporary = "$Manifest.tmp-$([Guid]::NewGuid().ToString('N'))"
    try {
        $payload | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $temporary -Encoding UTF8
        Move-Item -LiteralPath $temporary -Destination $Manifest -Force
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
    Write-Output "FROZEN source=$($snapshot.sha256) files=$($snapshot.fileCount) artifacts=$($artifacts.Count)"
    exit 0
}

if (-not (Test-Path -LiteralPath $Manifest -PathType Leaf)) { throw "Release manifest is missing: $Manifest" }
$expected = Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json
if ($expected.sourceSnapshot.fileCount -ne $snapshot.fileCount -or
        $expected.sourceSnapshot.sha256 -ne $snapshot.sha256) {
    throw "Production source changed after release freeze. Expected $($expected.sourceSnapshot.sha256), got $($snapshot.sha256)."
}
$expectedRows = @($expected.artifacts)
if ($expectedRows.Count -ne $artifacts.Count) {
    throw "Release artifact count changed after freeze. Expected $($expectedRows.Count), got $($artifacts.Count)."
}
for ($index = 0; $index -lt $artifacts.Count; $index++) {
    $wanted = $expectedRows[$index]
    $actual = $artifacts[$index]
    if ($wanted.path -ne $actual.path -or $wanted.bytes -ne $actual.bytes -or $wanted.sha256 -ne $actual.sha256) {
        throw "Release artifact changed after freeze: expected $($wanted.path) $($wanted.sha256), got $($actual.path) $($actual.sha256)."
    }
}
Write-Output "VERIFIED source=$($snapshot.sha256) files=$($snapshot.fileCount) artifacts=$($artifacts.Count)"

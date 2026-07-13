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
    [string]$Manifest
)
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Manifest)) {
    $Manifest = Join-Path $PSScriptRoot 'evidence/release-artifacts-v1.0.0.json'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$artifactPaths = @(
    'hosts/fabric/1.18.2/build/libs/lodestone-1.0.0.jar',
    'hosts/fabric/1.19.2/build/libs/lodestone-1.0.0.jar',
    'hosts/fabric/1.20.1/build/libs/lodestone-1.0.0.jar',
    'hosts/fabric/1.21.1/build/libs/lodestone-1.0.0.jar',
    'hosts/fabric/26.2/build/libs/lodestone-1.0.0.jar',
    'hosts/neoforge/1.21.1/build/libs/lodestone-1.0.0.jar',
    'hosts/forge/1.16.5/build/reobfJar/output.jar',
    'hosts/forge/1.18.2/build/reobfJar/output.jar',
    'hosts/forge/1.19.2/build/reobfJar/output.jar',
    'hosts/forge/1.20.1/build/reobfJar/output.jar',
    'hosts/forge/1.21.1/build/libs/lodestone-1.0.0.jar',
    'hosts/forge/1.12.2/build/libs/lodestone-1.0.0.jar',
    'hosts/forge/1.7.10/build/libs/lodestone-1.0.0.jar',
    'hosts/forge/1.8.9/build/libs/lodestone-1.0.0.jar',
    'hosts/paper/1.21.1/build/libs/lodestone-1.0.0.jar',
    'hosts/spigot/1.21.1/build/libs/lodestone-1.0.0.jar',
    'hosts/folia/1.21.4/build/libs/lodestone-1.0.0.jar',
    'gateway/rcon-launcher/build/distributions/rcon-launcher.zip',
    'gateway/legacy-bridge-launcher/build/distributions/legacy-bridge-launcher.zip'
)
$profilePaths = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'curseforge-profiles') `
        -Filter '*-local.zip' -File | Sort-Object Name | ForEach-Object {
            $_.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
        })
$expectedProfileNames = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'curseforge-profiles') `
        -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'manifest.json') } |
        Sort-Object Name | ForEach-Object { "$($_.Name)-local.zip" })
$actualProfileNames = @($profilePaths | ForEach-Object { Split-Path -Leaf $_ })
if ($expectedProfileNames.Count -ne 13) {
    throw "Expected 13 tracked CurseForge profile sources, found $($expectedProfileNames.Count)."
}
$profileNameDifference = @(Compare-Object -ReferenceObject $expectedProfileNames `
        -DifferenceObject $actualProfileNames -CaseSensitive)
if ($profileNameDifference.Count -ne 0) {
    throw "Release profile ZIPs do not exactly match tracked profiles: $($profileNameDifference.InputObject -join ', ')"
}
$allArtifactPaths = @($artifactPaths + $profilePaths)

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-ProfileArchive([string]$relativePath) {
    $fullPath = Join-Path $projectRoot $relativePath
    $archive = [IO.Compression.ZipFile]::OpenRead($fullPath)
    try {
        $names = @($archive.Entries | ForEach-Object FullName)
        if (@($names | Where-Object { $_ -ceq 'manifest.json' }).Count -ne 1) {
            throw "$relativePath must contain exactly one root manifest.json."
        }
        if (@($names | Where-Object { $_ -clike 'overrides/mods/*.jar' }).Count -lt 1) {
            throw "$relativePath must contain at least one staged mod JAR."
        }
        $duplicateNames = @($names | Group-Object | Where-Object Count -gt 1)
        if ($duplicateNames.Count -gt 0) {
            throw "$relativePath contains duplicate ZIP entries: $($duplicateNames.Name -join ', ')"
        }
        foreach ($name in $names) {
            $segments = @($name.Split('/'))
            if ($name.Contains('\') -or $name.StartsWith('/') -or $name -match '^[A-Za-z]:' -or
                    @($segments | Where-Object { $_ -in @('', '.', '..') }).Count -gt 0) {
                throw "$relativePath contains unsafe ZIP entry: $name"
            }
        }

        $profileName = ([IO.Path]::GetFileName($relativePath) -replace '-local\.zip$', '')
        $sourceManifestPath = Join-Path $PSScriptRoot "curseforge-profiles/$profileName/manifest.json"
        $manifestEntry = $archive.GetEntry('manifest.json')
        $stream = $manifestEntry.Open()
        try {
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true)
            try { $archiveManifest = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
        } finally {
            $stream.Dispose()
        }
        $sourceManifest = [IO.File]::ReadAllText($sourceManifestPath, [Text.Encoding]::UTF8)
        $archiveManifest = $archiveManifest.Replace("`r`n", "`n").Replace("`r", "`n").TrimEnd([char[]]"`n")
        $sourceManifest = $sourceManifest.Replace("`r`n", "`n").Replace("`r", "`n").TrimEnd([char[]]"`n")
        if ($archiveManifest -cne $sourceManifest) {
            throw "$relativePath manifest.json differs from its tracked profile source."
        }
    } finally {
        $archive.Dispose()
    }
}

foreach ($profilePath in $profilePaths) { Assert-ProfileArchive $profilePath }

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

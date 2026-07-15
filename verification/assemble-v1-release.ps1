# SPDX-License-Identifier: MIT
<#
    Atomically assembles or read-only verifies the complete Lodestone v1.0.0
    GitHub release payload. Historical C0 manifests are intentionally outside
    this tool's inputs and outputs.

    SHA256SUMS covers all 32 distributables, release-manifest.json, portable
    provenance, and the SPDX artifact-inventory SBOM. It excludes itself and
    any later detached signatures, avoiding checksum/self-signature cycles.

    The SPDX document is deliberately a first-party release-file inventory. It
    does not claim complete or package-manager-grade third-party dependency data.

    Assemble from a clean candidate whose HEAD is tagged v1.0.0 into a new
    directory outside the repository:
      pwsh ./verification/assemble-v1-release.ps1 -Mode Assemble `
        -StagingDirectory ../lodestone-v1.0.0-release

    Recheck the immutable staged bytes without modifying them:
      pwsh ./verification/assemble-v1-release.ps1 -Mode Verify `
        -StagingDirectory ../lodestone-v1.0.0-release
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Assemble', 'Verify')]
    [string]$Mode,

    [Parameter(Mandatory = $true)]
    [string]$StagingDirectory,

    [string]$ProjectRoot,

    # Test-fixture escape hatch only. Production freezes always derive identity
    # from Git and reject any tracked or untracked working-tree change.
    [switch]$AllowDirtyTreeForTests,
    [string]$SourceCommit,
    [string]$SourceTree,

    # The v1.0.0 tag predates the tracked-input snapshot fix. The release workflow
    # may overlay this exact verifier from an audited control commit; the release
    # source identity still comes from the immutable tag and no release input may
    # be dirty.
    [switch]$AllowReleaseToolOverlay,

    # When the workflow overlays a portability fix from a reviewed control
    # commit, record the exact tool commit and raw Git blob IDs in the sidecars.
    [string]$ReleaseToolCommit,
    [string]$ReleaseToolTree,
    [string]$ReleaseToolAssemblerBlob,
    [string]$ReleaseToolStagerBlob
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$InventoryPath = Join-Path $PSScriptRoot 'release-assets-v1.0.0.json'
$CertificationRelativePath = 'verification/evidence/release-conformance-v1.0.0.json'
$ManifestName = 'release-manifest.json'
$ChecksumsName = 'SHA256SUMS'
$ProvenanceName = 'lodestone-1.0.0-provenance.json'
$SbomName = 'lodestone-1.0.0-sbom.spdx.json'
$Repository = 'https://github.com/dev-willbird1936/Lodestone'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$PathComparison = if ($env:OS -eq 'Windows_NT') {
    [StringComparison]::OrdinalIgnoreCase
} else {
    [StringComparison]::Ordinal
}

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd(
    [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$CertificationPath = Join-Path $ProjectRoot $CertificationRelativePath
$StagingDirectory = [IO.Path]::GetFullPath($StagingDirectory).TrimEnd(
    [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$projectPathPrefix = $ProjectRoot + [IO.Path]::DirectorySeparatorChar
if ($StagingDirectory.StartsWith($projectPathPrefix, $PathComparison)) {
    throw 'Release staging must stay outside ProjectRoot so generated format-v2 release metadata never enters tagged source.'
}
$immutableC0Manifest = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'evidence/release-artifacts-2026-07-12.json'))
if ($StagingDirectory.Equals($immutableC0Manifest, $PathComparison) -or
        (Split-Path -Leaf $StagingDirectory) -ceq 'release-artifacts-2026-07-12.json') {
    throw 'Refusing to use the immutable C0 release manifest path as v1.0.0 staging.'
}

function Assert-HashIdentity([string]$Value, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -cnotmatch '^(?:[0-9a-f]{40}|[0-9a-f]{64})$') {
        throw "$Label must be a lowercase 40- or 64-character Git object ID."
    }
}

function Invoke-Git([string[]]$Arguments) {
    $output = @(& git --no-optional-locks -C $ProjectRoot @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Git command failed: git --no-optional-locks -C <project> $($Arguments -join ' '): $($output -join ' ')"
    }
    return ($output -join "`n").Trim()
}

function Get-SourceIdentity {
    if ($AllowDirtyTreeForTests) {
        Assert-HashIdentity $SourceCommit 'SourceCommit'
        Assert-HashIdentity $SourceTree 'SourceTree'
        return [ordered]@{
            commit = $SourceCommit
            tree = $SourceTree
            testBypass = $true
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($SourceCommit) -or
            -not [string]::IsNullOrWhiteSpace($SourceTree)) {
        throw 'SourceCommit and SourceTree overrides require -AllowDirtyTreeForTests.'
    }
    if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
        throw "Project root is missing: $ProjectRoot"
    }
    $repositoryRoot = [IO.Path]::GetFullPath((Invoke-Git @('rev-parse', '--show-toplevel'))).TrimEnd(
        [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    if (-not $repositoryRoot.Equals($ProjectRoot, $PathComparison)) {
        throw "ProjectRoot must be the Git repository root: expected $repositoryRoot, got $ProjectRoot."
    }
    $dirty = Invoke-Git @('status', '--porcelain=v1', '--untracked-files=all')
    if (-not [string]::IsNullOrWhiteSpace($dirty)) {
        $dirtyEntries = @($dirty -split "`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if (-not $AllowReleaseToolOverlay -or @($dirtyEntries | Where-Object {
                    $entry = $_.TrimEnd("`r")
                    $path = if ($entry.Length -ge 4) { $entry.Substring(3) } else { '' }
                    -not $path.Equals('verification/assemble-v1-release.ps1', [StringComparison]::OrdinalIgnoreCase)
                }).Count -ne 0) {
            throw "Final release freeze requires a clean Git tree. Dirty entries: $($dirty -replace "`n", '; ')"
        }
    }
    $commit = (Invoke-Git @('rev-parse', 'HEAD')).ToLowerInvariant()
    $tree = (Invoke-Git @('rev-parse', 'HEAD^{tree}')).ToLowerInvariant()
    Assert-HashIdentity $commit 'Git commit'
    Assert-HashIdentity $tree 'Git tree'
    try { $tagCommit = (Invoke-Git @('rev-parse', '--verify', 'refs/tags/v1.0.0^{commit}')).ToLowerInvariant() }
    catch { throw "Final release freeze requires tag v1.0.0 at HEAD. $($_.Exception.Message)" }
    if ($tagCommit -cne $commit) {
        throw "Final release freeze requires tag v1.0.0 at HEAD; tag resolves to $tagCommit, HEAD is $commit."
    }
    return [ordered]@{
        commit = $commit
        tree = $tree
        testBypass = $false
    }
}

function Assert-SourceIdentityUnchanged([hashtable]$Expected) {
    if ([bool]$Expected.testBypass) { return }
    $current = Get-SourceIdentity
    if ([string]$current.commit -cne [string]$Expected.commit -or
            [string]$current.tree -cne [string]$Expected.tree -or [bool]$current.testBypass) {
        throw 'Git source identity changed during release assembly or verification.'
    }
}

function Assert-RelativePath([string]$RelativePath, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or
            $RelativePath.Contains('\') -or $RelativePath.StartsWith('/') -or
            $RelativePath -match '^[A-Za-z]:' -or $RelativePath.Contains([char]0)) {
        throw "$Label is not a safe project-relative path: $RelativePath"
    }
    $segments = @($RelativePath.Split('/'))
    if (@($segments | Where-Object { $_ -in @('', '.', '..') }).Count -ne 0) {
        throw "$Label contains an unsafe path segment: $RelativePath"
    }
}

function Resolve-SourcePath([string]$RelativePath) {
    Assert-RelativePath $RelativePath 'Artifact sourcePath'
    $resolved = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $RelativePath))
    $prefix = $ProjectRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix, $PathComparison)) {
        throw "Artifact sourcePath escapes ProjectRoot: $RelativePath"
    }
    return $resolved
}

function Get-BuildInputSnapshot {
    $roots = @('common', 'adapters', 'gateway', 'hosts', 'protocol', 'verification/curseforge-profiles')
    # Hash the tracked source graph, not every matching file in the working tree. Profile staging
    # intentionally creates ignored CurseForge overrides and ZIP inputs beneath the profile roots;
    # including those generated files made the certified snapshot depend on whether staging had
    # already run. The clean-tree gate above still rejects any tracked edit or untracked source file.
    $tracked = @((Invoke-Git @('ls-files', '--cached')) -split "`n" |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $relativeFiles = @($tracked | Where-Object {
            $relative = $_.Replace('\', '/')
            $underRoot = @($roots | Where-Object {
                    $relative.StartsWith($_ + '/', [StringComparison]::Ordinal)
                }).Count -gt 0
            $underRoot -and [IO.Path]::GetExtension($relative).ToLowerInvariant() -in @(
                    '.java', '.json', '.toml', '.properties', '.gradle', '.kts', '.ps1')
        })
    foreach ($relativeFile in @('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
            'gradle/wrapper/gradle-wrapper.properties')) {
        if ($tracked -contains $relativeFile) { $relativeFiles += $relativeFile }
    }
    $files = @($relativeFiles | Sort-Object -Unique | ForEach-Object {
            $fullFile = Join-Path $ProjectRoot $_
            if (Test-Path -LiteralPath $fullFile -PathType Leaf) { Get-Item -LiteralPath $fullFile }
        })
    $rows = @($files | Sort-Object FullName -Unique | ForEach-Object {
            $relative = $_.FullName.Substring($ProjectRoot.Length + 1).Replace('\', '/')
            "$relative`t$(Get-Sha256 $_.FullName)"
        })
    $bytes = [Text.Encoding]::UTF8.GetBytes(($rows -join "`n"))
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
    return [ordered]@{ algorithm = 'sha256'; fileCount = $rows.Count; sha256 = $digest }
}

function Resolve-EvidenceLog([string]$RelativePath) {
    Assert-RelativePath $RelativePath 'Evidence log path'
    if (-not $RelativePath.StartsWith('verification/evidence/logs/', [StringComparison]::Ordinal)) {
        throw "Evidence logs must be retained beneath verification/evidence/logs: $RelativePath"
    }
    return Resolve-SourcePath $RelativePath
}

function Assert-UploadFilename([string]$Filename) {
    if ([string]::IsNullOrWhiteSpace($Filename) -or
            $Filename -cnotmatch '^[a-z0-9][a-z0-9._-]*$') {
        throw "Unsafe or noncanonical upload filename: $Filename"
    }
}

function Assert-NonEmptyStringArray([object]$Artifact, [string]$Field, [string]$Filename) {
    if ($Artifact.PSObject.Properties.Name -notcontains $Field) {
        throw "Inventory artifact $Filename is missing $Field."
    }
    $values = @($Artifact.$Field)
    if ($values.Count -eq 0) {
        throw "Inventory artifact $Filename has an empty $Field array."
    }
    foreach ($value in $values) {
        if ([string]::IsNullOrWhiteSpace([string]$value)) {
            throw "Inventory artifact $Filename contains a blank $Field value."
        }
    }
}

function Get-Inventory {
    if (-not (Test-Path -LiteralPath $InventoryPath -PathType Leaf)) {
        throw "Release inventory is missing: $InventoryPath"
    }
    $inventory = Get-Content -Raw -LiteralPath $InventoryPath | ConvertFrom-Json
    if ([int]$inventory.formatVersion -ne 1 -or
            [string]$inventory.productVersion -cne '1.0.0' -or
            [string]$inventory.tag -cne 'v1.0.0') {
        throw 'Release inventory must describe product 1.0.0 and tag v1.0.0 with formatVersion 1.'
    }
    $artifacts = @($inventory.artifacts)
    if ($artifacts.Count -ne 32) {
        throw "Release inventory must contain exactly 32 distributables; found $($artifacts.Count)."
    }
    $names = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $sources = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $counts = @{ mod = 0; plugin = 0; profile = 0; launcher = 0 }
    foreach ($artifact in $artifacts) {
        $filename = [string]$artifact.uploadFilename
        $sourcePath = [string]$artifact.sourcePath
        Assert-UploadFilename $filename
        Assert-RelativePath $sourcePath 'Artifact sourcePath'
        if (-not $names.Add($filename)) { throw "Duplicate upload filename: $filename" }
        if (-not $sources.Add($sourcePath)) { throw "Duplicate artifact sourcePath: $sourcePath" }
        $type = [string]$artifact.type
        if (-not $counts.ContainsKey($type)) { throw "Unsupported artifact type '$type' for $filename." }
        $counts[$type]++
        if ([string]::IsNullOrWhiteSpace([string]$artifact.platform)) {
            throw "Inventory artifact $filename is missing platform."
        }
        foreach ($field in @('minecraftVersions', 'evidenceRows', 'buildJava', 'runtimeJava')) {
            Assert-NonEmptyStringArray $artifact $field $filename
        }
        foreach ($version in @($artifact.minecraftVersions)) {
            if ([string]$version -ceq 'any' -or [string]$version -match ',') {
                throw "Inventory artifact $filename contains a pseudo Minecraft version: $version"
            }
        }
        foreach ($field in @('buildJava', 'runtimeJava')) {
            foreach ($java in @($artifact.$field)) {
                if ([string]$java -cnotmatch '^[0-9]+$') {
                    throw "Inventory artifact $filename contains invalid $field value: $java"
                }
            }
        }
        if ($type -ceq 'profile') {
            Assert-NonEmptyStringArray $artifact 'installerJava' $filename
            foreach ($java in @($artifact.installerJava)) {
                if ([string]$java -cnotmatch '^[0-9]+$') {
                    throw "Inventory artifact $filename contains invalid installerJava value: $java"
                }
            }
            if ([string]::IsNullOrWhiteSpace([string]$artifact.embeddedArtifactUploadFilename)) {
                throw "Inventory profile $filename is missing embeddedArtifactUploadFilename."
            }
            Assert-UploadFilename ([string]$artifact.embeddedArtifactUploadFilename)
        } elseif ($type -ceq 'launcher') {
            Assert-NonEmptyStringArray $artifact 'launcherJava' $filename
            foreach ($java in @($artifact.launcherJava)) {
                if ([string]$java -cnotmatch '^[0-9]+$') {
                    throw "Inventory artifact $filename contains invalid launcherJava value: $java"
                }
            }
        }
    }
    foreach ($expected in @{ mod = 14; plugin = 3; profile = 13; launcher = 2 }.GetEnumerator()) {
        if ($counts[$expected.Key] -ne $expected.Value) {
            throw "Release inventory type '$($expected.Key)' must contain $($expected.Value), found $($counts[$expected.Key])."
        }
    }
    $byName = @{}
    foreach ($artifact in $artifacts) { $byName.Add([string]$artifact.uploadFilename, $artifact) }
    foreach ($profile in @($artifacts | Where-Object { [string]$_.type -ceq 'profile' })) {
        $embedded = [string]$profile.embeddedArtifactUploadFilename
        if (-not $byName.ContainsKey($embedded) -or [string]$byName[$embedded].type -cne 'mod') {
            throw "Profile $($profile.uploadFilename) must bind to a declared mod artifact: $embedded"
        }
    }
    return $inventory
}

function Get-ReleaseCertification([object]$Inventory, [hashtable]$Source) {
    if (-not (Test-Path -LiteralPath $CertificationPath -PathType Leaf)) {
        throw "Release certification evidence is missing: $CertificationPath"
    }
    $evidence = Get-Content -Raw -LiteralPath $CertificationPath | ConvertFrom-Json
    if ([int]$evidence.formatVersion -ne 2 -or
            [string]$evidence.productVersion -cne '1.0.0' -or
            [string]$evidence.tag -cne 'v1.0.0') {
        throw 'Release certification evidence must describe product 1.0.0 and tag v1.0.0 with formatVersion 2.'
    }

    $buildSource = $evidence.buildSource
    if ($null -eq $buildSource -or $null -eq $buildSource.inputSnapshot) {
        throw 'Release certification must retain buildSource commit, tree, and inputSnapshot.'
    }
    Assert-HashIdentity ([string]$buildSource.commit) 'Certification buildSource.commit'
    Assert-HashIdentity ([string]$buildSource.tree) 'Certification buildSource.tree'
    $snapshot = $buildSource.inputSnapshot
    if ([string]$snapshot.algorithm -cne 'sha256' -or [int]$snapshot.fileCount -lt 1 -or
            [string]$snapshot.sha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'Release certification buildSource.inputSnapshot is malformed.'
    }
    if (-not [bool]$Source.testBypass) {
        Invoke-Git @('merge-base', '--is-ancestor', [string]$buildSource.commit, [string]$Source.commit) | Out-Null
        $declaredTree = (Invoke-Git @('rev-parse', "$($buildSource.commit)^{tree}")).ToLowerInvariant()
        if ($declaredTree -cne [string]$buildSource.tree) {
            throw 'Release certification buildSource tree does not match its declared commit.'
        }
        $currentSnapshot = Get-BuildInputSnapshot
        if ([int]$currentSnapshot.fileCount -ne [int]$snapshot.fileCount -or
                [string]$currentSnapshot.sha256 -cne [string]$snapshot.sha256) {
            throw "Release build inputs differ from the snapshot that produced the certified artifacts: expected $($snapshot.fileCount)/$($snapshot.sha256), got $($currentSnapshot.fileCount)/$($currentSnapshot.sha256)."
        }
    }

    $logsByPath = @{}
    foreach ($log in @($evidence.logs)) {
        if ($log.PSObject.Properties.Name -notcontains 'path' -or $log.PSObject.Properties.Name -notcontains 'sha256') {
            throw 'Release certification log is missing path or sha256.'
        }
        $path = [string]$log.path
        if ($logsByPath.ContainsKey($path)) { throw "Duplicate release certification log: $path" }
        $logPath = Resolve-EvidenceLog $path
        if (-not (Test-Path -LiteralPath $logPath -PathType Leaf)) { throw "Retained evidence log is missing: $path" }
        if ((Get-Sha256 $logPath) -cne [string]$log.sha256) { throw "Retained evidence log hash differs: $path" }
        $logsByPath.Add($path, $log)
    }
    if ($logsByPath.Count -eq 0) { throw 'Release certification must retain at least one checksummed evidence log.' }

    $referencedRows = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($artifact in @($Inventory.artifacts)) {
        foreach ($row in @($artifact.evidenceRows)) { [void]$referencedRows.Add([string]$row) }
    }
    $matrixRows = @($evidence.matrixRows)
    if ($matrixRows.Count -ne $referencedRows.Count) {
        throw "Release certification must contain exactly $($referencedRows.Count) matrix rows; found $($matrixRows.Count)."
    }
    $seenRows = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($row in $matrixRows) {
        foreach ($field in @('id', 'status', 'method', 'runLog')) {
            if ($row.PSObject.Properties.Name -notcontains $field -or
                    [string]::IsNullOrWhiteSpace([string]$row.$field)) {
                throw "Release certification matrix row is missing $field."
            }
        }
        $id = [string]$row.id
        if (-not $seenRows.Add($id)) { throw "Duplicate release certification matrix row: $id" }
        if (-not $referencedRows.Contains($id)) { throw "Release certification has an unreferenced matrix row: $id" }
        if ([string]$row.status -cne 'pass') { throw "Release certification matrix row is not PASS: $id" }
        if (-not $logsByPath.ContainsKey([string]$row.runLog)) {
            throw "Release certification matrix row must reference a retained checksummed log: $id"
        }
    }
    foreach ($id in $referencedRows) {
        if (-not $seenRows.Contains($id)) { throw "Release certification is missing matrix row: $id" }
    }

    $evidenceArtifacts = @($evidence.artifacts)
    if ($evidenceArtifacts.Count -ne 32) {
        throw "Release certification must bind exactly 32 artifacts; found $($evidenceArtifacts.Count)."
    }
    $inventoryByName = @{}
    foreach ($artifact in @($Inventory.artifacts)) {
        $inventoryByName.Add([string]$artifact.uploadFilename, $artifact)
    }
    $evidenceByName = @{}
    foreach ($artifact in $evidenceArtifacts) {
        foreach ($field in @('uploadFilename', 'sourcePath', 'bytes', 'sha256', 'evidenceRows')) {
            if ($artifact.PSObject.Properties.Name -notcontains $field) {
                throw "Release certification artifact is missing $field."
            }
        }
        $name = [string]$artifact.uploadFilename
        Assert-UploadFilename $name
        if (-not $inventoryByName.ContainsKey($name)) {
            throw "Release certification binds an unknown artifact: $name"
        }
        if ($evidenceByName.ContainsKey($name)) {
            throw "Duplicate release certification artifact: $name"
        }
        $expected = $inventoryByName[$name]
        if ([string]$artifact.sourcePath -cne [string]$expected.sourcePath -or
                (ConvertTo-CompactJson @($artifact.evidenceRows)) -cne
                (ConvertTo-CompactJson @($expected.evidenceRows))) {
            throw "Release certification metadata differs from inventory: $name"
        }
        if ([long]$artifact.bytes -lt 1 -or [string]$artifact.sha256 -cnotmatch '^[0-9a-f]{64}$') {
            throw "Release certification has invalid byte length or SHA-256: $name"
        }
        foreach ($row in @($artifact.evidenceRows)) {
            if (-not $seenRows.Contains([string]$row)) {
                throw "Release certification artifact references an unknown matrix row: $name / $row"
            }
        }
        $sourcePath = Resolve-SourcePath ([string]$artifact.sourcePath)
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Certification source artifact is missing: $($artifact.sourcePath)"
        }
        $sourceItem = Get-Item -LiteralPath $sourcePath
        if ([long]$sourceItem.Length -ne [long]$artifact.bytes -or
                (Get-Sha256 $sourcePath) -cne [string]$artifact.sha256) {
            throw "Certification artifact hash or byte length differs from source: $name"
        }
        $evidenceByName.Add($name, $artifact)
    }
    if ($evidenceByName.Count -ne $inventoryByName.Count) {
        throw 'Release certification does not bind every inventory artifact exactly once.'
    }
    return [ordered]@{
        evidence = $evidence
        artifactByName = $evidenceByName
        buildSource = [ordered]@{
            commit = [string]$buildSource.commit
            tree = [string]$buildSource.tree
            inputSnapshot = [ordered]@{
                algorithm = [string]$snapshot.algorithm
                fileCount = [int]$snapshot.fileCount
                sha256 = [string]$snapshot.sha256
            }
        }
        binding = [ordered]@{
            evidencePath = $CertificationRelativePath
            formatVersion = [int]$evidence.formatVersion
            sha256 = Get-Sha256 $CertificationPath
            matrixRowCount = $matrixRows.Count
            artifactCount = $evidenceArtifacts.Count
            retainedLogCount = $logsByPath.Count
        }
    }
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Normalize-ManifestText([string]$Text) {
    return $Text.Replace("`r`n", "`n").Replace("`r", "`n").TrimEnd([char[]]"`n")
}

function Get-ZipEntrySha256([IO.Compression.ZipArchiveEntry]$Entry) {
    $hash = [Security.Cryptography.SHA256]::Create()
    $stream = $Entry.Open()
    try {
        $digest = $hash.ComputeHash($stream)
        return ([BitConverter]::ToString($digest)).Replace('-', '').ToLowerInvariant()
    } finally {
        $stream.Dispose()
        $hash.Dispose()
    }
}

function Assert-FabricDependencyJar([IO.Compression.ZipArchiveEntry]$Entry, [string]$ProfilePath) {
    if ([long]$Entry.Length -gt 134217728) {
        throw "$ProfilePath contains a Fabric dependency over 128 MiB: $($Entry.FullName)"
    }
    $memory = [IO.MemoryStream]::new()
    $stream = $Entry.Open()
    try { $stream.CopyTo($memory) }
    finally { $stream.Dispose() }
    $memory.Position = 0
    try {
        $nested = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $true)
        try {
            $nestedEntries = @($nested.Entries)
            if ($nestedEntries.Count -gt 20000) {
                throw "$ProfilePath dependency JAR has too many entries: $($Entry.FullName)"
            }
            $nestedNames = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
            [long]$expandedBytes = 0
            foreach ($nestedEntry in $nestedEntries) {
                $name = [string]$nestedEntry.FullName
                if (-not $nestedNames.Add($name)) {
                    throw "$ProfilePath dependency JAR has duplicate or case-colliding entry: $($Entry.FullName)!/$name"
                }
                if ([string]::IsNullOrWhiteSpace($name) -or $name.Contains('\') -or
                        $name.StartsWith('/') -or $name -match '^[A-Za-z]:' -or $name.Contains([char]0)) {
                    throw "$ProfilePath dependency JAR contains unsafe entry: $($Entry.FullName)!/$name"
                }
                $trimmed = $name.TrimEnd('/')
                if ([string]::IsNullOrWhiteSpace($trimmed) -or
                        @($trimmed.Split('/') | Where-Object { $_ -in @('', '.', '..') }).Count -ne 0) {
                    throw "$ProfilePath dependency JAR contains unsafe entry: $($Entry.FullName)!/$name"
                }
                $unixType = (([int64]$nestedEntry.ExternalAttributes -shr 16) -band 0xF000)
                if ($unixType -eq 0xA000 -or
                        (([int64]$nestedEntry.ExternalAttributes -band 0x400) -ne 0)) {
                    throw "$ProfilePath dependency JAR contains a link or reparse entry: $($Entry.FullName)!/$name"
                }
                if ([long]$nestedEntry.Length -gt 134217728) {
                    throw "$ProfilePath dependency JAR contains an oversized entry: $($Entry.FullName)!/$name"
                }
                $expandedBytes += [long]$nestedEntry.Length
                if ($expandedBytes -gt 536870912) {
                    throw "$ProfilePath dependency JAR expands beyond 512 MiB: $($Entry.FullName)"
                }
            }
            if (@($nestedEntries | Where-Object { $_.FullName -ceq 'fabric.mod.json' }).Count -ne 1) {
                throw "$ProfilePath Fabric dependency lacks exactly one root fabric.mod.json: $($Entry.FullName)"
            }
        } finally {
            $nested.Dispose()
        }
    } finally {
        $memory.Dispose()
    }
}

function Get-ProfileArchiveDetails([object]$Artifact, [object]$Inventory) {
    $relativePath = [string]$Artifact.sourcePath
    $archivePath = Resolve-SourcePath $relativePath
    if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
        throw "Missing release profile: $relativePath"
    }
    $fileName = [IO.Path]::GetFileName($relativePath)
    if ($fileName -cnotmatch '^lodestone-[a-z0-9.-]+-local\.zip$') {
        throw "Noncanonical release profile filename: $relativePath"
    }
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        $entries = @($archive.Entries)
        if ($entries.Count -gt 4096) {
            throw "$relativePath contains too many ZIP entries: $($entries.Count)."
        }
        $entryNames = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        [long]$expandedBytes = 0
        $entryPairs = @()
        foreach ($entry in $entries) {
            $name = [string]$entry.FullName
            if (-not $entryNames.Add($name)) {
                throw "$relativePath contains duplicate or case-colliding ZIP entry: $name"
            }
            if ([string]::IsNullOrWhiteSpace($name) -or $name.Contains('\') -or
                    $name.StartsWith('/') -or $name -match '^[A-Za-z]:' -or $name.Contains([char]0)) {
                throw "$relativePath contains unsafe ZIP entry: $name"
            }
            $segments = @($name.Split('/'))
            if (@($segments | Where-Object { $_ -in @('', '.', '..') }).Count -ne 0) {
                throw "$relativePath contains unsafe ZIP entry: $name"
            }
            $unixType = (([int64]$entry.ExternalAttributes -shr 16) -band 0xF000)
            if ($unixType -eq 0xA000 -or (([int64]$entry.ExternalAttributes -band 0x400) -ne 0)) {
                throw "$relativePath contains a link or reparse ZIP entry: $name"
            }
            if ([long]$entry.Length -gt 134217728) {
                throw "$relativePath contains an oversized ZIP entry: $name"
            }
            $expandedBytes += [long]$entry.Length
            if ($expandedBytes -gt 536870912) {
                throw "$relativePath expands beyond the 512 MiB profile safety limit."
            }
            if ($name -cne 'manifest.json' -and
                    $name -cnotmatch '^overrides/mods/[A-Za-z0-9][A-Za-z0-9._+-]*\.jar$') {
                throw "$relativePath contains an unexpected profile entry outside the exact allowlist: $name"
            }
            $entryPairs += [pscustomobject]@{
                Entry = $entry
                Path = $name
                Bytes = [long]$entry.Length
                Sha256 = Get-ZipEntrySha256 $entry
                Role = if ($name -ceq 'manifest.json') { 'manifest' } else { 'unclassified-jar' }
            }
        }
        if (@($entries | Where-Object { $_.FullName -ceq 'manifest.json' }).Count -ne 1) {
            throw "$relativePath must contain exactly one root manifest.json."
        }
        if (@($entries | Where-Object { $_.FullName -clike 'overrides/mods/*.jar' }).Count -lt 1) {
            throw "$relativePath must contain at least one staged mod JAR."
        }
        $manifestEntry = @($entries | Where-Object { $_.FullName -ceq 'manifest.json' })[0]
        if ([long]$manifestEntry.Length -gt 1048576) {
            throw "$relativePath manifest.json exceeds 1 MiB."
        }
        $stream = $manifestEntry.Open()
        try {
            $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8, $true)
            try { $archiveManifest = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
        } finally {
            $stream.Dispose()
        }
        $profileName = $fileName.Substring(0, $fileName.Length - '-local.zip'.Length)
        $sourceManifestPath = Resolve-SourcePath "verification/curseforge-profiles/$profileName/manifest.json"
        if (-not (Test-Path -LiteralPath $sourceManifestPath -PathType Leaf)) {
            throw "$relativePath has no tracked profile manifest source: $sourceManifestPath"
        }
        $sourceManifest = [IO.File]::ReadAllText($sourceManifestPath, [Text.Encoding]::UTF8)
        if ((Normalize-ManifestText $archiveManifest) -cne (Normalize-ManifestText $sourceManifest)) {
            throw "$relativePath manifest.json differs from its tracked profile source."
        }
        try { $profileManifest = $archiveManifest | ConvertFrom-Json }
        catch { throw "$relativePath manifest.json is not valid JSON: $($_.Exception.Message)" }
        $minecraftVersions = @($Artifact.minecraftVersions)
        $modLoaders = @($profileManifest.minecraft.modLoaders)
        if ($profileManifest.PSObject.Properties.Name -notcontains 'files' -or
                @($profileManifest.files).Count -ne 0) {
            throw "$relativePath profile manifest files must be empty; all dependencies must be nested and hash-frozen."
        }
        $loaderPrefix = switch ([string]$Artifact.platform) {
            'fabric' { 'fabric-' }
            'quilt' { 'quilt-' }
            'forge' { 'forge-' }
            'neoforge' { 'neoforge-' }
            default { throw "$relativePath uses unsupported profile platform: $($Artifact.platform)" }
        }
        if ([string]$profileManifest.manifestType -cne 'minecraftModpack' -or
                [int]$profileManifest.manifestVersion -ne 1 -or
                [string]$profileManifest.version -cne '1.0.0' -or
                [string]$profileManifest.overrides -cne 'overrides' -or
                $minecraftVersions.Count -ne 1 -or
                [string]$profileManifest.minecraft.version -cne [string]$minecraftVersions[0] -or
                $modLoaders.Count -ne 1 -or
                -not ([string]$modLoaders[0].id).StartsWith($loaderPrefix, [StringComparison]::Ordinal) -or
                -not [bool]$modLoaders[0].primary) {
            throw "$relativePath profile manifest must target product 1.0.0, Minecraft $($minecraftVersions -join ','), and platform $($Artifact.platform)."
        }

        $embeddedName = [string]$Artifact.embeddedArtifactUploadFilename
        $embeddedArtifacts = @($Inventory.artifacts | Where-Object {
                [string]$_.uploadFilename -ceq $embeddedName -and [string]$_.type -ceq 'mod'
            })
        if ($embeddedArtifacts.Count -ne 1) {
            throw "$relativePath cannot resolve its embedded mod artifact binding: $embeddedName"
        }
        $embeddedSourcePath = Resolve-SourcePath ([string]$embeddedArtifacts[0].sourcePath)
        if (-not (Test-Path -LiteralPath $embeddedSourcePath -PathType Leaf)) {
            throw "$relativePath embedded mod source is missing: $($embeddedArtifacts[0].sourcePath)"
        }
        $embeddedItem = Get-Item -LiteralPath $embeddedSourcePath
        $embeddedHash = Get-Sha256 $embeddedSourcePath
        $embeddedPairs = @($entryPairs | Where-Object {
                $_.Role -ceq 'unclassified-jar' -and $_.Bytes -eq [long]$embeddedItem.Length -and
                $_.Sha256 -ceq $embeddedHash
            })
        if ($embeddedPairs.Count -ne 1) {
            throw "$relativePath must contain exactly one byte-identical copy of $embeddedName; found $($embeddedPairs.Count)."
        }
        $embeddedPairs[0].Role = 'embedded-mod'
        $dependencyPairs = @($entryPairs | Where-Object { $_.Role -ceq 'unclassified-jar' })
        $platform = [string]$Artifact.platform
        if ($platform -notin @('fabric', 'quilt') -and $dependencyPairs.Count -ne 0) {
            throw "$relativePath platform '$platform' must not contain undeclared dependency JARs."
        }
        foreach ($dependency in $dependencyPairs) {
            $leaf = [IO.Path]::GetFileName([string]$dependency.Path)
            if ($leaf -cnotmatch '^fabric-[a-z0-9][a-z0-9._+-]*\.jar$') {
                throw "$relativePath contains a dependency outside the Fabric API allowlist: $($dependency.Path)"
            }
            Assert-FabricDependencyJar $dependency.Entry $relativePath
            $dependency.Role = 'dependency'
        }

        $contentRows = @($entryPairs | Sort-Object Path | ForEach-Object {
                [ordered]@{
                    path = [string]$_.Path
                    bytes = [long]$_.Bytes
                    sha256 = [string]$_.Sha256
                    role = [string]$_.Role
                }
            })
        return [ordered]@{
            embeddedArtifactUploadFilename = $embeddedName
            embeddedEntry = [string]$embeddedPairs[0].Path
            dependencyCount = $dependencyPairs.Count
            entries = $contentRows
        }
    } finally {
        $archive.Dispose()
    }
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Copy-Atomic([string]$Source, [string]$Destination) {
    $temporary = "$Destination.tmp-$([Guid]::NewGuid().ToString('N'))"
    try {
        $input = [IO.File]::Open($Source, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        try {
            $output = [IO.FileStream]::new($temporary, [IO.FileMode]::CreateNew,
                [IO.FileAccess]::Write, [IO.FileShare]::None)
            try {
                $input.CopyTo($output)
                $output.Flush($true)
            } finally {
                $output.Dispose()
            }
        } finally {
            $input.Dispose()
        }
        [IO.File]::Move($temporary, $Destination)
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function Write-AtomicText([string]$Destination, [string]$Text) {
    $temporary = "$Destination.tmp-$([Guid]::NewGuid().ToString('N'))"
    try {
        [IO.File]::WriteAllText($temporary, $Text, $Utf8NoBom)
        [IO.File]::Move($temporary, $Destination)
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function ConvertTo-CanonicalJson([object]$Value) {
    return (($Value | ConvertTo-Json -Depth 20) + "`n")
}

function ConvertTo-CompactJson([object]$Value) {
    return ($Value | ConvertTo-Json -Depth 20 -Compress)
}

function New-Provenance([object[]]$ArtifactRows, [hashtable]$Source, [hashtable]$Certification,
                        [string]$GeneratedAtUtc, [hashtable]$ReleaseTool) {
    $subjects = @($ArtifactRows | Sort-Object uploadFilename | ForEach-Object {
            [ordered]@{
                name = $_.uploadFilename
                digest = [ordered]@{ sha256 = $_.sha256 }
            }
        })
    return [ordered]@{
        _type = 'https://in-toto.io/Statement/v1'
        subject = $subjects
        predicateType = 'https://slsa.dev/provenance/v1'
        predicate = [ordered]@{
            buildDefinition = [ordered]@{
                buildType = 'https://github.com/dev-willbird1936/Lodestone/verification/assemble-v1-release/v1'
                externalParameters = [ordered]@{
                    productVersion = '1.0.0'
                    tag = 'v1.0.0'
                    inventory = 'verification/release-assets-v1.0.0.json'
                    certifiedArtifactBuildSource = $Certification.buildSource
                }
                internalParameters = [ordered]@{
                    assemblySourceCommit = $Source.commit
                    assemblySourceTree = $Source.tree
                    dirtyTreeAllowedForTests = [bool]$Source.testBypass
                    releaseTool = $ReleaseTool
                }
                resolvedDependencies = @([ordered]@{
                        uri = "git+$Repository"
                        digest = [ordered]@{
                            gitCommit = $Certification.buildSource.commit
                            gitTree = $Certification.buildSource.tree
                        }
                    })
            }
            runDetails = [ordered]@{
                builder = [ordered]@{ id = 'urn:lodestone:release-assembly:powershell:v1' }
                metadata = [ordered]@{
                    invocationId = "urn:uuid:$([Guid]::NewGuid())"
                    startedOn = $GeneratedAtUtc
                    finishedOn = $GeneratedAtUtc
                }
            }
        }
    }
}

function New-SpdxSbom([object[]]$ArtifactRows, [hashtable]$Source, [string]$GeneratedAtUtc) {
    $files = @()
    $relationships = @([ordered]@{
            spdxElementId = 'SPDXRef-DOCUMENT'
            relationshipType = 'DESCRIBES'
            relatedSpdxElement = 'SPDXRef-Package-Lodestone'
        })
    $index = 0
    foreach ($row in @($ArtifactRows | Sort-Object uploadFilename)) {
        $index++
        $fileId = "SPDXRef-File-$index"
        $files += [ordered]@{
            fileName = "./$($row.uploadFilename)"
            SPDXID = $fileId
            checksums = @([ordered]@{
                    algorithm = 'SHA256'
                    checksumValue = $row.sha256
                })
            licenseConcluded = 'NOASSERTION'
            copyrightText = 'NOASSERTION'
        }
        $relationships += [ordered]@{
            spdxElementId = 'SPDXRef-Package-Lodestone'
            relationshipType = 'CONTAINS'
            relatedSpdxElement = $fileId
        }
    }
    return [ordered]@{
        spdxVersion = 'SPDX-2.3'
        dataLicense = 'CC0-1.0'
        SPDXID = 'SPDXRef-DOCUMENT'
        name = 'Lodestone 1.0.0 release artifact inventory'
        documentNamespace = "$Repository/releases/download/v1.0.0/lodestone-1.0.0-sbom-$($Source.commit)"
        documentComment = 'Scope: first-party release artifact inventory with exact SHA-256 binding. This document does not assert complete third-party or transitive dependency identification; dependency-grade SBOM precision requires ecosystem-specific scanners for each built artifact.'
        creationInfo = [ordered]@{
            created = $GeneratedAtUtc
            creators = @('Tool: Lodestone assemble-v1-release.ps1')
            comment = "Generated from Git commit $($Source.commit), tree $($Source.tree)."
        }
        packages = @([ordered]@{
                name = 'Lodestone'
                SPDXID = 'SPDXRef-Package-Lodestone'
                versionInfo = '1.0.0'
                downloadLocation = 'NOASSERTION'
                filesAnalyzed = $false
                licenseConcluded = 'NOASSERTION'
                licenseDeclared = 'NOASSERTION'
                copyrightText = 'NOASSERTION'
            })
        files = $files
        relationships = $relationships
    }
}

function Get-ChecksumText([string]$Directory) {
    $filesByName = @{}
    foreach ($file in @(Get-ChildItem -LiteralPath $Directory -File)) {
        if ($file.Name -cne $ChecksumsName) { $filesByName.Add($file.Name, $file.FullName) }
    }
    [string[]]$names = @($filesByName.Keys)
    [Array]::Sort($names, [StringComparer]::Ordinal)
    $lines = @($names | ForEach-Object { "$(Get-Sha256 ([string]$filesByName[$_]))  $_" })
    return (($lines -join "`n") + "`n")
}

function Remove-AssemblyDirectory([string]$Path, [string]$ExpectedParent, [string]$ExpectedPrefix) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return }
    $resolved = [IO.Path]::GetFullPath($Path)
    $parent = [IO.Path]::GetFullPath((Split-Path -Parent $resolved))
    $leaf = Split-Path -Leaf $resolved
    if (-not $parent.Equals([IO.Path]::GetFullPath($ExpectedParent), $PathComparison) -or
            -not $leaf.StartsWith($ExpectedPrefix, [StringComparison]::Ordinal)) {
        throw "Refusing to remove unexpected assembly directory: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function Invoke-Assemble([object]$Inventory, [hashtable]$Source, [hashtable]$Certification) {
    if (Test-Path -LiteralPath $StagingDirectory) {
        throw "Assembly staging directory must not already exist: $StagingDirectory"
    }
    $stagingParent = Split-Path -Parent $StagingDirectory
    $stagingLeaf = Split-Path -Leaf $StagingDirectory
    if ([string]::IsNullOrWhiteSpace($stagingParent) -or [string]::IsNullOrWhiteSpace($stagingLeaf)) {
        throw "Invalid assembly staging directory: $StagingDirectory"
    }
    [void](New-Item -ItemType Directory -Force -Path $stagingParent)
    $temporaryName = ".$stagingLeaf.assembling-$([Guid]::NewGuid().ToString('N'))"
    $temporaryDirectory = Join-Path $stagingParent $temporaryName
    [void](New-Item -ItemType Directory -Path $temporaryDirectory)
    try {
        $artifactRows = @()
        foreach ($artifact in @($Inventory.artifacts | Sort-Object uploadFilename)) {
            $profileContents = $null
            if ([string]$artifact.type -ceq 'profile') {
                $profileContents = Get-ProfileArchiveDetails $artifact $Inventory
            }
            $sourcePath = Resolve-SourcePath ([string]$artifact.sourcePath)
            if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
                throw "Missing release artifact: $($artifact.sourcePath)"
            }
            $destination = Join-Path $temporaryDirectory ([string]$artifact.uploadFilename)
            Copy-Atomic $sourcePath $destination
            $item = Get-Item -LiteralPath $destination
            $artifactRow = [ordered]@{
                uploadFilename = [string]$artifact.uploadFilename
                sourcePath = [string]$artifact.sourcePath
                bytes = [long]$item.Length
                sha256 = Get-Sha256 $destination
                type = [string]$artifact.type
                platform = [string]$artifact.platform
                minecraftVersions = @($artifact.minecraftVersions)
                buildJava = @($artifact.buildJava)
                runtimeJava = @($artifact.runtimeJava)
                evidenceRows = @($artifact.evidenceRows)
            }
            if ([string]$artifact.type -ceq 'profile') {
                $artifactRow['installerJava'] = @($artifact.installerJava)
                $artifactRow['embeddedArtifactUploadFilename'] = [string]$artifact.embeddedArtifactUploadFilename
                $artifactRow['profileContents'] = $profileContents
            } elseif ([string]$artifact.type -ceq 'launcher') {
                $artifactRow['launcherJava'] = @($artifact.launcherJava)
            }
            $artifactRows += $artifactRow
        }
        # The certification snapshot is immutable for v1.0.0. Deriving all
        # sidecar metadata from it makes repeated assembly byte-identical.
        $generatedAtUtc = [string]$Certification.certifiedAtUtc
        if ([string]::IsNullOrWhiteSpace($generatedAtUtc)) {
            throw 'Release certification is missing its deterministic certifiedAtUtc value.'
        }
        $releaseTool = [ordered]@{
            commit = if ([string]::IsNullOrWhiteSpace($ReleaseToolCommit)) { $Source.commit } else { $ReleaseToolCommit.ToLowerInvariant() }
            tree = if ([string]::IsNullOrWhiteSpace($ReleaseToolTree)) { $Source.tree } else { $ReleaseToolTree.ToLowerInvariant() }
            assemblerBlob = if ([string]::IsNullOrWhiteSpace($ReleaseToolAssemblerBlob)) {
                (Invoke-Git @('rev-parse', 'HEAD:verification/assemble-v1-release.ps1')).ToLowerInvariant()
            } else { $ReleaseToolAssemblerBlob.ToLowerInvariant() }
            stagerBlob = if ([string]::IsNullOrWhiteSpace($ReleaseToolStagerBlob)) {
                (Invoke-Git @('rev-parse', 'HEAD:verification/curseforge-profiles/stage-fabric-1182-profile.ps1')).ToLowerInvariant()
            } else { $ReleaseToolStagerBlob.ToLowerInvariant() }
        }
        $manifest = [ordered]@{
            formatVersion = 2
            productVersion = '1.0.0'
            tag = 'v1.0.0'
            generatedAtUtc = $generatedAtUtc
            source = [ordered]@{
                repository = $Repository
                commit = $Source.commit
                tree = $Source.tree
                testBypass = [bool]$Source.testBypass
                releaseTool = $releaseTool
            }
            certification = $Certification.binding
            integrity = [ordered]@{
                checksumFile = $ChecksumsName
                checksumAlgorithm = 'SHA-256'
                scope = 'All 32 distributables, release-manifest.json, provenance JSON, and SPDX SBOM. SHA256SUMS excludes itself to avoid a self-reference cycle and excludes detached signatures, which are not accepted in unsigned assembly staging.'
            }
            artifacts = $artifactRows
        }
        Write-AtomicText (Join-Path $temporaryDirectory $ManifestName) (ConvertTo-CanonicalJson $manifest)
        $provenance = New-Provenance $artifactRows $Source $Certification $generatedAtUtc $releaseTool
        Write-AtomicText (Join-Path $temporaryDirectory $ProvenanceName) (ConvertTo-CanonicalJson $provenance)
        $sbom = New-SpdxSbom $artifactRows $Source $generatedAtUtc
        Write-AtomicText (Join-Path $temporaryDirectory $SbomName) (ConvertTo-CanonicalJson $sbom)
        Write-AtomicText (Join-Path $temporaryDirectory $ChecksumsName) (Get-ChecksumText $temporaryDirectory)

        $assembledFiles = @(Get-ChildItem -LiteralPath $temporaryDirectory -Force)
        if ($assembledFiles.Count -ne 36 -or @($assembledFiles | Where-Object { $_.PSIsContainer }).Count -ne 0) {
            throw "Internal release assembly count mismatch: expected 36 flat files, found $($assembledFiles.Count)."
        }
        Invoke-Verify $Inventory $Source $Certification $temporaryDirectory -PreMove
        [IO.Directory]::Move($temporaryDirectory, $StagingDirectory)
        Write-Output "ASSEMBLED artifacts=32 files=36 commit=$($Source.commit) tree=$($Source.tree) manifest=$ManifestName"
    } finally {
        Remove-AssemblyDirectory $temporaryDirectory $stagingParent ".$stagingLeaf.assembling-"
    }
}

function Assert-SidecarBindings([string]$Directory, [object]$Manifest, [object[]]$ManifestRows) {
    $provenancePath = Join-Path $Directory $ProvenanceName
    $provenance = Get-Content -Raw -LiteralPath $provenancePath | ConvertFrom-Json
    if ([string]$provenance._type -cne 'https://in-toto.io/Statement/v1' -or
            [string]$provenance.predicateType -cne 'https://slsa.dev/provenance/v1') {
        throw 'Portable provenance has an unsupported statement or predicate type.'
    }
    if ([string]$provenance.predicate.buildDefinition.internalParameters.assemblySourceCommit -cne
            [string]$Manifest.source.commit -or
            [string]$provenance.predicate.buildDefinition.internalParameters.assemblySourceTree -cne
            [string]$Manifest.source.tree) {
        throw 'Portable provenance source identity differs from release-manifest.json.'
    }
    $subjects = @($provenance.subject)
    if ($subjects.Count -ne 32) { throw "Portable provenance must contain 32 subjects; found $($subjects.Count)." }
    $manifestByName = @{}
    foreach ($row in $ManifestRows) { $manifestByName.Add([string]$row.uploadFilename, $row) }
    foreach ($subject in $subjects) {
        $name = [string]$subject.name
        if (-not $manifestByName.ContainsKey($name) -or
                [string]$subject.digest.sha256 -cne [string]$manifestByName[$name].sha256) {
            throw "Portable provenance subject differs from manifest: $name"
        }
    }

    $sbomPath = Join-Path $Directory $SbomName
    $sbom = Get-Content -Raw -LiteralPath $sbomPath | ConvertFrom-Json
    if ([string]$sbom.spdxVersion -cne 'SPDX-2.3' -or
            [string]$sbom.dataLicense -cne 'CC0-1.0') {
        throw 'SBOM is not an SPDX 2.3 JSON document.'
    }
    $comment = [string]$sbom.documentComment
    if (-not $comment.Contains('first-party release artifact inventory') -or
            -not $comment.Contains('does not assert complete third-party')) {
        throw 'SBOM must state its first-party-only dependency inventory limitation.'
    }
    $sbomFiles = @($sbom.files)
    if ($sbomFiles.Count -ne 32) { throw "SPDX SBOM must contain 32 files; found $($sbomFiles.Count)." }
    foreach ($file in $sbomFiles) {
        $name = ([string]$file.fileName) -replace '^\./', ''
        $checksum = @($file.checksums | Where-Object { $_.algorithm -ceq 'SHA256' })
        if (-not $manifestByName.ContainsKey($name) -or $checksum.Count -ne 1 -or
                [string]$checksum[0].checksumValue -cne [string]$manifestByName[$name].sha256) {
            throw "SPDX SBOM file differs from manifest: $name"
        }
    }
}

function Invoke-Verify([object]$Inventory, [hashtable]$Source, [hashtable]$Certification,
        [string]$Directory, [switch]$PreMove) {
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        throw "Release staging directory is missing: $Directory"
    }
    $expectedNames = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($artifact in @($Inventory.artifacts)) { [void]$expectedNames.Add([string]$artifact.uploadFilename) }
    foreach ($sidecar in @($ManifestName, $ChecksumsName, $ProvenanceName, $SbomName)) {
        [void]$expectedNames.Add($sidecar)
    }
    $actualEntries = @(Get-ChildItem -LiteralPath $Directory -Force)
    if ($actualEntries.Count -ne 36) {
        throw "Release staging directory must contain exactly 36 files; found $($actualEntries.Count)."
    }
    $actualNames = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($entry in $actualEntries) {
        if ($entry.PSIsContainer) { throw "Unexpected staged directory: $($entry.Name)" }
        if (-not $actualNames.Add($entry.Name)) { throw "Duplicate staged filename: $($entry.Name)" }
        if (-not $expectedNames.Contains($entry.Name)) { throw "Unexpected staged file: $($entry.Name)" }
        if ($entry.Name -notin @($ManifestName, $ChecksumsName, $ProvenanceName, $SbomName)) {
            Assert-UploadFilename $entry.Name
        }
    }
    foreach ($name in $expectedNames) {
        if (-not $actualNames.Contains($name)) { throw "Missing staged file: $name" }
    }

    $manifestPath = Join-Path $Directory $ManifestName
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    if ([int]$manifest.formatVersion -ne 2 -or [string]$manifest.productVersion -cne '1.0.0' -or
            [string]$manifest.tag -cne 'v1.0.0') {
        throw 'release-manifest.json must be formatVersion 2 for product 1.0.0 and tag v1.0.0.'
    }
    if ([string]$manifest.source.repository -cne $Repository -or
            [string]$manifest.source.commit -cne [string]$Source.commit -or
            [string]$manifest.source.tree -cne [string]$Source.tree -or
            [bool]$manifest.source.testBypass -ne [bool]$Source.testBypass) {
        throw 'Release manifest source repository, commit, tree, or test-bypass state differs from current source identity.'
    }
    $expectedCertificationFields = @('artifactCount', 'evidencePath', 'formatVersion', 'matrixRowCount', 'retainedLogCount', 'sha256')
    $actualCertificationFields = @($manifest.certification.PSObject.Properties.Name | Sort-Object)
    if ((ConvertTo-CompactJson $actualCertificationFields) -cne
            (ConvertTo-CompactJson @($expectedCertificationFields | Sort-Object))) {
        throw 'Release manifest certification binding has missing or unexpected fields.'
    }
    foreach ($field in $expectedCertificationFields) {
        if ([string]$manifest.certification.$field -cne [string]$Certification.binding.$field) {
            throw "Release manifest certification binding differs for $field."
        }
    }
    try { [void][DateTimeOffset]::Parse([string]$manifest.generatedAtUtc, [Globalization.CultureInfo]::InvariantCulture) }
    catch { throw 'Release manifest generatedAtUtc is not a valid timestamp.' }
    if ([string]$manifest.integrity.checksumFile -cne $ChecksumsName -or
            [string]$manifest.integrity.checksumAlgorithm -cne 'SHA-256') {
        throw 'Release manifest integrity scope is missing or unsupported.'
    }

    $manifestRows = @($manifest.artifacts)
    if ($manifestRows.Count -ne 32) {
        throw "Release manifest must contain 32 artifact rows; found $($manifestRows.Count)."
    }
    $inventoryByName = @{}
    foreach ($artifact in @($Inventory.artifacts)) { $inventoryByName.Add([string]$artifact.uploadFilename, $artifact) }
    $seenManifestNames = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($row in $manifestRows) {
        $name = [string]$row.uploadFilename
        Assert-UploadFilename $name
        if (-not $seenManifestNames.Add($name)) { throw "Duplicate manifest artifact: $name" }
        if (-not $inventoryByName.ContainsKey($name)) { throw "Manifest contains unknown artifact: $name" }
        $expected = $inventoryByName[$name]
        foreach ($field in @('sourcePath', 'type', 'platform')) {
            if ([string]$row.$field -cne [string]$expected.$field) {
                throw "Manifest metadata differs for $name field ${field}: expected $($expected.$field), got $($row.$field)."
            }
        }
        foreach ($field in @('minecraftVersions', 'buildJava', 'runtimeJava', 'evidenceRows')) {
            if ((ConvertTo-CompactJson @($row.$field)) -cne (ConvertTo-CompactJson @($expected.$field))) {
                throw "Manifest metadata differs for $name field $field."
            }
        }
        $allowedFields = @('uploadFilename', 'sourcePath', 'bytes', 'sha256', 'type', 'platform',
            'minecraftVersions', 'buildJava', 'runtimeJava', 'evidenceRows')
        if ([string]$expected.type -ceq 'profile') {
            $allowedFields += @('installerJava', 'embeddedArtifactUploadFilename', 'profileContents')
            if ((ConvertTo-CompactJson @($row.installerJava)) -cne
                    (ConvertTo-CompactJson @($expected.installerJava)) -or
                    [string]$row.embeddedArtifactUploadFilename -cne
                    [string]$expected.embeddedArtifactUploadFilename) {
                throw "Manifest profile Java or embedded-artifact binding differs for $name."
            }
            $actualProfileContents = Get-ProfileArchiveDetails $expected $Inventory
            if ((ConvertTo-CompactJson $row.profileContents) -cne
                    (ConvertTo-CompactJson $actualProfileContents)) {
                throw "Manifest nested profile contents differ from source archive: $name"
            }
        } elseif ([string]$expected.type -ceq 'launcher') {
            $allowedFields += 'launcherJava'
            if ((ConvertTo-CompactJson @($row.launcherJava)) -cne
                    (ConvertTo-CompactJson @($expected.launcherJava))) {
                throw "Manifest launcher Java differs for $name."
            }
        }
        $actualFields = @($row.PSObject.Properties.Name | Sort-Object)
        $expectedFields = @($allowedFields | Sort-Object)
        if ((ConvertTo-CompactJson $actualFields) -cne (ConvertTo-CompactJson $expectedFields)) {
            throw "Manifest artifact has missing or unexpected fields: $name"
        }
        if ([string]$row.sha256 -cnotmatch '^[0-9a-f]{64}$' -or [long]$row.bytes -lt 1) {
            throw "Manifest has invalid byte length or SHA-256 for $name."
        }
        $stagedPath = Join-Path $Directory $name
        $staged = Get-Item -LiteralPath $stagedPath
        if ([long]$row.bytes -ne [long]$staged.Length -or
                [string]$row.sha256 -cne (Get-Sha256 $stagedPath)) {
            throw "Staged bytes differ from manifest: $name"
        }
        $sourcePath = Resolve-SourcePath ([string]$expected.sourcePath)
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Manifest source artifact is missing: $($expected.sourcePath)"
        }
        $sourceItem = Get-Item -LiteralPath $sourcePath
        if ([long]$row.bytes -ne [long]$sourceItem.Length -or
                [string]$row.sha256 -cne (Get-Sha256 $sourcePath)) {
            throw "Staged bytes differ from source artifact: $name"
        }
        $certified = $Certification.artifactByName[$name]
        if ([long]$row.bytes -ne [long]$certified.bytes -or
                [string]$row.sha256 -cne [string]$certified.sha256) {
            throw "Staged bytes differ from certification evidence: $name"
        }
    }
    if ($seenManifestNames.Count -ne $inventoryByName.Count) {
        throw 'Release manifest does not contain every inventory artifact exactly once.'
    }

    Assert-SidecarBindings $Directory $manifest $manifestRows
    $expectedChecksums = Get-ChecksumText $Directory
    $actualChecksums = [IO.File]::ReadAllText((Join-Path $Directory $ChecksumsName), [Text.Encoding]::UTF8)
    if ($actualChecksums -cne $expectedChecksums) {
        throw 'SHA256SUMS is not the exact sorted checksum set for distributables, manifest, provenance, and SBOM.'
    }
    Assert-SourceIdentityUnchanged $Source
    $prefix = if ($PreMove) { 'PREMOVE-VERIFIED' } else { 'VERIFIED' }
    Write-Output "$prefix artifacts=32 files=36 commit=$($Source.commit) tree=$($Source.tree)"
}

$inventory = Get-Inventory
$sourceIdentity = Get-SourceIdentity
$certification = Get-ReleaseCertification $inventory $sourceIdentity
if ($Mode -ceq 'Assemble') {
    Invoke-Assemble $inventory $sourceIdentity $certification
} else {
    Invoke-Verify $inventory $sourceIdentity $certification $StagingDirectory
}

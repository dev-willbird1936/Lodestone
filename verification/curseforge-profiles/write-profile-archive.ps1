# SPDX-License-Identifier: MIT
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Profile,
    [Parameter(Mandatory = $true)][string]$Output
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$profilePath = (Resolve-Path -LiteralPath $Profile).Path.TrimEnd(
    [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$rootPrefix = $profilePath + [IO.Path]::DirectorySeparatorChar
$outputPath = [IO.Path]::GetFullPath($Output)
$outputDirectory = Split-Path -Parent $outputPath
if ([string]::IsNullOrWhiteSpace($outputDirectory)) { throw "Archive output needs a parent directory: $Output" }
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

if ($outputPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Archive output must not be inside the source profile.'
}

$reparsePoints = @(Get-ChildItem -LiteralPath $profilePath -Recurse -Force -ErrorAction Stop |
    Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 })
if ($reparsePoints.Count -gt 0) {
    throw "Profile contains links or reparse points: $($reparsePoints.FullName -join ', ')"
}

$files = @(Get-ChildItem -LiteralPath $profilePath -Recurse -File -Force -ErrorAction Stop |
    Sort-Object FullName)
if ($files.Count -eq 0) { throw "Profile contains no files: $profilePath" }

$entries = foreach ($file in $files) {
    if (-not $file.FullName.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Profile file escaped its root: $($file.FullName)"
    }
    $name = $file.FullName.Substring($rootPrefix.Length).Replace(
        [IO.Path]::DirectorySeparatorChar, [char]'/')
    if ($name.Contains('\') -or $name.StartsWith('/') -or $name -match '^[A-Za-z]:' -or
        @($name.Split('/') | Where-Object { $_ -in @('', '.', '..') }).Count -gt 0) {
        throw "Unsafe ZIP entry path: $name"
    }
    [pscustomobject]@{ File = $file; Name = $name }
}

if (@($entries | Where-Object Name -ceq 'manifest.json').Count -ne 1) {
    throw 'CurseForge profile must contain exactly one root manifest.json.'
}

$temporary = Join-Path $outputDirectory ((Split-Path -Leaf $outputPath) + '.tmp-' + [Guid]::NewGuid().ToString('N'))
$backup = Join-Path $outputDirectory ((Split-Path -Leaf $outputPath) + '.bak-' + [Guid]::NewGuid().ToString('N'))
try {
    $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            foreach ($source in $entries) {
                $entry = $archive.CreateEntry($source.Name, [IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = [DateTimeOffset]$source.File.LastWriteTime
                $input = [IO.File]::OpenRead($source.File.FullName)
                $destination = $entry.Open()
                try { $input.CopyTo($destination) } finally { $destination.Dispose(); $input.Dispose() }
            }
        } finally { $archive.Dispose() }
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
    }

    if (Test-Path -LiteralPath $outputPath) {
        [IO.File]::Replace($temporary, $outputPath, $backup)
        Remove-Item -LiteralPath $backup -Force
    } else {
        [IO.File]::Move($temporary, $outputPath)
    }
} finally {
    Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
}

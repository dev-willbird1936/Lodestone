# SPDX-License-Identifier: MIT
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [Parameter(Mandatory = $true)][string]$Output,
    [string]$Minecraft = '1.20.1',
    [string]$FabricLoaderRange = '>=0.15.11'
)
$ErrorActionPreference = 'Stop'

$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$outputPath = [IO.Path]::GetFullPath($Output)
$outputDirectory = Split-Path -Parent $outputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceArchive = [IO.Compression.ZipFile]::OpenRead($jarPath)
$temporaryPath = "$outputPath.tmp"
if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
$archive = [IO.Compression.ZipFile]::Open($temporaryPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $entry = $sourceArchive.GetEntry('fabric.mod.json')
    if ($null -eq $entry) { throw 'Fabric host JAR does not contain fabric.mod.json.' }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($metadata.id -ne 'lodestone' -or $metadata.depends.minecraft -ne $Minecraft) {
        throw "Quilt compatibility variant requires a Fabric $Minecraft Lodestone host JAR."
    }

    $metadata.depends.fabricloader = $FabricLoaderRange

    foreach ($sourceEntry in $sourceArchive.Entries) {
        if ($sourceEntry.FullName -eq 'fabric.mod.json') { continue }
        $destinationEntry = $archive.CreateEntry($sourceEntry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
        $sourceStream = $sourceEntry.Open()
        $destinationStream = $destinationEntry.Open()
        try { $sourceStream.CopyTo($destinationStream) } finally {
            $destinationStream.Dispose()
            $sourceStream.Dispose()
        }
    }

    $replacement = $archive.CreateEntry('fabric.mod.json')
    $writer = [IO.StreamWriter]::new($replacement.Open(), [Text.UTF8Encoding]::new($false))
    try { $writer.Write(($metadata | ConvertTo-Json -Depth 20)) } finally { $writer.Dispose() }
} finally {
    $archive.Dispose()
    $sourceArchive.Dispose()
}
Move-Item -LiteralPath $temporaryPath -Destination $outputPath -Force

Write-Output "Created Quilt compatibility host $outputPath"

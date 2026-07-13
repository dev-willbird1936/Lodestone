# SPDX-License-Identifier: MIT
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$Profile = "$PSScriptRoot\lodestone-neoforge-1.21.1",
    [string]$Output = "$PSScriptRoot\lodestone-neoforge-1.21.1-local.zip"
)

$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$profilePath = (Resolve-Path -LiteralPath $Profile).Path

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $metadataEntry = $archive.GetEntry('META-INF/neoforge.mods.toml')
    if ($null -eq $metadataEntry) { throw 'Jar does not contain META-INF/neoforge.mods.toml.' }
    $reader = [IO.StreamReader]::new($metadataEntry.Open())
    try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($metadata -notmatch '(?m)^modId\s*=\s*"lodestone"' -or
        $metadata -notmatch '(?m)^side\s*=\s*"SERVER"' -or
        $null -eq $archive.GetEntry('dev/lodestone/neoforge/LodestoneNeoForgeMod.class')) {
        throw 'Jar is not a Lodestone NeoForge 1.21.1 host artifact.'
    }
} finally { $archive.Dispose() }

$modsPath = Join-Path $profilePath 'overrides\mods'
New-Item -ItemType Directory -Force -Path $modsPath | Out-Null
Get-ChildItem -LiteralPath $modsPath -Filter '*.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $modsPath (Split-Path $jarPath -Leaf)) -Force
& (Join-Path $PSScriptRoot 'write-profile-archive.ps1') -Profile $profilePath -Output $Output
Write-Output "Created $Output"

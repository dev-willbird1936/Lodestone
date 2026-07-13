# SPDX-License-Identifier: MIT
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$Profile = "$PSScriptRoot\lodestone-forge-1.18.2",
    [string]$Output = "$PSScriptRoot\lodestone-forge-1.18.2-local.zip"
)

$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$profilePath = (Resolve-Path -LiteralPath $Profile).Path

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $metadataEntry = $archive.GetEntry('META-INF/mods.toml')
    if ($null -eq $metadataEntry) { throw 'Jar does not contain META-INF/mods.toml.' }
    $reader = [IO.StreamReader]::new($metadataEntry.Open())
    try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($metadata -notmatch '(?m)^modId\s*=\s*"lodestone"' -or
            $metadata -notmatch '(?m)^side\s*=\s*"SERVER"' -or
            $null -eq $archive.GetEntry('dev/lodestone/forge/LodestoneForgeMod.class')) {
        throw 'Jar is not a Lodestone Forge 1.18.2 host artifact.'
    }
    if ($null -eq $archive.GetEntry('dev/lodestone/forge/ForgeAdapter.class')) {
        throw 'Jar does not contain the Forge adapter.'
    }
} finally { $archive.Dispose() }

$modsPath = Join-Path $profilePath 'overrides\mods'
New-Item -ItemType Directory -Force -Path $modsPath | Out-Null
Get-ChildItem -LiteralPath $modsPath -Filter '*.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $modsPath (Split-Path $jarPath -Leaf)) -Force
& (Join-Path $PSScriptRoot 'write-profile-archive.ps1') -Profile $profilePath -Output $Output
Write-Output "Created $Output"

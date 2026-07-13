# SPDX-License-Identifier: MIT
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$Profile = "$PSScriptRoot\lodestone-forge-1.16.5",
    [string]$Output = "$PSScriptRoot\lodestone-forge-1.16.5-local.zip"
)
$ErrorActionPreference = 'Stop'

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
        $metadata -notmatch '(?m)^versionRange\s*=\s*"\[1\.16\.5,1\.16\.6\)"' -or
        $null -eq $archive.GetEntry('dev/lodestone/forge/LodestoneForgeMod.class') -or
        $null -eq $archive.GetEntry('dev/lodestone/forge/ForgeAdapter.class')) {
        throw 'Jar is not a packaged Lodestone Forge 1.16.5 host artifact.'
    }
} finally { $archive.Dispose() }

$manifestPath = Join-Path $profilePath 'manifest.json'
$manifest = Get-Content -Raw -Encoding utf8 -LiteralPath $manifestPath | ConvertFrom-Json
if ($manifest.minecraft.version -ne '1.16.5' -or $manifest.minecraft.modLoaders[0].id -ne 'forge-36.2.42') {
    throw 'CurseForge profile manifest does not target Forge 1.16.5 / 36.2.42.'
}

$modsPath = Join-Path $profilePath 'overrides\mods'
New-Item -ItemType Directory -Force -Path $modsPath | Out-Null
Get-ChildItem -LiteralPath $modsPath -Filter '*.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $modsPath 'lodestone.jar') -Force
& (Join-Path $PSScriptRoot 'write-profile-archive.ps1') -Profile $profilePath -Output $Output
Write-Output "Created $Output"

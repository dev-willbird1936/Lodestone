# SPDX-License-Identifier: MIT
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$FabricApiJar,
    [string]$GameVersion = '1.20.1',
    [string]$QuiltLoader = '0.29.2',
    [string]$FabricApiVersion = '0.92.2+1.20.1',
    [string]$Profile,
    [string]$Output
)

if ([string]::IsNullOrWhiteSpace($Profile)) { $Profile = Join-Path $PSScriptRoot "lodestone-quilt-$GameVersion" }
if ([string]::IsNullOrWhiteSpace($Output)) { $Output = Join-Path $PSScriptRoot "lodestone-quilt-$GameVersion-local.zip" }

$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$profilePath = (Resolve-Path -LiteralPath $Profile).Path
$manifestPath = Join-Path $profilePath 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "Quilt profile manifest not found: $manifestPath" }
$manifest = Get-Content -Raw -Encoding utf8 -LiteralPath $manifestPath | ConvertFrom-Json
$expectedLoaderId = "quilt-$QuiltLoader"
if ($manifest.minecraft.version -ne $GameVersion -or
    $manifest.minecraft.modLoaders[0].id -ne $expectedLoaderId) {
    throw "Quilt profile manifest does not target Minecraft $GameVersion / Loader $QuiltLoader."
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $metadataEntry = $archive.GetEntry('fabric.mod.json')
    if ($null -eq $metadataEntry) { throw 'Jar does not contain fabric.mod.json.' }
    $reader = [IO.StreamReader]::new($metadataEntry.Open())
    try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($metadata.id -ne 'lodestone' -or $metadata.entrypoints.main[0] -ne 'dev.lodestone.fabric.LodestoneFabricMod') {
        throw 'Jar is not a Lodestone Fabric-compatible host artifact.'
    }
    if ($metadata.depends.minecraft -ne $GameVersion -or
        $null -eq $archive.GetEntry('dev/lodestone/fabric/LodestoneFabricMod.class')) {
        throw "Jar metadata does not target Quilt-compatible Minecraft $GameVersion."
    }
} finally { $archive.Dispose() }

$modsPath = Join-Path $profilePath 'overrides\mods'
New-Item -ItemType Directory -Force -Path $modsPath | Out-Null
Get-ChildItem -LiteralPath $modsPath -Filter '*.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $modsPath 'lodestone-quilt.jar') -Force

if ([string]::IsNullOrWhiteSpace($FabricApiJar)) {
    $cacheRoot = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\$FabricApiVersion"
    $FabricApiJar = (Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter "fabric-api-$FabricApiVersion.jar" -File |
        Select-Object -First 1 -ExpandProperty FullName)
}
if ([string]::IsNullOrWhiteSpace($FabricApiJar) -or -not (Test-Path -LiteralPath $FabricApiJar)) {
    throw "Fabric API $FabricApiVersion jar not found; pass -FabricApiJar explicitly."
}
Copy-Item -LiteralPath (Resolve-Path -LiteralPath $FabricApiJar).Path -Destination (Join-Path $modsPath (Split-Path $FabricApiJar -Leaf)) -Force

& (Join-Path $PSScriptRoot 'write-profile-archive.ps1') -Profile $profilePath -Output $Output
Write-Output "Created $Output"

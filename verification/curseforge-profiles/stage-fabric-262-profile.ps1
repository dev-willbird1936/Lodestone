# SPDX-License-Identifier: MIT
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$FabricApiJar,
    [string]$Profile = "$PSScriptRoot\lodestone-fabric-26.2",
    [string]$Output = "$PSScriptRoot\lodestone-fabric-26.2-local.zip"
)

$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$profilePath = (Resolve-Path -LiteralPath $Profile).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $metadataEntry = $archive.GetEntry('fabric.mod.json')
    if ($null -eq $metadataEntry) { throw 'Jar does not contain fabric.mod.json.' }
    $reader = [IO.StreamReader]::new($metadataEntry.Open())
    try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($metadata.id -ne 'lodestone' -or $metadata.entrypoints.main[0] -ne 'dev.lodestone.fabric.LodestoneFabricMod') {
        throw 'Jar is not a Lodestone Fabric host artifact.'
    }
    if ($metadata.depends.minecraft -ne '26.2' -or $metadata.depends.fabricloader -ne '0.19.3') {
        throw 'Jar metadata does not target Fabric 26.2 / Loader 0.19.3.'
    }
    if ($null -eq $archive.GetEntry('dev/lodestone/fabric/LodestoneFabricMod.class')) {
        throw 'Jar is missing the Fabric host entrypoint class.'
    }
} finally { $archive.Dispose() }

$modsPath = Join-Path $profilePath 'overrides\mods'
New-Item -ItemType Directory -Force -Path $modsPath | Out-Null
Get-ChildItem -LiteralPath $modsPath -Filter '*.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $modsPath (Split-Path $jarPath -Leaf)) -Force

if ([string]::IsNullOrWhiteSpace($FabricApiJar)) {
    $cacheRoot = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\0.154.2+26.2'
    $FabricApiJar = Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter 'fabric-api-0.154.2+26.2.jar' -File | Select-Object -First 1 -ExpandProperty FullName
}
if ([string]::IsNullOrWhiteSpace($FabricApiJar) -or -not (Test-Path -LiteralPath $FabricApiJar)) {
    throw 'Fabric API 0.154.2+26.2 jar not found; pass -FabricApiJar explicitly.'
}
Copy-Item -LiteralPath (Resolve-Path -LiteralPath $FabricApiJar).Path -Destination (Join-Path $modsPath (Split-Path $FabricApiJar -Leaf)) -Force

& (Join-Path $PSScriptRoot 'write-profile-archive.ps1') -Profile $profilePath -Output $Output
Write-Output "Created $Output"

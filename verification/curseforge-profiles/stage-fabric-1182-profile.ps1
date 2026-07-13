# SPDX-License-Identifier: MIT
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$FabricApiJar,
    [string]$Profile = "$PSScriptRoot\lodestone-fabric-1.18.2",
    [string]$Output = "$PSScriptRoot\lodestone-fabric-1.18.2-local.zip"
)

$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$profilePath = (Resolve-Path -LiteralPath $Profile).Path
$fabricApiVersion = '0.77.0+1.18.2'

function Resolve-FabricApiJars([string]$version) {
    $cacheRoot = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api'
    $queue = [Collections.Generic.Queue[object]]::new()
    $queue.Enqueue([pscustomobject]@{ Artifact = 'fabric-api'; Version = $version })
    $seen = @{}
    $jars = [Collections.Generic.List[string]]::new()
    while ($queue.Count -gt 0) {
        $dependency = $queue.Dequeue()
        $key = "$($dependency.Artifact):$($dependency.Version)"
        if ($seen.ContainsKey($key)) { continue }
        $seen[$key] = $true
        $artifactRoot = Join-Path $cacheRoot (Join-Path $dependency.Artifact $dependency.Version)
        $jar = Get-ChildItem -LiteralPath $artifactRoot -Recurse -Filter "$($dependency.Artifact)-$($dependency.Version).jar" -File -ErrorAction SilentlyContinue |
            Select-Object -First 1
        $pom = Get-ChildItem -LiteralPath $artifactRoot -Recurse -Filter "$($dependency.Artifact)-$($dependency.Version).pom" -File -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -eq $jar) { throw "Fabric API module not found in Gradle cache: $key" }
        $jars.Add($jar.FullName)
        if ($null -ne $pom) {
            $xml = [xml](Get-Content -Raw -LiteralPath $pom.FullName)
            foreach ($child in @($xml.project.dependencies.dependency)) {
                if ($child.groupId -eq 'net.fabricmc.fabric-api' -and $child.scope -ne 'test') {
                    $queue.Enqueue([pscustomobject]@{ Artifact = [string]$child.artifactId; Version = [string]$child.version })
                }
            }
        }
    }
    return $jars
}

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
    if ($metadata.depends.minecraft -ne '1.18.2' -or $metadata.depends.fabricloader -ne '0.14.25') {
        throw 'Jar metadata does not target Fabric 1.18.2 / Loader 0.14.25.'
    }
    if ($null -eq $archive.GetEntry('dev/lodestone/fabric/LodestoneFabricMod.class')) {
        throw 'Jar is missing the Fabric host entrypoint class.'
    }
} finally { $archive.Dispose() }

$modsPath = Join-Path $profilePath 'overrides\mods'
New-Item -ItemType Directory -Force -Path $modsPath | Out-Null
Get-ChildItem -LiteralPath $modsPath -Filter '*.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $modsPath (Split-Path $jarPath -Leaf)) -Force

$fabricApiJars = @(Resolve-FabricApiJars $fabricApiVersion)
if (-not [string]::IsNullOrWhiteSpace($FabricApiJar)) {
    if (-not (Test-Path -LiteralPath $FabricApiJar)) { throw "Fabric API JAR not found: $FabricApiJar" }
    $fabricApiJars = @($fabricApiJars | Where-Object { $_ -notlike '*\fabric-api-0.77.0+1.18.2.jar' }) + (Resolve-Path -LiteralPath $FabricApiJar).Path
}
foreach ($apiJar in $fabricApiJars) {
    Copy-Item -LiteralPath $apiJar -Destination (Join-Path $modsPath (Split-Path $apiJar -Leaf)) -Force
}

& (Join-Path $PSScriptRoot 'write-profile-archive.ps1') -Profile $profilePath -Output $Output
Write-Output "Created $Output"

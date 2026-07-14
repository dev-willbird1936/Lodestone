# SPDX-License-Identifier: MIT
<#
    Rebuilds every Lodestone v1.0.0 distributable using the exact historical
    Gradle generations required by its target. It deliberately does not launch
    game servers: fresh-world certification is separately retained in
    verification/evidence/release-conformance-v1.0.0.json.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Java8,
    [Parameter(Mandatory = $true)][string]$Java17,
    [Parameter(Mandatory = $true)][string]$Java21,
    [Parameter(Mandatory = $true)][string]$Java25,
    [Parameter(Mandatory = $true)][string]$Gradle2,
    [Parameter(Mandatory = $true)][string]$Gradle4,
    [Parameter(Mandatory = $true)][string]$Gradle7,
    [Parameter(Mandatory = $true)][string]$Gradle9,
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)

function Assert([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Resolve-Executable([string]$Path, [string]$Label) {
    $resolved = [IO.Path]::GetFullPath($Path)
    Assert (Test-Path -LiteralPath $resolved -PathType Leaf) "$Label is missing: $resolved"
    return $resolved
}

function Get-JavaHome([string]$Java) {
    return Split-Path -Parent (Split-Path -Parent $Java)
}

function Invoke-Gradle([string]$Label, [string]$Java, [string]$Gradle, [string[]]$Arguments) {
    $previousJavaHome = $env:JAVA_HOME
    try {
        $env:JAVA_HOME = Get-JavaHome $Java
        Write-Output "BEGIN $Label"
        & $Gradle @Arguments
        if ($LASTEXITCODE -ne 0) { throw "$Label failed with exit code $LASTEXITCODE." }
        Write-Output "PASS $Label"
    } finally {
        $env:JAVA_HOME = $previousJavaHome
    }
}

function Stage-Profiles {
    $profiles = Join-Path $ProjectRoot 'verification/curseforge-profiles'
    & "$profiles/stage-fabric-1182-profile.ps1" -Jar "$ProjectRoot/hosts/fabric/1.18.2/build/libs/lodestone-1.0.0.jar"
    & "$profiles/stage-fabric-1192-profile.ps1" -Jar "$ProjectRoot/hosts/fabric/1.19.2/build/libs/lodestone-1.0.0.jar"
    & "$profiles/stage-fabric-1201-profile.ps1" -Jar "$ProjectRoot/hosts/fabric/1.20.1/build/libs/lodestone-1.0.0.jar"
    & "$profiles/stage-fabric-profile.ps1" -Jar "$ProjectRoot/hosts/fabric/1.21.1/build/libs/lodestone-1.0.0.jar"
    & "$profiles/stage-fabric-262-profile.ps1" -Jar "$ProjectRoot/hosts/fabric/26.2/build/libs/lodestone-1.0.0.jar"
    & "$profiles/stage-forge-165-profile.ps1" -Jar "$ProjectRoot/hosts/forge/1.16.5/build/reobfJar/output.jar"
    & "$profiles/stage-forge-1182-profile.ps1" -Jar "$ProjectRoot/hosts/forge/1.18.2/build/reobfJar/output.jar"
    & "$profiles/stage-forge-1192-profile.ps1" -Jar "$ProjectRoot/hosts/forge/1.19.2/build/reobfJar/output.jar"
    & "$profiles/stage-forge-profile.ps1" -Jar "$ProjectRoot/hosts/forge/1.20.1/build/reobfJar/output.jar"
    & "$profiles/stage-forge-1211-profile.ps1" -Jar "$ProjectRoot/hosts/forge/1.21.1/build/libs/lodestone-1.0.0.jar"
    & "$profiles/stage-neoforge-profile.ps1" -Jar "$ProjectRoot/hosts/neoforge/1.21.1/build/libs/lodestone-1.0.0.jar"
    & "$profiles/stage-quilt-profile.ps1" -Jar "$ProjectRoot/hosts/fabric/1.20.1/build/libs/lodestone-1.0.0.jar" -GameVersion '1.20.1' -FabricApiVersion '0.92.2+1.20.1'
    & "$profiles/stage-quilt-profile.ps1" -Jar "$ProjectRoot/hosts/fabric/1.21.1/build/libs/lodestone-1.0.0.jar" -GameVersion '1.21.1' -FabricApiVersion '0.116.9+1.21.1'
}

$Java8 = Resolve-Executable $Java8 'Java 8'
$Java17 = Resolve-Executable $Java17 'Java 17'
$Java21 = Resolve-Executable $Java21 'Java 21'
$Java25 = Resolve-Executable $Java25 'Java 25'
$Gradle2 = Resolve-Executable $Gradle2 'Gradle 2.7'
$Gradle4 = Resolve-Executable $Gradle4 'Gradle 4.10.3'
$Gradle7 = Resolve-Executable $Gradle7 'Gradle 7.6.4'
$Gradle9 = Resolve-Executable $Gradle9 'Gradle 9.5.1'
$Gradle8 = Resolve-Executable (Join-Path $ProjectRoot 'gradlew.bat') 'Pinned Gradle wrapper'

Invoke-Gradle 'Forge 1.8.9 host (Java 8 / Gradle 2.7)' $Java8 $Gradle2 @('-p', "$ProjectRoot/hosts/forge/1.8.9", 'build', '--no-daemon')
Invoke-Gradle 'Forge 1.12.2 host (Java 8 / Gradle 4.10.3)' $Java8 $Gradle4 @('-p', "$ProjectRoot/hosts/forge/1.12.2", 'build', '--no-daemon')
Invoke-Gradle 'Forge 1.7.10 host (Java 8 / Gradle 4.10.3)' $Java8 $Gradle4 @('-p', "$ProjectRoot/hosts/forge/1.7.10", 'build', '--no-daemon')
Invoke-Gradle 'Forge 1.16.5 host (Java 17 / Gradle 7.6.4)' $Java17 $Gradle7 @(
    '-p', $ProjectRoot, ':hosts:forge:mc1_16_5:build', '-PincludeForge165=true', '-PincludeModern=false',
    '-PincludeForge=false', '-PincludeForge121=false', '-PincludeForge192=false', '-PincludeForge182=false', '--no-daemon'
)

$modernTasks = @(
    ':hosts:fabric:mc1_18_2:build', ':hosts:fabric:mc1_19_2:build', ':hosts:fabric:mc1_20_1:build', ':hosts:fabric:mc1_21_1:build',
    ':hosts:neoforge:mc1_21_1:build',
    ':hosts:forge:mc1_18_2:build', ':hosts:forge:mc1_19_2:build', ':hosts:forge:mc1_20_1:build', ':hosts:forge:mc1_21_1:build',
    ':hosts:paper:mc1_21_1:build', ':hosts:spigot:mc1_21_1:build', ':hosts:folia:mc1_21_4:build',
    ':gateway:legacy-bridge-launcher:distZip', ':gateway:rcon-launcher:distZip', '--no-daemon'
)
Invoke-Gradle 'Modern hosts, plugins, and launchers (Java 21 / pinned Gradle wrapper)' $Java21 $Gradle8 $modernTasks
Invoke-Gradle 'Fabric 26.2 host (Java 25 / Gradle 9.5.1)' $Java25 $Gradle9 @(
    '-p', $ProjectRoot, ':hosts:fabric:mc1_26_2:build', '-PincludeFabric262=true',
    '-PincludeForge=false', '-PincludeForge121=false', '-PincludeForge192=false', '-PincludeForge182=false', '--no-daemon'
)

Stage-Profiles
$profilesRoot = Join-Path $ProjectRoot 'verification/curseforge-profiles'
$firstHashes = @{}
Get-ChildItem -LiteralPath $profilesRoot -Filter '*-local.zip' -File | ForEach-Object {
    $firstHashes[$_.Name] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
Assert ($firstHashes.Count -eq 13) "Expected 13 CurseForge profiles, found $($firstHashes.Count)."
Stage-Profiles
Get-ChildItem -LiteralPath $profilesRoot -Filter '*-local.zip' -File | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert ($firstHashes[$_.Name] -ceq $hash) "CurseForge profile is non-deterministic: $($_.Name)"
    Write-Output "PASS deterministic CurseForge profile $($_.Name) $hash"
}
Write-Output 'PASS Lodestone v1.0.0 release artifact rebuild.'

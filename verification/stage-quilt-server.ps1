# SPDX-License-Identifier: MIT
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Minecraft,
    [Parameter(Mandatory = $true)][string]$QuiltLoader,
    [Parameter(Mandatory = $true)][string]$HostJar,
    [Parameter(Mandatory = $true)][string]$FabricApiJar,
    [string]$Root = (Join-Path $env:TEMP "lodestone-matrix\quilt-$Minecraft"),
    [int]$ServerPort = 25572,
    [string]$Java = 'java',
    [string]$InstallerVersion = '0.15.0'
)
$ErrorActionPreference = 'Stop'

function Assert([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

$hostPath = (Resolve-Path -LiteralPath $HostJar).Path
$apiPath = (Resolve-Path -LiteralPath $FabricApiJar).Path
$rootPath = [IO.Path]::GetFullPath($Root)
$modsPath = Join-Path $rootPath 'mods'
New-Item -ItemType Directory -Force -Path $modsPath | Out-Null

$installerPath = Join-Path $env:TEMP "lodestone-toolchains\quilt-installer-$InstallerVersion.jar"
if (-not (Test-Path -LiteralPath $installerPath)) {
    $installerUrl = "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/$InstallerVersion/quilt-installer-$InstallerVersion.jar"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $installerPath) | Out-Null
    Invoke-WebRequest -UseBasicParsing -Uri $installerUrl -OutFile $installerPath
}
Assert (Test-Path -LiteralPath $installerPath) "Quilt Installer $InstallerVersion was not found."

$launchJar = Join-Path $rootPath 'quilt-server-launch.jar'
if (-not (Test-Path -LiteralPath $launchJar)) {
    New-Item -ItemType Directory -Force -Path $rootPath | Out-Null
    & $Java -jar $installerPath install server $Minecraft $QuiltLoader "--install-dir=$rootPath" --download-server
    if ($LASTEXITCODE -ne 0) { throw "Quilt Installer failed with exit code $LASTEXITCODE." }
}
Assert (Test-Path -LiteralPath $launchJar) "Quilt server launcher was not installed at $launchJar."

Get-ChildItem -LiteralPath $modsPath -Filter 'lodestone*.jar' -File -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $hostPath -Destination (Join-Path $modsPath 'lodestone.jar') -Force
Copy-Item -LiteralPath $apiPath -Destination (Join-Path $modsPath (Split-Path $apiPath -Leaf)) -Force
Set-Content -LiteralPath (Join-Path $rootPath 'eula.txt') -Value 'eula=true' -Encoding ascii
@(
    "server-port=$ServerPort"
    'online-mode=false'
    'enable-query=false'
    'level-name=world'
) | Set-Content -LiteralPath (Join-Path $rootPath 'server.properties') -Encoding ascii

Write-Output "Staged Quilt $Minecraft / Loader $QuiltLoader server at $rootPath"

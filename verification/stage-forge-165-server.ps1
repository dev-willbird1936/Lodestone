# SPDX-License-Identifier: MIT
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$HostJar,
    [string]$Root = (Join-Path $env:TEMP 'lodestone-matrix\forge-1.16.5'),
    [string]$InstallerJava = (Join-Path $env:TEMP 'lodestone-toolchains\jdk8\jdk8u492-b09\bin\java.exe'),
    [int]$ServerPort = 25573
)
$ErrorActionPreference = 'Stop'

function Assert([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

$hostPath = (Resolve-Path -LiteralPath $HostJar).Path
$rootPath = [IO.Path]::GetFullPath($Root)
$modsPath = Join-Path $rootPath 'mods'
$forgeVersion = '1.16.5-36.2.42'
$installerName = "forge-$forgeVersion-installer.jar"
$installerPath = Join-Path $rootPath $installerName
$forgeJar = Join-Path $rootPath "forge-$forgeVersion.jar"
New-Item -ItemType Directory -Force -Path $rootPath, $modsPath | Out-Null
Assert (Test-Path -LiteralPath $InstallerJava) "Java 8 installer runtime not found: $InstallerJava"

if (-not (Test-Path -LiteralPath $installerPath)) {
    $installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/$forgeVersion/$installerName"
    Invoke-WebRequest -UseBasicParsing -Uri $installerUrl -OutFile $installerPath
}
if (-not (Test-Path -LiteralPath $forgeJar)) {
    Push-Location $rootPath
    try {
        & $InstallerJava -jar $installerPath --installServer
        if ($LASTEXITCODE -ne 0) { throw "Forge installer failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
}
Assert (Test-Path -LiteralPath $forgeJar) "Forge server launcher was not installed at $forgeJar."

Get-ChildItem -LiteralPath $modsPath -Filter 'lodestone*.jar' -File -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $hostPath -Destination (Join-Path $modsPath 'lodestone.jar') -Force
Set-Content -LiteralPath (Join-Path $rootPath 'eula.txt') -Value 'eula=true' -Encoding ascii
@(
    "server-port=$ServerPort"
    'online-mode=false'
    'enable-query=false'
    'level-name=world'
) | Set-Content -LiteralPath (Join-Path $rootPath 'server.properties') -Encoding ascii

Write-Output "Staged Forge 1.16.5 / 36.2.42 server at $rootPath"

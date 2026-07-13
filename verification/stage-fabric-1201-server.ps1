# SPDX-License-Identifier: MIT
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$FabricApiJar,
    [string]$Root = (Join-Path $env:TEMP 'lodestone-matrix\fabric-1.20.1'),
    [int]$ServerPort = 25560
)

$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$rootPath = [IO.Path]::GetFullPath($Root)
$launcherPath = Join-Path $rootPath 'fabric-server-launch.jar'
$modsPath = Join-Path $rootPath 'mods'

New-Item -ItemType Directory -Force -Path $rootPath, $modsPath | Out-Null
if (-not (Test-Path -LiteralPath $launcherPath)) {
    Invoke-WebRequest -UseBasicParsing `
        -Uri 'https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.15.11/1.0.1/server/jar' `
        -OutFile $launcherPath
}
if ([string]::IsNullOrWhiteSpace($FabricApiJar)) {
    $cacheRoot = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\0.92.2+1.20.1'
    $FabricApiJar = Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter 'fabric-api-0.92.2+1.20.1.jar' -File |
        Select-Object -First 1 -ExpandProperty FullName
}
if ([string]::IsNullOrWhiteSpace($FabricApiJar) -or -not (Test-Path -LiteralPath $FabricApiJar)) {
    throw 'Fabric API 0.92.2+1.20.1 jar not found; pass -FabricApiJar explicitly.'
}
Get-ChildItem -LiteralPath $modsPath -Filter '*.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $modsPath 'lodestone.jar') -Force
Copy-Item -LiteralPath (Resolve-Path -LiteralPath $FabricApiJar).Path -Destination (Join-Path $modsPath 'fabric-api.jar') -Force

Set-Content -LiteralPath (Join-Path $rootPath 'eula.txt') -Value 'eula=true' -Encoding ascii
@(
    "server-port=$ServerPort"
    'online-mode=false'
    'enable-query=false'
    'level-name=world'
    'motd=Lodestone Fabric 1.20.1 acceptance server'
) | Set-Content -LiteralPath (Join-Path $rootPath 'server.properties') -Encoding ascii
Write-Output "Staged Fabric 1.20.1 server at $rootPath"

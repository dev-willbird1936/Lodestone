# SPDX-License-Identifier: MIT
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$Root = (Join-Path $env:TEMP 'lodestone-matrix\forge-1.18.2'),
    [string]$Java = (Join-Path $env:TEMP 'lodestone-toolchains\jdk17\jdk-17.0.19+10\bin\java.exe'),
    [int]$ServerPort = 25568
)

$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$rootPath = [IO.Path]::GetFullPath($Root)
$installerPath = Join-Path $rootPath 'forge-installer.jar'
$forgeVersion = '1.18.2-40.3.12'
$forgeRoot = Join-Path $rootPath 'libraries\net\minecraftforge\forge\1.18.2-40.3.12'
$modsPath = Join-Path $rootPath 'mods'

if (-not (Test-Path -LiteralPath $Java)) { throw "Java executable not found: $Java" }
New-Item -ItemType Directory -Force -Path $rootPath, $modsPath | Out-Null
if (-not (Test-Path -LiteralPath $installerPath)) {
    Invoke-WebRequest -UseBasicParsing `
        -Uri "https://maven.minecraftforge.net/net/minecraftforge/forge/$forgeVersion/forge-$forgeVersion-installer.jar" `
        -OutFile $installerPath
}
if (-not (Test-Path -LiteralPath (Join-Path $forgeRoot 'win_args.txt'))) {
    Push-Location $rootPath
    try { & $Java -jar $installerPath --installServer } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "Forge installer failed with exit code $LASTEXITCODE." }
}
Get-ChildItem -LiteralPath $modsPath -Filter '*.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $modsPath 'lodestone.jar') -Force

Set-Content -LiteralPath (Join-Path $rootPath 'eula.txt') -Value 'eula=true' -Encoding ascii
@(
    "server-port=$ServerPort"
    'online-mode=false'
    'enable-query=false'
    'level-name=world'
    'motd=Lodestone Forge 1.18.2 acceptance server'
) | Set-Content -LiteralPath (Join-Path $rootPath 'server.properties') -Encoding ascii
Write-Output "Staged Forge 1.18.2 server at $rootPath"

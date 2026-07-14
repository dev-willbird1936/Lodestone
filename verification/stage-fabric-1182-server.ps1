# SPDX-License-Identifier: MIT
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$FabricApiJar,
    [string]$Root = (Join-Path $env:TEMP 'lodestone-matrix\fabric-1.18.2'),
    [int]$ServerPort = 25569
)

$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$rootPath = [IO.Path]::GetFullPath($Root)
$fabricApiVersion = '0.77.0+1.18.2'
$launcherPath = Join-Path $rootPath 'fabric-server-launch.jar'
$modsPath = Join-Path $rootPath 'mods'

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
            # POMs may either declare no dependencies or use an XML namespace.
            # local-name() handles both without strict-mode property lookups.
            foreach ($child in @($xml.SelectNodes("/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency']"))) {
                $groupId = $child.SelectSingleNode("*[local-name()='groupId']")
                $artifactId = $child.SelectSingleNode("*[local-name()='artifactId']")
                $versionNode = $child.SelectSingleNode("*[local-name()='version']")
                $scope = $child.SelectSingleNode("*[local-name()='scope']")
                $scopeValue = if ($null -eq $scope) { '' } else { [string]$scope.InnerText }
                if ($null -ne $groupId -and $null -ne $artifactId -and $null -ne $versionNode `
                        -and $groupId.InnerText -eq 'net.fabricmc.fabric-api' -and $scopeValue -ne 'test') {
                    $queue.Enqueue([pscustomobject]@{ Artifact = [string]$artifactId.InnerText; Version = [string]$versionNode.InnerText })
                }
            }
        }
    }
    return $jars
}

New-Item -ItemType Directory -Force -Path $rootPath,$modsPath | Out-Null
if (-not (Test-Path -LiteralPath $launcherPath)) {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://meta.fabricmc.net/v2/versions/loader/1.18.2/0.14.25/1.0.1/server/jar' -OutFile $launcherPath
}
$fabricApiJars = @(Resolve-FabricApiJars $fabricApiVersion)
if (-not [string]::IsNullOrWhiteSpace($FabricApiJar)) {
    if (-not (Test-Path -LiteralPath $FabricApiJar)) { throw "Fabric API JAR not found: $FabricApiJar" }
    $fabricApiJars = @($fabricApiJars | Where-Object { $_ -notlike '*\fabric-api-0.77.0+1.18.2.jar' }) + (Resolve-Path -LiteralPath $FabricApiJar).Path
}
Get-ChildItem -LiteralPath $modsPath -Filter '*.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $modsPath (Split-Path $jarPath -Leaf)) -Force
foreach ($apiJar in $fabricApiJars) {
    Copy-Item -LiteralPath $apiJar -Destination (Join-Path $modsPath (Split-Path $apiJar -Leaf)) -Force
}

Set-Content -LiteralPath (Join-Path $rootPath 'eula.txt') -Value 'eula=true' -Encoding ascii
@(
    "server-port=$ServerPort"
    'online-mode=false'
    'enable-query=false'
    'level-name=world'
    'motd=Lodestone Fabric 1.18.2 acceptance server'
) | Set-Content -LiteralPath (Join-Path $rootPath 'server.properties') -Encoding ascii
Write-Output "Staged Fabric 1.18.2 server at $rootPath"

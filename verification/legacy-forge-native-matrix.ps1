# SPDX-License-Identifier: MIT
<##
    Boots a legacy Forge server with the Java 8-native Lodestone bridge and drives it
    through the Java 21 Lodestone MCP gateway adapter.
##>
[CmdletBinding()]
param(
    [string]$Root = (Join-Path $env:TEMP 'lodestone-legacy-native-matrix'),
    [string]$Java8 = (Join-Path $env:TEMP 'lodestone-toolchains\jdk8\jdk8u492-b09\bin\java.exe'),
    [string]$LauncherJava = (Join-Path $env:USERPROFILE 'scoop\apps\temurin21-jdk\current\bin\java.exe'),
    [string]$LauncherDist = (Join-Path (Split-Path -Parent $PSScriptRoot) 'gateway/legacy-bridge-launcher/build/install/legacy-bridge-launcher'),
    [string]$Minecraft = '1.12.2',
    [string]$Forge = '14.23.5.2859',
    [string]$InstallerName = '',
    [int]$ServerPort = 25569,
    [int]$BridgePort = 37940,
    [int]$McpPort = 37941,
    [string]$BridgeToken = 'legacy-native-1122-bridge-token',
    [string]$GatewayToken = 'legacy-native-1122-gateway-token'
)
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$serverDirectory = Join-Path $Root ('forge-' + $Minecraft)
$installer = if ([string]::IsNullOrWhiteSpace($InstallerName)) { 'forge-' + $Minecraft + '-' + $Forge + '-installer.jar' } else { $InstallerName }
$installerUrl = 'https://maven.minecraftforge.net/net/minecraftforge/forge/' + $Minecraft + '-' + $Forge + '/' + $installer
if ($Minecraft -in @('1.7.10', '1.8.9') -and $Forge -notmatch ('-' + [regex]::Escape($Minecraft) + '$')) {
    throw "Forge $Minecraft requires the full Forge artifact version in -Forge, including '-$Minecraft' (for example 10.13.4.1614-$Minecraft)."
}

function Assert([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }
function Read-Log([string]$directory) {
    $parts = @()
    foreach ($relative in @('logs/latest.log', 'native.stdout.log')) {
        $path = Join-Path $directory $relative
        if (Test-Path -LiteralPath $path) { $parts += Get-Content -Raw -LiteralPath $path }
    }
    return ($parts -join "`n")
}
function Assert-CleanShutdown([string]$directory) {
    $crashDirectory = Join-Path $directory 'crash-reports'
    $crashes = if (Test-Path -LiteralPath $crashDirectory) { @(Get-ChildItem -LiteralPath $crashDirectory -File -ErrorAction SilentlyContinue) } else { @() }
    Assert ($crashes.Count -eq 0) "Forge $Minecraft produced crash reports: $($crashes.Name -join ', ')"
    $text = Read-Log $directory
    Assert ($text -notmatch '(?im)Encountered an unexpected exception|Exception in server tick loop|Crash report saved|Could not bind') "Forge $Minecraft logged a fatal shutdown error."
    Assert ($text -match '(?im)Stopping (the )?server') "Forge $Minecraft did not log a normal server stop."
}
function Invoke-Mcp([string]$uri, [string]$token, [object]$request) {
    $headers = @{ 'X-Lodestone-Token' = $token; 'MCP-Protocol-Version' = '2025-11-25' }
    if ($request.method -ne 'initialize' -and -not [string]::IsNullOrWhiteSpace($script:SessionId)) { $headers['Mcp-Session-Id'] = $script:SessionId }
    $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -Method Post -Headers $headers -ContentType 'application/json' -Body ($request | ConvertTo-Json -Depth 30)
    $assigned = $response.Headers['Mcp-Session-Id']; if (-not [string]::IsNullOrWhiteSpace($assigned)) { $script:SessionId = $assigned }
    return $response.Content | ConvertFrom-Json
}
function Invoke-Capability([string]$uri, [string]$token, [int]$id, [string]$capability, [hashtable]$payload) {
    return Invoke-Mcp $uri $token ([ordered]@{ jsonrpc = '2.0'; id = $id; method = 'tools/call'; params = @{ name = 'lodestone_capability_invoke'; arguments = @{ capability = $capability; input = $payload } } })
}

Assert (Test-Path -LiteralPath $Java8) "Java 8 executable not found: $Java8"
Assert (Test-Path -LiteralPath $LauncherJava) "Launcher Java executable not found: $LauncherJava"
$hostJar = Join-Path $projectRoot ('hosts/forge/' + $Minecraft + '/build/reobfJar/output.jar')
if (-not (Test-Path -LiteralPath $hostJar)) {
    $hostJar = Join-Path $projectRoot ('hosts/forge/' + $Minecraft + '/build/libs/lodestone-' + '1.0.0.jar')
}
Assert (Test-Path -LiteralPath $hostJar) "Missing native Forge $Minecraft host artifact: $hostJar"

$server = $null; $launcher = $null; $oldEnvironment = @{}
try {
    New-Item -ItemType Directory -Force -Path $Root, $serverDirectory, (Join-Path $serverDirectory 'mods') | Out-Null
    $installerPath = Join-Path $Root $installer
    if (-not (Test-Path -LiteralPath $installerPath)) { Invoke-WebRequest -UseBasicParsing -Uri $installerUrl -OutFile $installerPath }
    $universal = Get-ChildItem -LiteralPath $serverDirectory -Filter '*universal*.jar' -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $universal) {
        Push-Location $serverDirectory
        try { & $Java8 -jar $installerPath --installServer | Out-Null; $installerExitCode = $LASTEXITCODE } finally { Pop-Location }
        Assert ($installerExitCode -eq 0) "Forge $Minecraft installer failed."
        $universal = Get-ChildItem -LiteralPath $serverDirectory -Filter '*universal*.jar' -File -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if ($null -eq $universal) {
        $universal = Get-ChildItem -LiteralPath $serverDirectory -Filter '*forge*.jar' -File -ErrorAction SilentlyContinue |
            Where-Object Name -notmatch 'installer' | Sort-Object Length -Descending | Select-Object -First 1
    }
    Assert ($null -ne $universal) "Forge $Minecraft universal server JAR was not installed."

    Remove-Item -LiteralPath (Join-Path $serverDirectory 'world'), (Join-Path $serverDirectory 'logs'), (Join-Path $serverDirectory 'crash-reports'), (Join-Path $serverDirectory 'server.properties') -Recurse -Force -ErrorAction SilentlyContinue
    Get-ChildItem -LiteralPath (Join-Path $serverDirectory 'mods') -File -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
    Copy-Item -LiteralPath $hostJar -Destination (Join-Path $serverDirectory 'mods/Lodestone.jar') -Force
    Set-Content -LiteralPath (Join-Path $serverDirectory 'eula.txt') -Value 'eula=true' -Encoding ascii
    @("server-port=$ServerPort", 'online-mode=false', 'enable-rcon=false', 'enable-query=false', 'broadcast-rcon-to-ops=false') | Set-Content -LiteralPath (Join-Path $serverDirectory 'server.properties') -Encoding ascii
    Assert (@(Get-Content -LiteralPath (Join-Path $serverDirectory 'server.properties') | Where-Object { $_ -eq "server-port=$ServerPort" }).Count -eq 1) "Forge $Minecraft matrix wrote the wrong server port."

    $serverOut = Join-Path $serverDirectory 'native.stdout.log'; $serverErr = Join-Path $serverDirectory 'native.stderr.log'
    Remove-Item -LiteralPath $serverOut, $serverErr -Force -ErrorAction SilentlyContinue
    $server = Start-Process -FilePath $Java8 -ArgumentList @('-Xmx1G',('-Dlodestone.legacy.port=' + $BridgePort),('-Dlodestone.legacy.token=' + $BridgeToken),'-Dfml.queryResult=confirm','-jar',$universal.Name,'nogui','--port',[string]$ServerPort) -WorkingDirectory $serverDirectory -WindowStyle Hidden -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr -PassThru
    $deadline = (Get-Date).AddMinutes(4); $log = ''
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2; $log = Read-Log $serverDirectory
        if ($log -match 'Done \(') { break }
        $tail = if ([string]::IsNullOrEmpty($log)) { '<no latest.log output yet>' } elseif ($log.Length -gt 3000) { $log.Substring($log.Length - 3000) } else { $log }
        Assert ($null -ne (Get-Process -Id $server.Id -ErrorAction SilentlyContinue)) "Forge $Minecraft exited before Done. Tail: $tail"
    }
    Assert ($log -match 'Done \(') "Forge $Minecraft did not reach Done."
    Assert ((Test-Path -LiteralPath (Join-Path $serverDirectory 'world/level.dat')) -or
        (Test-Path -LiteralPath (Join-Path $serverDirectory 'world/region'))) "Forge $Minecraft did not create a world."
    Assert ($log -match 'Legacy .*MCP bridge listening') 'Lodestone native bridge did not report listening.'

    foreach ($name in @('LODESTONE_LEGACY_TOKEN','LODESTONE_TOKEN','LODESTONE_LEGACY_HOST','LODESTONE_LEGACY_VERSION','LODESTONE_LEGACY_PORT','LODESTONE_MCP_PORT','LODESTONE_PERMISSIONS')) { $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    $env:LODESTONE_LEGACY_TOKEN = $BridgeToken; $env:LODESTONE_TOKEN = $GatewayToken; $env:LODESTONE_LEGACY_HOST = '127.0.0.1'; $env:LODESTONE_LEGACY_VERSION = $Minecraft; $env:LODESTONE_LEGACY_PORT = [string]$BridgePort; $env:LODESTONE_MCP_PORT = [string]$McpPort; $env:LODESTONE_PERMISSIONS = 'observe,modify-world,communicate,administer-server'
    $dist = [IO.Path]::GetFullPath($LauncherDist); Assert (Test-Path -LiteralPath (Join-Path $dist 'lib')) "Legacy bridge launcher distribution is missing: $dist"
    Write-Output "LEGACY_BRIDGE_LAUNCHER_DIST=$dist"
    $launcherOut = Join-Path $serverDirectory 'launcher.stdout.log'; $launcherErr = Join-Path $serverDirectory 'launcher.stderr.log'; Remove-Item -LiteralPath $launcherOut, $launcherErr -Force -ErrorAction SilentlyContinue
    $launcher = Start-Process -FilePath $LauncherJava -ArgumentList @('-cp',("`"$(Join-Path $dist 'lib\*')`""),'dev.lodestone.legacybridge.launcher.LegacyBridgeMain') -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput $launcherOut -RedirectStandardError $launcherErr -PassThru

    $endpoint = "http://127.0.0.1:$mcpPort/mcp"; $httpDeadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $httpDeadline) { Start-Sleep -Milliseconds 500; try { $null = Invoke-WebRequest -UseBasicParsing -Uri $endpoint -Method Post -Headers @{ 'X-Lodestone-Token' = $gatewayToken } -ContentType 'application/json' -Body '{}'; break } catch { }; Assert ($null -ne (Get-Process -Id $launcher.Id -ErrorAction SilentlyContinue)) 'Legacy bridge launcher exited early.' }
    $initialize = Invoke-Mcp $endpoint $gatewayToken ([ordered]@{ jsonrpc = '2.0'; id = 1; method = 'initialize'; params = @{ protocolVersion = '2025-11-25'; capabilities = @{}; clientInfo = @{ name = 'legacy-native-matrix'; version = '1' } } })
    Assert ($initialize.result.protocolVersion -eq '2025-11-25') 'Legacy native MCP negotiation failed.'

    $read = Invoke-Capability $endpoint $gatewayToken 2 'minecraft.world.block.read' @{ x = 0; y = 64; z = 0; dimension = 'minecraft:overworld' }
    Assert ($read.result.structuredContent.status -eq 'ok') ("Legacy native block read failed: " + ($read | ConvertTo-Json -Depth 20 -Compress))
    $far = Invoke-Capability $endpoint $gatewayToken 3 'minecraft.world.block.read' @{ x = 100000; y = 64; z = 100000; dimension = 'minecraft:overworld' }
    Assert ($far.result.structuredContent.output.loaded -eq $false) 'Legacy native far read loaded a chunk.'
    $blocks = Invoke-Capability $endpoint $gatewayToken 4 'minecraft.world.blocks.read' @{ x = 0; y = 64; z = 0; sizeX = 2; sizeY = 2; sizeZ = 2 }
    Assert ($blocks.result.structuredContent.output.count -eq 8) 'Legacy native bulk read returned the wrong count.'
    $scan = Invoke-Capability $endpoint $gatewayToken 5 'minecraft.world.region.scan' @{ x = 0; y = 64; z = 0; sizeX = 2; sizeY = 2; sizeZ = 2 }
    Assert ($scan.result.structuredContent.output.totalCells -eq 8) 'Legacy native region scan failed.'
    $dryRun = Invoke-Capability $endpoint $gatewayToken 6 'minecraft.world.blocks.write' @{ changes = @(@{ x = 0; y = 64; z = 0; block = 'minecraft:gold_block' }); dryRun = $true }
    Assert ($dryRun.result.structuredContent.output.dryRun -eq $true) ("Legacy native dry-run write failed: " + ($dryRun | ConvertTo-Json -Depth 20 -Compress))
    Start-Sleep -Milliseconds 1200
    $apply = Invoke-Capability $endpoint $gatewayToken 7 'minecraft.world.blocks.write' @{ changes = @(@{ x = 0; y = 64; z = 0; block = 'minecraft:gold_block' }) }
    Assert ($apply.result.structuredContent.output.changedCount -eq 1) ("Legacy native block write failed: " + ($apply | ConvertTo-Json -Depth 20 -Compress))
    $readBack = Invoke-Capability $endpoint $gatewayToken 8 'minecraft.world.block.read' @{ x = 0; y = 64; z = 0; dimension = 'minecraft:overworld' }
    Assert ($readBack.result.structuredContent.output.block -eq 'minecraft:gold_block') 'Legacy native read-after-write failed.'
    Start-Sleep -Milliseconds 1200
    $createBlockEntity = Invoke-Capability $endpoint $gatewayToken 12 'minecraft.world.blocks.write' @{ changes = @(@{ x = 1; y = 100; z = 0; block = 'minecraft:chest' }) }
    Assert ($createBlockEntity.result.structuredContent.status -eq 'ok') 'Legacy native bridge could not create the block-entity fixture.'
    Start-Sleep -Milliseconds 1200
    $protectedBlockEntity = Invoke-Capability $endpoint $gatewayToken 13 'minecraft.world.blocks.write' @{ changes = @(@{ x = 1; y = 100; z = 0; block = 'minecraft:stone' }) }
    Assert ($protectedBlockEntity.result.structuredContent.status -eq 'error') 'Legacy native bridge allowed a block-entity replacement.'
    $blockEntityReadback = Invoke-Capability $endpoint $gatewayToken 14 'minecraft.world.block.read' @{ x = 1; y = 100; z = 0; dimension = 'minecraft:overworld' }
    Assert ($blockEntityReadback.result.structuredContent.output.block -eq 'minecraft:chest') 'Legacy native bridge changed a protected block entity.'
    $entities = Invoke-Capability $endpoint $gatewayToken 9 'minecraft.entity.list' @{ limit = 8; includePlayers = $false }
    Assert ($entities.result.structuredContent.status -eq 'ok') 'Legacy native entity query failed.'
    $chat = Invoke-Capability $endpoint $GatewayToken 10 'minecraft.chat.send' @{ message = "Lodestone Forge $Minecraft native matrix" }
    Assert ($chat.result.structuredContent.status -eq 'ok') 'Legacy native chat failed.'
    Start-Sleep -Seconds 7
    $stop = Invoke-Capability $endpoint $gatewayToken 11 'minecraft.command.execute' @{ command = 'stop' }
    Assert ($stop.result.structuredContent.status -eq 'ok') 'Legacy native stop command failed.'
    $exitDeadline = (Get-Date).AddSeconds(30); while ((Get-Date) -lt $exitDeadline -and (Get-Process -Id $server.Id -ErrorAction SilentlyContinue)) { Start-Sleep -Seconds 1 }
    Assert (-not (Get-Process -Id $server.Id -ErrorAction SilentlyContinue)) 'Legacy Forge server did not exit after native stop.'
    Assert-CleanShutdown $serverDirectory
    Write-Output "PASS Forge $Minecraft / $Forge native bridge"
} catch {
    Write-Output "FAIL Forge $Minecraft native bridge: $($_.Exception.Message)"; throw
} finally {
    if ($server -and (Get-Process -Id $server.Id -ErrorAction SilentlyContinue)) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    if ($launcher -and (Get-Process -Id $launcher.Id -ErrorAction SilentlyContinue)) { Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue }
    foreach ($name in $oldEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process') }
}

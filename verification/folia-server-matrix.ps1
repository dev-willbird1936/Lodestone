# SPDX-License-Identifier: MIT
<##
    Boots an official Folia 1.21.4 server with the packaged Lodestone plugin
    and exercises the authenticated MCP/world-control slice using Folia's
    global, region, and entity schedulers.

    Folia 1.21.1 is not currently published by the official build service;
    the 1.21.4 row is the nearest available 1.21 integration target.
##>
[CmdletBinding()]
param(
    [string]$Root = (Join-Path $env:TEMP 'lodestone-folia-matrix'),
    [string]$ServerJar = (Join-Path $env:TEMP 'lodestone-folia-1.21.4-build\folia-1.21.4-6.jar'),
    [string]$Java = (Join-Path ${env:JAVA_HOME} 'bin/java.exe'),
    [int]$ServerPort = 25573
)
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$hostJar = Join-Path $projectRoot 'hosts/folia/1.21.4/build/libs/lodestone-1.0.0.jar'
$port = 37933
$token = 'folia-matrix-token'
$serverSha256 = 'dcf2333211c1468c8eddc482bc8549600818cc661a709124a79c752f8fa2ac3a'

function Assert([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Read-ServerLog([string]$directory) {
    $path = Join-Path $directory 'logs/latest.log'
    if (Test-Path -LiteralPath $path) { return Get-Content -Raw -LiteralPath $path }
    return ''
}

function Log-Tail([string]$log, [int]$length = 2500) {
    if ([string]::IsNullOrEmpty($log)) { return '' }
    return $log.Substring([Math]::Max(0, $log.Length - $length))
}

function Invoke-Mcp([string]$uri, [string]$authToken, [object]$request, [string]$sessionId = '') {
    $headers = @{
        'X-Lodestone-Token' = $authToken
        'MCP-Protocol-Version' = '2025-11-25'
    }
    if (-not [string]::IsNullOrWhiteSpace($sessionId)) { $headers['Mcp-Session-Id'] = $sessionId }
    $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -Method Post -Headers $headers `
        -ContentType 'application/json' -Body ($request | ConvertTo-Json -Depth 30)
    return [pscustomobject]@{
        Body = $response.Content | ConvertFrom-Json
        Session = [string]$response.Headers['Mcp-Session-Id']
    }
}

function Assert-BadTokenRejected([string]$uri) {
    $statusCode = 0
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $uri -Method Post -Headers @{
            'X-Lodestone-Token' = 'invalid-folia-matrix-token'
            'MCP-Protocol-Version' = '2025-11-25'
        } -ContentType 'application/json' -Body '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{}}' | Out-Null
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        $statusCode = [int]$_.Exception.Response.StatusCode
    }
    Assert ($statusCode -eq 401) "Folia bad-token request returned HTTP $statusCode instead of 401."
    Write-Host 'Folia bad-token rejection verified (HTTP 401).'
}

function Invoke-Capability([string]$uri, [string]$authToken, [string]$sessionId,
                           [int]$id, [string]$capability, [hashtable]$payload) {
    return Invoke-Mcp $uri $authToken ([ordered]@{
            jsonrpc = '2.0'; id = $id; method = 'tools/call'
            params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                    capability = $capability; input = $payload
                } }
        }) $sessionId
}

New-Item -ItemType Directory -Force -Path $Root, (Join-Path $Root 'plugins') | Out-Null
Assert (Test-Path -LiteralPath $hostJar) "Missing packaged Folia host JAR: $hostJar"
Assert (Test-Path -LiteralPath $ServerJar) "Missing Folia server JAR: $ServerJar. Download an official Folia 1.21.4 build first."
Assert (Test-Path -LiteralPath $Java) "Java executable not found: $Java"
Assert ((Get-FileHash -LiteralPath $ServerJar -Algorithm SHA256).Hash.ToLowerInvariant() -eq $serverSha256) `
    'Official Folia server JAR SHA-256 mismatch.'

$serverJarName = Split-Path -Leaf $ServerJar
Copy-Item -LiteralPath $ServerJar -Destination (Join-Path $Root $serverJarName) -Force
Remove-Item -LiteralPath (Join-Path $Root 'world') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $Root 'world_nether') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $Root 'world_the_end') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $Root 'logs') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $Root 'crash-reports') -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $hostJar -Destination (Join-Path $Root 'plugins/Lodestone.jar') -Force
Set-Content -LiteralPath (Join-Path $Root 'eula.txt') -Value 'eula=true' -Encoding ascii
Set-Content -LiteralPath (Join-Path $Root 'server.properties') -Value @(
    'online-mode=false', 'level-name=world', "server-port=$ServerPort", 'view-distance=4', 'simulation-distance=4',
    'spawn-protection=0', 'enable-command-block=true'
) -Encoding ascii

$stdout = Join-Path $Root 'folia.stdout.log'
$stderr = Join-Path $Root 'folia.stderr.log'
Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
$process = $null
$uri = "http://127.0.0.1:$port/mcp"
$sessionId = ''

try {
    $launch = @(
        "-Dlodestone.port=$port",
        "-Dlodestone.token=$token",
        '-Dlodestone.permissions=observe,modify-world,communicate,administer-server',
        '-jar', $serverJarName, '--nogui'
    )
    $process = Start-Process -FilePath $Java -ArgumentList $launch -WorkingDirectory $Root `
        -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $null = $process.Handle

    $deadline = (Get-Date).AddMinutes(4)
    $log = ''
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $log = Read-ServerLog $Root
        if ($log -match 'Done \(') { break }
        Assert ($null -ne (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) `
            "Folia exited before reaching Done. Tail: $(Log-Tail $log)"
    }
    Assert ($log -match 'Done \(') 'Folia did not reach Done within four minutes.'
    Assert (Test-Path -LiteralPath (Join-Path $Root 'world')) 'Folia did not create a world directory.'
    Assert ($log -match '\[Lodestone\] Lodestone MCP loopback endpoint listening') `
        'Lodestone did not report a listening MCP endpoint.'
    # Folia initializes world regions after the global Done line.
    Start-Sleep -Seconds 5

    Assert-BadTokenRejected $uri
    $initialize = Invoke-Mcp $uri $token ([ordered]@{
            jsonrpc = '2.0'; id = 1; method = 'initialize'
            params = @{ protocolVersion = '2025-11-25'; capabilities = @{}; clientInfo = @{ name = 'folia-matrix'; version = '1' } }
        })
    $sessionId = $initialize.Session
    Assert ($initialize.Body.result.protocolVersion -eq '2025-11-25') 'Folia MCP negotiation failed.'

    $tools = Invoke-Mcp $uri $token ([ordered]@{ jsonrpc = '2.0'; id = 2; method = 'tools/list'; params = @{} }) $sessionId
    Assert (@($tools.Body.result.tools).Count -ge 1) 'Folia MCP tools/list returned no tools.'

    $capability = Invoke-Mcp $uri $token ([ordered]@{
            jsonrpc = '2.0'; id = 3; method = 'tools/call'
            params = @{ name = 'lodestone_capability_get'; arguments = @{ capability = 'minecraft.world.block.read' } }
        }) $sessionId
    Assert ($capability.Body.result.structuredContent.output.capability.availability -eq 'available') `
        'Folia world capability was not available after world load.'
    Assert ($capability.Body.result.structuredContent.output.capability.loader -eq 'folia') `
        'Folia capability was served by the wrong loader adapter.'

    $read = Invoke-Capability $uri $token $sessionId 4 'minecraft.world.block.read' `
        @{ x = 0; y = 64; z = 0; dimension = 'minecraft:overworld' }
    Assert ($read.Body.result.structuredContent.status -eq 'ok') `
        "Folia block read failed: $($read.Body | ConvertTo-Json -Depth 20 -Compress)"

    $unloaded = Invoke-Capability $uri $token $sessionId 16 'minecraft.world.block.read' `
        @{ x = 100000; y = 64; z = 100000; dimension = 'minecraft:overworld' }
    Assert ($unloaded.Body.result.structuredContent.output.loaded -eq $false) `
        'Folia single-block read unexpectedly loaded a far-away chunk.'
    Assert ($unloaded.Body.result.structuredContent.output.block -eq 'lodestone:unloaded') `
        'Folia single-block read did not report an unloaded chunk honestly.'

    $blocks = Invoke-Capability $uri $token $sessionId 5 'minecraft.world.blocks.read' `
        @{ x = 0; y = 64; z = 0; sizeX = 2; sizeY = 2; sizeZ = 2 }
    Assert ($blocks.Body.result.structuredContent.output.count -eq 8) 'Folia bulk read returned the wrong count.'

    $scan = Invoke-Capability $uri $token $sessionId 6 'minecraft.world.region.scan' `
        @{ x = 0; y = 64; z = 0; sizeX = 2; sizeY = 2; sizeZ = 2 }
    Assert ($scan.Body.result.structuredContent.output.totalCells -eq 8) 'Folia region scan failed.'

    $entities = Invoke-Mcp $uri $token ([ordered]@{
            jsonrpc = '2.0'; id = 7; method = 'tools/call'
            params = @{ name = 'lodestone_capability_get'; arguments = @{ capability = 'minecraft.entity.list' } }
        }) $sessionId
    Assert ($entities.Body.result.structuredContent.output.capability.availability -eq 'unavailable') `
        'Folia entity listing was advertised as available without a safe region-aware implementation.'

    # The write path refuses to implicitly load chunks. Pin the acceptance cell
    # before testing a write against it.
    $loadWriteChunk = Invoke-Capability $uri $token $sessionId 17 'minecraft.command.execute' `
        @{ command = 'forceload add 100 100' }
    Assert ($loadWriteChunk.Body.result.structuredContent.status -eq 'ok') `
        'Could not prepare a loaded chunk for Folia write acceptance.'
    Start-Sleep -Seconds 2

    $baseline = Invoke-Capability $uri $token $sessionId 21 'minecraft.world.block.read' `
        @{ x = 100; y = 100; z = 100; dimension = 'minecraft:overworld' }
    Assert ($baseline.Body.result.structuredContent.output.loaded -eq $true) 'Folia acceptance cell is not loaded.'
    $baselineBlock = [string]$baseline.Body.result.structuredContent.output.block

    $dryRun = Invoke-Capability $uri $token $sessionId 8 'minecraft.world.blocks.write' `
        @{ changes = @(@{ x = 100; y = 100; z = 100; block = 'minecraft:gold_block' }); dryRun = $true }
    Assert ($dryRun.Body.result.structuredContent.output.dryRun -eq $true) 'Folia dry-run write failed.'
    $dryRunReadback = Invoke-Capability $uri $token $sessionId 22 'minecraft.world.block.read' `
        @{ x = 100; y = 100; z = 100; dimension = 'minecraft:overworld' }
    Assert ($dryRunReadback.Body.result.structuredContent.output.block -eq $baselineBlock) `
        'Folia dry-run write mutated the acceptance cell.'
    Start-Sleep -Milliseconds 1200
    $apply = Invoke-Capability $uri $token $sessionId 9 'minecraft.world.blocks.write' `
        @{ changes = @(@{ x = 100; y = 100; z = 100; block = 'minecraft:gold_block' }) }
    Assert ($apply.Body.result.structuredContent.output.changedCount -eq 1) 'Folia block write failed.'
    $readBack = Invoke-Capability $uri $token $sessionId 10 'minecraft.world.block.read' `
        @{ x = 100; y = 100; z = 100; dimension = 'minecraft:overworld' }
    Assert ($readBack.Body.result.structuredContent.output.block -eq 'minecraft:gold_block') `
        'Folia read-after-write did not observe the requested block.'
    Start-Sleep -Milliseconds 1200
    $noOp = Invoke-Capability $uri $token $sessionId 23 'minecraft.world.blocks.write' `
        @{ changes = @(@{ x = 100; y = 100; z = 100; block = 'minecraft:gold_block' }) }
    Assert ($noOp.Body.result.structuredContent.output.changedCount -eq 0) `
        'Folia no-op write reported a changed cell.'

    Start-Sleep -Milliseconds 1200
    $createBlockEntity = Invoke-Capability $uri $token $sessionId 18 'minecraft.world.blocks.write' `
        @{ changes = @(@{ x = 101; y = 100; z = 100; block = 'minecraft:chest' }) }
    Assert ($createBlockEntity.Body.result.structuredContent.status -eq 'ok') 'Folia could not create the block-entity fixture.'
    Start-Sleep -Milliseconds 1200
    $protectedBlockEntity = Invoke-Capability $uri $token $sessionId 19 'minecraft.world.blocks.write' `
        @{ changes = @(@{ x = 101; y = 100; z = 100; block = 'minecraft:stone' }) }
    Assert ($protectedBlockEntity.Body.result.structuredContent.status -eq 'error') 'Folia allowed a block-entity replacement.'
    $blockEntityReadback = Invoke-Capability $uri $token $sessionId 20 'minecraft.world.block.read' `
        @{ x = 101; y = 100; z = 100; dimension = 'minecraft:overworld' }
    Assert ($blockEntityReadback.Body.result.structuredContent.output.block -eq 'minecraft:chest') `
        'Folia changed a protected block entity.'

    $chat = Invoke-Capability $uri $token $sessionId 11 'minecraft.chat.send' @{ message = 'Lodestone Folia matrix test' }
    Assert ($chat.Body.result.structuredContent.status -eq 'ok') 'Folia chat broadcast failed.'
    $command = Invoke-Capability $uri $token $sessionId 12 'minecraft.command.execute' @{ command = 'say Lodestone Folia matrix command' }
    Assert ($command.Body.result.structuredContent.status -eq 'ok') 'Folia command execution failed.'

    $player = Invoke-Capability $uri $token $sessionId 13 'minecraft.player.state.read' @{}
    Assert ($player.Body.result.structuredContent.error.code -eq 'ADAPTER_FAILURE') `
        'Folia no-player state failure was not structured.'
    $inventory = Invoke-Capability $uri $token $sessionId 14 'minecraft.inventory.read' @{}
    Assert ($inventory.Body.result.structuredContent.error.code -eq 'ADAPTER_FAILURE') `
        'Folia no-player inventory failure was not structured.'

    Start-Sleep -Seconds 7
    $stop = $null
    $stopConnectionClosed = $false
    try {
        $stop = Invoke-Capability $uri $token $sessionId 15 'minecraft.command.execute' @{ command = 'stop' }
    } catch {
        if ($_.Exception.Message -match '(?i)connection.*closed|closed unexpectedly|response.*closed') {
            $stopConnectionClosed = $true
        } else {
            throw
        }
    }
    if ($null -ne $stop) {
        Assert ($stop.Body.result.structuredContent.status -eq 'ok') 'Folia clean-stop command failed.'
    }
    $shutdownDeadline = (Get-Date).AddSeconds(30)
    while ($null -ne (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) -and (Get-Date) -lt $shutdownDeadline) {
        Start-Sleep -Milliseconds 500
    }
    Assert ($null -eq (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) 'Folia did not shut down cleanly.'
    $exitedCleanly = $process.WaitForExit(5000)
    Assert $exitedCleanly 'Folia process handle did not report exit.'
    $exitCode = $process.ExitCode
    Assert ($null -ne $exitCode -and $exitCode -eq 0) "Folia exited with code $exitCode."
    Assert (-not (Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object LocalPort -in @($ServerPort, $port))) 'Folia retained a test listener after shutdown.'

    $log = Read-ServerLog $Root
    Assert (($null -ne $stop) -or ($stopConnectionClosed -and $log -match '(?im)Stopping (the )?server')) `
        'Folia stop was neither acknowledged nor followed by a normal server stop.'
    $crashes = @(Get-ChildItem -LiteralPath (Join-Path $Root 'crash-reports') -File -ErrorAction SilentlyContinue)
    Assert ($crashes.Count -eq 0) "Folia produced crash reports: $($crashes.Name -join ', ')"
    Assert ($log -notmatch 'Exception in thread|Crash report saved|Failed to start the minecraft server|Failed to load|Could not load|FAILED TO BIND|Address already in use|OutOfMemoryError|NoClassDefFoundError') `
        'Folia log contains a crash, bind, or plugin-load failure.'
    Write-Output "PASS: Folia 1.21.4 build 6, world loaded, region/global scheduler MCP slice passed."
}
finally {
    if ($process -and (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
}

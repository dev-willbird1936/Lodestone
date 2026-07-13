# SPDX-License-Identifier: MIT
<##
    Boots an official Paper 1.21.1 server with the packaged Lodestone plugin and
    exercises the authenticated MCP server/world control slice.

    Paper is a plugin platform, not a CurseForge mod-loader profile, so this is
    intentionally a server-plugin acceptance test rather than a CurseForge row.
##>
[CmdletBinding()]
param(
    [string]$Root = (Join-Path $env:TEMP 'lodestone-paper-matrix'),
    [string]$Java = (Join-Path ${env:JAVA_HOME} 'bin/java.exe'),
    [int]$ServerPort = 25571
)
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$paperJarName = 'paper-1.21.1-133.jar'
$paperJarUrl = 'https://fill-data.papermc.io/v1/objects/39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9/paper-1.21.1-133.jar'
$paperJarSha256 = '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9'
$hostJar = Join-Path $projectRoot 'hosts/paper/1.21.1/build/libs/lodestone-1.0.0.jar'
$port = 37931
$token = 'paper-matrix-token'

function Assert([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Read-ServerLog([string]$directory) {
    $path = Join-Path $directory 'logs/latest.log'
    if (Test-Path -LiteralPath $path) { return Get-Content -Raw -LiteralPath $path }
    return ''
}

function Log-Tail([object]$log, [int]$length = 2500) {
    $text = [string]$log
    return $text.Substring([Math]::Max(0, $text.Length - $length))
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
Assert (Test-Path -LiteralPath $hostJar) "Missing packaged Paper host JAR: $hostJar"
Assert (Test-Path -LiteralPath $Java) "Java executable not found: $Java"

$paperJar = Join-Path $Root $paperJarName
if (-not (Test-Path -LiteralPath $paperJar) -or (Get-Item -LiteralPath $paperJar).Length -ne 49394394) {
    Invoke-WebRequest -UseBasicParsing -Headers @{
        'User-Agent' = 'Lodestone/1.0.0 (local compatibility test)'
    } -Uri $paperJarUrl -OutFile $paperJar -TimeoutSec 180
}
Assert ((Get-FileHash -LiteralPath $paperJar -Algorithm SHA256).Hash.ToLowerInvariant() -eq $paperJarSha256) `
    'Official Paper server JAR SHA-256 mismatch.'

Remove-Item -LiteralPath (Join-Path $Root 'world') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $Root 'world_nether') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $Root 'world_the_end') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $Root 'logs') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $Root 'crash-reports') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $Root 'plugins/.paper-remapped') -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $hostJar -Destination (Join-Path $Root 'plugins/Lodestone.jar') -Force
Set-Content -LiteralPath (Join-Path $Root 'eula.txt') -Value 'eula=true' -Encoding ascii
Set-Content -LiteralPath (Join-Path $Root 'server.properties') -Value @(
    'online-mode=false', 'level-name=world', "server-port=$ServerPort", 'view-distance=4', 'simulation-distance=4'
) -Encoding ascii

$stdout = Join-Path $Root 'paper.stdout.log'
$stderr = Join-Path $Root 'paper.stderr.log'
Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
$process = $null
$uri = "http://127.0.0.1:$port/mcp"
$sessionId = ''

try {
    $launch = @(
        "-Dlodestone.port=$port",
        "-Dlodestone.token=$token",
        '-Dlodestone.permissions=observe,modify-world,communicate,administer-server',
        '-jar', $paperJarName, '--nogui'
    )
    $process = Start-Process -FilePath $Java -ArgumentList $launch -WorkingDirectory $Root `
        -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $null = $process.Handle

    $deadline = (Get-Date).AddMinutes(3)
    $log = ''
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $log = Read-ServerLog $Root
        if ($log -match 'Done \(') { break }
        Assert ($null -ne (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) `
            "Paper exited before reaching Done. Tail: $(Log-Tail $log)"
    }
    Assert ($log -match 'Done \(') 'Paper did not reach Done within three minutes.'
    Assert (Test-Path -LiteralPath (Join-Path $Root 'world')) 'Paper did not create a world directory.'

    $initialize = Invoke-Mcp $uri $token ([ordered]@{
            jsonrpc = '2.0'; id = 1; method = 'initialize'
            params = @{ protocolVersion = '2025-11-25'; capabilities = @{}; clientInfo = @{ name = 'paper-matrix'; version = '1' } }
        })
    $sessionId = $initialize.Session
    Assert ($initialize.Body.result.protocolVersion -eq '2025-11-25') 'Paper MCP negotiation failed.'

    $tools = Invoke-Mcp $uri $token ([ordered]@{ jsonrpc = '2.0'; id = 2; method = 'tools/list'; params = @{} }) $sessionId
    Assert (@($tools.Body.result.tools).Count -ge 1) 'Paper MCP tools/list returned no tools.'

    $capability = Invoke-Mcp $uri $token ([ordered]@{
            jsonrpc = '2.0'; id = 3; method = 'tools/call'
            params = @{ name = 'lodestone_capability_get'; arguments = @{ capability = 'minecraft.world.block.read' } }
        }) $sessionId
    Assert ($capability.Body.result.structuredContent.output.capability.availability -eq 'available') `
        'Paper world capability was not available after world load.'

    $read = Invoke-Capability $uri $token $sessionId 4 'minecraft.world.block.read' `
        @{ x = 0; y = 64; z = 0; dimension = 'minecraft:overworld' }
    Assert ($read.Body.result.structuredContent.status -eq 'ok') `
        "Paper block read failed: $($read.Body | ConvertTo-Json -Depth 20 -Compress)"

    $unloaded = Invoke-Capability $uri $token $sessionId 16 'minecraft.world.block.read' `
        @{ x = 100000; y = 64; z = 100000; dimension = 'minecraft:overworld' }
    Assert ($unloaded.Body.result.structuredContent.output.loaded -eq $false) `
        'Paper single-block read unexpectedly loaded a far-away chunk.'
    Assert ($unloaded.Body.result.structuredContent.output.block -eq 'lodestone:unloaded') `
        'Paper single-block read did not report an unloaded chunk honestly.'

    $blocks = Invoke-Capability $uri $token $sessionId 5 'minecraft.world.blocks.read' `
        @{ x = 0; y = 64; z = 0; sizeX = 2; sizeY = 2; sizeZ = 2 }
    Assert ($blocks.Body.result.structuredContent.output.count -eq 8) 'Paper bulk read returned the wrong count.'

    $scan = Invoke-Capability $uri $token $sessionId 6 'minecraft.world.region.scan' `
        @{ x = 0; y = 64; z = 0; sizeX = 2; sizeY = 2; sizeZ = 2 }
    Assert ($scan.Body.result.structuredContent.output.totalCells -eq 8) 'Paper region scan failed.'

    $entities = Invoke-Capability $uri $token $sessionId 7 'minecraft.entity.list' `
        @{ limit = 8; includePlayers = $false }
    Assert ($entities.Body.result.structuredContent.status -eq 'ok') `
        "Paper entity query failed: $($entities.Body | ConvertTo-Json -Depth 20 -Compress)"

    # The write path refuses to implicitly load chunks. Pin the acceptance cell
    # before testing a write against it.
    $loadWriteChunk = Invoke-Capability $uri $token $sessionId 17 'minecraft.command.execute' `
        @{ command = 'forceload add 100 100' }
    Assert ($loadWriteChunk.Body.result.structuredContent.status -eq 'ok') `
        'Could not prepare a loaded chunk for Paper write acceptance.'

    $baseline = Invoke-Capability $uri $token $sessionId 23 'minecraft.world.block.read' `
        @{ x = 100; y = 100; z = 100; dimension = 'minecraft:overworld' }
    Assert ($baseline.Body.result.structuredContent.output.loaded -eq $true) 'Paper acceptance cell is not loaded.'
    $baselineBlock = [string]$baseline.Body.result.structuredContent.output.block

    $dryRun = Invoke-Capability $uri $token $sessionId 8 'minecraft.world.blocks.write' `
        @{ changes = @(@{ x = 100; y = 100; z = 100; block = 'minecraft:gold_block' }); dryRun = $true }
    Assert ($dryRun.Body.result.structuredContent.output.dryRun -eq $true) 'Paper dry-run write failed.'
    $dryRunReadback = Invoke-Capability $uri $token $sessionId 24 'minecraft.world.block.read' `
        @{ x = 100; y = 100; z = 100; dimension = 'minecraft:overworld' }
    Assert ($dryRunReadback.Body.result.structuredContent.output.block -eq $baselineBlock) `
        'Paper dry-run write mutated the acceptance cell.'
    Start-Sleep -Milliseconds 1200
    $apply = Invoke-Capability $uri $token $sessionId 9 'minecraft.world.blocks.write' `
        @{ changes = @(@{ x = 100; y = 100; z = 100; block = 'minecraft:gold_block' }) }
    Assert ($apply.Body.result.structuredContent.output.changedCount -eq 1) 'Paper block write failed.'
    $readBack = Invoke-Capability $uri $token $sessionId 10 'minecraft.world.block.read' `
        @{ x = 100; y = 100; z = 100; dimension = 'minecraft:overworld' }
    Assert ($readBack.Body.result.structuredContent.output.block -eq 'minecraft:gold_block') `
        'Paper read-after-write did not observe the requested block.'
    Start-Sleep -Milliseconds 1200
    $noOp = Invoke-Capability $uri $token $sessionId 25 'minecraft.world.blocks.write' `
        @{ changes = @(@{ x = 100; y = 100; z = 100; block = 'minecraft:gold_block' }) }
    Assert ($noOp.Body.result.structuredContent.output.changedCount -eq 0) `
        'Paper no-op write reported a changed cell.'

    # Prove that a populated block entity is rejected before mutation and retains its data.
    Start-Sleep -Milliseconds 1200
    $createBlockEntity = Invoke-Capability $uri $token $sessionId 18 'minecraft.world.blocks.write' `
        @{ changes = @(
                @{ x = 101; y = 100; z = 100; block = 'minecraft:chest' },
                @{ x = 102; y = 100; z = 100; block = 'minecraft:air' }
            ) }
    Assert ($createBlockEntity.Body.result.structuredContent.status -eq 'ok') 'Paper could not create the block-entity fixture.'
    $populateBlockEntity = Invoke-Capability $uri $token $sessionId 19 'minecraft.command.execute' `
        @{ command = 'data merge block 101 100 100 {CustomName:''{"text":"LodestoneSentinel"}''}' }
    Assert ($populateBlockEntity.Body.result.structuredContent.status -eq 'ok') 'Paper could not populate the block-entity fixture.'
    Start-Sleep -Milliseconds 1200
    $protectedBlockEntity = Invoke-Capability $uri $token $sessionId 20 'minecraft.world.blocks.write' `
        @{ changes = @(@{ x = 101; y = 100; z = 100; block = 'minecraft:stone' }) }
    Assert ($protectedBlockEntity.Body.result.structuredContent.status -eq 'error') 'Paper allowed a populated block-entity replacement.'
    Assert ($protectedBlockEntity.Body.result.structuredContent.error.message -match 'block-entity') `
        'Paper block-entity rejection did not explain the safety boundary.'
    $blockEntityReadback = Invoke-Capability $uri $token $sessionId 21 'minecraft.world.block.read' `
        @{ x = 101; y = 100; z = 100; dimension = 'minecraft:overworld' }
    Assert ($blockEntityReadback.Body.result.structuredContent.output.block -eq 'minecraft:chest') `
        'Paper changed a protected block entity.'

    $chat = Invoke-Capability $uri $token $sessionId 11 'minecraft.chat.send' @{ message = 'Lodestone Paper matrix test' }
    Assert ($chat.Body.result.structuredContent.status -eq 'ok') 'Paper chat broadcast failed.'
    Start-Sleep -Seconds 7
    $command = Invoke-Capability $uri $token $sessionId 12 'minecraft.command.execute' `
        @{ command = 'execute if data block 101 100 100 CustomName run setblock 102 100 100 minecraft:emerald_block' }
    Assert ($command.Body.result.structuredContent.status -eq 'ok') 'Paper command execution failed.'
    $blockEntityDataMarker = Invoke-Capability $uri $token $sessionId 22 'minecraft.world.block.read' `
        @{ x = 102; y = 100; z = 100; dimension = 'minecraft:overworld' }
    Assert ($blockEntityDataMarker.Body.result.structuredContent.output.block -eq 'minecraft:emerald_block') `
        'Paper did not preserve populated block-entity data after rejection.'

    $player = Invoke-Capability $uri $token $sessionId 13 'minecraft.player.state.read' @{}
    Assert ($player.Body.result.structuredContent.error.code -eq 'ADAPTER_FAILURE') `
        'Paper no-player state failure was not structured.'
    $inventory = Invoke-Capability $uri $token $sessionId 14 'minecraft.inventory.read' @{}
    Assert ($inventory.Body.result.structuredContent.error.code -eq 'ADAPTER_FAILURE') `
        'Paper no-player inventory failure was not structured.'

    Start-Sleep -Seconds 7
    $stop = Invoke-Capability $uri $token $sessionId 15 'minecraft.command.execute' @{ command = 'stop' }
    Assert ($stop.Body.result.structuredContent.status -eq 'ok') 'Paper clean-stop command failed.'
    $shutdownDeadline = (Get-Date).AddSeconds(30)
    while ($null -ne (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) -and (Get-Date) -lt $shutdownDeadline) {
        Start-Sleep -Milliseconds 500
    }
    Assert ($null -eq (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) 'Paper did not shut down cleanly.'
    $exitedCleanly = $process.WaitForExit(5000)
    Assert $exitedCleanly 'Paper process handle did not report exit.'
    $exitCode = $process.ExitCode
    Assert ($null -ne $exitCode -and $exitCode -eq 0) "Paper exited with code $exitCode."
    Assert (-not (Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object LocalPort -in @($ServerPort, $port))) 'Paper retained a test listener after shutdown.'

    $log = Read-ServerLog $Root
    $crashes = @(Get-ChildItem -LiteralPath (Join-Path $Root 'crash-reports') -File -ErrorAction SilentlyContinue)
    Assert ($crashes.Count -eq 0) "Paper produced crash reports: $($crashes.Name -join ', ')"
    Assert ($log -notmatch 'Exception in thread|Crash report saved|Failed to start the minecraft server|Failed to load|Could not load|FAILED TO BIND|Address already in use|OutOfMemoryError|NoClassDefFoundError') `
        'Paper log contains a crash or plugin-load failure.'
    Write-Output "PASS: Paper 1.21.1 build 133, world loaded, MCP control slice passed."
}
finally {
    if ($process -and (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
}

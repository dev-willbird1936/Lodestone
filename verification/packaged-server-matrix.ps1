# SPDX-License-Identifier: MIT
<#
    Runs the packaged-artifact acceptance matrix against already-prepared,
    disposable dedicated-server directories. Each directory must contain its
    official loader runtime and exact loader dependencies.
#>
[CmdletBinding()]
param(
    [string]$Root = (Join-Path $env:TEMP 'lodestone-matrix'),
    [switch]$SkipCleanWorld,
    [string[]]$Only = @()
)
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$targets = @(
    [pscustomobject]@{
        Name = 'Fabric 1.20.1 / Loader 0.15.11'
        Directory = Join-Path $Root 'fabric-1.20.1'
        HostJar = Join-Path $projectRoot 'hosts/fabric/1.20.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar'
        ServerPort = 25560
        Port = 37921
        Token = 'matrix-fabric-1201'
        Java = Join-Path $env:TEMP 'lodestone-toolchains\jdk17\jdk-17.0.19+10\bin\java.exe'
        Launch = @('-Dlodestone.port=37921', '-Dlodestone.token=matrix-fabric-1201',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '-jar',
            'fabric-server-launch.jar', 'nogui')
    },
    [pscustomobject]@{
        Name = 'Fabric 1.19.2 / Loader 0.14.25'
        Directory = Join-Path $Root 'fabric-1.19.2'
        HostJar = Join-Path $projectRoot 'hosts/fabric/1.19.2/build/libs/lodestone-0.1.0-SNAPSHOT.jar'
        ServerPort = 25561
        Port = 37930
        Token = 'matrix-fabric-1192'
        Java = Join-Path $env:TEMP 'lodestone-toolchains\jdk17\jdk-17.0.19+10\bin\java.exe'
        Launch = @('-Dlodestone.port=37930', '-Dlodestone.token=matrix-fabric-1192',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '-jar',
            'fabric-server-launch.jar', 'nogui')
    },
    [pscustomobject]@{
        Name = 'Fabric 1.18.2 / Loader 0.14.25'
        Directory = Join-Path $Root 'fabric-1.18.2'
        HostJar = Join-Path $projectRoot 'hosts/fabric/1.18.2/build/libs/lodestone-0.1.0-SNAPSHOT.jar'
        ServerPort = 25570
        Port = 37933
        Token = 'matrix-fabric-1182'
        Java = Join-Path $env:TEMP 'lodestone-toolchains\jdk17\jdk-17.0.19+10\bin\java.exe'
        Launch = @('-Dlodestone.port=37933', '-Dlodestone.token=matrix-fabric-1182',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '-jar',
            'fabric-server-launch.jar', 'nogui')
    },
    [pscustomobject]@{
        Name = 'Fabric 1.21.1 / Loader 0.16.10'
        Directory = Join-Path $Root 'fabric-1.21.1'
        HostJar = Join-Path $projectRoot 'hosts/fabric/1.21.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar'
        ServerPort = 25562
        Port = 37922
        Token = 'matrix-fabric-1211'
        Launch = @('-Dlodestone.port=37922', '-Dlodestone.token=matrix-fabric-1211',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '-jar',
            'fabric-server-launch.jar', 'nogui')
    },
    [pscustomobject]@{
        Name = 'Fabric 26.2 / Loader 0.19.3 (Vulkan client setting; dedicated server)'
        Directory = Join-Path $Root 'fabric-26.2'
        HostJar = Join-Path $projectRoot 'hosts/fabric/26.2/build/libs/lodestone-0.1.0-SNAPSHOT.jar'
        ServerPort = 25563
        Port = 37929
        Token = 'matrix-fabric-262'
        Java = Join-Path $env:TEMP 'lodestone-toolchains\jdk25\jdk-25\bin\java.exe'
        Launch = @('-Dlodestone.port=37929', '-Dlodestone.token=matrix-fabric-262',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '-jar',
            'fabric-server-launch.jar', 'nogui')
    },
    [pscustomobject]@{
        Name = 'Quilt 1.20.1 / Loader 0.29.2 via Fabric compatibility'
        Directory = Join-Path $Root 'quilt-1.20.1'
        HostJar = Join-Path $Root 'quilt-1.20.1/lodestone-quilt.jar'
        SourceHostJar = Join-Path $projectRoot 'hosts/fabric/1.20.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar'
        QuiltMinecraft = '1.20.1'
        QuiltFabricLoaderRange = '>=0.15.11'
        ServerPort = 25564
        Port = 37924
        Token = 'matrix-quilt-1201'
        Java = Join-Path $env:TEMP 'lodestone-toolchains\jdk17\jdk-17.0.19+10\bin\java.exe'
        Launch = @('-Dlodestone.port=37924', '-Dlodestone.token=matrix-quilt-1201',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '-jar',
            'quilt-server-launch.jar', 'nogui')
    },
    [pscustomobject]@{
        Name = 'Quilt 1.21.1 / Loader 0.29.2 via Fabric compatibility'
        Directory = Join-Path $Root 'quilt-1.21.1'
        HostJar = Join-Path $Root 'quilt-1.21.1/lodestone-quilt.jar'
        SourceHostJar = Join-Path $projectRoot 'hosts/fabric/1.21.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar'
        QuiltMinecraft = '1.21.1'
        QuiltFabricLoaderRange = '>=0.16.10'
        ServerPort = 25572
        Port = 37934
        Token = 'matrix-quilt-1211'
        Launch = @('-Dlodestone.port=37934', '-Dlodestone.token=matrix-quilt-1211',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '-jar',
            'quilt-server-launch.jar', 'nogui')
    },
    [pscustomobject]@{
        Name = 'NeoForge 1.21.1 / 21.1.211'
        Directory = Join-Path $Root 'neoforge-1.21.1'
        HostJar = Join-Path $projectRoot 'hosts/neoforge/1.21.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar'
        ServerPort = 25565
        Port = 37923
        Token = 'matrix-neoforge-1211'
        Launch = @('-Dlodestone.port=37923', '-Dlodestone.token=matrix-neoforge-1211',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '@user_jvm_args.txt',
            '@libraries/net/neoforged/neoforge/21.1.211/win_args.txt', 'nogui')
    },
    [pscustomobject]@{
        Name = 'Forge 1.21.1 / 52.1.0'
        Directory = Join-Path $Root 'forge-1.21.1'
        HostJar = Join-Path $projectRoot 'hosts/forge/1.21.1/build/libs/lodestone-0.1.0-SNAPSHOT.jar'
        ServerPort = 25566
        Port = 37926
        Token = 'matrix-forge-1211'
        Launch = @('-Dlodestone.port=37926', '-Dlodestone.token=matrix-forge-1211',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '@user_jvm_args.txt',
            '@libraries/net/minecraftforge/forge/1.21.1-52.1.0/win_args.txt', 'nogui')
    },
    [pscustomobject]@{
        Name = 'Forge 1.20.1 / 47.4.10'
        Directory = Join-Path $Root 'forge-1.20.1'
        HostJar = Join-Path $projectRoot 'hosts/forge/1.20.1/build/reobfJar/output.jar'
        ServerPort = 25567
        Port = 37925
        Token = 'matrix-forge-1201'
        Launch = @('-Dlodestone.port=37925', '-Dlodestone.token=matrix-forge-1201',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '@user_jvm_args.txt',
            '@libraries/net/minecraftforge/forge/1.20.1-47.4.10/win_args.txt', 'nogui')
    },
    [pscustomobject]@{
        Name = 'Forge 1.19.2 / 43.5.2'
        Directory = Join-Path $Root 'forge-1.19.2'
        HostJar = Join-Path $projectRoot 'hosts/forge/1.19.2/build/reobfJar/output.jar'
        ServerPort = 25568
        Port = 37931
        Token = 'matrix-forge-1192'
        Java = Join-Path $env:TEMP 'lodestone-toolchains\jdk17\jdk-17.0.19+10\bin\java.exe'
        Launch = @('-Dlodestone.port=37931', '-Dlodestone.token=matrix-forge-1192',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '@user_jvm_args.txt',
            '@libraries/net/minecraftforge/forge/1.19.2-43.5.2/win_args.txt', 'nogui')
    },
    [pscustomobject]@{
        Name = 'Forge 1.18.2 / 40.3.12'
        Directory = Join-Path $Root 'forge-1.18.2'
        HostJar = Join-Path $projectRoot 'hosts/forge/1.18.2/build/reobfJar/output.jar'
        ServerPort = 25569
        Port = 37932
        Token = 'matrix-forge-1182'
        Java = Join-Path $env:TEMP 'lodestone-toolchains\jdk17\jdk-17.0.19+10\bin\java.exe'
        Launch = @('-Dlodestone.port=37932', '-Dlodestone.token=matrix-forge-1182',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '@user_jvm_args.txt',
            '@libraries/net/minecraftforge/forge/1.18.2-40.3.12/win_args.txt', 'nogui')
    },
    [pscustomobject]@{
        Name = 'Forge 1.16.5 / 36.2.42'
        Directory = Join-Path $Root 'forge-1.16.5'
        HostJar = Join-Path $projectRoot 'hosts/forge/1.16.5/build/reobfJar/output.jar'
        ServerPort = 25573
        Port = 37935
        Token = 'matrix-forge-1165'
        Java = Join-Path $env:TEMP 'lodestone-toolchains\jdk17\jdk-17.0.19+10\bin\java.exe'
        Launch = @('--add-exports=java.base/sun.security.util=ALL-UNNAMED',
            '--add-opens=java.base/java.util.jar=ALL-UNNAMED',
            '-Dlodestone.port=37935', '-Dlodestone.token=matrix-forge-1165',
            '-Dlodestone.permissions=observe,modify-world,communicate,administer-server', '-jar',
            'forge-1.16.5-36.2.42.jar', 'nogui')
    }
)

if ($Only.Count -gt 0) {
    $targets = @($targets | Where-Object Name -in $Only)
    if ($targets.Count -eq 0) { throw "No matrix targets matched -Only: $($Only -join ', ')" }
}

function Assert([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Read-Log([string]$directory) {
    $path = Join-Path $directory 'logs/latest.log'
    if (Test-Path -LiteralPath $path) { return Get-Content -Raw -LiteralPath $path }
    return ''
}
function Log-Tail([object]$log, [int]$length = 2000) {
    $text = [string]$log
    return $text.Substring([Math]::Max(0, $text.Length - $length))
}
function Assert-CleanShutdown([string]$directory, [string]$name) {
    $crashDirectory = Join-Path $directory 'crash-reports'
    $crashes = if (Test-Path -LiteralPath $crashDirectory) { @(Get-ChildItem -LiteralPath $crashDirectory -File -ErrorAction SilentlyContinue) } else { @() }
    Assert ($crashes.Count -eq 0) "$name produced crash reports: $($crashes.Name -join ', ')"
    $text = Read-Log $directory
    $stdoutPath = Join-Path $directory 'matrix.stdout.log'; $stderrPath = Join-Path $directory 'matrix.stderr.log'
    foreach ($path in @($stdoutPath, $stderrPath)) { if (Test-Path -LiteralPath $path) { $text += "`n" + (Get-Content -Raw -LiteralPath $path) } }
    $fatal = '(?im)Encountered an unexpected exception|Exception in server tick loop|Exception in thread|Crash report saved|Failed to start the minecraft server|Failed to load|Could not load|Could not bind|FAILED TO BIND|Address already in use|OutOfMemoryError|NoClassDefFoundError|ModLoadingException|Mixin[^\r\n]*(?:failed|error)'
    Assert ($text -notmatch $fatal) "$name logged a fatal shutdown error."
    Assert ($text -match '(?im)Stopping (the )?server') "$name did not log a normal server stop."
}

function Invoke-Mcp([string]$uri, [string]$token, [object]$request, [string]$sessionId = '', [string]$captureSession = '') {
    $headers = @{ 'X-Lodestone-Token' = $token }
    $method = [string]$request.method
    $effectiveSessionId = $sessionId
    if ([string]::IsNullOrWhiteSpace($effectiveSessionId) -and $method -ne 'initialize' -and
        -not [string]::IsNullOrWhiteSpace($script:MainMcpSessionId)) {
        $effectiveSessionId = $script:MainMcpSessionId
    }
    $headers['MCP-Protocol-Version'] = '2025-11-25'
    if (-not [string]::IsNullOrWhiteSpace($effectiveSessionId)) {
        $headers['Mcp-Session-Id'] = $effectiveSessionId
    }
    $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -Method Post -Headers $headers `
        -ContentType 'application/json' -Body ($request | ConvertTo-Json -Depth 20)
    $assigned = $response.Headers['Mcp-Session-Id']
    if (-not [string]::IsNullOrWhiteSpace($assigned)) {
        if (-not [string]::IsNullOrWhiteSpace($captureSession)) {
            Set-Variable -Scope Script -Name $captureSession -Value $assigned
        } elseif ([string]::IsNullOrWhiteSpace($sessionId)) {
            $script:MainMcpSessionId = $assigned
        }
    }
    return $response.Content | ConvertFrom-Json
}

foreach ($target in $targets) {
    $process = $null
    try {
        if ($target.PSObject.Properties.Name -contains 'SourceHostJar') {
            $quiltArgs = @{
                Jar = $target.SourceHostJar
                Output = $target.HostJar
            }
            if ($target.PSObject.Properties.Name -contains 'QuiltMinecraft') {
                $quiltArgs.Minecraft = $target.QuiltMinecraft
            }
            if ($target.PSObject.Properties.Name -contains 'QuiltFabricLoaderRange') {
                $quiltArgs.FabricLoaderRange = $target.QuiltFabricLoaderRange
            }
            & (Join-Path $projectRoot 'verification/prepare-quilt-host.ps1') @quiltArgs
        }
        Assert (Test-Path -LiteralPath $target.Directory) "Missing prepared server directory: $($target.Directory)"
        Assert (Test-Path -LiteralPath $target.HostJar) "Missing packaged host JAR: $($target.HostJar)"
        Assert (Test-Path -LiteralPath (Join-Path $target.Directory 'mods')) "Missing mods directory: $($target.Directory)"

        if (-not $SkipCleanWorld) {
            Remove-Item -LiteralPath (Join-Path $target.Directory 'world') -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath (Join-Path $target.Directory 'logs') -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath (Join-Path $target.Directory 'crash-reports') -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath (Join-Path $target.Directory 'server.properties') -Force -ErrorAction SilentlyContinue
        }
        Get-ChildItem -LiteralPath (Join-Path $target.Directory 'mods') -Filter 'lodestone*.jar' -File -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
        Copy-Item -LiteralPath $target.HostJar -Destination (Join-Path $target.Directory 'mods/lodestone.jar') -Force
        Set-Content -LiteralPath (Join-Path $target.Directory 'eula.txt') -Value 'eula=true' -Encoding ascii
        @(
            "server-port=$($target.ServerPort)"
            'online-mode=false'
            'enable-query=false'
            'level-name=world'
        ) | Set-Content -LiteralPath (Join-Path $target.Directory 'server.properties') -Encoding ascii

        $stdout = Join-Path $target.Directory 'matrix.stdout.log'
        $stderr = Join-Path $target.Directory 'matrix.stderr.log'
        Remove-Item -LiteralPath $stdout,$stderr -Force -ErrorAction SilentlyContinue
        $java = if ($target.PSObject.Properties.Name -contains 'Java') { $target.Java } else { 'java' }
        if ($java -ne 'java') { Assert (Test-Path -LiteralPath $java) "Java executable not found: $java" }
        $process = Start-Process -FilePath $java -ArgumentList $target.Launch -WorkingDirectory $target.Directory `
            -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        # Windows PowerShell may lazily open the process handle. Retain it now so ExitCode
        # remains available after the server has terminated.
        $null = $process.Handle

        $log = ''
        $deadline = (Get-Date).AddMinutes(3)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            $log = Read-Log $target.Directory
            if ($log -match 'Done \(') { break }
            if (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
                throw "Server exited before Done. Tail: $(Log-Tail $log)"
            }
        }
        Assert ($log -match 'Done \(') 'Server did not reach Done within three minutes.'
        Assert (Test-Path -LiteralPath (Join-Path $target.Directory 'world/level.dat')) 'World level.dat was not generated.'
        $regionDirectories = @(
            (Join-Path $target.Directory 'world/region'),
            (Join-Path $target.Directory 'world/dimensions/minecraft/overworld/region')
        )
        Assert (@($regionDirectories | Where-Object { Test-Path -LiteralPath $_ }).Count -gt 0) 'Overworld region directory was not generated.'

        $uri = "http://127.0.0.1:$($target.Port)/mcp"
        $initialize = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 1; method = 'initialize'
                params = @{ protocolVersion = '2025-11-25'; capabilities = @{}; clientInfo = @{ name = 'matrix'; version = '1' } }
            })
        Assert ($initialize.result.protocolVersion -eq '2025-11-25') 'MCP protocol negotiation failed.'

        $sessionAInitialize = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 12; method = 'initialize'
                params = @{ protocolVersion = '2025-11-25'; capabilities = @{}; clientInfo = @{ name = 'matrix-peer'; version = '1' } }
            }) '' 'PeerMcpSessionId'
        Assert ($sessionAInitialize.result.protocolVersion -eq '2025-11-25') 'MCP peer session negotiation failed.'

        $uninitializedPeer = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 10; method = 'tools/list'; params = @{}
            }) 'matrix-session-b'
        Assert ($uninitializedPeer.error.code -in @(-32001, -32002)) "MCP session state leaked between clients. Response: $($uninitializedPeer | ConvertTo-Json -Depth 20 -Compress)"

        $initializedPeer = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 11; method = 'tools/list'; params = @{}
            }) $script:PeerMcpSessionId
        Assert ($null -ne $initializedPeer.result.tools) 'Initialized MCP session could not list tools.'

        $clientCapability = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 9; method = 'tools/call'
                params = @{ name = 'lodestone_capability_get'; arguments = @{
                        capability = 'minecraft.player.look'
                    } }
            })
        Assert ($clientCapability.result.structuredContent.status -eq 'ok') "Client capability lookup failed: $($clientCapability | ConvertTo-Json -Depth 20 -Compress)"
        Assert ($clientCapability.result.structuredContent.output.capability.availability -eq 'unavailable') `
            'Dedicated server incorrectly advertised client player control as available.'

        $world = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 2; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.block.read'; input = @{ x = 0; y = 64; z = 0; dimension = 'minecraft:overworld' }
                    } }
            })
        Assert ($world.result.structuredContent.status -eq 'ok') "Native block read failed: $($world | ConvertTo-Json -Depth 20 -Compress)"

        $unloadedWorld = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 3; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.block.read'; input = @{ x = 100000; y = 64; z = 100000; dimension = 'minecraft:overworld' }
                    } }
            })
        Assert ($unloadedWorld.result.structuredContent.status -eq 'ok') 'Native far block read failed.'
        Assert ($unloadedWorld.result.structuredContent.output.loaded -eq $false) `
            'Native single-block read unexpectedly loaded a far-away chunk.'
        Assert ($unloadedWorld.result.structuredContent.output.block -eq 'lodestone:unloaded') `
            'Native single-block read did not report an unloaded chunk honestly.'

        $blocks = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 6; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.blocks.read'; input = @{
                            x = 0; y = 64; z = 0; sizeX = 2; sizeY = 2; sizeZ = 2
                        }
                    } }
            })
        Assert ($blocks.result.structuredContent.status -eq 'ok') 'Native bulk block read failed.'
        Assert ($blocks.result.structuredContent.output.count -eq 8) 'Native bulk block read returned the wrong cell count.'

        $scan = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 8; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.region.scan'; input = @{
                            x = 0; y = 64; z = 0; sizeX = 2; sizeY = 2; sizeZ = 2
                        }
                    } }
            })
        Assert ($scan.result.structuredContent.status -eq 'ok') 'Native region scan failed.'
        Assert ($scan.result.structuredContent.output.totalCells -eq 8) 'Native region scan returned the wrong cell count.'

        $entities = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 3; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.entity.list'; input = @{ limit = 8; includePlayers = $false }
                    } }
            })
        Assert ($entities.result.structuredContent.status -eq 'ok') 'Native entity query failed.'

        # Native writes intentionally refuse to load chunks. Pin the acceptance cell
        # before testing the write path so the test exercises a loaded-world write.
        $loadWriteChunk = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 13; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.command.execute'; input = @{ command = 'forceload add 0 0' }
                    } }
            })
        Assert ($loadWriteChunk.result.structuredContent.status -eq 'ok') 'Could not prepare a loaded chunk for native write acceptance.'

        $baseline = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 21; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.block.read'; input = @{
                            x = 0; y = 64; z = 0; dimension = 'minecraft:overworld'
                        }
                    } }
            })
        Assert ($baseline.result.structuredContent.status -eq 'ok') 'Prepared-cell baseline read failed.'
        Assert ($baseline.result.structuredContent.output.loaded -eq $true) 'Prepared acceptance cell is not loaded.'
        $baselineBlock = [string]$baseline.result.structuredContent.output.block

        $write = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 4; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.blocks.write'; input = @{
                            changes = @(@{ x = 0; y = 64; z = 0; block = 'minecraft:emerald_block' }); dryRun = $true
                        }
                    } }
            })
        Assert ($write.result.structuredContent.status -eq 'ok') 'Native dry-run world write failed.'
        Assert ($write.result.structuredContent.output.validated -eq $true) 'Native dry-run write was not validated.'

        $dryRunReadback = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 19; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.block.read'; input = @{
                            x = 0; y = 64; z = 0; dimension = 'minecraft:overworld'
                        }
                    } }
            })
        Assert ($dryRunReadback.result.structuredContent.status -eq 'ok') 'Dry-run verification read failed.'
        Assert ($dryRunReadback.result.structuredContent.output.block -eq $baselineBlock) `
            'Dry-run world write mutated the acceptance cell.'
        Start-Sleep -Milliseconds 1200

        $apply = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 14; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.blocks.write'; input = @{
                            changes = @(@{ x = 0; y = 64; z = 0; block = 'minecraft:diamond_block' })
                        }
                    } }
            })
        Assert ($apply.result.structuredContent.status -eq 'ok') ("Native applied world write failed: " + ($apply | ConvertTo-Json -Depth 20 -Compress))
        Assert ($apply.result.structuredContent.output.changedCount -eq 1) 'Native applied world write changed the wrong number of cells.'

        $readBack = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 15; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.block.read'; input = @{
                            x = 0; y = 64; z = 0; dimension = 'minecraft:overworld'
                        }
                    } }
            })
        Assert ($readBack.result.structuredContent.status -eq 'ok') 'Applied world write readback failed.'
        Assert ($readBack.result.structuredContent.output.block -eq 'minecraft:diamond_block') 'Applied world write did not persist through readback.'

        Start-Sleep -Milliseconds 1200
        $noOpWrite = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 20; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.blocks.write'; input = @{
                            changes = @(@{ x = 0; y = 64; z = 0; block = 'minecraft:diamond_block' })
                        }
                    } }
            })
        Assert ($noOpWrite.result.structuredContent.status -eq 'ok') 'Native no-op world write failed.'
        Assert ($noOpWrite.result.structuredContent.output.changedCount -eq 0) `
            'Native no-op world write reported a changed cell.'

        # A block entity cannot be transactionally restored without its NBT. Create one through
        # the same write API, then prove a later replacement is rejected and leaves it intact.
        Start-Sleep -Milliseconds 1200
        $createBlockEntity = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 16; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.blocks.write'; input = @{
                            changes = @(@{ x = 1; y = 100; z = 0; block = 'minecraft:chest' })
                        }
                    } }
            })
        Assert ($createBlockEntity.result.structuredContent.status -eq 'ok') 'Could not create the block-entity safety fixture.'
        Start-Sleep -Milliseconds 1200
        $protectedBlockEntity = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 17; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.blocks.write'; input = @{
                            changes = @(@{ x = 1; y = 100; z = 0; block = 'minecraft:stone' })
                        }
                    } }
            })
        Assert ($protectedBlockEntity.result.structuredContent.status -eq 'error') 'Block-entity replacement was not rejected.'
        Assert ($protectedBlockEntity.result.structuredContent.error.code -eq 'ADAPTER_FAILURE') 'Block-entity rejection was not structured.'
        $blockEntityReadback = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 18; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.world.block.read'; input = @{
                            x = 1; y = 100; z = 0; dimension = 'minecraft:overworld'
                        }
                    } }
            })
        Assert ($blockEntityReadback.result.structuredContent.output.block -eq 'minecraft:chest') 'Rejected block-entity write changed the world.'

        $chat = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 7; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.chat.send'; input = @{ message = 'Lodestone packaged matrix message' }
                    } }
            })
        Assert ($chat.result.structuredContent.status -eq 'ok') 'Native chat send failed.'

        $badTokenStatus = 0
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $uri -Method Post -Headers @{ 'X-Lodestone-Token' = 'invalid' } `
                -ContentType 'application/json' -Body '{}' | Out-Null
        } catch { $badTokenStatus = $_.Exception.Response.StatusCode.value__ }
        Assert ($badTokenStatus -eq 401) 'Invalid token was not rejected with HTTP 401.'

        $stop = Invoke-Mcp $uri $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 5; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.command.execute'; input = @{ command = 'stop' }
                    } }
            })
        Assert ($stop.result.structuredContent.status -eq 'ok') 'Command-driven stop failed.'

        $exitDeadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $exitDeadline -and (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
            Start-Sleep -Seconds 1
        }
        Assert (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) 'Server did not exit after stop.'
        $exitedCleanly = $process.WaitForExit(5000)
        Assert $exitedCleanly 'Server process handle did not report exit.'
        $exitCode = $process.ExitCode
        Assert ($null -ne $exitCode -and $exitCode -eq 0) "Server process exited with code $exitCode."
        Assert (-not (Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object LocalPort -in @($target.ServerPort, $target.Port))) 'Server or Lodestone port remained open after stop.'
        Assert-CleanShutdown $target.Directory $target.Name
        Write-Output "PASS $($target.Name)"
    } catch {
        Write-Output "FAIL $($target.Name): $($_.Exception.Message)"
        throw
    } finally {
        if ($process -and (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

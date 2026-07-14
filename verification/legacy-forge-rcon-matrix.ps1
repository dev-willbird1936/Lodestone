# SPDX-License-Identifier: MIT
<#
    Acceptance matrix for Java 8-era Forge servers. Lodestone runs as the
    external RCON MCP bridge because the modern Java 17 native host cannot be
    loaded by these historical Minecraft runtimes.
#>
[CmdletBinding()]
param(
    [string]$Root = (Join-Path $env:TEMP 'lodestone-legacy-matrix'),
    [string]$Java8 = (Join-Path $env:TEMP 'lodestone-toolchains\jdk8\jdk8u492-b09\bin\java.exe'),
    [string]$LauncherJava = 'java',
    [string]$LauncherDist = (Join-Path (Split-Path -Parent $PSScriptRoot) 'gateway/rcon-launcher/build/install/rcon-launcher'),
    [string[]]$Only = @()
)
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$targets = @(
    [pscustomobject]@{
        Name = 'Forge 1.12.2 / 14.23.5.2859 via RCON'
        Minecraft = '1.12.2'; Forge = '14.23.5.2859'; Installer = 'forge-1.12.2-14.23.5.2859-installer.jar'
        Directory = Join-Path $Root 'forge-1.12.2'; ServerPort = 25566; RconPort = 25576; McpPort = 37926
        Token = 'legacy-forge-1122-token'; Password = 'legacy-forge-1122-password'
        InstallerUrl = 'https://maven.minecraftforge.net/net/minecraftforge/forge/1.12.2-14.23.5.2859/forge-1.12.2-14.23.5.2859-installer.jar'
    },
    [pscustomobject]@{
        Name = 'Forge 1.8.9 / 11.15.1.2318 via RCON'
        Minecraft = '1.8.9'; Forge = '11.15.1.2318'; Installer = 'forge-1.8.9-11.15.1.2318-installer.jar'
        Directory = Join-Path $Root 'forge-1.8.9'; ServerPort = 25567; RconPort = 25577; McpPort = 37927
        Token = 'legacy-forge-189-token'; Password = 'legacy-forge-189-password'
        InstallerUrl = 'https://maven.minecraftforge.net/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-installer.jar'
    },
    [pscustomobject]@{
        Name = 'Forge 1.7.10 / 10.13.4.1614 via RCON'
        Minecraft = '1.7.10'; Forge = '10.13.4.1614'; Installer = 'forge-1.7.10-10.13.4.1614-1.7.10-installer.jar'
        Directory = Join-Path $Root 'forge-1.7.10'; ServerPort = 25568; RconPort = 25578; McpPort = 37928
        Token = 'legacy-forge-1710-token'; Password = 'legacy-forge-1710-password'
        InstallerUrl = 'https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-installer.jar'
    }
)

if ($Only.Count -gt 0) {
    $targets = @($targets | Where-Object Name -in $Only)
    if ($targets.Count -eq 0) { throw "No legacy targets matched -Only: $($Only -join ', ')" }
}

function Assert([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Read-ServerLog([string]$directory) {
    $path = Join-Path $directory 'logs/latest.log'
    if (Test-Path -LiteralPath $path) { return Get-Content -Raw -LiteralPath $path }
    return ''
}
function Log-Tail([object]$log, [int]$length = 3000) {
    $text = [string]$log
    return $text.Substring([Math]::Max(0, $text.Length - $length))
}
function Assert-CleanShutdown([string]$directory, [string]$name) {
    $crashDirectory = Join-Path $directory 'crash-reports'
    $crashes = if (Test-Path -LiteralPath $crashDirectory) { @(Get-ChildItem -LiteralPath $crashDirectory -File -ErrorAction SilentlyContinue) } else { @() }
    Assert ($crashes.Count -eq 0) "$name produced crash reports: $($crashes.Name -join ', ')"
    $text = Read-ServerLog $directory
    Assert ($text -notmatch '(?im)Encountered an unexpected exception|Exception in server tick loop|Crash report saved|Could not bind') "$name logged a fatal shutdown error."
    Assert ($text -match '(?im)Stopping (the )?server') "$name did not log a normal server stop."
}

function Invoke-Mcp([string]$uri, [string]$token, [object]$request) {
    $headers = @{ 'X-Lodestone-Token' = $token }
    $method = [string]$request.method
    if ($method -ne 'initialize' -and -not [string]::IsNullOrWhiteSpace($script:MainMcpSessionId)) {
        $headers['Mcp-Session-Id'] = $script:MainMcpSessionId
    }
    $headers['MCP-Protocol-Version'] = '2025-11-25'
    $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -Method Post -Headers $headers `
        -ContentType 'application/json' -Body ($request | ConvertTo-Json -Depth 20)
    $assigned = $response.Headers['Mcp-Session-Id']
    if (-not [string]::IsNullOrWhiteSpace($assigned)) { $script:MainMcpSessionId = $assigned }
    return $response.Content | ConvertFrom-Json
}

Assert (Test-Path -LiteralPath $Java8) "Java 8 executable not found: $Java8"
$dist = [IO.Path]::GetFullPath($LauncherDist)
Assert (Test-Path -LiteralPath (Join-Path $dist 'lib')) "RCON launcher distribution missing: $dist"
$launcherClasspath = Join-Path $dist 'lib\*'
Write-Output "RCON_LAUNCHER_DIST=$dist"

foreach ($target in $targets) {
    $server = $null
    $launcher = $null
    $oldEnvironment = @{}
    try {
        New-Item -ItemType Directory -Force -Path $target.Directory | Out-Null
        $installerPath = Join-Path $Root $target.Installer
        New-Item -ItemType Directory -Force -Path $Root | Out-Null
        if (-not (Test-Path -LiteralPath $installerPath)) {
            Invoke-WebRequest -Uri $target.InstallerUrl -OutFile $installerPath
        }
        $serverJar = Get-ChildItem -LiteralPath $target.Directory -Filter '*universal*.jar' -File -ErrorAction SilentlyContinue
        if ($null -eq $serverJar) {
            Push-Location $target.Directory
            try {
                & $Java8 -jar $installerPath --installServer | Out-Null
                $installerExitCode = $LASTEXITCODE
            } finally {
                Pop-Location
            }
            Assert ($installerExitCode -eq 0) "Forge installer failed for $($target.Name)."
            $serverJar = Get-ChildItem -LiteralPath $target.Directory -Filter '*universal*.jar' -File -ErrorAction SilentlyContinue
        }
        if ($null -eq $serverJar) {
            $serverJar = Get-ChildItem -LiteralPath $target.Directory -Filter '*forge*.jar' -File -ErrorAction SilentlyContinue |
                Where-Object Name -notmatch 'installer' | Sort-Object Length -Descending | Select-Object -First 1
        }
        Assert ($null -ne $serverJar) "Forge server JAR was not installed for $($target.Name)."

        Remove-Item -LiteralPath (Join-Path $target.Directory 'world'),(Join-Path $target.Directory 'logs'), `
            (Join-Path $target.Directory 'crash-reports'),(Join-Path $target.Directory 'server.properties') `
            -Recurse -Force -ErrorAction SilentlyContinue
        Set-Content -LiteralPath (Join-Path $target.Directory 'eula.txt') -Value 'eula=true' -Encoding ascii
        @(
            'enable-rcon=true'
            "rcon.port=$($target.RconPort)"
            "rcon.password=$($target.Password)"
            "server-port=$($target.ServerPort)"
            'online-mode=false'
            'enable-query=false'
            'broadcast-rcon-to-ops=false'
        ) | Set-Content -LiteralPath (Join-Path $target.Directory 'server.properties') -Encoding ascii

        $serverOut = Join-Path $target.Directory 'matrix.stdout.log'
        $serverErr = Join-Path $target.Directory 'matrix.stderr.log'
        Remove-Item -LiteralPath $serverOut,$serverErr -Force -ErrorAction SilentlyContinue
        $serverArgs = @('-Xmx1G','-Dfml.queryResult=confirm','-jar',$serverJar.Name,'nogui')
        $server = Start-Process -FilePath $Java8 -ArgumentList $serverArgs -WorkingDirectory $target.Directory `
            -WindowStyle Hidden -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr -PassThru

        $log = ''
        $deadline = (Get-Date).AddMinutes(4)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            $log = Read-ServerLog $target.Directory
            if ($log -match 'Done \(') { break }
            if (-not (Get-Process -Id $server.Id -ErrorAction SilentlyContinue) -and $log -notmatch 'Done \(') {
                throw "Server exited before Done. Tail: $(Log-Tail $log)"
            }
        }
        Assert ($log -match 'Done \(') "Server did not reach Done for $($target.Name)."
        $worldDirectory = Join-Path $target.Directory 'world'
        $worldRegionDirectory = Join-Path $worldDirectory 'region'
        $worldHasGeneratedData = (Test-Path -LiteralPath (Join-Path $worldDirectory 'level.dat')) -or
            (Test-Path -LiteralPath $worldRegionDirectory)
        Assert $worldHasGeneratedData 'World generated no level metadata or region data.'

        foreach ($name in @('LODESTONE_RCON_PASSWORD','LODESTONE_TOKEN','LODESTONE_RCON_HOST','LODESTONE_RCON_PORT','LODESTONE_MCP_PORT','LODESTONE_PERMISSIONS')) {
            $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        }
        $env:LODESTONE_RCON_PASSWORD = $target.Password
        $env:LODESTONE_TOKEN = $target.Token
        $env:LODESTONE_RCON_HOST = '127.0.0.1'
        $env:LODESTONE_RCON_PORT = [string]$target.RconPort
        $env:LODESTONE_MCP_PORT = [string]$target.McpPort
        $env:LODESTONE_PERMISSIONS = 'observe,modify-world,communicate,administer-server'
        $launcherOut = Join-Path $target.Directory 'launcher.stdout.log'
        $launcherErr = Join-Path $target.Directory 'launcher.stderr.log'
        $launcher = Start-Process -FilePath $LauncherJava -ArgumentList @('-cp',("`"$launcherClasspath`""),'dev.lodestone.rcon.launcher.LodestoneRconMain') `
            -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput $launcherOut -RedirectStandardError $launcherErr -PassThru

        $endpoint = "http://127.0.0.1:$($target.McpPort)/mcp"
        $httpDeadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $httpDeadline) {
            Start-Sleep -Milliseconds 500
            try { $null = Invoke-WebRequest -UseBasicParsing -Uri $endpoint -Method Post -Headers @{ 'X-Lodestone-Token' = $target.Token } -ContentType 'application/json' -Body '{}'; break } catch {}
            if (-not (Get-Process -Id $launcher.Id -ErrorAction SilentlyContinue)) { throw "RCON launcher exited early for $($target.Name)." }
        }

        $initialize = Invoke-Mcp $endpoint $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 1; method = 'initialize'
                params = @{ protocolVersion = '2025-11-25'; capabilities = @{}; clientInfo = @{ name = 'legacy-matrix'; version = '1' } }
            })
        Assert ($initialize.result.protocolVersion -eq '2025-11-25') 'RCON MCP negotiation failed.'
        $command = Invoke-Mcp $endpoint $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 2; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.command.rcon.execute'; input = @{ command = 'list' }
                    } }
            })
        Assert ($command.result.structuredContent.status -eq 'ok') "RCON command failed: $($command | ConvertTo-Json -Depth 20 -Compress)"
        Assert (-not [string]::IsNullOrWhiteSpace($command.result.structuredContent.output.text)) 'RCON command returned no output.'

        # The MCP gateway has a bounded mutation rate. This is a separate, intentional
        # dispatch after the read-only probes rather than a retry of an uncertain command.
        Start-Sleep -Seconds 7
        $setGameRule = Invoke-Mcp $endpoint $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 6; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.command.rcon.execute'; input = @{ command = 'gamerule doDaylightCycle false' }
                    } }
            })
        Assert ($setGameRule.result.structuredContent.status -eq 'ok') `
            "RCON gamerule mutation command failed: $($setGameRule | ConvertTo-Json -Depth 20 -Compress)"
        Start-Sleep -Seconds 7
        $readBack = Invoke-Mcp $endpoint $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 7; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.command.rcon.execute'; input = @{ command = 'gamerule doDaylightCycle' }
                    } }
            })
        Assert ($readBack.result.structuredContent.status -eq 'ok') `
            "RCON gamerule readback command failed: $($readBack | ConvertTo-Json -Depth 20 -Compress)"
        Assert ([string]$readBack.result.structuredContent.output.text -match '(?i)\bfalse\b') `
            "RCON gamerule mutation was not confirmed by readback: $($readBack.result.structuredContent.output.text)"
        Start-Sleep -Seconds 7
        $say = Invoke-Mcp $endpoint $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 4; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.command.rcon.execute'; input = @{ command = 'say Lodestone legacy matrix' }
                    } }
            })
        Assert ($say.result.structuredContent.status -eq 'ok') 'RCON chat command failed.'
        $badTokenStatus = 0
        try { Invoke-WebRequest -UseBasicParsing -Uri $endpoint -Method Post -Headers @{ 'X-Lodestone-Token' = 'invalid' } -ContentType 'application/json' -Body '{}' | Out-Null }
        catch { $badTokenStatus = $_.Exception.Response.StatusCode.value__ }
        Assert ($badTokenStatus -eq 401) 'Invalid RCON bridge token was not rejected.'

        Start-Sleep -Seconds 7
        $stop = Invoke-Mcp $endpoint $target.Token ([ordered]@{
                jsonrpc = '2.0'; id = 5; method = 'tools/call'
                params = @{ name = 'lodestone_capability_invoke'; arguments = @{
                        capability = 'minecraft.command.rcon.execute'; input = @{ command = 'stop' }
                    } }
            })
        Assert ($stop.result.structuredContent.status -eq 'ok') 'RCON stop command failed.'
        $exitDeadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $exitDeadline -and (Get-Process -Id $server.Id -ErrorAction SilentlyContinue)) { Start-Sleep -Seconds 1 }
        Assert (-not (Get-Process -Id $server.Id -ErrorAction SilentlyContinue)) 'Legacy server did not exit after RCON stop.'
        $worldHasDurableData = (Test-Path -LiteralPath (Join-Path $worldDirectory 'level.dat')) -or
            (Test-Path -LiteralPath $worldRegionDirectory)
        Assert $worldHasDurableData 'World data was not durable after the legacy server stopped.'
        Assert-CleanShutdown $target.Directory $target.Name
        Write-Output "PASS $($target.Name)"
    } catch {
        Write-Output "FAIL $($target.Name): $($_.Exception.Message)"
        throw
    } finally {
        if ($server -and (Get-Process -Id $server.Id -ErrorAction SilentlyContinue)) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
        if ($launcher -and (Get-Process -Id $launcher.Id -ErrorAction SilentlyContinue)) { Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue }
        foreach ($name in $oldEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process') }
    }
}

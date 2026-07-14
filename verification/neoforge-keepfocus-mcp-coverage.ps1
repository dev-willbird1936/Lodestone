[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $Token,
    [int] $Port = 37828,
    [string] $ReportPath = (Join-Path $PSScriptRoot 'evidence\neoforge-keepfocus-tool-coverage-2026-07-14.json'),
    [string] $LodestoneArtifact = (Join-Path $PSScriptRoot '..\hosts\neoforge\1.21.1\build\libs\lodestone-1.0.0.jar'),
    [string] $KeepFocusArtifact = (Join-Path $PSScriptRoot '..\..\KeepFocus\build\libs\keep_focus-1.0.0+1.21.1.jar')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$script:requestId = 0
$script:sessionId = $null
$script:client = [System.Net.Http.HttpClient]::new()
$uri = "http://127.0.0.1:$Port/mcp"
$report = [ordered]@{
    formatVersion = 1
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    endpoint = $uri
    tools = @()
    capabilities = @()
    records = @()
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$artifactRows = foreach ($artifactPath in @($LodestoneArtifact, $KeepFocusArtifact)) {
    $resolved = (Resolve-Path -LiteralPath $artifactPath -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved
    [ordered]@{ path = $resolved; bytes = [int64] $item.Length; sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$report.provenance = [ordered]@{
    sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
    sourceDirtyPaths = @(& git -C $repoRoot status --short | Where-Object { $_ -notmatch 'verification/evidence/' -and $_ -notmatch 'run-artifact-client/' })
    lodestoneArtifact = $artifactRows[0]
    keepFocusArtifact = $artifactRows[1]
}

trap {
    $report.failure = [ordered]@{ message = $_.Exception.Message; script = $_.ScriptStackTrace }
    $report.completedAtUtc = [DateTime]::UtcNow.ToString('o')
    $report | ConvertTo-Json -Depth 64 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    throw
}

function Invoke-Rpc {
    param([string] $Method, [hashtable] $Params = @{})

    $script:requestId++
    $body = @{ jsonrpc = '2.0'; id = $script:requestId; method = $Method; params = $Params } |
        ConvertTo-Json -Depth 64 -Compress
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
    $message.Headers.Add('X-Lodestone-Token', $Token)
    if ($script:sessionId) {
        $message.Headers.Add('Mcp-Session-Id', $script:sessionId)
        $message.Headers.Add('MCP-Protocol-Version', '2025-11-25')
    }
    $message.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
    $response = $script:client.SendAsync($message).Result
    if ($response.Headers.Contains('Mcp-Session-Id')) {
        $script:sessionId = ($response.Headers.GetValues('Mcp-Session-Id') -join '')
    }
    $content = $response.Content.ReadAsStringAsync().Result
    [PSCustomObject]@{
        HttpStatus = [int] $response.StatusCode
        Json = if ($content) { $content | ConvertFrom-Json } else { $null }
    }
}

function Convert-ResultSummary {
    param($Rpc)
    if ($Rpc.Json.error) {
        return [ordered]@{ kind = 'rpc-error'; code = $Rpc.Json.error.code; message = $Rpc.Json.error.message }
    }
    $content = @($Rpc.Json.result.content)
    if ($content.Count -eq 0) {
        return [ordered]@{ kind = 'empty' }
    }
    $first = $content[0]
    if ($first.type -ne 'text') {
        return [ordered]@{ kind = [string] $first.type }
    }
    try {
        $parsed = $first.text | ConvertFrom-Json
        if ($parsed.status) {
            return [ordered]@{
                kind = 'lodestone-envelope'
                status = [string] $parsed.status
                errorCode = if ($parsed.error) { [string] $parsed.error.code } else { $null }
                resultKeys = if ($parsed.result) { @($parsed.result.psobject.Properties.Name) } else { @() }
            }
        }
        return [ordered]@{ kind = 'json'; keys = @($parsed.psobject.Properties.Name) }
    } catch {
        return [ordered]@{ kind = 'text'; length = ([string] $first.text).Length }
    }
}

function Add-Record {
    param([string] $Scope, [string] $Name, [hashtable] $InvocationInput, $Rpc)
    $outcome = Convert-ResultSummary $Rpc
    $classification = if ($Scope -eq 'tool-schema' -or $Scope -eq 'capability-schema-dry-run') {
        if ($outcome.kind -eq 'rpc-error' -and [string] $outcome.message -match 'missing required field|must be an array') {
            'conditional-runtime-validation'
        } else {
            'schema-valid-generated-sample'
        }
    } elseif ($Scope -eq 'events') {
        'event-lifecycle'
    } else {
        'descriptor-or-runtime-observation'
    }
    $report.records += [ordered]@{
        scope = $Scope
        name = $Name
        input = $InvocationInput
        classification = $classification
        httpStatus = $Rpc.HttpStatus
        outcome = $outcome
    }
}

function Get-FirstSchema {
    param($Schema)
    if ($Schema.oneOf -and @($Schema.oneOf).Count -gt 0) { return $Schema.oneOf[0] }
    if ($Schema.anyOf -and @($Schema.anyOf).Count -gt 0) { return $Schema.anyOf[0] }
    return $Schema
}

function New-Sample {
    param($Schema)
    if ($null -eq $Schema) { return $null }
    if ($null -ne $Schema.const) { return $Schema.const }
    if ($Schema.enum -and @($Schema.enum).Count -gt 0) { return $Schema.enum[0] }
    if ($Schema.anyOf -and @($Schema.anyOf).Count -gt 0 -and $Schema.properties) {
        $branch = $Schema.anyOf[0]
        $result = [ordered]@{}
        foreach ($required in @($branch.required)) {
            if ($Schema.properties.$required) {
                $result[$required] = if ($required -eq 'uuid') {
                    '00000000-0000-0000-0000-000000000001'
                } else {
                    New-Sample $Schema.properties.$required
                }
            }
        }
        if ($result.Count -gt 0) { return $result }
    }
    $effective = Get-FirstSchema $Schema
    if ($effective -ne $Schema) { return New-Sample $effective }
    $type = [string] $Schema.type
    if (-not $type -and $Schema.properties) { $type = 'object' }
    switch ($type) {
        'object' {
            $result = [ordered]@{}
            foreach ($required in @($Schema.required)) {
                if ($Schema.properties.$required) {
                    $result[$required] = New-Sample $Schema.properties.$required
                }
            }
            return $result
        }
        'array' {
            if ([int] $Schema.minItems -gt 0) { return @(New-Sample $Schema.items) }
            return @()
        }
        'integer' {
            if ($null -ne $Schema.minimum) { return [int] $Schema.minimum }
            return 0
        }
        'number' {
            if ($null -ne $Schema.minimum) { return [double] $Schema.minimum }
            return 0.0
        }
        'boolean' { return $false }
        'string' {
            $pattern = [string] $Schema.pattern
            if ($pattern -match '0-9a-fA-F.{8}-.{4}-.{4}-.{4}') { return '00000000-0000-0000-0000-000000000000' }
            if ($pattern -match 'minecraft:overworld') { return 'minecraft:overworld' }
            if ($pattern -match 'in_world\|screen_open|screen_closed') { return 'in_world' }
            if ($pattern -match 'block-state' -or $pattern -match ':\[a-z') { return 'minecraft:stone' }
            if ($pattern -match '\[a-z0-9_.-\]') { return 'minecraft' }
            if ([int] $Schema.minLength -gt 0) { return ('x' * [Math]::Min([int] $Schema.minLength, 8)) }
            return 'x'
        }
        default { return $null }
    }
}

function Get-SchemaCases {
    param($Schema)
    $branches = if ($Schema.oneOf) { @($Schema.oneOf) } else { @($Schema) }
    $cases = @()
    foreach ($branch in $branches) {
        $base = [ordered]@{}
        $requiredNames = @(@($Schema.required) + @($branch.required) | Select-Object -Unique)
        foreach ($required in $requiredNames) {
            $definition = $null
            if ($branch.properties -and $branch.properties.$required) { $definition = $branch.properties.$required }
            elseif ($Schema.properties -and $Schema.properties.$required) { $definition = $Schema.properties.$required }
            if ($definition) { $base[$required] = New-Sample $definition }
        }
        if ($base.Count -eq 0) {
            $base = New-Sample $branch
            if ($null -eq $base) { $base = [ordered]@{} }
        }
        $cases += $base
        $properties = if ($branch.properties) { $branch.properties } else { $Schema.properties }
        if ($properties) {
            foreach ($property in @($properties.psobject.Properties)) {
                $name = $property.Name
                $definition = $property.Value
                if ($definition.enum) {
                    foreach ($value in @($definition.enum)) {
                        $case = [ordered]@{} + $base
                        $case[$name] = $value
                        $cases += $case
                    }
                }
                if ($name -notin $requiredNames -and $null -eq $definition.const) {
                    $case = [ordered]@{} + $base
                    $case[$name] = New-Sample $definition
                    $cases += $case
                }
            }
        }
    }
    $unique = @{}
    foreach ($case in $cases) {
        $key = $case | ConvertTo-Json -Depth 32 -Compress
        $unique[$key] = $case
    }
    return @($unique.Values)
}

function Invoke-Tool {
    param([string] $Name, [hashtable] $Arguments)
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $rpc = Invoke-Rpc 'tools/call' @{ name = $Name; arguments = $Arguments }
        $payload = Get-ToolPayload $rpc
        if ($null -eq $payload -or $payload.error.code -ne 'RATE_LIMIT_EXCEEDED') { return $rpc }
        Start-Sleep -Milliseconds 700
    }
    return $rpc
}

function Get-ToolPayload {
    param($Rpc)
    if ($Rpc.Json.result.structuredContent) { return $Rpc.Json.result.structuredContent }
    $content = @($Rpc.Json.result.content)
    if ($content.Count -gt 0 -and $content[0].type -eq 'text') {
        return $content[0].text | ConvertFrom-Json
    }
    return $null
}

$init = Invoke-Rpc 'initialize' @{ protocolVersion = '2025-11-25'; clientInfo = @{ name = 'lodestone-neoforge-keepfocus-coverage'; version = '1.0' } }
if ($init.HttpStatus -ne 200 -or -not $script:sessionId) { throw 'MCP initialize failed' }
$null = Invoke-Rpc 'notifications/initialized' @{}

$toolsRpc = Invoke-Rpc 'tools/list' @{}
if ($toolsRpc.Json.error) { throw 'tools/list failed' }
$tools = @($toolsRpc.Json.result.tools)
$report.tools = @($tools | ForEach-Object name)

$capabilitiesRpc = Invoke-Tool 'lodestone_capabilities_list' @{}
$capabilities = (Get-ToolPayload $capabilitiesRpc).capabilities
$report.capabilities = @($capabilities | ForEach-Object {
    [ordered]@{ id = $_.id; version = $_.version; availability = $_.availability }
})

foreach ($tool in $tools) {
    $name = [string] $tool.name
    if ($name -in @('lodestone_capability_get', 'lodestone_capability_invoke', 'lodestone_events_poll',
            'lodestone_events_unsubscribe', 'ui_navigate')) { continue }
    foreach ($arguments in Get-SchemaCases $tool.inputSchema) {
        $rpc = Invoke-Tool $name $arguments
        Add-Record 'tool-schema' $name $arguments $rpc
        Start-Sleep -Milliseconds 120
    }
}

foreach ($capability in $capabilities) {
    $get = Invoke-Tool 'lodestone_capability_get' @{ capability = $capability.id }
    Add-Record 'capability-descriptor' $capability.id @{ capability = $capability.id } $get
    if ($capability.id -eq 'lodestone.ui.navigate') { continue }
    foreach ($input in Get-SchemaCases $capability.inputSchema) {
        $invoke = Invoke-Tool 'lodestone_capability_invoke' @{
            capability = $capability.id
            capabilityVersion = $capability.version
            input = $input
            dryRun = $true
        }
        Add-Record 'capability-schema-dry-run' $capability.id $input $invoke
        Start-Sleep -Milliseconds 120
    }
}

$subscribe = Invoke-Tool 'lodestone_events_subscribe' @{ eventPrefix = 'minecraft.'; bufferLimit = 1 }
Add-Record 'events' 'lodestone_events_subscribe' @{ eventPrefix = 'minecraft.'; bufferLimit = 1 } $subscribe
$subscriptionId = $null
try { $subscriptionId = (Get-ToolPayload $subscribe).id } catch { }
if ($subscriptionId) {
    $poll = Invoke-Tool 'lodestone_events_poll' @{ subscriptionId = $subscriptionId; maxEvents = 1 }
    Add-Record 'events' 'lodestone_events_poll' @{ subscriptionId = $subscriptionId; maxEvents = 1 } $poll
    $unsubscribe = Invoke-Tool 'lodestone_events_unsubscribe' @{ subscriptionId = $subscriptionId }
    Add-Record 'events' 'lodestone_events_unsubscribe' @{ subscriptionId = $subscriptionId } $unsubscribe
}

$report.completedAtUtc = [DateTime]::UtcNow.ToString('o')
$report | ConvertTo-Json -Depth 64 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
[PSCustomObject]@{
    Session = $script:sessionId
    ToolCount = $report.tools.Count
    CapabilityCount = $report.capabilities.Count
    RecordCount = $report.records.Count
    OutcomeCounts = @($report.records | Group-Object { $_.outcome.kind + ':' + $_.outcome.status + ':' + $_.outcome.errorCode } |
        ForEach-Object { [PSCustomObject]@{ outcome = $_.Name; count = $_.Count } })
    ReportPath = $ReportPath
} | ConvertTo-Json -Depth 16

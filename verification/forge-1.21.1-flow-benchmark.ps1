[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Token,
    [int] $Port = 37831,
    [ValidateSet('main-menu', 'world', 'shutdown')] [string] $Stage = 'main-menu',
    [string] $EvidenceDirectory = (Join-Path $PSScriptRoot 'evidence'),
    [string] $LodestoneArtifact = (Join-Path $PSScriptRoot '..\hosts\forge\1.21.1\build\libs\lodestone-1.0.0.jar'),
    [string] $ClientRunDirectory = (Join-Path $PSScriptRoot '..\hosts\forge\1.21.1\run-artifact-client')
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'neoforge-keepfocus-flow-benchmark.ps1') `
    -Token $Token `
    -Port $Port `
    -Stage $Stage `
    -EvidenceDirectory $EvidenceDirectory `
    -LodestoneArtifact $LodestoneArtifact `
    -KeepFocusArtifact '' `
    -ClientRunDirectory $ClientRunDirectory `
    -BenchmarkName 'forge-1.21.1-client-flow' `
    -ReportPrefix 'forge-1.21.1-flow' `
    -ChatMarker '[Lodestone] Forge 1.21.1 flow benchmark marker' `
    -MenuTargets @('options', 'language', 'accessibility') `
    -DirectCreateWorld `
    -ExpectedMenuUnavailableTools @('search_minecraft_item', 'capture_screenshot') `
    -ExpectedMenuUnavailableCapabilities @('minecraft.ui.text.insert', 'minecraft.input.key.set', 'minecraft.input.mouse.set', 'minecraft.input.release-all') `
    -MinimalWorld

[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Token,
    [int] $Port = 37831,
    [ValidateSet('main-menu', 'world', 'shutdown')] [string] $Stage = 'main-menu',
    [string] $EvidenceDirectory = (Join-Path $PSScriptRoot 'evidence'),
    [string] $LodestoneArtifact = (Join-Path $PSScriptRoot '..\hosts\fabric\1.18.2\build\libs\lodestone-1.0.0.jar'),
    [string] $ClientRunDirectory = (Join-Path $PSScriptRoot '..\hosts\fabric\1.18.2\run')
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
    -BenchmarkName 'fabric-1.18.2-client-flow' `
    -ReportPrefix 'fabric-1.18.2-flow' `
    -ChatMarker '[Lodestone] Fabric 1.18.2 flow benchmark marker' `
    -MenuTargets @('options', 'language', 'accessibility', 'singleplayer') `
    -DirectCreateWorld `
    -ExpectedWorldUnavailableCapabilities @('minecraft.chat.read')

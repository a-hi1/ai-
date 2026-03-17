param(
  [ValidateSet('start', 'stop', 'restart', 'status')]
  [string]$Action = 'start',

  [switch]$SkipBuild,

  [switch]$Force,

  [int]$StartupTimeoutSec = 120
)

$nodes = @(
  @{ ServiceName = 'product-service'; ServerType = 'product'; Port = 8081 },
  @{ ServiceName = 'order-service'; ServerType = 'order'; Port = 8082 },
  @{ ServiceName = 'user-service'; ServerType = 'user'; Port = 8083 }
)

for ($index = 0; $index -lt $nodes.Count; $index++) {
  $node = $nodes[$index]
  $forward = @{
    Action = $Action
    ServiceName = [string]$node.ServiceName
    ServerType = [string]$node.ServerType
    Port = [int]$node.Port
    StartupTimeoutSec = $StartupTimeoutSec
  }

  $shouldSkipBuild = $SkipBuild
  if (-not $shouldSkipBuild -and ($Action -eq 'start' -or $Action -eq 'restart') -and $index -gt 0) {
    # Domain services share the same backend target jar. Build once to avoid clean step file-lock failures.
    $shouldSkipBuild = $true
  }

  if ($shouldSkipBuild) {
    $forward.SkipBuild = $true
  }
  if ($Force) {
    $forward.Force = $true
  }

  & (Join-Path $PSScriptRoot 'start-monitor-node-instance.ps1') @forward
}
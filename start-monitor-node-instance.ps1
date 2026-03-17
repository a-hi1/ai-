param(
  [ValidateSet('start', 'stop', 'restart', 'status')]
  [string]$Action = 'start',

  [string]$ServiceName,

  [string]$ServerType,

  [int]$Port,

  [string]$AdvertiseHost = '127.0.0.1',

  [string]$RuntimeName = '',

  [string]$ServiceId = '',

  [string]$HealthPath = '',

  [switch]$SkipBuild,

  [switch]$Force,

  [int]$StartupTimeoutSec = 120,

  [string]$MonitorOverviewUrl = 'http://127.0.0.1:9091/api/monitor/overview',

  [string]$MonitorFrontUrl = 'http://127.0.0.1:5176/#/',

  [int]$VerifyTimeoutSec = 90,

  [int]$VerifyIntervalSec = 3
)

$ErrorActionPreference = 'Stop'

$repoRoot = 'D:\zhuomian\workspace'
$backendScript = Join-Path $repoRoot 'start-ecommerce-backend.ps1'

function Get-EnvironmentValue {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Name,

    [string]$Default = ''
  )

  foreach ($scope in @('Process', 'User', 'Machine')) {
    $value = [Environment]::GetEnvironmentVariable($Name, $scope)
    if (-not [string]::IsNullOrWhiteSpace($value)) {
      return $value
    }
  }

  return $Default
}

function Resolve-HealthPath {
  param(
    [string]$InputPath,
    [string]$Type
  )

  if (-not [string]::IsNullOrWhiteSpace($InputPath)) {
    return $InputPath
  }

  switch ($Type.Trim().ToLowerInvariant()) {
    'product' { return '/api/products/health' }
    'order' { return '/api/orders/health' }
    'user' { return '/api/users/health' }
    'backend' { return '/api/products/health' }
    default { return '/api/products/health' }
  }
}

function Wait-NodeVisibleInOverview {
  param(
    [string]$OverviewUrl,
    [string]$ExpectedServiceId,
    [string]$ExpectedServiceName,
    [string]$ExpectedServerType,
    [int]$ExpectedPort,
    [int]$TimeoutSec,
    [int]$IntervalSec
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  $lastServices = @()

  while ((Get-Date) -lt $deadline) {
    try {
      $overview = Invoke-RestMethod -Uri $OverviewUrl -TimeoutSec 6
      $services = @($overview.services)
      $lastServices = $services

      $matched = $services | Where-Object {
        $sameId = ([string]$_.serverId) -eq $ExpectedServiceId
        $sameName = ([string]$_.serviceName) -eq $ExpectedServiceName
        $sameType = ([string]$_.serverType) -eq $ExpectedServerType
        $samePort = ([int]$_.port) -eq $ExpectedPort
        $sameId -or ($sameName -and $sameType -and $samePort)
      } | Select-Object -First 1

      if ($matched) {
        return [pscustomobject]@{
          Success = $true
          Match = $matched
          Services = $services
        }
      }
    } catch {
      # monitor-server may still be starting; keep retrying
    }

    Start-Sleep -Seconds $IntervalSec
  }

  return [pscustomobject]@{
    Success = $false
    Match = $null
    Services = $lastServices
  }
}

if (-not (Test-Path $backendScript)) {
  throw "Required script not found: $backendScript"
}

# When invoked by monitor agent restart command, no arguments are passed.
# In that case derive required identity from node runtime environment.
if ([string]::IsNullOrWhiteSpace($ServiceName)) {
  $ServiceName = Get-EnvironmentValue -Name 'MONITOR_SERVICE_NAME'
}
if ([string]::IsNullOrWhiteSpace($ServerType)) {
  $ServerType = Get-EnvironmentValue -Name 'MONITOR_SERVER_TYPE' -Default 'product'
}
if (-not $Port -or $Port -le 0) {
  $portText = Get-EnvironmentValue -Name 'MONITOR_ADVERTISE_PORT'
  if ([string]::IsNullOrWhiteSpace($portText)) {
    $portText = Get-EnvironmentValue -Name 'SERVER_PORT'
  }
  if (-not [string]::IsNullOrWhiteSpace($portText)) {
    $parsed = 0
    if ([int]::TryParse($portText, [ref]$parsed)) {
      $Port = $parsed
    }
  }
}
if ([string]::IsNullOrWhiteSpace($AdvertiseHost) -or $AdvertiseHost -eq '127.0.0.1') {
  $envHost = Get-EnvironmentValue -Name 'MONITOR_ADVERTISE_HOST'
  if (-not [string]::IsNullOrWhiteSpace($envHost)) {
    $AdvertiseHost = $envHost
  }
}

if ((-not $PSBoundParameters.ContainsKey('Action')) -and -not [string]::IsNullOrWhiteSpace($ServiceName)) {
  $Action = 'restart'
}

if ([string]::IsNullOrWhiteSpace($ServiceName)) {
  throw 'ServiceName is required. Provide -ServiceName or set MONITOR_SERVICE_NAME in environment.'
}
if ([string]::IsNullOrWhiteSpace($ServerType)) {
  throw 'ServerType is required. Provide -ServerType or set MONITOR_SERVER_TYPE in environment.'
}

if ($Port -lt 1 -or $Port -gt 65535) {
  throw 'Port must be between 1 and 65535.'
}

$resolvedRuntimeName = if ([string]::IsNullOrWhiteSpace($RuntimeName)) {
  "$ServiceName-$Port"
} else {
  $RuntimeName
}
$resolvedServiceId = if ([string]::IsNullOrWhiteSpace($ServiceId)) {
  "$ServiceName@$AdvertiseHost`:$Port"
} else {
  $ServiceId
}
$resolvedHealthPath = Resolve-HealthPath -InputPath $HealthPath -Type $ServerType

$forward = @{
  Action = $Action
  ServiceName = $ServiceName
  ServerType = $ServerType
  AppPort = $Port
  RuntimeName = $resolvedRuntimeName
  AdvertiseHost = $AdvertiseHost
  ServiceId = $resolvedServiceId
  HealthPath = $resolvedHealthPath
  RestartScriptPath = $PSCommandPath
  StartupTimeoutSec = $StartupTimeoutSec
}

if ($SkipBuild) {
  $forward.SkipBuild = $true
}
if ($Force) {
  $forward.Force = $true
}

& $backendScript @forward

if ($Action -in @('start', 'restart')) {
  Write-Host ''
  Write-Host 'Verifying node visibility in monitor overview...'

  $verify = Wait-NodeVisibleInOverview `
    -OverviewUrl $MonitorOverviewUrl `
    -ExpectedServiceId $resolvedServiceId `
    -ExpectedServiceName $ServiceName `
    -ExpectedServerType $ServerType `
    -ExpectedPort $Port `
    -TimeoutSec $VerifyTimeoutSec `
    -IntervalSec $VerifyIntervalSec

  if ($verify.Success) {
    $node = $verify.Match
    $status = [string]$node.status
    $heartbeat = [string]$node.lastHeartbeat

    Write-Host 'Node registration check: PASS'
    Write-Host ("- serviceId:   {0}" -f [string]$node.serverId)
    Write-Host ("- serviceName: {0}" -f [string]$node.serviceName)
    Write-Host ("- serverType:  {0}" -f [string]$node.serverType)
    Write-Host ("- endpoint:    {0}:{1}" -f [string]$node.host, [string]$node.port)
    Write-Host ("- status:      {0}" -f $status)
    Write-Host ("- heartbeat:   {0}" -f $heartbeat)
    Write-Host ("- monitor ui:  {0}" -f $MonitorFrontUrl)
  } else {
    $servicePreview = @($verify.Services | Select-Object -First 8 | ForEach-Object {
      "{0} [{1}] {2}:{3}" -f [string]$_.serviceName, [string]$_.serverType, [string]$_.host, [string]$_.port
    }) -join '; '

    Write-Warning 'Node process started, but monitor overview did not include it within the verify window.'
    Write-Warning ("Overview endpoint: {0}" -f $MonitorOverviewUrl)
    if (-not [string]::IsNullOrWhiteSpace($servicePreview)) {
      Write-Host ("Current nodes(sample): {0}" -f $servicePreview)
    }
    exit 2
  }
}

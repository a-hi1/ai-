param(
  [ValidateSet('domain', 'single', 'none')]
  [string]$NodeMode = 'domain',

  [switch]$ForceBuildNodes
)

$ErrorActionPreference = 'Stop'

$repoRoot = 'D:\zhuomian\workspace'
$monitorServerHealthUrl = 'http://127.0.0.1:9091/health'
$monitorOverviewUrl = 'http://127.0.0.1:9091/api/monitor/overview'
$monitorFrontUrl = 'http://127.0.0.1:5176/#/'

function Wait-HttpReady {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Url,
    [int]$MaxAttempts = 30,
    [int]$DelaySeconds = 2
  )

  for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    try {
      $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
      if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
        return $true
      }
    } catch {
      Start-Sleep -Seconds $DelaySeconds
      continue
    }
    Start-Sleep -Seconds $DelaySeconds
  }

  return $false
}

function Get-ListeningProcessIds {
  param(
    [Parameter(Mandatory = $true)]
    [int]$Port
  )

  $connections = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -eq $Port }

  if ($connections) {
    return @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
  }

  $netstatLines = netstat -ano -p tcp | Select-String 'LISTENING'
  $pids = foreach ($line in $netstatLines) {
    $text = ($line.ToString() -replace '^\s+', '')
    $parts = $text -split '\s+'
    if ($parts.Count -lt 5) {
      continue
    }
    $localAddress = $parts[1]
    if ($localAddress.EndsWith(":$Port") -or $localAddress.EndsWith("]:$Port")) {
      [int]$parts[-1]
    }
  }

  return @($pids | Select-Object -Unique)
}

function Stop-PortListeners {
  param(
    [Parameter(Mandatory = $true)]
    [int]$Port,

    [string]$Reason = 'port recovery'
  )

  $pids = Get-ListeningProcessIds -Port $Port
  foreach ($pidValue in $pids) {
    try {
      Stop-Process -Id $pidValue -Force -ErrorAction SilentlyContinue
      Write-Warning ("Stopped PID {0} on port {1} ({2})." -f $pidValue, $Port, $Reason)
    } catch {
      Write-Warning ("Failed to stop PID {0} on port {1}: {2}" -f $pidValue, $Port, $_.Exception.Message)
    }
  }
}

Push-Location $repoRoot
try {
  & "$repoRoot\init-monitor-storage.ps1"

  Start-Process powershell -ArgumentList '-NoExit', '-ExecutionPolicy', 'Bypass', '-File', "$repoRoot\start-monitor-server.ps1" | Out-Null

  $monitorFrontDir = Join-Path $repoRoot 'monitor-front'
  if (Test-Path $monitorFrontDir) {
    $frontAlreadyReady = Wait-HttpReady -Url $monitorFrontUrl -MaxAttempts 1 -DelaySeconds 1
    if (-not $frontAlreadyReady) {
      $frontPort = 5176
      $frontPortListeners = Get-ListeningProcessIds -Port $frontPort
      if ($frontPortListeners.Count -gt 0) {
        Stop-PortListeners -Port $frontPort -Reason 'monitor-front not reachable before startup'
      }
    }

    Start-Process powershell -ArgumentList '-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', "Set-Location '$monitorFrontDir'; npm run dev:fixed" | Out-Null
  }

  if ($NodeMode -eq 'domain' -and (Test-Path "$repoRoot\start-domain-services.ps1")) {
    $domainArgs = @(
      '-ExecutionPolicy', 'Bypass',
      '-File', "$repoRoot\start-domain-services.ps1",
      '-Action', 'start'
    )
    if (-not $ForceBuildNodes) {
      $domainArgs += '-SkipBuild'
    }
    Start-Process powershell -ArgumentList $domainArgs | Out-Null
  } elseif ($NodeMode -eq 'single' -and (Test-Path "$repoRoot\start-ecommerce-backend.ps1")) {
    $singleArgs = @(
      '-ExecutionPolicy', 'Bypass',
      '-File', "$repoRoot\start-ecommerce-backend.ps1"
    )
    if (-not $ForceBuildNodes) {
      $singleArgs += '-SkipBuild'
    }
    Start-Process powershell -ArgumentList $singleArgs | Out-Null
  }

  $serverReady = Wait-HttpReady -Url $monitorServerHealthUrl -MaxAttempts 40 -DelaySeconds 2
  $frontReady = Wait-HttpReady -Url $monitorFrontUrl -MaxAttempts 30 -DelaySeconds 2

  $serverStatusText = if ($serverReady) { 'READY' } else { 'NOT_READY' }
  $frontStatusText = if ($frontReady) { 'READY' } else { 'NOT_READY' }

  Write-Host ''
  Write-Host 'Monitor module startup result'
  Write-Host "- node mode:      $NodeMode"
  Write-Host "- monitor-server: $serverStatusText"
  Write-Host "- monitor-front:  $frontStatusText"
  Write-Host "- front url:      $monitorFrontUrl"
  Write-Host "- health url:     $monitorServerHealthUrl"
  Write-Host "- overview url:   $monitorOverviewUrl"

  if ($serverReady) {
    try {
      $overview = Invoke-RestMethod -Uri $monitorOverviewUrl -TimeoutSec 5
      $serviceCount = @($overview.services).Count
      $alertCount = @($overview.alerts).Count
      $serviceNames = @($overview.services | ForEach-Object {
        if ([string]::IsNullOrWhiteSpace([string]$_.serviceName)) { [string]$_.serverId } else { [string]$_.serviceName }
      } | Select-Object -First 8) -join ', '
      $onlineByType = @($overview.services |
        Where-Object { [string]$_.status -eq 'ONLINE' } |
        Group-Object -Property serverType |
        ForEach-Object { "{0}:{1}" -f ([string]$_.Name), ([int]$_.Count) }) -join ', '
      if (-not $serviceNames) {
        $serviceNames = 'NO_SERVICE_REPORTED'
      }
      if (-not $onlineByType) {
        $onlineByType = 'NO_ONLINE_NODE'
      }
      Write-Host "- service count:  $serviceCount"
      Write-Host "- alert count:    $alertCount"
      Write-Host "- service names:  $serviceNames"
      Write-Host "- online by type: $onlineByType"
    } catch {
      Write-Warning "monitor-server is up, but overview request failed: $($_.Exception.Message)"
    }
  }

  if (-not $serverReady) {
    Write-Warning 'monitor-server did not pass health check within the wait window.'
  }

  if (-not $frontReady) {
    Write-Warning 'monitor-front did not respond within the wait window.'
  }
} finally {
  Pop-Location
}
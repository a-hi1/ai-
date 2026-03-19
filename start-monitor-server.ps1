$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceRoot = $scriptRoot
$monitorPom = Join-Path $workspaceRoot 'monitor-server\pom.xml'
$tcpPort = '9999'
$httpPort = '9091'
$controlToken = 'monitor-dev-token'
$env:MONITOR_PG_HOST = if ($env:MONITOR_PG_HOST) { $env:MONITOR_PG_HOST } else { '127.0.0.1' }
$env:MONITOR_PG_PORT = if ($env:MONITOR_PG_PORT) { $env:MONITOR_PG_PORT } else { '5432' }
$env:MONITOR_PG_DATABASE = if ($env:MONITOR_PG_DATABASE) { $env:MONITOR_PG_DATABASE } else { 'ecommerce' }
$env:MONITOR_PG_USERNAME = if ($env:MONITOR_PG_USERNAME) { $env:MONITOR_PG_USERNAME } else { 'postgres' }
$env:MONITOR_PG_PASSWORD = if ($env:MONITOR_PG_PASSWORD) { $env:MONITOR_PG_PASSWORD } else { '123456' }
$env:MONITOR_DB_URL = if ($env:MONITOR_DB_URL) { $env:MONITOR_DB_URL } else { "jdbc:postgresql://$($env:MONITOR_PG_HOST):$($env:MONITOR_PG_PORT)/$($env:MONITOR_PG_DATABASE)" }
$env:MONITOR_DB_DATABASE = if ($env:MONITOR_DB_DATABASE) { $env:MONITOR_DB_DATABASE } else { $env:MONITOR_PG_DATABASE }
$env:MONITOR_DB_USERNAME = if ($env:MONITOR_DB_USERNAME) { $env:MONITOR_DB_USERNAME } else { $env:MONITOR_PG_USERNAME }
$env:MONITOR_DB_PASSWORD = if ($env:MONITOR_DB_PASSWORD) { $env:MONITOR_DB_PASSWORD } else { $env:MONITOR_PG_PASSWORD }
$env:MONITOR_LOCAL_RESTART_SCRIPT = if ($env:MONITOR_LOCAL_RESTART_SCRIPT) { $env:MONITOR_LOCAL_RESTART_SCRIPT } else { Join-Path $workspaceRoot 'start-ecommerce-backend.ps1' }

if (-not (Test-Path $monitorPom)) {
  Write-Error "找不到监控后端 POM 文件: $monitorPom"
  exit 1
}

$existingPids = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object { $_.LocalPort -in 9999, 9091 } |
  Select-Object -ExpandProperty OwningProcess -Unique

foreach ($pidValue in $existingPids) {
  Stop-Process -Id $pidValue -Force -ErrorAction SilentlyContinue
}

mvn -f $monitorPom org.codehaus.mojo:exec-maven-plugin:3.6.3:java "-Dexec.mainClass=com.example.monitor.MonitorServer" "-Dexec.args=$tcpPort $httpPort $controlToken"
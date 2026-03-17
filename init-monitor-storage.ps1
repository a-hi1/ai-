$ErrorActionPreference = 'Stop'

$repoRoot = 'D:\zhuomian\workspace'
$sqlFile = Join-Path $repoRoot 'monitor-server\sql\init-monitor-postgres.sql'

$pgHost = if ($env:MONITOR_PG_HOST) { $env:MONITOR_PG_HOST } else { '127.0.0.1' }
$pgPort = if ($env:MONITOR_PG_PORT) { $env:MONITOR_PG_PORT } else { '5432' }
$pgDatabase = if ($env:MONITOR_PG_DATABASE) { $env:MONITOR_PG_DATABASE } elseif ($env:MONITOR_DB_DATABASE) { $env:MONITOR_DB_DATABASE } else { 'ecommerce' }
$pgUsername = if ($env:MONITOR_PG_USERNAME) { $env:MONITOR_PG_USERNAME } elseif ($env:MONITOR_DB_USERNAME) { $env:MONITOR_DB_USERNAME } else { 'postgres' }
$pgPassword = if ($env:MONITOR_PG_PASSWORD) { $env:MONITOR_PG_PASSWORD } elseif ($env:MONITOR_DB_PASSWORD) { $env:MONITOR_DB_PASSWORD } else { '123456' }

$env:PGPASSWORD = $pgPassword

$psql = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psql) {
  Write-Warning 'psql was not found. PostgreSQL auto-init was skipped. Run monitor-server/sql/init-monitor-postgres.sql manually if needed.'
} else {
  & $psql.Source -h $pgHost -p $pgPort -U $pgUsername -d $pgDatabase -v ON_ERROR_STOP=1 -f $sqlFile
  Write-Host "PostgreSQL monitor tables initialized for database: $pgDatabase"
}
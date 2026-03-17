param(
  [ValidateSet('start', 'stop', 'restart', 'status')]
  [string]$Action = 'start',

  [switch]$SkipBuild,

  [switch]$Force,

  [int]$StartupTimeoutSec = 120,

  [string]$ServiceName = 'ecommerce-backend',

  [string]$ServerType = 'product',

  [int]$AppPort = 8080,

  [string]$AdvertiseHost = '127.0.0.1',

  [string]$ServiceId = '',

  [string]$RuntimeName = '',

  [string]$HealthPath = '/api/products/health',

  [string]$RestartScriptPath = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = 'D:\zhuomian\workspace'
$backendDir = Join-Path $repoRoot 'ecommerce-backend'
$backendPom = Join-Path $backendDir 'pom.xml'
$runtimeFolderName = if ([string]::IsNullOrWhiteSpace($RuntimeName)) { $ServiceName } else { $RuntimeName }
$runtimeFolderName = [Regex]::Replace($runtimeFolderName, '[^a-zA-Z0-9._-]', '-')
$runtimeDir = Join-Path $repoRoot (".runtime\" + $runtimeFolderName)
$pidFile = Join-Path $runtimeDir 'backend-process.json'
$stdoutLog = Join-Path $runtimeDir 'backend.stdout.log'
$stderrLog = Join-Path $runtimeDir 'backend.stderr.log'

$dbHost = '127.0.0.1'
$dbPort = '5432'
$dbName = 'ecommerce'
$dbUser = 'postgres'
$dbPassword = '123456'
$dbInitMode = 'never'

$paymentFrontendBaseUrl = 'http://127.0.0.1:5173'
$paymentAlipayMode = 'demo'
$paymentAlipayGateway = 'https://openapi-sandbox.dl.alipaydev.com/gateway.do'
$paymentAlipayAppId = '9021000162605948'
$paymentAlipayPrivateKey = 'MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCaKktsYpeK/SGYnhvwAu9rWlTAEKdJcwR+aS2bL4NXU7jOCoIIPnw87rEMudkrc6YyqEGEH0Ra+m5JgU4btek6h9J1/vEopq/UtqL+HW/9HT1shoTXwuvQTi+HQ7/eHDonNQsVqc5fQqEDQNU8LvxFpxneJLZFkb8RcJma4MplNOH945R782k+W1nx+OX+VCQQH9YOaW3SQzeie0VA24p1aGq9P39eVeNpdRUcDilKwzR/GKDI6bPZ0y5trDE4DPkKfE7EksfA5mMlmjVmtMExU1iGgHFBFu8bVHwjS51MsnMScW2eoSFGM9r1ahsojLacBKnv5mqexj76PqqxbeH/AgMBAAECggEBAJG1/hnQSfnFbRaqAi/lJlPqSgZK90KV4p50XPW7f8FvUVH1XpfaL2UMGe20Tw4hvelNXLc7MklnQAxbme7ZDjsTGxoJv/gzLCe6A+549ltfYqoLrs2b33TAIW4Q2+1b8p8SJIryNyuUd0jy41iB9FEFMo7jViAFNHmQWq6nXsUuMgqRU+sMJRLFpd8OKKJL8ajArjjKmSQgxTuR4+cWV2x1tS5P1CpZYJ52FyK3zd4U6mNBSX8fo5XSMqNplMc3U5z5FBtm9mVdiFCx55VM8U072F8+7dr9d/FYjLWl4dSEy7Am1af/5KbNlNQ0N3dzQhSI3bKqqjDQc4ndlErGS6kCgYEAyEsFADDtxLrW7yxPd5O253bfRsRHiNMcRDqNFcS5r+Jtr0/Ue1WFSr26ln8DYH35sm8JGfcLwSv7qYp/vNaSdmoyHY97wssQPsePfjBV1el+zBAGhTVUbZQxphQMM1BiJsjAYR1ZvX9/q/jDCHs1iTt5DaqWWU1dg4AYu8ilcAsCgYEAxQryY9rOoDllZLTTFmCC3HU0R/vftA+a9kfxGODed0NUuM6fSB4zMpYefocG2xNn3jMK0xKufvly5BjYiFSu0OrRhX6XkE0vJvUrm5RVKcByRHbgOtniKNvWIF8Gg+z8jbcGBDtUEymUtI17v/W5XfSN/40zrghzF4Wy7rJzSl0CgYAXOVOgHQxExmFLpDimpdMMbaUgAHxG6iuZ4awgPQpG0ZtkO35oupbU/sDW2G1cz96XCPbmMtYDFQV+OBftnnenEVM0SEHSaVc94EfMGvCo+AUCxkzrPy7F8ev71NFBVe27j4V2/T89kL4OA68hmZ+whNvZcNE0E5tnNwAnLfG41wKBgQCRKHdYyKDfuGjRv8Sh/4doEY/qV8t9QuHN/UHIKiC5i4imfNEqt9TPt3fPjnmmeq1SLBgVVvXl5K6XCxa5mGkQz2x7A790Nug9su/lNWln0LZdaZXWxRyvLQSZ5GdQWQ3U+Lgd9fcCBufd+zznNYbRiI/za3pfAyqGcaBX6G62oQKBgHxLVwH1lQoLHdTbEm+LDX1LQtSWdMmlf+TP4vUXsS6FG0fgigKTqt/IX7Y+khPSZTgBzMZSWUiXS8HoTvUZehOzPgV+wjj96a2kBy/U6xUbIaO/oJEPTQf0ZRBBBviUgTdtoSzHabJyyt3thU82fdyUTW1LXC+97rXw+C2mmcpj'
$paymentAlipayPublicKey = 'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlhHOKa09FCwv6+6Gnsba1tAG0HQwwqOVeEPCMrVX7tLTx0h0hpYn8vPZhf/XnCz8fo3gTO0Ir5RS98Ac1s4iQCIXS9RnlIFpJ6sD+7b5wQ/dzvCYJ7Kvx/gx2HuSBH+69kVyyrKyIFQIAF6OLQpYI7Erh5avBXJn2HT2fwSwy7xD3a38hXa16EW65t6dLKXMStpvuxEIaCBy3mIRe+X3eut/5iY1znHLB6E39VBOD1vnoaSGZ784Jgfi7WlnCE1dy7TkwAU6uh/a/ZNbydK48PsARjbPbkCWCy+j751jxuv2u4c8M9B7I1BmzKkES+9ORInQe3lj8SwN0bHEledfDQIDAQAB'

$monitorHost = '127.0.0.1'
$monitorPort = '9999'
$monitorServiceName = $ServiceName
$monitorServerType = $ServerType
$monitorAdvertiseHost = $AdvertiseHost
$monitorAdvertisePort = [string]$AppPort
$monitorHeartbeatMs = '30000'
$monitorControlToken = 'monitor-dev-token'
$resolvedRestartScriptPath = if ([string]::IsNullOrWhiteSpace($RestartScriptPath)) { $PSCommandPath } else { $RestartScriptPath }
$resolvedServiceId = if ([string]::IsNullOrWhiteSpace($ServiceId)) { "${monitorServiceName}@${monitorAdvertiseHost}:$AppPort" } else { $ServiceId }

$postgresBin = 'D:\postsql\bin'
$psqlPath = Join-Path $postgresBin 'psql.exe'
$createdbPath = Join-Path $postgresBin 'createdb.exe'
$schemaPath = Join-Path $backendDir 'src\main\resources\schema.sql'

function Ensure-RuntimeDirectory {
  if (-not (Test-Path $runtimeDir)) {
    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
  }
}

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

function Get-PidInfo {
  if (-not (Test-Path $pidFile)) {
    return $null
  }

  try {
    return Get-Content -Path $pidFile -Raw | ConvertFrom-Json
  } catch {
    Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
    return $null
  }
}

function Save-PidInfo {
  param(
      [int]$ProcessId,
    [string]$JarPath
  )

  Ensure-RuntimeDirectory
    $payload = [pscustomobject]@{
      pid = $ProcessId
    jarPath = $JarPath
    startedAt = (Get-Date).ToString('o')
    port = $AppPort
    stdoutLog = $stdoutLog
    stderrLog = $stderrLog
    } | ConvertTo-Json

    Set-Content -Path $pidFile -Value $payload -Encoding UTF8 -Force
}

function Remove-PidInfo {
  if (Test-Path $pidFile) {
    Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
  }
}

function Get-ListeningProcessIds {
  param(
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

function Get-ProcessCommandLine {
  param(
      [int]$ProcessId
  )

  try {
      $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId"
    return [string]$proc.CommandLine
  } catch {
    return ''
  }
}

function Test-ProcessAlive {
  param(
      [int]$ProcessId
  )

    return $null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

function Test-BackendLikeProcess {
  param(
      [int]$ProcessId
  )

    $commandLine = (Get-ProcessCommandLine -ProcessId $ProcessId).ToLowerInvariant()
  if ([string]::IsNullOrWhiteSpace($commandLine)) {
    return $false
  }

  return $commandLine.Contains('ecommerce-backend') -or
    $commandLine.Contains('com.example.ecommerce.application') -or
    $commandLine.Contains('spring-boot')
}

function Stop-ProcessByPid {
  param(
    [int]$ProcessId,
    [string]$Reason
  )

  if (-not (Test-ProcessAlive -ProcessId $ProcessId)) {
    return
  }

  Write-Host ("Stopping PID {0}: {1}" -f $ProcessId, $Reason)
  Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue

  $deadline = (Get-Date).AddSeconds(15)
  while ((Get-Date) -lt $deadline) {
    if (-not (Test-ProcessAlive -ProcessId $ProcessId)) {
      return
    }
    Start-Sleep -Milliseconds 500
  }

  throw "PID $ProcessId could not be stopped within 15 seconds."
}

function Stop-BackendProcess {
  $pidInfo = Get-PidInfo
  $stoppedAny = $false

  if ($pidInfo -and $pidInfo.pid) {
    if (Test-ProcessAlive -ProcessId ([int]$pidInfo.pid)) {
      Stop-ProcessByPid -ProcessId ([int]$pidInfo.pid) -Reason 'managed backend process'
      $stoppedAny = $true
    }
    Remove-PidInfo
  }

  $listeningPids = Get-ListeningProcessIds -Port $AppPort
  foreach ($listeningProcessId in $listeningPids) {
    if (-not (Test-ProcessAlive -ProcessId $listeningProcessId)) {
      continue
    }

    if ((Test-BackendLikeProcess -ProcessId $listeningProcessId) -or $Force) {
      Stop-ProcessByPid -ProcessId $listeningProcessId -Reason "stale process occupying port $AppPort"
      $stoppedAny = $true
      continue
    }

    throw "Port $AppPort is occupied by PID $listeningProcessId, and it does not look like this backend. Re-run with -Force if you want to take over the port."
  }

  if (-not $stoppedAny) {
    Write-Host 'No managed backend process is running.'
  }
}

function Test-BackendHealthy {
  try {
    $response = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}{1}" -f $AppPort, $HealthPath) -UseBasicParsing -TimeoutSec 5
    return $response.StatusCode -ge 200 -and $response.StatusCode -lt 500
  } catch {
    return $false
  }
}

function Wait-BackendHealthy {
  param(
    [int]$TimeoutSec
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (Test-BackendHealthy) {
      return $true
    }
    Start-Sleep -Seconds 2
  }

  return $false
}

function Get-JarMainClass {
  param(
    [Parameter(Mandatory = $true)]
    [string]$JarPath
  )

  try {
    Add-Type -AssemblyName 'System.IO.Compression.FileSystem' -ErrorAction SilentlyContinue | Out-Null
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
      $manifestEntry = $zip.GetEntry('META-INF/MANIFEST.MF')
      if (-not $manifestEntry) {
        return ''
      }

      $reader = New-Object System.IO.StreamReader($manifestEntry.Open())
      try {
        $manifestText = $reader.ReadToEnd()
      } finally {
        $reader.Dispose()
      }
    } finally {
      $zip.Dispose()
    }

    $mainClassMatch = [regex]::Match($manifestText, '(?im)^Main-Class:\s*(.+)\s*$')
    if ($mainClassMatch.Success) {
      return $mainClassMatch.Groups[1].Value.Trim()
    }
  } catch {
    return ''
  }

  return ''
}

function Test-ExecutableBackendJar {
  param(
    [Parameter(Mandatory = $true)]
    [System.IO.FileInfo]$JarFile
  )

  $mainClass = (Get-JarMainClass -JarPath $JarFile.FullName).ToLowerInvariant()
  if ([string]::IsNullOrWhiteSpace($mainClass)) {
    return $false
  }

  return $mainClass.Contains('springframework.boot.loader') -or
    $mainClass -eq 'com.example.ecommerce.application'
}

function Resolve-BackendJar {
  $targetDir = Join-Path $backendDir 'target'
  if (-not (Test-Path $targetDir)) {
    return $null
  }

  return Get-ChildItem -Path $targetDir -Filter '*.jar' -File -ErrorAction SilentlyContinue |
    Where-Object {
      $_.Name -notlike '*.original' -and
      $_.Name -notlike 'original-*' -and
      (Test-ExecutableBackendJar -JarFile $_)
    } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
}

function Ensure-Database {
  if (-not ((Test-Path $psqlPath) -and (Test-Path $createdbPath))) {
    return
  }

  $env:PGPASSWORD = $dbPassword
  $databaseExists = & $psqlPath -h $dbHost -p $dbPort -U $dbUser -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='${dbName}';"
  if ($LASTEXITCODE -ne 0) {
    throw 'Unable to connect to PostgreSQL using the configured credentials.'
  }

  if ($databaseExists -ne '1') {
    & $createdbPath -h $dbHost -p $dbPort -U $dbUser $dbName
    if ($LASTEXITCODE -ne 0) {
      throw "Failed to create database '${dbName}'."
    }
    Write-Host "Created database '${dbName}'."
  }

  if (Test-Path $schemaPath) {
    chcp 65001 > $null
    [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $env:PGCLIENTENCODING = 'UTF8'
    & $psqlPath -h $dbHost -p $dbPort -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -f $schemaPath
    if ($LASTEXITCODE -ne 0) {
      throw "Failed to apply schema from '${schemaPath}'."
    }
  }
}

function Set-BackendEnvironment {
  $paymentAlipayPrivateKey = $script:paymentAlipayPrivateKey.Trim().Trim('<', '>')
  $paymentAlipayPublicKey = $script:paymentAlipayPublicKey.Trim().Trim('<', '>')

  if ($paymentAlipayPrivateKey -like 'REPLACE_*' -or $paymentAlipayPublicKey -like 'REPLACE_*') {
    throw 'Please fill PAYMENT_ALIPAY_PRIVATE_KEY and PAYMENT_ALIPAY_PUBLIC_KEY in start-ecommerce-backend.ps1.'
  }

  $deepseekApiKey = Get-EnvironmentValue -Name 'DEEPSEEK_API_KEY'
  $deepseekBaseUrl = Get-EnvironmentValue -Name 'DEEPSEEK_BASE_URL' -Default 'https://api.deepseek.com/v1'
  $aiProvider = Get-EnvironmentValue -Name 'AI_PROVIDER' -Default 'deepseek'
  $normalizedAiProvider = $aiProvider.ToLowerInvariant()
  $openaiApiKey = Get-EnvironmentValue -Name 'OPENAI_API_KEY'
  $openaiBaseUrl = Get-EnvironmentValue -Name 'OPENAI_BASE_URL' -Default 'https://api.openai.com/v1'
  $openaiModelName = Get-EnvironmentValue -Name 'OPENAI_MODEL_NAME' -Default 'gpt-4o-mini'
  $deepseekModelName = Get-EnvironmentValue -Name 'DEEPSEEK_MODEL_NAME' -Default 'deepseek-chat'
  $siliconflowApiKey = Get-EnvironmentValue -Name 'SILICONFLOW_API_KEY'
  $siliconflowBaseUrl = Get-EnvironmentValue -Name 'SILICONFLOW_BASE_URL' -Default 'https://api.siliconflow.cn/v1'
  $siliconflowModelName = Get-EnvironmentValue -Name 'SILICONFLOW_MODEL_NAME'

  if ($normalizedAiProvider -eq 'deepseek' -and [string]::IsNullOrWhiteSpace($deepseekApiKey)) {
    Write-Warning 'DEEPSEEK_API_KEY is missing. Backend will start, but AI chat will use fallback recommendation mode instead of real DeepSeek responses.'
  }
  if (@('chatgpt', 'openai', 'openai-compatible') -contains $normalizedAiProvider -and [string]::IsNullOrWhiteSpace($openaiApiKey)) {
    Write-Warning 'OPENAI_API_KEY is missing. For OpenAI-compatible providers, set OPENAI_BASE_URL, OPENAI_MODEL_NAME and OPENAI_API_KEY.'
  }
  if ($normalizedAiProvider -eq 'siliconflow' -and [string]::IsNullOrWhiteSpace($siliconflowApiKey)) {
    Write-Warning 'SILICONFLOW_API_KEY is missing. Backend will start, but AI chat will use fallback mode instead of real SiliconFlow responses.'
  }
  if ($normalizedAiProvider -eq 'siliconflow' -and [string]::IsNullOrWhiteSpace($siliconflowModelName)) {
    Write-Warning 'SILICONFLOW_MODEL_NAME is missing. Set a valid SiliconFlow model name, otherwise AI chat will fall back.'
  }

  $env:DB_HOST = $dbHost
  $env:DB_PORT = $dbPort
  $env:DB_NAME = $dbName
  $env:DB_USER = $dbUser
  $env:DB_PASSWORD = $dbPassword
  $env:DB_INIT_MODE = $dbInitMode
  $env:SERVER_PORT = [string]$AppPort

  $env:PAYMENT_FRONTEND_BASE_URL = $paymentFrontendBaseUrl
  $env:PAYMENT_ALIPAY_MODE = $paymentAlipayMode
  $env:PAYMENT_ALIPAY_GATEWAY = $paymentAlipayGateway
  $env:PAYMENT_ALIPAY_APP_ID = $paymentAlipayAppId
  $env:PAYMENT_ALIPAY_PRIVATE_KEY = $paymentAlipayPrivateKey
  $env:PAYMENT_ALIPAY_PUBLIC_KEY = $paymentAlipayPublicKey
  $env:PAYMENT_ALIPAY_SIGN_ENABLED = 'false'
  $env:PAYMENT_ALIPAY_NOTIFY_URL = ''
  $env:PAYMENT_ALIPAY_RETURN_URL = 'http://127.0.0.1:5173/payment/callback'

  $env:AI_PROVIDER = $aiProvider
  $env:DEEPSEEK_API_KEY = $deepseekApiKey
  $env:DEEPSEEK_BASE_URL = $deepseekBaseUrl
  $env:DEEPSEEK_MODEL_NAME = $deepseekModelName
  $env:DEEPSEEK_TIMEOUT = 'PT120S'
  $env:DEEPSEEK_MAX_RETRIES = '2'
  $env:DEEPSEEK_LOG_REQUESTS = 'false'
  $env:DEEPSEEK_LOG_RESPONSES = 'false'
  $env:OPENAI_API_KEY = $openaiApiKey
  $env:OPENAI_BASE_URL = $openaiBaseUrl
  $env:OPENAI_MODEL_NAME = $openaiModelName
  $env:OPENAI_TIMEOUT = 'PT60S'
  $env:OPENAI_MAX_RETRIES = '1'
  $env:OPENAI_LOG_REQUESTS = 'false'
  $env:OPENAI_LOG_RESPONSES = 'false'
  $env:SILICONFLOW_API_KEY = $siliconflowApiKey
  $env:SILICONFLOW_BASE_URL = $siliconflowBaseUrl
  $env:SILICONFLOW_MODEL_NAME = $siliconflowModelName
  $env:SILICONFLOW_TIMEOUT = 'PT60S'
  $env:SILICONFLOW_MAX_RETRIES = '1'
  $env:SILICONFLOW_LOG_REQUESTS = 'false'
  $env:SILICONFLOW_LOG_RESPONSES = 'false'

  $env:MONITOR_HOST = $monitorHost
  $env:MONITOR_PORT = $monitorPort
  $env:MONITOR_SERVICE_NAME = $monitorServiceName
  $env:MONITOR_SERVICE_ID = $resolvedServiceId
  $env:MONITOR_SERVER_TYPE = $monitorServerType
  $env:MONITOR_ADVERTISE_HOST = $monitorAdvertiseHost
  $env:MONITOR_ADVERTISE_PORT = $monitorAdvertisePort
  $env:MONITOR_HEARTBEAT_MS = $monitorHeartbeatMs
  $env:MONITOR_CONTROL_TOKEN = $monitorControlToken
  $env:MONITOR_RESTART_SCRIPT = $resolvedRestartScriptPath
  $env:MONITOR_COMMAND_SHUTDOWN_DELAY_MS = '1800'
}

function Build-BackendArtifact {
  Write-Host 'Building backend artifact...'
  $existingExecutableJar = Resolve-BackendJar

  Push-Location $backendDir
  try {
    mvn -DskipTests clean package
    if ($LASTEXITCODE -eq 0) {
      $builtJar = Resolve-BackendJar
      if (-not $builtJar) {
        throw 'Build finished, but no executable Spring Boot jar was produced.'
      }
      return
    }

    Write-Warning 'clean package failed (likely target jar is locked by a running instance).'

    if ($existingExecutableJar) {
      Write-Warning ("Fallback to existing executable jar: {0}" -f $existingExecutableJar.FullName)
      return
    }

    Write-Warning 'No executable fallback jar exists. Retrying with package only...'
    mvn -DskipTests package
    if ($LASTEXITCODE -eq 0) {
      $fallbackBuiltJar = Resolve-BackendJar
      if (-not $fallbackBuiltJar) {
        throw 'package finished, but no executable Spring Boot jar was produced.'
      }
      return
    }

    $existingJar = Resolve-BackendJar
    if ($existingJar) {
      Write-Warning ("Build failed, fallback to existing executable jar: {0}" -f $existingJar.FullName)
      return
    }

    throw 'Backend package failed and no executable Spring Boot jar is available.'
  } finally {
    Pop-Location
  }
}

function Start-BackendProcess {
  Ensure-RuntimeDirectory

  $pidInfo = Get-PidInfo
  if ($pidInfo -and $pidInfo.pid -and (Test-ProcessAlive -ProcessId ([int]$pidInfo.pid)) -and (Test-BackendHealthy)) {
    Write-Host ("Backend is already running. PID={0} URL=http://127.0.0.1:{1}" -f $pidInfo.pid, $AppPort)
    return
  }

  $existingPortPids = Get-ListeningProcessIds -Port $AppPort
  if ($existingPortPids.Count -gt 0) {
    Write-Host ("Cleaning up existing port {0} listeners before start..." -f $AppPort)
    Stop-BackendProcess
  }

  Ensure-Database
  Set-BackendEnvironment

  if (-not $SkipBuild) {
    Build-BackendArtifact
  }

  $jar = Resolve-BackendJar
  if (-not $jar) {
    throw 'No executable Spring Boot jar found under ecommerce-backend/target. Stop running instances, then run start once without -SkipBuild to rebuild.'
  }

  foreach ($logFile in @($stdoutLog, $stderrLog)) {
    if (Test-Path $logFile) {
      Remove-Item -Path $logFile -Force -ErrorAction SilentlyContinue
    }
  }

  $process = Start-Process -FilePath 'java' `
    -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', $jar.FullName) `
    -WorkingDirectory $backendDir `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -PassThru

    Save-PidInfo -ProcessId $process.Id -JarPath $jar.FullName
  Write-Host ("Backend process launched. PID={0}" -f $process.Id)

  if (-not (Wait-BackendHealthy -TimeoutSec $StartupTimeoutSec)) {
    $stderrTail = ''
    if (Test-Path $stderrLog) {
      $stderrTail = (Get-Content -Path $stderrLog -Tail 40 -ErrorAction SilentlyContinue) -join [Environment]::NewLine
    }
    if (Test-ProcessAlive -ProcessId $process.Id) {
      Stop-ProcessByPid -ProcessId $process.Id -Reason 'startup health check failed'
    }
    Remove-PidInfo
    throw "Backend failed health check within ${StartupTimeoutSec}s. See logs under $runtimeDir.`n$stderrTail"
  }

  Write-Host ("Backend is healthy. URL=http://127.0.0.1:{0} Logs={1}" -f $AppPort, $runtimeDir)
}

function Show-BackendStatus {
  $pidInfo = Get-PidInfo
  $portPids = Get-ListeningProcessIds -Port $AppPort
  $healthy = Test-BackendHealthy

  [pscustomobject]@{
    action = $Action
    serviceName = $ServiceName
    appPort = $AppPort
    healthy = $healthy
    managedPid = if ($pidInfo) { $pidInfo.pid } else { $null }
    portPids = @($portPids)
    runtimeDir = $runtimeDir
    stdoutLog = $stdoutLog
    stderrLog = $stderrLog
  } | ConvertTo-Json -Depth 4
}

switch ($Action) {
  'start' {
    Start-BackendProcess
  }
  'stop' {
    Stop-BackendProcess
  }
  'restart' {
    Stop-BackendProcess
    Start-BackendProcess
  }
  'status' {
    Show-BackendStatus
  }
}
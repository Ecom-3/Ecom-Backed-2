param([string]$EnvFile = (Join-Path $PSScriptRoot '.env'))

$ErrorActionPreference = 'Stop'
$releaseDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendDir = Join-Path $releaseDir 'backend'
$logDir = Join-Path $releaseDir 'logs'
$pidDir = Join-Path $releaseDir 'pids'
$servicePorts = [ordered]@{
    'Ecom-eureka-server' = 58761
    'Ecom-config-server' = 58888
    'Ecom-Org-access' = 53310
    'customer-profile-service' = 58088
    'catalog-service' = 58089
    'inventory-service' = 58095
    'cart-service' = 58091
    'notification-service' = 53317
    'fulfillment-service' = 53315
    'payment-service' = 53314
    'order-service' = 53311
    'Ecom-api-gateway' = 58082
}

if (-not (Test-Path -LiteralPath $EnvFile)) { throw "Private environment file not found: $EnvFile. Copy .env.example to .env and configure it." }
$settings = @{}
foreach ($line in Get-Content -LiteralPath $EnvFile) {
    $entry = $line.Trim()
    if (-not $entry -or $entry.StartsWith('#')) { continue }
    if ($entry -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Invalid KEY=value line in the private environment file.' }
    $name, $value = $Matches[1], $Matches[2].Trim()
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    $settings[$name] = $value
}
foreach ($name in $settings.Keys) { [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process') }

$required = @('MYSQL_HOST','MYSQL_PORT','MYSQL_USERNAME','MYSQL_PASSWORD','JWT_SECRET','ORDER_SYNC_KEY','PAYMENT_SYNC_KEY','FULFILLMENT_SYNC_KEY','INVENTORY_SYNC_KEY')
foreach ($name in $required) { if ([string]::IsNullOrWhiteSpace($settings[$name]) -or $settings[$name] -match '^replace-with-') { throw "Configure $name in the private environment file before starting." } }
if ($settings['JWT_SECRET'].Length -lt 32) { throw 'JWT_SECRET must be at least 32 characters.' }
foreach ($name in @('ORDER_SYNC_KEY','PAYMENT_SYNC_KEY','FULFILLMENT_SYNC_KEY','INVENTORY_SYNC_KEY')) {
    if ($settings[$name].Length -lt 32) { throw "$name must be at least 32 characters." }
}

New-Item -ItemType Directory -Force -Path $logDir,$pidDir | Out-Null
$env:CONFIG_SERVER_URL = 'http://127.0.0.1:58888'
$env:EUREKA_SERVER_URL = 'http://127.0.0.1:58761/eureka'
$env:ORDER_SERVICE_URL = 'http://127.0.0.1:53311'
$env:ORDERS_SERVICE_URL = $env:ORDER_SERVICE_URL
$env:PAYMENT_SERVICE_URL = 'http://127.0.0.1:53314'
$env:FULFILLMENT_SERVICE_URL = 'http://127.0.0.1:53315'
$env:NOTIFICATION_SERVICE_URL = 'http://127.0.0.1:53317'
$env:NOTIFICATIONS_SERVICE_URL = $env:NOTIFICATION_SERVICE_URL
$env:CATALOG_SERVICE_URL = 'http://127.0.0.1:58089'
$env:CART_SERVICE_URL = 'http://127.0.0.1:58091'
$env:INVENTORY_SERVICE_URL = 'http://127.0.0.1:58095'
$env:ORG_ACCESS_SERVICE_URL = 'http://127.0.0.1:53310'
$env:ORG_ACCESS_URL = $env:ORG_ACCESS_SERVICE_URL
$env:EUREKA_INSTANCE_HOSTNAME = '127.0.0.1'
$env:EUREKA_INSTANCE_PREFER_IP_ADDRESS = 'true'
$env:FLYWAY_ENABLED = 'false'
$env:SPRING_FLYWAY_ENABLED = 'false'
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
$env:SPRING_SQL_INIT_MODE = 'never'
$env:JAVA_TOOL_OPTIONS = '-Djava.net.preferIPv4Stack=true'

$mysqlReady = Test-NetConnection -ComputerName $settings['MYSQL_HOST'] -Port ([int]$settings['MYSQL_PORT']) -InformationLevel Quiet -WarningAction SilentlyContinue
if (-not $mysqlReady) { throw "MySQL is not reachable at $($settings['MYSQL_HOST']):$($settings['MYSQL_PORT'])." }

function Wait-Health {
    param([string]$Name, [int]$Port)
    $url = "http://127.0.0.1:$Port/actuator/health"
    $until = (Get-Date).AddMinutes(5)
    do {
        try {
            $result = Invoke-RestMethod -Uri $url -TimeoutSec 4
            if ($result.status -eq 'UP') { Write-Host "Healthy: $Name"; return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $until)
    throw "$Name did not become healthy. Inspect $logDir\$Name.stdout.log and $logDir\$Name.stderr.log."
}

foreach ($name in $servicePorts.Keys) {
    $jarPath = Join-Path $backendDir "$name.jar"
    if (-not (Test-Path -LiteralPath $jarPath)) { throw "Missing release jar for ${name}: $jarPath" }
    $pidFile = Join-Path $pidDir "$name.pid"
    if (Test-Path -LiteralPath $pidFile) {
        $priorPid = 0
        if ([int]::TryParse((Get-Content -LiteralPath $pidFile -Raw).Trim(), [ref]$priorPid) -and (Get-Process -Id $priorPid -ErrorAction SilentlyContinue)) {
            throw "$name already has a recorded process (PID $priorPid). Stop the release before starting it again."
        }
        Remove-Item -LiteralPath $pidFile -Force
    }
    $listener = Get-NetTCPConnection -LocalPort $servicePorts[$name] -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) { throw "Port $($servicePorts[$name]) for $name is already occupied by PID $($listener.OwningProcess)." }

    $stdoutFile = Join-Path $logDir "$name.stdout.log"
    $stderrFile = Join-Path $logDir "$name.stderr.log"
    $arguments = "-Dserver.port=$($servicePorts[$name]) -Dserver.address=127.0.0.1 -jar `"$jarPath`""
    $process = Start-Process -FilePath 'java' -ArgumentList $arguments -WorkingDirectory $releaseDir -WindowStyle Hidden -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile -PassThru
    Set-Content -LiteralPath $pidFile -Value $process.Id
    Write-Host "Started $name (PID $($process.Id))"
    Wait-Health -Name $name -Port $servicePorts[$name]
}

Write-Host 'Release is running. Serve the frontend folder through a web server that proxies the API paths to http://127.0.0.1:58082.'

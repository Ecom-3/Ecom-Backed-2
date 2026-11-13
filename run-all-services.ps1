param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'restart')]
    [string]$Action = 'start',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Service = @(),
    [string]$MysqlHost = "localhost",
    [string]$MysqlPort = "3306",
    [string]$MysqlUsername = "root",
    [string]$MysqlPassword = $env:MYSQL_PASSWORD,
    [string]$EnvFile,
    [string]$KafkaBootstrapServers = "localhost:9092",
    [switch]$EnableLegacyAlerts,
    [switch]$StartFrontend,
    [switch]$IsolatedTest,
    [string]$ExpectedTestDatabaseUuid,
    [string]$PinnedRelease,
    [switch]$BrowserQualification
)

$rootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoDir = Split-Path -Parent $rootDir

function Import-ServiceEnvironment {
    param([string]$Path)
    $settings = @{}
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $Path -ErrorAction Stop) {
        $lineNumber++
        $entry = $line.Trim()
        if (-not $entry -or $entry.StartsWith('#')) { continue }
        if ($entry -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') {
            throw "Invalid KEY=value entry in environment file at line $lineNumber."
        }
        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $settings[$name] = $value
    }
    foreach ($name in $settings.Keys) {
        # Deployment-injected environment variables take precedence. Values are literal, never executed.
        if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name, 'Process')) -and $settings[$name] -ne '') {
            [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
        }
    }
}

function Read-RequiredSecret {
    param([string]$Prompt, [int]$MinimumLength = 1)

    $secureValue = Read-Host -Prompt $Prompt -AsSecureString
    try {
        $value = [System.Net.NetworkCredential]::new('', $secureValue).Password
        if ($value.Length -lt $MinimumLength) {
            throw "A value of at least $MinimumLength characters is required. Set the corresponding environment variable or rerun and enter it at the prompt."
        }
        return $value
    } finally {
        if ($null -ne $secureValue) { $secureValue.Dispose() }
    }
}

if ($PinnedRelease) {
    if (-not $IsolatedTest -or $Action -ne 'start' -or $MysqlHost -ne '127.0.0.1' -or $MysqlPort -ne '14306' -or
        $ExpectedTestDatabaseUuid -ne '580d1315-c4bf-11f1-bf4b-14cb19888cfa' -or $EnableLegacyAlerts -or $StartFrontend) {
        throw 'Pinned qualification requires the explicit isolated target; no live launch permitted.'
    }
    & (Join-Path $repoDir 'deployment\Test-PinnedStack.ps1') -Release $PinnedRelease -BrowserQualification:$BrowserQualification
    return
}
# Keep qualification runs independent of local configuration unless explicitly selected.
if ($EnvFile) {
    Import-ServiceEnvironment -Path $EnvFile
} elseif (-not $IsolatedTest -and (Test-Path -LiteralPath (Join-Path $rootDir '.env'))) {
    Import-ServiceEnvironment -Path (Join-Path $rootDir '.env')
}
foreach ($setting in @{
    MysqlHost='MYSQL_HOST'; MysqlPort='MYSQL_PORT'; MysqlUsername='MYSQL_USERNAME';
    MysqlPassword='MYSQL_PASSWORD'; KafkaBootstrapServers='KAFKA_BOOTSTRAP_SERVERS'
}.GetEnumerator()) {
    $configuredValue = [Environment]::GetEnvironmentVariable($setting.Value, 'Process')
    if (-not $PSBoundParameters.ContainsKey($setting.Key) -and -not [string]::IsNullOrEmpty($configuredValue)) {
        Set-Variable -Name $setting.Key -Value $configuredValue
    }
}
$localDir = Join-Path $repoDir ".local"
if ($IsolatedTest) {
    if ($Action -ne 'start') { throw 'IsolatedTest supports start only; stop test processes after checking their identity.' }
    $testMysqlPort=0
    if (-not [int]::TryParse($MysqlPort,[ref]$testMysqlPort) -or $testMysqlPort -lt 1 -or $testMysqlPort -gt 65535 -or $MysqlHost -notin @('127.0.0.1','localhost') -or $testMysqlPort -eq 3306 -or -not $ExpectedTestDatabaseUuid) {
        throw 'IsolatedTest requires a loopback non-live MySQL port and ExpectedTestDatabaseUuid.'
    }
    if ($EnableLegacyAlerts -or $StartFrontend) { throw 'IsolatedTest does not permit legacy alerts or the live frontend.' }
    if (-not $Service -or $Service -contains 'Ecom-frontend') { throw 'IsolatedTest requires explicitly selected backend services.' }
    $localDir = Join-Path $localDir "isolated-test-$MysqlPort"
}
$logDir = Join-Path $localDir "logs"
$pidDir = Join-Path $localDir "pids"
$toolsDir = Join-Path $repoDir "tools"
if (-not (Test-Path $toolsDir)) { $toolsDir = Join-Path $rootDir 'tools' }
$servicePorts = @{'Ecom-eureka-server'=58761;'Ecom-config-server'=58888;'Ecom-Org-access'=53310;'customer-profile-service'=58088;'catalog-service'=58089;'inventory-service'=58095;'cart-service'=58091;'order-service'=53311;'payment-service'=53314;'fulfillment-service'=53315;'notification-service'=53317;'Ecom-api-gateway'=58082;'Ecom-Alerts'=58087}
if ($IsolatedTest) {
    foreach ($name in @($servicePorts.Keys)) { $servicePorts[$name] += 10000 }
    $env:SPRING_CLOUD_CONFIG_ENABLED='false'
    $env:EUREKA_CLIENT_ENABLED='false'
    $env:NOTIFICATION_SENDING_ENABLED='false'
    $env:ORDER_SERVICE_URL='http://127.0.0.1:16311'
    $env:ORDERS_SERVICE_URL='http://127.0.0.1:16311'
    $env:PAYMENT_SERVICE_URL='http://127.0.0.1:16314'
    $env:FULFILLMENT_SERVICE_URL='http://127.0.0.1:16315'
    $env:NOTIFICATION_SERVICE_URL='http://127.0.0.1:16317'
    $env:NOTIFICATIONS_SERVICE_URL='http://127.0.0.1:16317'
    $env:CATALOG_SERVICE_URL='http://127.0.0.1:18089'
    $env:CART_SERVICE_URL='http://127.0.0.1:18091'
    $env:ORG_ACCESS_SERVICE_URL='http://127.0.0.1:16310'
    $env:CONFIG_SERVER_URL='http://127.0.0.1:18888'
    $env:EUREKA_SERVER_URL='http://127.0.0.1:18761/eureka'
}

function Use-ToolFallback {
    param(
        [string]$Command,
        [string]$FallbackBin
    )

    if (Get-Command $Command -ErrorAction SilentlyContinue) { return }
    if (Test-Path $FallbackBin) {
        $env:PATH = "$(Split-Path -Parent $FallbackBin);$env:PATH"
    }
}

Use-ToolFallback "java" (Join-Path $toolsDir "java\bin\java.exe")
Use-ToolFallback "mvn" (Join-Path $toolsDir "maven\bin\mvn.cmd")
Use-ToolFallback "npm" (Join-Path $toolsDir "node\npm.cmd")

$fallbackJavaHome = Join-Path $toolsDir "java"
$systemJavaVersion = (& java -version 2>&1 | Out-String)
if ((-not (Get-Command "java" -ErrorAction SilentlyContinue) -or $systemJavaVersion -notmatch 'version "21\.') -and (Test-Path (Join-Path $fallbackJavaHome "bin\java.exe"))) {
    $env:JAVA_HOME = $fallbackJavaHome
    $env:PATH = "$(Join-Path $fallbackJavaHome 'bin');$env:PATH"
}

function Get-JavaMajorVersion {
    $javaOutput = (& java -version 2>&1 | Out-String)
    $match = [regex]::Match($javaOutput, 'version "(?<version>[^".]+)')
    if (-not $match.Success) { return $null }
    return [int]$match.Groups['version'].Value
}

if (-not (Get-Command "java" -ErrorAction SilentlyContinue) -or (Get-JavaMajorVersion) -ne 21) {
    throw "Java 21 is required. Install Java 21 or place it under tools\java."
}
if (-not (Get-Command "mvn" -ErrorAction SilentlyContinue)) {
    throw "Maven is required. Install Maven or provide ecom-backend\tools\maven."
}
if ($StartFrontend -and -not (Get-Command "npm" -ErrorAction SilentlyContinue)) {
    throw "Node.js/npm is required. Install Node.js or provide ecom-backend\tools\node."
}

if ($EnableLegacyAlerts -and -not $env:KAFKA_HOME) {
    $installedKafka = @('C:\kafka', (Join-Path $toolsDir 'kafka')) |
        Where-Object { Test-Path (Join-Path $_ 'bin\windows\kafka-server-start.bat') } |
        Select-Object -First 1
    if ($installedKafka) { $env:KAFKA_HOME = $installedKafka }
}
if ($EnableLegacyAlerts -and -not $env:KAFKA_HOME) {
    throw "Kafka is required at C:\kafka or tools\kafka."
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
New-Item -ItemType Directory -Force -Path $pidDir | Out-Null

$env:MYSQL_HOST = $MysqlHost
$env:MYSQL_PORT = $MysqlPort
$env:MYSQL_USERNAME = $MysqlUsername
$env:MYSQL_PASSWORD = $MysqlPassword
if ($EnableLegacyAlerts) { $env:KAFKA_BOOTSTRAP_SERVERS = $KafkaBootstrapServers }
$env:CONFIG_SERVER_URL = "http://localhost:58888"
$env:EUREKA_SERVER_URL = "http://localhost:58761/eureka"
$env:FLYWAY_ENABLED = "false"
$env:SPRING_FLYWAY_ENABLED = "false"
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = "validate"
$env:SPRING_SQL_INIT_MODE = "never"
# Each Spring service otherwise gets a separate default-sized Hikari pool. Keep
# the aggregate MySQL connection count low enough for local MySQL installations.
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "2"
$env:SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE = "0"
$env:CUSTOMER_MIGRATION_JDBC_URL = "jdbc:mysql://$MysqlHost`:$MysqlPort/customer_profiles?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
$env:CUSTOMER_MIGRATION_DB_USER = $MysqlUsername
$env:CUSTOMER_MIGRATION_DB_PASSWORD = $MysqlPassword
$env:ORDER_SERVICE_URL = 'http://127.0.0.1:53311'
$env:ORDERS_SERVICE_URL = $env:ORDER_SERVICE_URL
$env:PAYMENT_SERVICE_URL = 'http://127.0.0.1:53314'
$env:FULFILLMENT_SERVICE_URL = 'http://127.0.0.1:53315'
$env:NOTIFICATION_SERVICE_URL = 'http://127.0.0.1:53317'
$env:NOTIFICATIONS_SERVICE_URL = $env:NOTIFICATION_SERVICE_URL
$env:CATALOG_SERVICE_URL = 'http://127.0.0.1:58089'
$env:CART_SERVICE_URL = 'http://127.0.0.1:58091'
$env:ORG_ACCESS_SERVICE_URL = 'http://127.0.0.1:53310'
$env:INVENTORY_SERVICE_URL = 'http://127.0.0.1:58095'
$env:ORG_ACCESS_URL = $env:ORG_ACCESS_SERVICE_URL

$dependencyKafkaPidFile = Join-Path $pidDir "__launcher-kafka.pid"
$dependencyMysqlMarker = Join-Path $pidDir "__launcher-mysql.started"
$bundledKafkaHome = [IO.Path]::GetFullPath((Join-Path $toolsDir 'kafka'))
$usingBundledKafka = $EnableLegacyAlerts -and $env:KAFKA_HOME -and ([IO.Path]::GetFullPath($env:KAFKA_HOME) -ieq $bundledKafkaHome)

function Test-LocalPort {
    param([string]$HostName, [int]$Port)
    return [bool](Test-NetConnection -ComputerName $HostName -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue)
}

function Get-ListeningServicePorts {
    param([int[]]$Ports)

    foreach ($line in (& netstat.exe -ano -p tcp 2>$null)) {
        if ($line -match '^\s*TCP\s+\S+:(?<port>\d+)\s+\S+\s+LISTENING\s+(?<pid>\d+)\s*$') {
            $port = [int]$Matches.port
            if ($port -in $Ports) {
                [pscustomobject]@{ LocalPort = $port; OwningProcess = [int]$Matches.pid }
            }
        }
    }
}

function Wait-LocalPort {
    param([string]$HostName, [int]$Port, [string]$Name, [int]$TimeoutSeconds = 60)
    for ($attempt = 1; $attempt -le $TimeoutSeconds; $attempt++) {
        if (Test-LocalPort -HostName $HostName -Port $Port) { return $true }
        Start-Sleep -Seconds 1
    }
    Write-Error "$Name did not become available at ${HostName}:$Port."
    return $false
}

function Start-Dependencies {
    Write-Host "[1/10] Checking MySQL..."
    if (Test-LocalPort -HostName $MysqlHost -Port ([int]$MysqlPort)) {
        Write-Host "MySQL is already running on ${MysqlHost}:$MysqlPort."
    } else {
        if ($IsolatedTest) { throw 'The isolated MySQL server is unavailable; no system MySQL service will be started.' }
        $mysqlService = Get-Service -Name "MySQL80" -ErrorAction SilentlyContinue
        if ($mysqlService) {
            if ($mysqlService.Status -ne "Running") {
                Write-Host "MySQL80 service found. Starting MySQL..."
                Start-Service -Name "MySQL80"
                Set-Content -Path $dependencyMysqlMarker -Value "MySQL80"
            }
            Write-Host "Waiting for ${MysqlHost}:$MysqlPort..."
            if (-not (Wait-LocalPort -HostName $MysqlHost -Port ([int]$MysqlPort) -Name "MySQL")) { throw "MySQL Server is required and must be running on localhost:3306." }
            Write-Host "MySQL is ready."
        } else {
            throw "MySQL Server is required and must be running on localhost:3306."
        }
    }

    if (-not $EnableLegacyAlerts) {
        Write-Host "Core e-commerce uses durable HTTP synchronization; Kafka is not started. Legacy booking/house alerts are excluded."
        return
    }
    Write-Host "[2/10] Checking Kafka for legacy alerts..."
    $kafkaHost, $kafkaPortText = $KafkaBootstrapServers -split ":", 2
    $kafkaPort = [int]$kafkaPortText
    if (Test-LocalPort -HostName $kafkaHost -Port $kafkaPort) {
        Write-Host "Kafka is already running on ${kafkaHost}:$kafkaPort."
        return
    }

    $kafkaStart = Join-Path $env:KAFKA_HOME "bin\windows\kafka-server-start.bat"
    $sourceKafkaConfig = Join-Path $env:KAFKA_HOME "config\kraft\server.properties"
    $kafkaConfig = $sourceKafkaConfig
    if (-not (Test-Path $kafkaStart) -or -not (Test-Path $sourceKafkaConfig)) {
        throw "Kafka installation or KRaft configuration was not found under $env:KAFKA_HOME."
    }

    if ($usingBundledKafka) {
        $bundledDataDir = Join-Path $env:KAFKA_HOME 'data'
        $runtimeKafkaDir = Join-Path $localDir 'kafka'
        $kafkaConfig = Join-Path $runtimeKafkaDir 'server.properties'
        New-Item -ItemType Directory -Force -Path $runtimeKafkaDir,$bundledDataDir | Out-Null

        $configText = Get-Content -LiteralPath $sourceKafkaConfig -Raw
        if ($configText -notmatch '(?m)^\s*process\.roles\s*=\s*broker,controller\s*$' -or
            $configText -notmatch '(?m)^\s*listeners\s*=.*PLAINTEXT://localhost:9092.*CONTROLLER://localhost:9093' -or
            $configText -notmatch '(?m)^\s*advertised\.listeners\s*=\s*PLAINTEXT://localhost:9092\s*$') {
            throw "Bundled Kafka configuration is not the required localhost KRaft configuration."
        }
        $dataPath = ($bundledDataDir -replace '\\','/')
        $configText = [regex]::Replace($configText, '(?m)^\s*log\.dirs\s*=.*$', "log.dirs=$dataPath")
        Set-Content -LiteralPath $kafkaConfig -Value $configText -Encoding UTF8
        if (-not (Test-Path -LiteralPath $kafkaConfig)) {
            throw "Kafka runtime configuration was not created: $kafkaConfig"
        }

        $dataFiles = @(Get-ChildItem -LiteralPath $bundledDataDir -Force -ErrorAction SilentlyContinue)
        $metaFile = Join-Path $bundledDataDir 'meta.properties'
        if (-not (Test-Path $metaFile)) {
            if ($dataFiles.Count -gt 0) {
                throw "Bundled Kafka data appears partially initialized. Manual recovery is required. No Kafka data was deleted or reformatted."
            }

            $storageTool = Join-Path $env:KAFKA_HOME 'bin\windows\kafka-storage.bat'
            if (-not (Test-Path $storageTool)) { throw "Bundled Kafka storage tool was not found." }
            Write-Host "Bundled Kafka is uninitialized. Creating its new KRaft cluster once..."
            $uuidOutput = & cmd.exe /c "`"$storageTool`" random-uuid" 2>&1 | Out-String
            $uuidMatch = [regex]::Match($uuidOutput, '[A-Za-z0-9_-]{20,30}')
            if (-not $uuidMatch.Success) { throw "Could not generate a KRaft cluster ID for bundled Kafka." }
            $clusterId = $uuidMatch.Value
            & cmd.exe /c "`"$storageTool`" format -t $clusterId -c `"$kafkaConfig`"" | Out-Host
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $metaFile)) { throw "Bundled Kafka storage initialization failed." }
        } else {
            Write-Host "Bundled Kafka data is already initialized; reusing it without formatting."
        }
    }

    Write-Host "Kafka installation found. Starting Kafka..."
    # cmd.exe needs the outer quotes around the complete /c command when the
    # command itself starts with a quoted batch-file path.
    if (-not (Test-Path -LiteralPath $kafkaConfig)) {
        throw "Kafka runtime configuration is missing: $kafkaConfig"
    }
    $kafkaCommand = '/d /s /c ""{0}" "{1}""' -f $kafkaStart, $kafkaConfig
    $kafkaProcess = Start-Process -FilePath "cmd.exe" -ArgumentList $kafkaCommand -WorkingDirectory $env:KAFKA_HOME -WindowStyle Hidden -PassThru
    Set-Content -Path $dependencyKafkaPidFile -Value $kafkaProcess.Id
    Write-Host "Waiting for ${kafkaHost}:$kafkaPort..."
    if (-not (Wait-LocalPort -HostName $kafkaHost -Port $kafkaPort -Name "Kafka" -TimeoutSeconds 90)) { throw "Kafka is unavailable. Check the Kafka KRaft log and try again." }
    Write-Host "Kafka is ready."
}

function Stop-Dependencies {
    if ($EnableLegacyAlerts -and (Test-Path $dependencyKafkaPidFile)) {
        $pidValue = (Get-Content $dependencyKafkaPidFile -ErrorAction SilentlyContinue | Select-Object -First 1).Trim()
        if ($pidValue -match '^\d+$' -and (Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue)) {
            Stop-Process -Id ([int]$pidValue) -Force -ErrorAction SilentlyContinue
            Write-Host "Stopped Kafka started by this launcher (PID $pidValue)."
        }
        Remove-Item $dependencyKafkaPidFile -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path $dependencyMysqlMarker) {
        Stop-Service -Name "MySQL80" -ErrorAction SilentlyContinue
        Remove-Item $dependencyMysqlMarker -Force -ErrorAction SilentlyContinue
        Write-Host "Stopped MySQL80 started by this launcher."
    }
}

$startupGroups = @(
    @("Ecom-eureka-server"),
    @("Ecom-config-server"),
    @(
        "Ecom-Org-access",
        "customer-profile-service",
        "catalog-service",
        "inventory-service",
        "cart-service",
        "notification-service",
        "fulfillment-service",
        "payment-service",
        "order-service",
        "Ecom-api-gateway"
    )
)
if ($EnableLegacyAlerts) { $startupGroups += ,@("Ecom-Alerts") }

$allServiceNames = @($startupGroups | ForEach-Object { $_ })

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Missing required command '$Name'. Install it and open a new PowerShell window."
    }
}

function Resolve-ServiceList {
    param([string[]]$Requested)

    if (-not $Requested -or $Requested.Count -eq 0) {
        return @($allServiceNames | Select-Object -Unique)
    }

    $result = New-Object System.Collections.Generic.List[string]
    foreach ($item in $Requested) {
        $value = [string]$item
        if ([string]::IsNullOrWhiteSpace($value)) { continue }
        if ($value -ieq 'all' -or $value -eq '*') {
            return @($allServiceNames | Select-Object -Unique)
        }
        if ($value -ieq 'frontend') {
            $result.Add('Ecom-frontend')
            continue
        }
        if ($value -ieq 'Ecom-Alerts' -and -not $EnableLegacyAlerts) {
            throw "Legacy alerts still require Kafka. Use -EnableLegacyAlerts explicitly."
        }

        $match = $allServiceNames | Where-Object {
            $_ -ieq $value -or $_.ToLower().Contains($value.ToLower())
        } | Select-Object -First 1

        if ($match) {
            $result.Add($match)
        } else {
            Write-Warning "Unknown service '$value'. Ignoring it."
        }
    }

    if ($result.Count -eq 0) {
        return @($allServiceNames | Select-Object -Unique)
    }

    return @($result | Select-Object -Unique)
}

function Stop-LocalService {
    param([string]$Name)

    $pidFile = Join-Path $pidDir "$Name.pid"
    if (-not (Test-Path $pidFile)) {
        Write-Host "Already stopped: $Name"
        return
    }

    $pidValue = (Get-Content -Path $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1).Trim()
    if ($pidValue -and $pidValue -match '^\d+$') {
        $process = Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue
        if ($process) {
            # The tracked PID is the launcher PowerShell process; Maven/Java is
            # its child. Terminate the owned process tree so DB connections do
            # not survive a stop/restart and exhaust MySQL's limit.
            & taskkill.exe /PID ([int]$pidValue) /T /F | Out-Null
            if ($LASTEXITCODE -ne 0 -and (Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue)) {
                throw "Could not stop the service process tree for $Name (PID $pidValue)."
            }
            Write-Host ("Stopped {0} (PID {1})" -f $Name, $pidValue)
        } else {
            Write-Host "Already stopped: $Name"
        }
    }

    Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
}

function Clear-ServicePorts {
    param([string[]]$Selected)

    $requiredPorts = @{}
    foreach ($name in $Selected) {
        if ($servicePorts.ContainsKey($name)) { $requiredPorts[$name] = $servicePorts[$name] }
    }
    if ($Selected -contains 'Ecom-frontend' -or $StartFrontend) {
        $requiredPorts['Ecom-frontend'] = 55173
    }
    if ($requiredPorts.Count -eq 0) { return }

    Write-Host "Checking required service ports: $(($requiredPorts.Values | Sort-Object -Unique) -join ', ')"
    # netstat works in standard user sessions where the TCP CIM provider may deny access.
    $listeners = @(Get-ListeningServicePorts -Ports @($requiredPorts.Values))
    $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($ownerId in $owners) {
        if ($ownerId -le 4 -or $ownerId -eq $PID) {
            throw "Required port belongs to protected PID $ownerId. Cannot free it automatically."
        }
    }
    # Stop tracked launcher shells as well, otherwise their PID files can cause
    # Start-LocalProcess to skip relaunching a service whose Java child was killed.
    foreach ($name in $requiredPorts.Keys) {
        Stop-LocalService -Name $name
    }
    foreach ($ownerId in $owners) {
        $process = Get-Process -Id $ownerId -ErrorAction SilentlyContinue
        if ($process) {
            $ports = ($listeners | Where-Object OwningProcess -eq $ownerId |
                Select-Object -ExpandProperty LocalPort -Unique) -join ', '
            Write-Host "Stopping $($process.ProcessName) (PID $ownerId) on port(s) $ports."
            try {
                Stop-Process -Id $ownerId -Force -ErrorAction Stop
            } catch {
                if (Get-Process -Id $ownerId -ErrorAction SilentlyContinue) {
                    throw "Cannot stop PID $ownerId on port(s) ${ports}: $($_.Exception.Message)"
                }
            }
        }
    }
    $deadline = (Get-Date).AddSeconds(15)
    do {
        $remaining = @(Get-ListeningServicePorts -Ports @($requiredPorts.Values))
        if ($remaining.Count -eq 0) {
            Write-Host "All required service ports are available."
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    $details = ($remaining | ForEach-Object { "$($_.LocalPort) (PID $($_.OwningProcess))" }) -join ', '
    throw "Required ports are still occupied: $details. No application services were started."
}

function Start-LocalProcess {
    param(
        [string]$Name,
        [string]$Directory,
        [string]$Command
    )

    if (-not (Test-Path $Directory)) {
        Write-Warning "Skipping missing directory: $Directory"
        return
    }

    $pidFile = Join-Path $pidDir "$Name.pid"
    if (Test-Path $pidFile) {
        $pidValue = (Get-Content -Path $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1).Trim()
        if ($pidValue -and $pidValue -match '^\d+$' -and (Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue)) {
            Write-Host ("Already running: {0} (PID {1})" -f $Name, $pidValue)
            return
        }
    }

    if ($servicePorts.ContainsKey($Name) -and (Test-LocalPort -HostName '127.0.0.1' -Port $servicePorts[$Name])) {
        throw "Port $($servicePorts[$Name]) for $Name became occupied after port cleanup. Rerun the launcher."
    }
    $logFile = Join-Path $logDir "$Name.log"
    $script = "Set-Location -LiteralPath '$Directory'; $Command *> '$logFile'"
    $process = Start-Process -FilePath "powershell" -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $script) -WindowStyle Hidden -PassThru
    Set-Content -Path $pidFile -Value $process.Id
    Write-Host ("Started {0} (PID {1}) -> {2}" -f $Name, $process.Id, $logFile)
}

function Start-Services {
    param([string[]]$Selected)

    # Stop only listeners on this launcher's assigned service ports before DB
    # preflight; stale instances can otherwise keep MySQL at max_connections.
    Clear-ServicePorts -Selected $Selected

    if ($IsolatedTest) {
        $mysql = Get-Command mysql.exe -ErrorAction SilentlyContinue
        $mysqlPath = if ($mysql) { $mysql.Source } else { 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' }
        $env:MYSQL_PWD=$MysqlPassword
        try {
            $identity = & $mysqlPath --host=$MysqlHost --port=$MysqlPort --user=$MysqlUsername --batch --skip-column-names --execute='SELECT @@server_uuid'
            if ($LASTEXITCODE -ne 0 -or $identity -ne $ExpectedTestDatabaseUuid) { throw 'Isolated database identity mismatch.' }
        } finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
    }

    if ($Selected | Where-Object { $_ -in @('cart-service','order-service','payment-service','fulfillment-service','notification-service') }) {
        Assert-SyncSchemas -Selected $Selected
        foreach ($key in @('ORDER_SYNC_KEY','PAYMENT_SYNC_KEY','FULFILLMENT_SYNC_KEY','INVENTORY_SYNC_KEY')) {
            $value = [Environment]::GetEnvironmentVariable($key)
            if (-not $value -or $value.Length -lt 32) { throw "$key must be configured with at least 32 characters." }
        }
        Push-Location (Join-Path $rootDir 'http-sync')
        try {
            & mvn -DskipTests install
            if ($LASTEXITCODE -ne 0) { throw "HTTP synchronization library build failed." }
        } finally { Pop-Location }
    }

    foreach ($group in $startupGroups) {
        foreach ($serviceName in $group) {
            if ($Selected -contains $serviceName) {
                $serviceDir = Join-Path $rootDir $serviceName
                # -DskipTests still compiles test sources; startup must not be
                # blocked by unrelated/stale test fixtures.
                $command = "mvn '-Dmaven.test.skip=true' spring-boot:run"
                $clientEnabled = if ($serviceName -eq 'Ecom-eureka-server') { 'true' } else { 'false' }
                $command = "`$env:SERVER_PORT='$($servicePorts[$serviceName])'; `$env:SERVER_ADDRESS='127.0.0.1'; `$env:EUREKA_CLIENT_ENABLED='$clientEnabled'; $command"
                Start-LocalProcess -Name $serviceName -Directory $serviceDir -Command $command
                Wait-ServiceHealth -Name $serviceName
            }
        }
        $groupHasSelected = $group | Where-Object { $Selected -contains $_ }
        if ($groupHasSelected) {
            Start-Sleep -Seconds 15
        }
    }

    if ($Selected -contains 'Ecom-frontend' -or $StartFrontend) {
        $frontendDir = Join-Path $repoDir "Ecom-frontend"
        Start-LocalProcess -Name "Ecom-frontend" -Directory $frontendDir -Command "if (-not (Test-Path 'node_modules')) { npm.cmd install }; node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 55173 --strictPort"
    }
}

function Assert-SyncSchemas {
    param([string[]]$Selected)
    if ($env:MYSQL_DB) { throw 'Unset the shared MYSQL_DB override: each service needs its own reviewed schema.' }
    $mysqlCommand = Get-Command mysql.exe -ErrorAction SilentlyContinue
    $mysql = if ($mysqlCommand) { $mysqlCommand.Source } else { Join-Path $env:ProgramFiles 'MySQL\MySQL Server 8.0\bin\mysql.exe' }
    if (-not (Test-Path $mysql)) { throw 'MySQL client is required for read-only schema preflight. Put mysql.exe on PATH.' }
    $schemas = @{'cart-service'='cart_service';'order-service'='order-service';'payment-service'='payment_service';'fulfillment-service'='fulfillment-service';'notification-service'='notification_service'}
    $columns = @{
        'sync_receiver_lock'='id'
        'sync_inbox'='source,event_id,body_hash,received_at'
        'sync_outbox'='id,event_id,target,aggregate_id,body,state,attempts,next_attempt,lease_token,lease_until,last_error'
        'order_creation_requests'='customer_id,request_key,request_hash,order_id'
        'provider_payment_bindings'='provider_payment_id,payment_id'
        'provider_payment_receipts'='event_id,provider_payment_id,evidence_id,body_hash'
        'cart_write_lock'='id'
        'cart_mutation_requests'='cart_id,request_key,request_hash,created_at'
        'cart_checkout_claims'='cart_id,request_hash,created_at'
    }
    $oldPassword = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $MysqlPassword
        # Run all read-only checks in one mysql session to use one DB connection.
        $queries = [System.Collections.Generic.List[string]]::new()
        $expected = @{}
        foreach ($name in $Selected) {
            if (-not $schemas.ContainsKey($name)) { continue }
            $schema = $schemas[$name]
            $tables = @('sync_receiver_lock','sync_inbox','sync_outbox')
            if ($name -eq 'cart-service') { $tables = @('cart_write_lock','cart_mutation_requests','cart_checkout_claims') }
            if ($name -eq 'order-service') { $tables += 'order_creation_requests' }
            if ($name -eq 'payment-service') { $tables += @('provider_payment_bindings','provider_payment_receipts') }
            foreach ($table in $tables) {
                $key = "$schema.$table"
                $expected[$key] = @{ Service = $name; ColumnCount = @($columns[$table].Split(',')).Count }
                $queries.Add("SELECT CONCAT('TABLE|$key|',COUNT(*)) FROM information_schema.tables WHERE table_schema='$schema' AND table_name='$table';")
                $columnNames = ($columns[$table].Split(',') | ForEach-Object { "'$_'" }) -join ','
                $queries.Add("SELECT CONCAT('COLUMNS|$key|',COUNT(*)) FROM information_schema.columns WHERE table_schema='$schema' AND table_name='$table' AND column_name IN ($columnNames);")
                $queries.Add("SELECT CONCAT('ENGINE|$key|',COALESCE(MAX(engine),'MISSING')) FROM information_schema.tables WHERE table_schema='$schema' AND table_name='$table';")
            }
            $lockTable = if ($name -eq 'cart-service') { 'cart_write_lock' } else { 'sync_receiver_lock' }
            $queries.Add("SELECT CONCAT('SEED|$name|$schema|',COUNT(*)) FROM ``$schema``.$lockTable WHERE id=1;")
            if ($name -eq 'order-service') {
                $queries.Add("SELECT CONCAT('HANDLING_FEE|',COUNT(*)) FROM information_schema.columns WHERE table_schema='$schema' AND table_name='orders' AND column_name='handling_fee' AND data_type='decimal' AND numeric_precision=10 AND numeric_scale=2 AND is_nullable='NO' AND column_default='0.00';")
                $queries.Add("SELECT CONCAT('SOURCE_ADDRESS|',COUNT(*)) FROM information_schema.columns WHERE table_schema='$schema' AND table_name='order_addresses' AND column_name='source_address_id' AND data_type='varchar' AND character_maximum_length=64 AND is_nullable='YES';")
            }
        }
        $query = $queries -join "`n"
        $results = @($query | & $mysql --host=$MysqlHost --port=$MysqlPort --user=$MysqlUsername --batch --skip-column-names)
        if ($LASTEXITCODE -ne 0) { throw 'Read-only schema preflight failed. MySQL rejected the preflight connection or query; no service started.' }
        foreach ($line in $results) {
            $parts = ([string]$line).Split('|')
            switch ($parts[0]) {
                'TABLE' {
                    if ($parts[2] -ne '1') { throw "Missing $($parts[1]). Obtain migration approval and provision the reviewed schema before starting services." }
                }
                'COLUMNS' {
                    if ($parts[2] -ne [string]$expected[$parts[1]].ColumnCount) { throw "Required columns are missing in $($parts[1])." }
                }
                'ENGINE' {
                    if ($parts[2] -ne 'InnoDB') { throw "Transactional InnoDB is required for $($parts[1])." }
                }
                'SEED' {
                    if ($parts[3] -ne '1') { throw "Missing synchronization lock seed in $($parts[2])." }
                }
                'HANDLING_FEE' {
                    if ($parts[1] -ne '1') { throw 'Order handling_fee requires compatible reviewed V10 schema.' }
                }
                'SOURCE_ADDRESS' {
                    if ($parts[1] -ne '1') { throw 'Order order_addresses.source_address_id requires compatible reviewed V20 schema. Apply V20__add_selected_source_address_reference.sql before starting order-service.' }
                }
            }
        }
    } finally { $env:MYSQL_PWD = $oldPassword }
}

function Wait-ServiceHealth {
    param([string]$Name)
    $deadline = (Get-Date).AddSeconds(300)
    do {
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$($servicePorts[$Name])/actuator/health" -TimeoutSec 15
            if ($health.status -eq 'UP') { Write-Host "Healthy: $Name"; return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Service $Name did not become healthy. Inspect $logDir\$Name.log; startup is not verified."
}

function Stop-Services {
    param([string[]]$Selected)

    foreach ($serviceName in $Selected) {
        if ($serviceName -eq 'Ecom-frontend') {
            Stop-LocalService -Name $serviceName
            continue
        }

        $serviceDir = Join-Path $rootDir $serviceName
        if (Test-Path $serviceDir) {
            Stop-LocalService -Name $serviceName
        } else {
            Write-Warning "Skipping missing service folder: $serviceDir"
        }
    }
}

Require-Command "java"
Require-Command "mvn"
if ($StartFrontend -or ($Service -contains 'Ecom-frontend')) {
    Require-Command "npm"
}

$selectedServices = Resolve-ServiceList -Requested $Service

if ($Action -eq 'stop') {
    Stop-Services -Selected $selectedServices
    Stop-Dependencies
    Write-Host ""
    Write-Host "Stop requested for: $($selectedServices -join ', ')"
    return
}

# Resolve credentials before preflight or stopping services during a restart.
# Environment variables and explicit parameters still support unattended runs.
if ([string]::IsNullOrEmpty($MysqlPassword)) {
    $MysqlPassword = Read-RequiredSecret -Prompt "MySQL password for $MysqlUsername at ${MysqlHost}:${MysqlPort}"
}
$env:MYSQL_PASSWORD = $MysqlPassword
$env:CUSTOMER_MIGRATION_DB_PASSWORD = $MysqlPassword

if ($selectedServices | Where-Object { $_ -in @('cart-service','order-service','payment-service','fulfillment-service','notification-service','inventory-service') }) {
    foreach ($key in @('ORDER_SYNC_KEY','PAYMENT_SYNC_KEY','FULFILLMENT_SYNC_KEY','INVENTORY_SYNC_KEY')) {
        $value = [Environment]::GetEnvironmentVariable($key)
        if ([string]::IsNullOrEmpty($value)) {
            $value = Read-RequiredSecret -Prompt "Enter configured $key (at least 32 characters)" -MinimumLength 32
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        } elseif ($value.Length -lt 32) {
            throw "$key must be configured with at least 32 characters."
        }
    }
}

if ($Action -eq 'restart') {
    Stop-Services -Selected $selectedServices
    Start-Dependencies
    Start-Services -Selected $selectedServices
    Write-Host ""
    Write-Host "Restart completed for: $($selectedServices -join ', ')"
    return
}

Start-Dependencies
Start-Services -Selected $selectedServices
Write-Host ""
Write-Host "Local startup requested for: $($selectedServices -join ', ')"
if ($IsolatedTest) {
    foreach ($name in $selectedServices) { Write-Host "Test service ${name}: http://127.0.0.1:$($servicePorts[$name])" }
} else {
    Write-Host "Frontend: http://127.0.0.1:55173"
    Write-Host "Gateway:  http://localhost:58082"
    Write-Host "Eureka:   http://localhost:58761"
}
Write-Host "Logs:     $logDir"

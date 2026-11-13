param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'releases'),
    [string]$FrontendApiBaseUrl = ''
)

$ErrorActionPreference = 'Stop'
$backendDir = Split-Path -Parent $PSScriptRoot
$workspaceDir = Split-Path -Parent (Split-Path -Parent $backendDir)
$frontendDir = Join-Path $workspaceDir 'Ecom-Frontend'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$releaseDir = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) $stamp
$services = @(
    'Ecom-eureka-server',
    'Ecom-config-server',
    'Ecom-Org-access',
    'customer-profile-service',
    'catalog-service',
    'inventory-service',
    'cart-service',
    'notification-service',
    'fulfillment-service',
    'payment-service',
    'order-service',
    'Ecom-api-gateway'
)

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven is required on PATH.' }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java is required on PATH.' }
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { throw 'Node.js/npm is required on PATH.' }
$priorErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$version = (& java -version 2>&1 | Out-String)
$ErrorActionPreference = $priorErrorAction
if ($version -notmatch 'version "21\.') { throw 'Java 21 is required to build the backend.' }

New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$backendModules = Join-Path $releaseDir 'backend'
New-Item -ItemType Directory -Force -Path $backendModules | Out-Null

Write-Host 'Installing the shared HTTP sync library into the local Maven repository...'
Push-Location (Join-Path $backendDir 'http-sync')
try {
    & mvn -B '-Dmaven.test.skip=true' install
    if ($LASTEXITCODE -ne 0) { throw 'http-sync Maven install failed.' }
} finally { Pop-Location }

foreach ($service in $services) {
    $serviceDir = Join-Path $backendDir $service
    Write-Host "Packaging $service..."
    Push-Location $serviceDir
    try {
        & mvn -B '-Dmaven.test.skip=true' package
        if ($LASTEXITCODE -ne 0) { throw "Maven package failed for $service." }
    } finally { Pop-Location }

    $jar = @(Get-ChildItem -LiteralPath (Join-Path $serviceDir 'target') -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike '*-plain.jar' -and $_.Name -notlike '*-original.jar' } |
        Sort-Object LastWriteTime -Descending)
    if ($jar.Count -eq 0) { throw "No executable jar was produced for $service." }
    Copy-Item -LiteralPath $jar[0].FullName -Destination (Join-Path $backendModules "$service.jar")
}

Write-Host 'Building the frontend production bundle...'
Push-Location $frontendDir
try {
    if (-not (Test-Path -LiteralPath (Join-Path $frontendDir 'node_modules'))) {
        & npm ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    $env:VITE_API_BASE_URL = $FrontendApiBaseUrl
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend production build failed.' }
} finally {
    Remove-Item Env:VITE_API_BASE_URL -ErrorAction SilentlyContinue
    Pop-Location
}
Copy-Item -LiteralPath (Join-Path $frontendDir 'dist') -Destination (Join-Path $releaseDir 'frontend') -Recurse

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Start-Release.ps1') -Destination $releaseDir
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Stop-Release.ps1') -Destination $releaseDir
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'nginx.conf.example') -Destination $releaseDir
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '.env.example') -Destination $releaseDir
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination $releaseDir

Write-Host "Release ready: $releaseDir"
Write-Host 'The release contains compiled jars and static frontend files. It does not contain .env or database credentials.'

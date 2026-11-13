$ErrorActionPreference = 'Stop'
$releaseDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidDir = Join-Path $releaseDir 'pids'
if (-not (Test-Path -LiteralPath $pidDir)) { Write-Host 'No release PID directory found.'; exit 0 }

foreach ($pidFile in Get-ChildItem -LiteralPath $pidDir -Filter '*.pid' -File) {
    $pidValue = 0
    if ([int]::TryParse((Get-Content -LiteralPath $pidFile.FullName -Raw).Trim(), [ref]$pidValue)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $pidValue" -ErrorAction SilentlyContinue
        if ($process -and $process.CommandLine -like "*$(Join-Path $releaseDir 'backend')*") {
            Stop-Process -Id $pidValue -Force -ErrorAction SilentlyContinue
            Write-Host "Stopped $($pidFile.BaseName) (PID $pidValue)."
        }
    }
    Remove-Item -LiteralPath $pidFile.FullName -Force -ErrorAction SilentlyContinue
}

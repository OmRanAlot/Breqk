# run_tests.ps1 — Windows PowerShell shim for the Breqk static test harness.
#
# Usage (from project root):
#   pwsh tests/static/run_tests.ps1
#   pwsh tests/static/run_tests.ps1 --slow
#   pwsh tests/static/run_tests.ps1 --filter manifest
#   pwsh tests/static/run_tests.ps1 --strict --verbose
#
# All arguments are forwarded to run_all.py unchanged.

$ErrorActionPreference = "Stop"

# Find Python executable
$python = $null
foreach ($candidate in @("python", "python3", "py")) {
    try {
        $ver = & $candidate --version 2>&1
        if ($ver -match "Python 3") {
            $python = $candidate
            break
        }
    } catch { }
}

if (-not $python) {
    Write-Error "Python 3 not found. Install Python 3.8+ and ensure it is on PATH."
    exit 2
}

# Resolve the run_all.py path relative to this script
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$runner    = Join-Path $scriptDir "run_all.py"

# Run and propagate exit code
& $python $runner @args
exit $LASTEXITCODE

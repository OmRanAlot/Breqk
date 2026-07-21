# run_tests.ps1 — Windows PowerShell entrypoint for the Break master test runner.
#
# Usage (from project root):
#   pwsh tests/run_tests.ps1                        # both suites
#   pwsh tests/run_tests.ps1 --jest                 # Jest only
#   pwsh tests/run_tests.ps1 --static               # static audit only
#   pwsh tests/run_tests.ps1 --slow                 # include slow static tests
#   pwsh tests/run_tests.ps1 --strict --verbose     # strict + verbose
#   pwsh tests/run_tests.ps1 --filter manifest      # filter static tests
#
# All arguments are forwarded to tests/run_all.py unchanged.

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

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$runner    = Join-Path $scriptDir "run_all.py"

& $python $runner @args
exit $LASTEXITCODE

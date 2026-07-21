# Break Static Test Harness

## What this is
The harness is a zero-dependency Python 3.8+ stack of small self-contained
tests under `tests/static/` that audit manifest wiring, JS↔Java bridge
consistency, prefs key hygiene, code structure, and a static security
surface. It exists alongside (not instead of) Jest/JUnit/Detox.

## Quick start

To run **all** project tests (Jest + static), use the master runner from the project root:

```bash
python tests/run_all.py          # both suites
python tests/run_all.py --static # static only
pwsh   tests/run_tests.ps1       # Windows, both suites
```

To run this static suite in isolation:
- **Windows:**  `pwsh tests/static/run_tests.ps1`
- **Bash:**     `python tests/static/run_all.py`
- **Slow tier** (toolchain + adb): add `--slow`
- **Verbose:**  add `--verbose`
- **One test:** add `--filter <substring>`
- **List only:** add `--list`

## What it covers (and what it does not)
| Category | Covered? |
|----------|----------|
| Manifest ↔ Java class wiring | ✅ tests 001/002/010-019 |
| JS ↔ Java bridge methods | ✅ tests 020-023 |
| SharedPreferences key hygiene | ✅ tests 040-042 |
| Code structure / docs / log tags | ✅ tests 060-065 |
| OWASP Mobile static surface | ✅ tests 075-094 |
| Build / lint (gradle, tsc, eslint) | ✅ tests 100-105 (slow) |
| Live device smoke (adb) | ✅ tests 120-124 (slow) |
| Optional 3rd-party scanners | ✅ tests 140-143 (slow, opt-in) |
| Espresso / Detox / Jest UI tests | ❌ out of scope |
| Real penetration testing | ❌ out of scope (MobSF in 143 is closest) |

## How to read the output
- `[test_NNN_name] …` — banner
- `  step …` — progress
- `VIOLATION: …` / `MISSING: …` / `ORPHAN: …` — one finding per line
- `summary: …` — aggregate counts
- `RESULT: PASS|FAIL|WARN|SKIP: <reason>` — final line, parsed by runner

Status meanings:
- **PASS** — assertion holds
- **WARN** — soft signal (e.g. file > 800 lines, optional config absent)
- **FAIL** — hard signal (real bug, secret leak, missing class)
- **SKIP** — prerequisite missing (binary not on PATH, no device attached)

The runner echoes the full trace on FAIL/ERROR or `--verbose`; PASS runs
stay quiet.

## Numbering scheme (reserve blocks of 20)
- 001-009  — original manifest tests
- 010-019  — manifest & wiring extensions
- 020-029  — JS↔Java bridge consistency
- 040-049  — SharedPreferences hygiene
- 060-069  — code structure & documentation
- 075-094  — security (static)
- 100-119  — build / toolchain (slow)
- 120-139  — adb device smoke (slow)
- 140-149  — optional third-party scanners (slow)

NNN ≥ 90 is "slow tier" by `run_all.py` convention — only runs with --slow.

## Anatomy of a test file
Every test follows the template at `tests/static/_template.py`. Required
sections in order:
  1. Module docstring with WHAT / WHY / HOW / OUTPUTS / EXTEND headings.
  2. CONFIG block (constants, allowlists) at the top of the module.
  3. main() that prints a banner, walks its inputs with progress prints,
     emits one VIOLATION/MISSING/ORPHAN line per finding, prints a summary,
     and ends with exactly one `result(STATUS, "…")` call.

## How to add a test (5 steps)
  1. Copy `tests/static/_template.py` to `test_NNN_<short_name>.py`.
  2. Fill in WHAT/WHY/HOW/OUTPUTS/EXTEND.
  3. Define your CONFIG constants (paths from `_paths`, allowlists).
  4. Implement main(): banner print → scan → per-finding prints → summary →
     `result(PASS|WARN|FAIL|SKIP, "…")`.
  5. Run `pwsh tests/run_tests.ps1 --static --filter <short_name> --verbose`
     (or `pwsh tests/static/run_tests.ps1 --filter <short_name> --verbose`)
     to see the trace.

## How to allowlist a false positive
Each test exposes an `ALLOWLIST` constant near the top. Add the symbol /
file / class name there with a short comment explaining why. Allowlist
entries appear as `SKIPPED: <name>  # reason` in the verbose trace so the
exclusion is visible during review.

## Bug-to-test guard map
This table makes it explicit which test catches a regression of which bug.
Update whenever a P0 bug is fixed — the test should keep guarding it.

| Bug ID | Description | Guard test |
|--------|-------------|------------|
| B2     | BreakVpnService extending VpnService | `test_011_no_vpn_service_regression.py` |
| B5     | versionCode = 1 (launch blocker) | `test_084_versioncode_launch_ready.py` |
| B8     | Release build uses debug keystore | `test_083_release_signing_not_debug.py` |
| B9     | Verbose logging in production | `test_062_no_println_in_production.py` |
| B12    | customize.js > 1500 lines | `test_060_file_size_limit.py` |
| (security baseline) | ProGuard disabled in release | `test_082_proguard_release_minify.py` |

## Known intentional reds
The harness will FAIL out-of-the-box on:
  082 (proguard release minify), 083 (release signing), 084 (versionCode).
These three correspond to documented launch blockers in `docs/TASKS.md`
(B5/B8/security baseline). Fix the production code, then the harness goes
green. The harness should never be made green by relaxing these tests.

## Extension points (for agents)
Agents iterating on this harness should:
  - Read this README, then `_template.py`, then any existing test in the
    target category for an example.
  - Add helpers to `_harness.py` rather than duplicating logic.
  - Add new path constants to `_paths.py` rather than hardcoding paths.
  - Never write a test that imports a third-party package without a SKIP
    fallback when that package is missing.
  - Never emit more than one `RESULT:` line.

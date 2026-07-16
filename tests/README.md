# Break Test Suite

Two complementary suites, one runner.

## Quick start

```bash
# Both suites (recommended)
python tests/run_all.py

# Windows PowerShell
pwsh tests/run_tests.ps1
```

## Suites

| Suite | Location | What it tests |
|-------|----------|---------------|
| Jest unit | `tests/unit/` | JS logic (lockCycle, scheduleWindow) + App render |
| Python static | `tests/static/` | Manifest↔Java wiring, JS↔Java bridge, prefs hygiene, OWASP security |

## Options

| Flag | Effect |
|------|--------|
| `--jest` | Jest suite only |
| `--static` | Static audit only |
| `--slow` | Include slow static tests (090+: build toolchain, adb) |
| `--strict` | Treat WARN as FAIL in static suite |
| `--filter <word>` | Run only static tests whose filename contains `<word>` |
| `--verbose` | Full output for both suites |
| `--list` | List static tests without running |

## npm scripts

```bash
npm test          # both suites via python tests/run_all.py
npm run test:jest   # Jest only (npx jest)
npm run test:static # static audit only
```

## Adding tests

- **JS unit test**: add a `*.test.js` or `*.test.tsx` file under `tests/unit/`.
- **Static audit**: follow the instructions in `tests/static/README.md`.

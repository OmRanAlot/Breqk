"""
run_all.py — Master runner for the Breqk static test harness.

Usage:
  python tests/static/run_all.py              # fast tier (default)
  python tests/static/run_all.py --slow       # include slow tests (test_090+)
  python tests/static/run_all.py --strict     # WARN counts as FAIL
  python tests/static/run_all.py --filter manifest   # only tests with "manifest" in name
  python tests/static/run_all.py --list       # list tests without running
  python tests/static/run_all.py --verbose    # show full output for every test

Exit codes:
  0 — all tests PASS (or WARN/SKIP, unless --strict)
  1 — one or more FAIL
  2 — internal harness error
"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).parent
SLOW_THRESHOLD = 90  # test_NNN where NNN >= 90 is "slow tier"


def parse_result_line(output: str) -> tuple:
    """
    Find the 'RESULT: STATUS: reason' line in subprocess output.
    Returns (status, reason) or ('ERROR', raw_output) if not found.
    """
    for line in reversed(output.splitlines()):
        line = line.strip()
        if line.startswith("RESULT:"):
            parts = line.split(":", 2)
            if len(parts) == 3:
                return parts[1].strip(), parts[2].strip()
    return "ERROR", output.strip()[:120] or "(no output)"


def test_number(path: Path) -> int:
    """Extract the numeric prefix from test_NNN_name.py."""
    try:
        return int(path.stem.split("_")[1])
    except (IndexError, ValueError):
        return 999


def main():
    parser = argparse.ArgumentParser(description="Breqk static test harness")
    parser.add_argument("--slow",    action="store_true", help="Include slow tests (090+)")
    parser.add_argument("--strict",  action="store_true", help="Treat WARN as FAIL")
    parser.add_argument("--filter",  default="",          help="Only run tests matching this string")
    parser.add_argument("--list",    action="store_true", help="List tests without running")
    parser.add_argument("--verbose", action="store_true", help="Show full output for every test")
    args = parser.parse_args()

    # Discover tests
    all_tests = sorted(HERE.glob("test_*.py"), key=test_number)

    # Apply filters
    tests = []
    for t in all_tests:
        num = test_number(t)
        if num >= SLOW_THRESHOLD and not args.slow:
            continue
        if args.filter and args.filter not in t.name:
            continue
        tests.append(t)

    if not tests:
        print("No tests matched. Try --slow or --filter.")
        sys.exit(0)

    tier = "fast+slow" if args.slow else "fast"
    print(f"\nBreqk Static Test Harness")
    print("=" * 40)
    print(f"Running {len(tests)} tests ({tier} tier)\n")

    if args.list:
        for i, t in enumerate(tests, 1):
            print(f"  [{i:03d}] {t.name}")
        sys.exit(0)

    counts = {"PASS": 0, "FAIL": 0, "WARN": 0, "SKIP": 0, "ERROR": 0}
    start_total = time.monotonic()

    for i, test_file in enumerate(tests, 1):
        label = f"[{i:03d}/{len(tests):03d}] {test_file.name}"
        t0 = time.monotonic()

        proc = subprocess.run(
            [sys.executable, str(test_file)],
            capture_output=True, text=True, timeout=120
        )

        elapsed = time.monotonic() - t0
        combined = proc.stdout + proc.stderr
        status, reason = parse_result_line(combined)

        display_status = "FAIL" if (args.strict and status == "WARN") else status
        counts[status] = counts.get(status, 0) + 1

        colour = {"PASS": "\033[32m", "FAIL": "\033[31m", "WARN": "\033[33m",
                  "SKIP": "\033[90m", "ERROR": "\033[35m"}.get(display_status, "")
        reset = "\033[0m"

        print(f"{label:<65} {colour}{display_status:<5}{reset}  ({elapsed:.2f}s)")

        if args.verbose or display_status in ("FAIL", "ERROR"):
            detail = combined.strip()
            if detail:
                for line in detail.splitlines():
                    print(f"   {line}")
            print()

    total = time.monotonic() - start_total
    fail_count = counts["FAIL"] + counts["ERROR"] + (counts["WARN"] if args.strict else 0)

    print()
    print("-" * 55)
    print(f"Summary: {counts['PASS']} PASS, {counts['FAIL']} FAIL, "
          f"{counts['WARN']} WARN, {counts['SKIP']} SKIP, "
          f"{counts['ERROR']} ERROR   ({total:.1f}s total)")
    print("-" * 55)

    if fail_count == 0:
        print("\033[32mOK\033[0m")
        sys.exit(0)
    else:
        print(f"\033[31mFAILED ({fail_count} failure(s))\033[0m")
        sys.exit(1)


if __name__ == "__main__":
    main()

"""
run_all.py — Master test runner for the Break project.

Runs both test suites in order and prints a combined summary:
  1. Jest unit tests   (tests/unit/)
  2. Python static audit (tests/static/)

Usage (from project root):
  python tests/run_all.py              # both suites
  python tests/run_all.py --jest       # Jest only
  python tests/run_all.py --static     # static only
  python tests/run_all.py --slow       # include slow static tests (090+)
  python tests/run_all.py --strict     # WARN counts as FAIL in static suite
  python tests/run_all.py --filter manifest  # pass --filter to static runner
  python tests/run_all.py --verbose    # verbose output for both suites
  python tests/run_all.py --list       # list static tests without running

Exit codes:
  0 — all active suites pass
  1 — one or more tests fail
  2 — internal runner error
"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
STATIC_RUNNER = HERE / "static" / "run_all.py"


def run_jest(verbose: bool) -> tuple[int, float]:
    """Run Jest against tests/unit/ and return (exit_code, elapsed_seconds)."""
    print("\nJest Unit Tests")
    print("=" * 40)
    # npx/npm are .cmd files on Windows; shell=True is required on that platform.
    use_shell = sys.platform == "win32"
    cmd = [
        "npx", "--yes", "jest",
        "--testPathPattern", "tests/unit",
        "--forceExit",
    ]
    if verbose:
        cmd.append("--verbose")

    t0 = time.monotonic()
    result = subprocess.run(cmd, cwd=str(HERE.parent), shell=use_shell)
    elapsed = time.monotonic() - t0
    return result.returncode, elapsed


def run_static(extra_args: list[str]) -> tuple[int, float]:
    """Run the Python static suite and return (exit_code, elapsed_seconds)."""
    print("\nPython Static Audit")
    print("=" * 40)
    cmd = [sys.executable, str(STATIC_RUNNER)] + extra_args
    t0 = time.monotonic()
    result = subprocess.run(cmd, cwd=str(HERE.parent))
    elapsed = time.monotonic() - t0
    return result.returncode, elapsed


def main():
    parser = argparse.ArgumentParser(description="Break master test runner")
    suite = parser.add_mutually_exclusive_group()
    suite.add_argument("--jest",   action="store_true", help="Jest only")
    suite.add_argument("--static", action="store_true", help="Static suite only")

    # Static-suite pass-through flags
    parser.add_argument("--slow",    action="store_true", help="Include slow static tests (090+)")
    parser.add_argument("--strict",  action="store_true", help="Treat WARN as FAIL in static suite")
    parser.add_argument("--filter",  default="",          help="Filter static tests by name substring")
    parser.add_argument("--list",    action="store_true", help="List static tests without running")
    parser.add_argument("--verbose", action="store_true", help="Verbose output for both suites")
    args = parser.parse_args()

    run_both = not args.jest and not args.static

    # Build the static pass-through arg list
    static_args = []
    if args.slow:    static_args.append("--slow")
    if args.strict:  static_args.append("--strict")
    if args.filter:  static_args += ["--filter", args.filter]
    if args.list:    static_args.append("--list")
    if args.verbose: static_args.append("--verbose")

    results = {}  # suite -> (exit_code, elapsed)

    if args.jest or run_both:
        code, elapsed = run_jest(verbose=args.verbose)
        results["jest"] = (code, elapsed)

    if args.static or run_both:
        code, elapsed = run_static(static_args)
        results["static"] = (code, elapsed)

    # Combined summary
    total_elapsed = sum(e for _, e in results.values())
    any_failed = any(c != 0 for c, _ in results.values())

    print()
    print("=" * 55)
    print("COMBINED SUMMARY")
    print("-" * 55)
    for suite_name, (code, elapsed) in results.items():
        status = "\033[32mPASS\033[0m" if code == 0 else "\033[31mFAIL\033[0m"
        print(f"  {suite_name:<10} {status}  ({elapsed:.1f}s)")
    print("-" * 55)
    print(f"  Total time: {total_elapsed:.1f}s")
    print()
    if any_failed:
        print("\033[31mFAILED\033[0m")
        sys.exit(1)
    else:
        print("\033[32mOK\033[0m")
        sys.exit(0)


if __name__ == "__main__":
    main()

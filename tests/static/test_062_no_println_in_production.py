"""
test_062_no_println_in_production.py
=====================================

WHAT:
  Java has zero System.out.println(. WARN on console.log( outside tests/,
  __tests__/, *.test.js.

WHY:
  System.out.println bypasses Android's Logcat TAG filtering and is a
  code smell indicating debug leftovers (B9 regression guard).
  console.log in production JS adds noise to Metro and Hermes output.

HOW:
  1. Grep all Java files for System.out.println(.
  2. Grep all JS/TS files (excluding tests) for console.log(.
  3. FAIL on Java println, WARN on JS console.log.

OUTPUTS:
  PASS — no println or console.log in production code.
  WARN — console.log found in production JS (non-blocking).
  FAIL — System.out.println found in Java.
  SKIP — (not used).

EXTEND:
  - To allowlist a file: add to FILE_ALLOWLIST below.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, WARN, result, grep_java, grep_js
from _paths import PROJECT_ROOT, JAVA_SRC, COMPONENTS_DIR

# ── CONFIG ─────────────────────────────────────────────────────────────
FILE_ALLOWLIST: set = set()
TEST_DIRS = {"tests", "test"}
TEST_ID = Path(__file__).stem


def is_test_file(path: Path) -> bool:
    """Check if a file is in a test directory or is a test file."""
    path_str = str(path)
    for td in TEST_DIRS:
        if f"/{td}/" in path_str or f"\\{td}\\" in path_str:
            return True
    return path.name.endswith(".test.js") or path.name.endswith(".test.ts")


def main() -> None:
    print(f"[{TEST_ID}] checking for System.out.println and console.log in production code")

    # Step 1: Java println
    println_hits = grep_java(JAVA_SRC, r"System\.out\.println\s*\(")
    java_violations = []
    for f, line, content in println_hits:
        if f.name in FILE_ALLOWLIST:
            continue
        java_violations.append(f"{f.name}:{line}")
        print(f"  VIOLATION: {f.name}:{line} — {content}")

    # Step 2: JS console.log (WARN only)
    console_hits = grep_js(COMPONENTS_DIR, r"console\.log\s*\(")
    # Also check App.tsx
    app_tsx = PROJECT_ROOT / "App.tsx"
    if app_tsx.exists():
        import re
        text = app_tsx.read_text(encoding="utf-8", errors="ignore")
        for i, line in enumerate(text.splitlines(), 1):
            if re.search(r"console\.log\s*\(", line):
                console_hits.append((app_tsx, i, line.strip()))

    js_warnings = []
    for f, line, content in console_hits:
        if f.name in FILE_ALLOWLIST or is_test_file(f):
            continue
        js_warnings.append(f"{f.name}:{line}")
        print(f"  WARN-CANDIDATE: {f.name}:{line} — {content}")

    print(f"  summary: {len(java_violations)} println(s), {len(js_warnings)} console.log(s)")

    if java_violations:
        result(FAIL, f"{len(java_violations)} System.out.println call(s) in Java (B9 regression)")
    elif js_warnings:
        result(WARN, f"{len(js_warnings)} console.log call(s) in production JS")
    else:
        result(PASS, "no println or console.log in production code")


if __name__ == "__main__":
    main()

"""
test_042_single_prefs_file_name.py
===================================

WHAT:
  The literal string "breqk_prefs" only appears in BreqkPrefs.java. All other
  files must call BreqkPrefs.get(context) instead of
  context.getSharedPreferences("breqk_prefs", ...) directly.

WHY:
  If the prefs file name is duplicated, a rename would miss one site and
  create a second prefs file — data split across two files, invisible to
  the user.

HOW:
  1. Grep all Java files for the literal string "breqk_prefs".
  2. Exclude BreqkPrefs.java itself.
  3. Report any other files containing the literal.

OUTPUTS:
  PASS — "breqk_prefs" only appears in BreqkPrefs.java.
  FAIL — other Java files contain the raw prefs file name.
  WARN — (not used).
  SKIP — (not used).

EXTEND:
  - To allowlist a file: add to FILE_ALLOWLIST below.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, result, grep_java
from _paths import JAVA_SRC

# ── CONFIG ─────────────────────────────────────────────────────────────
FILE_ALLOWLIST = {"BreqkPrefs.java"}
TEST_ID = Path(__file__).stem
PREFS_FILE_NAME = "breqk_prefs"


def main() -> None:
    print(f"[{TEST_ID}] verifying '{PREFS_FILE_NAME}' only appears in BreqkPrefs.java")

    hits = grep_java(JAVA_SRC, rf'"{PREFS_FILE_NAME}"')
    violations = []

    for f, line, content in hits:
        if f.name in FILE_ALLOWLIST:
            print(f"  OK: {f.name}:{line} (allowlisted)")
            continue
        violations.append(f"{f.name}:{line}")
        print(f"  VIOLATION: {f.name}:{line} — {content}")

    print(f"  summary: {len(violations)} violation(s) of {len(hits)} occurrences")

    if violations:
        result(FAIL, f"{len(violations)} file(s) use raw prefs name '{PREFS_FILE_NAME}' — use BreqkPrefs.get()")
    else:
        result(PASS, f"'{PREFS_FILE_NAME}' only appears in BreqkPrefs.java")


if __name__ == "__main__":
    main()

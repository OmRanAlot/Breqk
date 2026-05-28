"""
test_087_runtime_exec_forbidden.py
===================================

WHAT:
  Forbid in Java: Runtime.getRuntime().exec(, new ProcessBuilder(.

WHY:
  Runtime.exec and ProcessBuilder enable arbitrary command execution.
  If user input flows into these APIs (even indirectly), it's a critical
  remote code execution vulnerability.

HOW:
  1. Grep all Java files for Runtime.getRuntime().exec( and new ProcessBuilder(.
  2. Report each match.

OUTPUTS:
  PASS — no runtime exec patterns found.
  FAIL — runtime exec or ProcessBuilder usage detected.
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
FILE_ALLOWLIST: set = set()
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] scanning Java for Runtime.exec() and ProcessBuilder usage")

    violations = []

    patterns = [
        ("Runtime.exec()", r"Runtime\.getRuntime\(\)\.exec\s*\("),
        ("new ProcessBuilder()", r"new\s+ProcessBuilder\s*\("),
    ]

    for name, pattern in patterns:
        hits = grep_java(JAVA_SRC, pattern)
        for f, line, content in hits:
            if f.name in FILE_ALLOWLIST:
                continue
            violations.append(f"{f.name}:{line} [{name}]")
            print(f"  VIOLATION: {f.name}:{line} — {name}: {content[:80]}")

    print(f"  summary: {len(violations)} violation(s)")

    if violations:
        result(FAIL, f"{len(violations)} Runtime.exec/ProcessBuilder usage(s) found")
    else:
        result(PASS, "no Runtime.exec or ProcessBuilder usage in Java source")


if __name__ == "__main__":
    main()

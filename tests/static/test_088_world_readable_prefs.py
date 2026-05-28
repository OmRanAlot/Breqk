"""
test_088_world_readable_prefs.py — Forbid MODE_WORLD_READABLE/WRITEABLE.
See full docstring in the source.
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, result, grep_java
from _paths import JAVA_SRC

FILE_ALLOWLIST: set = set()
TEST_ID = Path(__file__).stem

def main() -> None:
    print(f"[{TEST_ID}] scanning Java for MODE_WORLD_READABLE/WRITEABLE usage")
    violations = []
    for pattern in [r"MODE_WORLD_READABLE", r"MODE_WORLD_WRITEABLE"]:
        for f, line, content in grep_java(JAVA_SRC, pattern):
            if f.name in FILE_ALLOWLIST:
                continue
            violations.append(f"{f.name}:{line}")
            print(f"  VIOLATION: {f.name}:{line} — {content.strip()[:80]}")
    print(f"  summary: {len(violations)} violation(s)")
    if violations:
        result(FAIL, f"{len(violations)} world-accessible prefs mode(s) found")
    else:
        result(PASS, "no MODE_WORLD_READABLE/WRITEABLE usage in Java source")

if __name__ == "__main__":
    main()

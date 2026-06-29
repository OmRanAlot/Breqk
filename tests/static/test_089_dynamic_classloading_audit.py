"""
test_089_dynamic_classloading_audit.py — WARN for Class.forName with non-literal arg.
"""
import re, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, WARN, result, grep_java
from _paths import JAVA_SRC

TEST_ID = Path(__file__).stem

def main() -> None:
    print(f"[{TEST_ID}] auditing Class.forName usage for potential injection vectors")
    hits = grep_java(JAVA_SRC, r"Class\.forName\s*\(")
    dynamic = []
    for f, line, content in hits:
        # Check if argument is a string literal
        if re.search(r'Class\.forName\s*\(\s*"[^"]*"\s*\)', content):
            print(f"  OK: {f.name}:{line} — static literal")
        else:
            dynamic.append(f"{f.name}:{line}")
            print(f"  WARN-CANDIDATE: {f.name}:{line} — dynamic arg: {content[:80]}")
    print(f"  summary: {len(dynamic)} dynamic Class.forName of {len(hits)} total")
    if dynamic:
        result(WARN, f"{len(dynamic)} dynamic Class.forName call(s) — potential injection vector")
    else:
        result(PASS, f"all {len(hits)} Class.forName calls use static literals")

if __name__ == "__main__":
    main()

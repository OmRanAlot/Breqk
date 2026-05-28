"""
test_086_dangerous_js_patterns.py
==================================

WHAT:
  Forbid in JS: eval(, new Function(, dangerouslySetInnerHTML, direct
  innerHTML = writes.

WHY:
  These patterns enable code injection attacks. eval() and new Function()
  can execute arbitrary code. innerHTML writes bypass React's XSS protection.

HOW:
  1. Walk all JS/TS files.
  2. Regex for forbidden patterns.
  3. Report each match.

OUTPUTS:
  PASS — no dangerous JS patterns found.
  FAIL — one or more dangerous patterns detected.
  WARN — (not used).
  SKIP — (not used).

EXTEND:
  - To allowlist a file: add to FILE_ALLOWLIST below.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, result, walk_source_files
from _paths import PROJECT_ROOT

# ── CONFIG ─────────────────────────────────────────────────────────────
FILE_ALLOWLIST: set = set()
TEST_ID = Path(__file__).stem

DANGEROUS_PATTERNS = [
    ("eval()", re.compile(r'\beval\s*\(')),
    ("new Function()", re.compile(r'\bnew\s+Function\s*\(')),
    ("dangerouslySetInnerHTML", re.compile(r'dangerouslySetInnerHTML')),
    ("innerHTML =", re.compile(r'\.innerHTML\s*=')),
]


def main() -> None:
    print(f"[{TEST_ID}] scanning JS/TS for dangerous patterns (eval, innerHTML, etc.)")

    violations = []
    scanned = 0
    js_extensions = (".js", ".jsx", ".ts", ".tsx")

    for f in walk_source_files(PROJECT_ROOT, extensions=js_extensions):
        scanned += 1
        if f.name in FILE_ALLOWLIST:
            continue
        try:
            text = f.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue

        for i, line in enumerate(text.splitlines(), 1):
            for name, pattern in DANGEROUS_PATTERNS:
                if pattern.search(line):
                    rel = f.relative_to(PROJECT_ROOT)
                    violations.append(f"{rel}:{i} [{name}]")
                    print(f"  VIOLATION: {rel}:{i} — {name}: {line.strip()[:80]}")

    print(f"  scanned: {scanned} JS/TS files; violations: {len(violations)}")

    if violations:
        result(FAIL, f"{len(violations)} dangerous JS pattern(s) found")
    else:
        result(PASS, f"no dangerous JS patterns in {scanned} files")


if __name__ == "__main__":
    main()

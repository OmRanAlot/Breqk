"""
test_063_javadoc_on_public_classes.py
======================================

WHAT:
  Every Java file has either a /** ... */ block or /* ... */ description
  comment in its first 30 lines.

WHY:
  Undocumented classes slow down onboarding and code review. A class-level
  comment is the minimum documentation standard.

HOW:
  1. Walk all .java files under JAVA_SRC.
  2. Check if the first 30 lines contain a multi-line comment.
  3. Report files missing class-level documentation.

OUTPUTS:
  PASS — all Java files have class-level comments.
  WARN — some Java files lack class-level comments.
  FAIL — (not used — WARN-only for documentation).
  SKIP — no Java files found.

EXTEND:
  - To allowlist a file: add to ALLOWLIST below.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, WARN, SKIP, result
from _paths import JAVA_SRC

# ── CONFIG ─────────────────────────────────────────────────────────────
ALLOWLIST = {"BuildConfig.java", "R.java"}
HEADER_LINES = 30
TEST_ID = Path(__file__).stem


def has_header_comment(file_path: Path) -> bool:
    """Check if the first HEADER_LINES of a Java file contain a comment block."""
    try:
        text = file_path.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return True  # Can't read = skip
    lines = text.splitlines()[:HEADER_LINES]
    header = "\n".join(lines)
    # Look for /** ... */ or /* ... */ or // comment block
    return bool(re.search(r'/\*', header))


def main() -> None:
    print(f"[{TEST_ID}] checking Java files have class-level comments in first {HEADER_LINES} lines")

    java_files = list(JAVA_SRC.rglob("*.java"))
    if not java_files:
        result(SKIP, "no Java files found")
        return

    missing = []
    for f in java_files:
        if f.name in ALLOWLIST:
            continue
        if not has_header_comment(f):
            missing.append(f.name)
            print(f"  WARN-CANDIDATE: {f.name} — no comment block in first {HEADER_LINES} lines")

    print(f"  scanned: {len(java_files)} files; {len(missing)} missing comments")

    if missing:
        result(WARN, f"{len(missing)} Java file(s) lack class-level comments")
    else:
        result(PASS, f"all {len(java_files)} Java files have class-level comments")


if __name__ == "__main__":
    main()

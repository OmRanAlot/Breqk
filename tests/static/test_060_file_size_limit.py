"""
test_060_file_size_limit.py
============================

WHAT:
  No file in android/app/src/main/java/, components/, or top-level TS/JS
  over 1500 lines. WARN at 800 lines.

WHY:
  Large files are hard to review, test, and maintain. They indicate a
  decomposition opportunity. Known WARN: customize.js at ~1723 lines (B12).

HOW:
  1. Walk Java, JS, TS files in source directories.
  2. Count lines per file.
  3. WARN at 800+, FAIL at 1500+.

OUTPUTS:
  PASS — all files under 800 lines.
  WARN — files between 800–1499 lines found (soft signal).
  FAIL — files over 1500 lines found.
  SKIP — (not used).

EXTEND:
  - To change thresholds: edit WARN_THRESHOLD and FAIL_THRESHOLD below.
  - To allowlist a file: add its name to ALLOWLIST below.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, WARN, result, walk_source_files
from _paths import PROJECT_ROOT, JAVA_SRC, COMPONENTS_DIR

# ── CONFIG ─────────────────────────────────────────────────────────────
WARN_THRESHOLD = 800
FAIL_THRESHOLD = 1500
ALLOWLIST: set = set()
TEST_ID = Path(__file__).stem

# Directories to scan
SCAN_DIRS = [JAVA_SRC, COMPONENTS_DIR]
# Also check top-level JS/TS files
TOP_LEVEL_EXTENSIONS = (".js", ".jsx", ".ts", ".tsx")


def main() -> None:
    print(f"[{TEST_ID}] checking file sizes (warn >{WARN_THRESHOLD}, fail >{FAIL_THRESHOLD} lines)")

    warnings = []
    failures = []
    scanned = 0

    # Scan directories
    for scan_dir in SCAN_DIRS:
        if not scan_dir.exists():
            continue
        for f in walk_source_files(scan_dir):
            scanned += 1
            if f.name in ALLOWLIST:
                continue
            try:
                lines = len(f.read_text(encoding="utf-8", errors="ignore").splitlines())
            except Exception:
                continue
            rel = f.relative_to(PROJECT_ROOT)
            if lines >= FAIL_THRESHOLD:
                failures.append((str(rel), lines))
                print(f"  VIOLATION: {rel} — {lines} lines (>{FAIL_THRESHOLD})")
            elif lines >= WARN_THRESHOLD:
                warnings.append((str(rel), lines))
                print(f"  WARN-CANDIDATE: {rel} — {lines} lines (>{WARN_THRESHOLD})")

    # Scan top-level files
    for ext in TOP_LEVEL_EXTENSIONS:
        for f in PROJECT_ROOT.glob(f"*{ext}"):
            scanned += 1
            if f.name in ALLOWLIST:
                continue
            try:
                lines = len(f.read_text(encoding="utf-8", errors="ignore").splitlines())
            except Exception:
                continue
            if lines >= FAIL_THRESHOLD:
                failures.append((f.name, lines))
                print(f"  VIOLATION: {f.name} — {lines} lines (>{FAIL_THRESHOLD})")
            elif lines >= WARN_THRESHOLD:
                warnings.append((f.name, lines))
                print(f"  WARN-CANDIDATE: {f.name} — {lines} lines (>{WARN_THRESHOLD})")

    print(f"  scanned: {scanned} files; {len(failures)} fail(s), {len(warnings)} warn(s)")

    if failures:
        result(FAIL, f"{len(failures)} file(s) over {FAIL_THRESHOLD} lines")
    elif warnings:
        result(WARN, f"{len(warnings)} file(s) over {WARN_THRESHOLD} lines (decomposition opportunity)")
    else:
        result(PASS, f"all {scanned} files under {WARN_THRESHOLD} lines")


if __name__ == "__main__":
    main()

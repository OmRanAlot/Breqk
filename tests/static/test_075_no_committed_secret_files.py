"""
test_075_no_committed_secret_files.py
======================================

WHAT:
  Walks the repo (skipping node_modules, build dirs, .git) and FAILs on any
  *.pem, *.key, *.p12, *.jks (except android/app/debug.keystore), *.env, id_rsa*.

WHY:
  Committed secret files are the #1 credential leak vector. A .pem or .env
  file in Git history is permanent even after deletion.

HOW:
  1. Walk the repo excluding SCAN_EXCLUDE_DIRS.
  2. Check each filename against the forbidden patterns.
  3. Report any matches.

OUTPUTS:
  PASS — no secret files found.
  FAIL — one or more secret files committed to the repo.
  WARN — (not used).
  SKIP — (not used).

EXTEND:
  - To allowlist a file: add its name to FILE_ALLOWLIST below.
  - To add more patterns: extend FORBIDDEN_PATTERNS.
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, result
from _paths import PROJECT_ROOT, SCAN_EXCLUDE_DIRS

# ── CONFIG ─────────────────────────────────────────────────────────────
FILE_ALLOWLIST = {"debug.keystore", ".xcode.env"}  # debug.keystore expected; .xcode.env is RN iOS build config (RUBY_VERSION etc.), not secrets
FORBIDDEN_EXTENSIONS = {".pem", ".key", ".p12", ".jks", ".env"}
FORBIDDEN_PREFIXES = {"id_rsa"}
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] scanning repo for committed secret files")

    violations = []
    scanned = 0

    for dirpath, dirnames, filenames in os.walk(PROJECT_ROOT, topdown=True):
        # Prune excluded dirs
        dirnames[:] = [d for d in dirnames if d not in SCAN_EXCLUDE_DIRS]
        for fname in filenames:
            scanned += 1
            if fname in FILE_ALLOWLIST:
                continue

            # Check extensions
            _, ext = os.path.splitext(fname)
            if ext.lower() in FORBIDDEN_EXTENSIONS:
                rel = os.path.relpath(os.path.join(dirpath, fname), PROJECT_ROOT)
                violations.append(rel)
                print(f"  VIOLATION: {rel}")
                continue

            # Check prefixes
            for prefix in FORBIDDEN_PREFIXES:
                if fname.startswith(prefix):
                    rel = os.path.relpath(os.path.join(dirpath, fname), PROJECT_ROOT)
                    violations.append(rel)
                    print(f"  VIOLATION: {rel}")
                    break

    print(f"  scanned: {scanned} files; violations: {len(violations)}")

    if violations:
        result(FAIL, f"{len(violations)} secret file(s) committed to repo")
    else:
        result(PASS, f"no secret files found in {scanned} files scanned")


if __name__ == "__main__":
    main()

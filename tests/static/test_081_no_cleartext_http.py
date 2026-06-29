"""
test_081_no_cleartext_http.py
==============================

WHAT:
  No http:// URLs in source — except localhost / 127.0.0.1 / 10.0.2.2
  (Android emulator host).

WHY:
  Cleartext HTTP allows man-in-the-middle attacks. All production
  traffic must use HTTPS.

HOW:
  1. Walk all source files.
  2. Regex for http:// URLs.
  3. Exclude localhost-family addresses.

OUTPUTS:
  PASS — no cleartext HTTP URLs found.
  FAIL — cleartext HTTP URL(s) found in source.
  WARN — (not used).
  SKIP — (not used).

EXTEND:
  - To allowlist a URL: add to URL_ALLOWLIST below.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, result, walk_source_files
from _paths import PROJECT_ROOT

# ── CONFIG ─────────────────────────────────────────────────────────────
LOCALHOST_PATTERNS = {"localhost", "127.0.0.1", "10.0.2.2", "0.0.0.0"}
URL_ALLOWLIST: set = set()
TEST_ID = Path(__file__).stem
HTTP_PATTERN = re.compile(r'http://([^\s"\'>/]+)')


def main() -> None:
    print(f"[{TEST_ID}] scanning source for cleartext http:// URLs")

    violations = []
    scanned = 0

    for f in walk_source_files(PROJECT_ROOT):
        scanned += 1
        try:
            text = f.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue

        for i, line in enumerate(text.splitlines(), 1):
            for m in HTTP_PATTERN.finditer(line):
                host = m.group(1).split("/")[0].split(":")[0]
                if host in LOCALHOST_PATTERNS:
                    continue
                url = m.group(0)
                if url in URL_ALLOWLIST:
                    continue
                rel = f.relative_to(PROJECT_ROOT)
                violations.append(f"{rel}:{i}")
                print(f"  VIOLATION: {rel}:{i} — {url}")

    print(f"  scanned: {scanned} files; violations: {len(violations)}")

    if violations:
        result(FAIL, f"{len(violations)} cleartext http:// URL(s) found")
    else:
        result(PASS, f"no cleartext HTTP URLs in {scanned} source files")


if __name__ == "__main__":
    main()

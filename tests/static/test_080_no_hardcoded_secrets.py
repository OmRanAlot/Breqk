"""
test_080_no_hardcoded_secrets.py
=================================

WHAT:
  Regex scan across Java/JS/Kotlin for hardcoded secrets: AWS keys, Google
  API keys, Slack tokens, private keys, JWTs, GitHub PATs, and generic
  api_key/secret/password patterns.

WHY:
  Hardcoded secrets in source code are the #1 credential exposure vector
  per OWASP Mobile Top 10 (M9 — Reverse Engineering). Secrets in committed
  code persist in Git history.

HOW:
  1. Walk source files (Java, JS, TS, Kotlin).
  2. Apply a battery of regex patterns for known secret formats.
  3. Report each match.

OUTPUTS:
  PASS — no hardcoded secrets detected.
  FAIL — one or more potential secrets found.
  WARN — (not used).
  SKIP — (not used).

EXTEND:
  - To allowlist a line/pattern: add to LINE_ALLOWLIST below.
  - To add a new pattern: add to SECRET_PATTERNS.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, result, walk_source_files
from _paths import PROJECT_ROOT

# ── CONFIG ─────────────────────────────────────────────────────────────
LINE_ALLOWLIST = {
    "example",
    "placeholder",
    "YOUR_API_KEY",
    "INSERT_KEY_HERE",
}
TEST_ID = Path(__file__).stem

SECRET_PATTERNS = [
    ("AWS Access Key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("Google API Key", re.compile(r"AIza[0-9A-Za-z\-_]{35}")),
    ("Slack Token", re.compile(r"xox[abp]-[0-9A-Za-z\-]+")),
    ("Stripe Live Key", re.compile(r"sk_live_[0-9A-Za-z]{24,}")),
    ("Private Key", re.compile(r"-----BEGIN (?:RSA |EC )?PRIVATE KEY-----")),
    ("GitHub PAT", re.compile(r"gh[ps]_[A-Za-z0-9]{36,}")),
    ("Generic Secret", re.compile(
        r'(?i)(?:api[_\-]?key|secret|password|token)\s*[=:]\s*["\'][^"\']{12,}["\']'
    )),
]


def is_allowlisted(line: str) -> bool:
    line_lower = line.lower()
    return any(al in line_lower for al in LINE_ALLOWLIST)


def main() -> None:
    print(f"[{TEST_ID}] scanning source files for hardcoded secrets")

    violations = []
    scanned = 0

    for f in walk_source_files(PROJECT_ROOT):
        scanned += 1
        try:
            text = f.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue

        for i, line in enumerate(text.splitlines(), 1):
            if is_allowlisted(line):
                continue
            for name, pattern in SECRET_PATTERNS:
                if pattern.search(line):
                    rel = f.relative_to(PROJECT_ROOT)
                    violations.append(f"{rel}:{i} [{name}]")
                    print(f"  LEAK: {rel}:{i} — {name}: {line.strip()[:80]}")

    print(f"  scanned: {scanned} files; leaks: {len(violations)}")

    if violations:
        result(FAIL, f"{len(violations)} potential secret(s) found in source code")
    else:
        result(PASS, f"no hardcoded secrets in {scanned} source files")


if __name__ == "__main__":
    main()

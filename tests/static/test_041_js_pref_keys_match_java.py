"""
test_041_js_pref_keys_match_java.py
====================================

WHAT:
  Every key string passed to SettingsModule.getSetting('xxx') /
  setSetting('xxx', ...) from JS exists as a KEY_* constant in BreakPrefs.java.

WHY:
  A JS key string that doesn't match any Java KEY_* constant means the JS
  side is reading/writing a pref that doesn't exist in Java — silently
  returns defaults and the setting has no effect.

HOW:
  1. Parse BreakPrefs.java for all KEY_* constant values.
  2. Scan JS/TS files for getSetting('key') / setSetting('key', ...) calls.
  3. Report JS keys that have no matching Java KEY_* constant.

OUTPUTS:
  PASS — all JS pref keys match Java KEY_* constants.
  FAIL — one or more JS keys have no matching Java constant.
  WARN — (not used).
  SKIP — BreakPrefs.java not found or no JS setting calls found.

EXTEND:
  - To allowlist a JS key: add to ALLOWLIST below.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, SKIP, result, grep_js
from _paths import PROJECT_ROOT, Break_PREFS

# ── CONFIG ─────────────────────────────────────────────────────────────
ALLOWLIST: set = set()
TEST_ID = Path(__file__).stem


def extract_java_key_values(prefs_file: Path) -> set:
    """Extract string values from KEY_* and FEATURE_* constants in BreakPrefs.java."""
    text = prefs_file.read_text(encoding="utf-8", errors="ignore")
    values = set()
    for m in re.finditer(r'public\s+static\s+final\s+String\s+(?:KEY_|FEATURE_)\w+\s*=\s*"([^"]+)"', text):
        values.add(m.group(1))
    return values


def main() -> None:
    print(f"[{TEST_ID}] verifying JS pref keys match Java KEY_* constants")

    if not Break_PREFS.exists():
        result(SKIP, f"BreakPrefs.java not found at {Break_PREFS}")
        return

    java_keys = extract_java_key_values(Break_PREFS)
    print(f"  Java KEY_*/FEATURE_* values: {len(java_keys)}")

    # Find JS calls: getSetting('key'), setSetting('key', ...)
    setting_pattern = r"(?:getSetting|setSetting)\s*\(\s*['\"]([^'\"]+)['\"]"
    hits = grep_js(PROJECT_ROOT, setting_pattern)

    js_keys = set()
    for f, line, content in hits:
        for m in re.finditer(setting_pattern, content):
            js_keys.add(m.group(1))

    print(f"  JS setting keys found: {len(js_keys)}")

    if not js_keys:
        result(SKIP, "no getSetting/setSetting calls found in JS")
        return

    violations = []
    for key in sorted(js_keys):
        if key in ALLOWLIST:
            print(f"  SKIPPED: '{key}'  # allowlisted")
            continue

        if key in java_keys:
            print(f"  OK: '{key}' matches Java constant")
        else:
            violations.append(key)
            print(f"  VIOLATION: JS key '{key}' has no matching Java KEY_* constant")

    print(f"  summary: {len(violations)} unmatched of {len(js_keys)} JS keys")

    if violations:
        result(FAIL, f"{len(violations)} JS pref key(s) have no matching Java constant")
    else:
        result(PASS, f"all {len(js_keys)} JS pref keys match Java KEY_* constants")


if __name__ == "__main__":
    main()

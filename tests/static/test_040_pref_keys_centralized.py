"""
test_040_pref_keys_centralized.py
==================================

WHAT:
  Java files outside BreqkPrefs.java and WidgetPrefs.java should not contain
  raw string literals matching keys defined in BreqkPrefs.KEY_* (forces use
  of constants).

WHY:
  Raw string keys are a typo hazard. If a developer writes "monitorin_enabled"
  instead of KEY_MONITORING_ENABLED, the pref read silently returns the default
  and the feature appears broken.

HOW:
  1. Parse BreqkPrefs.java for all KEY_* constant values.
  2. Scan all other Java files for those literal strings.
  3. Report violations (files using raw strings instead of constants).

OUTPUTS:
  PASS — no raw pref key strings found outside BreqkPrefs/WidgetPrefs.
  WARN — raw key strings found (likely should use constants).
  FAIL — (not used — WARN-only to allow migration code).
  SKIP — BreqkPrefs.java not found.

EXTEND:
  - To allowlist a file or class: add to FILE_ALLOWLIST below.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, WARN, SKIP, result
from _paths import JAVA_SRC, BREQK_PREFS

# ── CONFIG ─────────────────────────────────────────────────────────────
FILE_ALLOWLIST = {"BreqkPrefs.java", "WidgetPrefs.java"}
TEST_ID = Path(__file__).stem


def extract_key_values(prefs_file: Path) -> set:
    """Extract string values from KEY_* constants in BreqkPrefs.java."""
    text = prefs_file.read_text(encoding="utf-8", errors="ignore")
    # Match: public static final String KEY_xxx = "value";
    values = set()
    for m in re.finditer(r'public\s+static\s+final\s+String\s+KEY_\w+\s*=\s*"([^"]+)"', text):
        values.add(m.group(1))
    return values


def main() -> None:
    print(f"[{TEST_ID}] checking pref keys are centralized in BreqkPrefs constants")

    if not BREQK_PREFS.exists():
        result(SKIP, f"BreqkPrefs.java not found at {BREQK_PREFS}")
        return

    key_values = extract_key_values(BREQK_PREFS)
    print(f"  extracted {len(key_values)} KEY_* constant values from BreqkPrefs.java")

    violations = []
    scanned = 0

    for java_file in JAVA_SRC.rglob("*.java"):
        if java_file.name in FILE_ALLOWLIST:
            continue
        scanned += 1
        text = java_file.read_text(encoding="utf-8", errors="ignore")
        for key_val in key_values:
            # Look for raw string literal usage (not as part of a constant definition)
            pattern = f'"{re.escape(key_val)}"'
            if pattern.replace("\\", "") in text:
                # Check it's not just importing or referencing the constant
                for i, line in enumerate(text.splitlines(), 1):
                    if f'"{key_val}"' in line and "KEY_" not in line and "PREFS_NAME" not in line:
                        violations.append(f"{java_file.name}:{i} uses raw key '{key_val}'")
                        print(f"  VIOLATION: {java_file.name}:{i} — raw key '{key_val}'")

    print(f"  scanned: {scanned} Java files; violations: {len(violations)}")

    if violations:
        result(WARN, f"{len(violations)} raw pref key string(s) found outside BreqkPrefs (use constants)")
    else:
        result(PASS, f"no raw pref key strings found in {scanned} Java files")


if __name__ == "__main__":
    main()

"""
test_017_allow_backup_disabled.py
==================================

WHAT:
  android:allowBackup="false" is set on <application> and
  android:fullBackupContent is not set (warn-only).

WHY:
  allowBackup=true (the default) allows ADB backup to extract
  SharedPreferences containing app policies, blocked apps, and mode
  configurations. An attacker with USB access can dump all user data.

HOW:
  1. Parse AndroidManifest.xml.
  2. Check <application android:allowBackup="false">.
  3. WARN if android:fullBackupContent is set (shouldn't be needed
     when allowBackup is false).

OUTPUTS:
  PASS — allowBackup="false" and no fullBackupContent.
  WARN — allowBackup="false" but fullBackupContent is set.
  FAIL — allowBackup is not "false" (missing or "true").
  SKIP — (not used).

EXTEND:
  - No allowlist needed.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, WARN, result, time_guard, parse_manifest, android_attr
from _paths import MANIFEST

# ── CONFIG ─────────────────────────────────────────────────────────────
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] verifying android:allowBackup='false' on <application>")

    with time_guard(Path(__file__)):
        root = parse_manifest(MANIFEST)
        app = root.find("application")

        if app is None:
            result(FAIL, "no <application> element found in manifest")
            return

        allow_backup = android_attr(app, "allowBackup")
        full_backup = android_attr(app, "fullBackupContent")
        print(f"  android:allowBackup = '{allow_backup}'")
        print(f"  android:fullBackupContent = '{full_backup}'")

        if allow_backup.lower() != "false":
            print("  VIOLATION: allowBackup is not 'false'")
            result(
                FAIL,
                "android:allowBackup is not 'false' — data extractable via ADB backup",
            )
        elif full_backup:
            print(
                "  WARN-CANDIDATE: fullBackupContent is set despite allowBackup='false'"
            )
            result(
                WARN,
                "allowBackup='false' but fullBackupContent is also set (redundant)",
            )
        else:
            result(
                PASS, "allowBackup='false' and no fullBackupContent — data protected"
            )


if __name__ == "__main__":
    main()

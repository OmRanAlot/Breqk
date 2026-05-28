"""
test_016_no_debuggable_attribute.py
====================================

WHAT:
  <application> does not set android:debuggable="true". Build types
  should drive this — an explicit debuggable=true in the manifest
  overrides Gradle and ships a debuggable APK to production.

WHY:
  A debuggable production APK allows ADB attach, memory dumps, and
  bypasses certificate pinning. This is a critical security issue
  that will be caught by Google Play Protect but may slip through
  side-loading.

HOW:
  1. Parse AndroidManifest.xml.
  2. Find the <application> element.
  3. Check that android:debuggable is NOT "true".

OUTPUTS:
  PASS — <application> does not set debuggable="true".
  FAIL — <application> has android:debuggable="true".
  WARN — (not used).
  SKIP — (not used).

EXTEND:
  - No allowlist needed. Debuggable should never be hardcoded in manifest.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, result, time_guard, parse_manifest, android_attr
from _paths import MANIFEST

# ── CONFIG ─────────────────────────────────────────────────────────────
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] verifying <application> does not set android:debuggable='true'")

    with time_guard(Path(__file__)):
        root = parse_manifest(MANIFEST)
        app = root.find("application")

        if app is None:
            print("  VIOLATION: no <application> element found in manifest")
            result(FAIL, "no <application> element found in manifest")
            return

        debuggable = android_attr(app, "debuggable")
        print(f"  android:debuggable = '{debuggable}' (empty = not set = OK)")

        if debuggable.lower() == "true":
            print("  VIOLATION: android:debuggable='true' is set in manifest")
            result(
                FAIL, "android:debuggable='true' hardcoded in manifest — security risk"
            )
        else:
            result(PASS, "<application> does not set debuggable='true'")


if __name__ == "__main__":
    main()

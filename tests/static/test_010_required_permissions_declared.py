"""
test_010_required_permissions_declared.py
=========================================

WHAT:
  All required <uses-permission> entries from CLAUDE.md are present in
  AndroidManifest.xml. WARN if extras appear that aren't in the allowlist.

WHY:
  Missing permissions cause runtime SecurityExceptions or silent feature
  failures (e.g., FOREGROUND_SERVICE missing = crash on startForeground).
  Extra undocumented permissions raise Play Store review red flags.

HOW:
  1. Parse AndroidManifest.xml.
  2. Extract all <uses-permission> android:name values.
  3. Check that every REQUIRED_PERMISSION is present.
  4. WARN on any extras not in the EXTRAS_ALLOWLIST.

OUTPUTS:
  PASS — all required permissions present and no unexpected extras.
  WARN — all required present, but unexpected extras found.
  FAIL — one or more required permissions missing from manifest.
  SKIP — (not used).

EXTEND:
  - To add a new required permission: add to REQUIRED_PERMISSIONS below.
  - To allowlist an extra permission: add to EXTRAS_ALLOWLIST below.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import (
    PASS,
    FAIL,
    WARN,
    SKIP,
    result,
    time_guard,
    parse_manifest,
    parse_manifest_uses_permissions,
)
from _paths import MANIFEST

# ── CONFIG ─────────────────────────────────────────────────────────────
REQUIRED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.RECEIVE_BOOT_COMPLETED",
}

EXTRAS_ALLOWLIST = {
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
}

TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] verifying all required permissions are declared in manifest")
    print(f"  manifest: {MANIFEST}")

    with time_guard(Path(__file__)):
        root = parse_manifest(MANIFEST)
        declared = parse_manifest_uses_permissions(root)
        print(f"  declared permissions: {len(declared)}")
        print(f"  required permissions: {len(REQUIRED_PERMISSIONS)}")

        # Check for missing required permissions
        missing = REQUIRED_PERMISSIONS - declared
        for p in sorted(missing):
            print(f"  MISSING: {p}")

        # Check for unexpected extras
        expected = REQUIRED_PERMISSIONS | EXTRAS_ALLOWLIST
        extras = declared - expected
        for p in sorted(extras):
            print(f"  WARN-CANDIDATE: unexpected permission {p}")

        print(
            f"  summary: {len(missing)} missing, {len(extras)} unexpected of {len(declared)} declared"
        )

        if missing:
            result(FAIL, f"{len(missing)} required permission(s) missing from manifest")
        elif extras:
            result(
                WARN,
                f"all required permissions present, but {len(extras)} unexpected extra(s)",
            )
        else:
            result(
                PASS, f"all {len(REQUIRED_PERMISSIONS)} required permissions declared"
            )


if __name__ == "__main__":
    main()

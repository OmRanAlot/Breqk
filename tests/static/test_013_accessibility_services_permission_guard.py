"""
test_013_accessibility_services_permission_guard.py
===================================================

WHAT:
  Every accessibility <service> element has
  android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE".

WHY:
  Without this permission guard, any third-party app could bind to our
  accessibility service and receive accessibility events, leaking screen
  content to attackers.

HOW:
  1. Parse AndroidManifest.xml.
  2. Find all <service> elements with an accessibility intent-filter.
  3. Assert each has android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE".

OUTPUTS:
  PASS — all accessibility services have BIND_ACCESSIBILITY_SERVICE permission.
  FAIL — one or more accessibility services lack the permission guard.
  WARN — (not used).
  SKIP — no accessibility services found.

EXTEND:
  - To allowlist a service: add its android:name to ALLOWLIST below.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, SKIP, result, time_guard, parse_manifest, android_attr
from _paths import MANIFEST

# ── CONFIG ─────────────────────────────────────────────────────────────
ALLOWLIST: set = set()
TEST_ID = Path(__file__).stem
REQUIRED_PERMISSION = "android.permission.BIND_ACCESSIBILITY_SERVICE"


def main() -> None:
    print(
        f"[{TEST_ID}] verifying accessibility services have BIND_ACCESSIBILITY_SERVICE permission"
    )

    with time_guard(Path(__file__)):
        root = parse_manifest(MANIFEST)
        accessibility_services = []

        for svc in root.iter("service"):
            for ifilt in svc.iter("intent-filter"):
                for action in ifilt.iter("action"):
                    if (
                        android_attr(action, "name")
                        == "android.accessibilityservice.AccessibilityService"
                    ):
                        accessibility_services.append(svc)
                        break

        print(f"  found {len(accessibility_services)} accessibility service(s)")

        if not accessibility_services:
            result(SKIP, "no accessibility services found in manifest")
            return

        violations = []
        for svc in accessibility_services:
            name = android_attr(svc, "name")
            if name in ALLOWLIST:
                print(f"  SKIPPED: {name}  # allowlisted")
                continue

            perm = android_attr(svc, "permission")
            print(f"  checking: {name} -> permission='{perm}'")

            if perm != REQUIRED_PERMISSION:
                violations.append(name)
                print(f"  VIOLATION: {name} lacks {REQUIRED_PERMISSION}")

        print(
            f"  summary: {len(violations)} violation(s) of {len(accessibility_services)} accessibility services"
        )

        if violations:
            result(
                FAIL,
                f"{len(violations)} accessibility service(s) missing BIND_ACCESSIBILITY_SERVICE permission",
            )
        else:
            result(
                PASS,
                f"all {len(accessibility_services)} accessibility services have permission guard",
            )


if __name__ == "__main__":
    main()

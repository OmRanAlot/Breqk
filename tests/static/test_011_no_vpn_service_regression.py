"""
test_011_no_vpn_service_regression.py
=====================================

WHAT:
  Guards B2 fix: no Java file extends VpnService; manifest has no
  BIND_VPN_SERVICE permission, no foregroundServiceType="vpn", and no
  VPN intent-filter android.net.VpnService.

WHY:
  The original BreqkVpnService was migrated from VpnService to a standard
  foreground Service (B2 fix) to avoid Google Play Store rejection and
  improve user trust. If any VPN artifact regresses back into the code,
  the app will be rejected.

HOW:
  1. Grep all Java files for "extends VpnService".
  2. Check manifest for BIND_VPN_SERVICE permission on any <service>.
  3. Check manifest for foregroundServiceType="vpn" on any <service>.
  4. Check manifest for android.net.VpnService intent-filter action.

OUTPUTS:
  PASS — no VPN artifacts found in code or manifest.
  FAIL — one or more VPN artifacts detected (B2 regression).
  WARN — (not used).
  SKIP — (not used).

EXTEND:
  - This test should never need an allowlist. Any VPN reference is a regression.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import (
    PASS,
    FAIL,
    result,
    time_guard,
    parse_manifest,
    android_attr,
    grep_java,
    iter_components,
    parse_intent_filters,
)
from _paths import MANIFEST, JAVA_SRC

# ── CONFIG ─────────────────────────────────────────────────────────────
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] guarding against VPN service regression (B2)")

    with time_guard(Path(__file__)):
        violations = []

        # Step 1: grep Java for "extends VpnService"
        print("  step 1: scanning Java files for 'extends VpnService'")
        hits = grep_java(JAVA_SRC, r"\bextends\s+VpnService\b")
        for f, line, content in hits:
            violations.append(f"Java extends VpnService: {f.name}:{line}")
            print(f"  VIOLATION: {f.name}:{line} — {content}")

        # Step 2-4: check manifest
        print("  step 2: scanning manifest for VPN artifacts")
        root = parse_manifest(MANIFEST)

        for el, tag in iter_components(root):
            name = android_attr(el, "name")
            perm = android_attr(el, "permission")
            fst = android_attr(el, "foregroundServiceType")

            if "BIND_VPN_SERVICE" in perm:
                violations.append(f"BIND_VPN_SERVICE on {tag} {name}")
                print(f"  VIOLATION: {tag} '{name}' has BIND_VPN_SERVICE permission")

            if fst == "vpn":
                violations.append(f"foregroundServiceType=vpn on {tag} {name}")
                print(f"  VIOLATION: {tag} '{name}' has foregroundServiceType='vpn'")

            filters = parse_intent_filters(el)
            for filt in filters:
                if "android.net.VpnService" in filt["actions"]:
                    violations.append(f"VpnService intent-filter on {tag} {name}")
                    print(
                        f"  VIOLATION: {tag} '{name}' has android.net.VpnService intent-filter"
                    )

        print(f"  summary: {len(violations)} VPN artifact(s) found")

        if violations:
            result(FAIL, f"{len(violations)} VPN artifact(s) detected — B2 regression")
        else:
            result(PASS, "no VPN artifacts found — B2 fix intact")


if __name__ == "__main__":
    main()

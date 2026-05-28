"""
test_019_deep_link_scheme_locked_down.py
=========================================

WHAT:
  The only deep-link <intent-filter> on MainActivity uses scheme="breqk"
  AND a non-empty host. No wildcard hosts. No http/https deep links
  accepted by MainActivity.

WHY:
  An http/https intent-filter would make the app a candidate handler for
  web URLs, causing Android disambiguation dialogs and potential phishing
  attack surface. The breqk:// scheme with explicit host is safe because
  only our widget and internal code generate those URIs.

HOW:
  1. Parse AndroidManifest.xml.
  2. Find MainActivity.
  3. For each <intent-filter> with a <data> element:
     a. Assert scheme is "breqk" (not http/https).
     b. Assert host is non-empty (no wildcard).

OUTPUTS:
  PASS — all deep-link filters use scheme="breqk" with non-empty host.
  FAIL — an intent-filter uses http/https scheme or has empty/missing host.
  WARN — (not used).
  SKIP — no deep-link intent-filters found on MainActivity.

EXTEND:
  - If a new deep-link scheme is added, update ALLOWED_SCHEMES below.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import (
    PASS,
    FAIL,
    SKIP,
    result,
    time_guard,
    parse_manifest,
    android_attr,
    parse_intent_filters,
)
from _paths import MANIFEST

# ── CONFIG ─────────────────────────────────────────────────────────────
ALLOWED_SCHEMES = {"breqk"}
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] verifying deep-link scheme is locked down on MainActivity")

    with time_guard(Path(__file__)):
        root = parse_manifest(MANIFEST)

        main_activity = None
        for act in root.iter("activity"):
            name = android_attr(act, "name")
            if name.endswith("MainActivity"):
                main_activity = act
                break

        if main_activity is None:
            result(FAIL, "MainActivity not found in manifest")
            return

        filters = parse_intent_filters(main_activity)
        deep_link_filters = [f for f in filters if f["data"]]

        if not deep_link_filters:
            result(SKIP, "no deep-link intent-filters found on MainActivity")
            return

        violations = []
        for filt in deep_link_filters:
            for data in filt["data"]:
                scheme = data["scheme"]
                host = data["host"]
                print(f"  checking: scheme='{scheme}' host='{host}'")

                if scheme and scheme not in ALLOWED_SCHEMES:
                    violations.append(f"forbidden scheme '{scheme}'")
                    print(f"  VIOLATION: scheme '{scheme}' is not in {ALLOWED_SCHEMES}")

                if scheme in ALLOWED_SCHEMES and not host:
                    violations.append(f"scheme '{scheme}' has empty/wildcard host")
                    print(f"  VIOLATION: scheme '{scheme}' has no host (wildcard)")

        print(
            f"  summary: {len(violations)} violation(s) across {len(deep_link_filters)} deep-link filter(s)"
        )

        if violations:
            result(
                FAIL,
                f"{len(violations)} deep-link violation(s) — see VIOLATION lines above",
            )
        else:
            result(PASS, "all deep-link filters use 'breqk' scheme with explicit host")


if __name__ == "__main__":
    main()

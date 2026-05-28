"""
test_015_exported_components_justified.py
==========================================

WHAT:
  For every <activity|service|receiver> with android:exported="true": must have
  either a launcher intent-filter, breqk deep-link, BIND-flavoured permission,
  or be an AppWidget receiver. Lists violators.

WHY:
  Exported components without justification are attack surface. A malicious app
  can start, bind to, or send broadcasts to unprotected exported components.

HOW:
  1. Parse AndroidManifest.xml.
  2. Iterate all components with android:exported="true".
  3. Check if justified by: launcher intent-filter, breqk scheme, BIND_*
     permission, or appwidget action.
  4. Report unjustified exports.

OUTPUTS:
  PASS — all exported components are justified.
  FAIL — one or more exported components lack justification.
  WARN — (not used).
  SKIP — no exported components found.

EXTEND:
  - To allowlist a component: add its android:name to ALLOWLIST below.
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
    iter_components,
    is_exported,
    parse_intent_filters,
)
from _paths import MANIFEST

# ── CONFIG ─────────────────────────────────────────────────────────────
ALLOWLIST: set = set()
TEST_ID = Path(__file__).stem


def is_justified(element) -> bool:
    """Check if an exported component has a valid justification."""
    # Has a BIND-flavoured permission
    perm = android_attr(element, "permission")
    if "BIND_" in perm:
        return True

    filters = parse_intent_filters(element)
    for filt in filters:
        actions = filt["actions"]

        # Launcher intent-filter
        if "android.intent.action.MAIN" in actions:
            return True

        # AppWidget
        if "android.appwidget.action.APPWIDGET_UPDATE" in actions:
            return True

        # Device admin
        if "android.app.action.DEVICE_ADMIN_ENABLED" in actions:
            return True

        # Boot receiver
        if "android.intent.action.BOOT_COMPLETED" in actions:
            return True

        # Deep-link with breqk scheme
        for data in filt["data"]:
            if data["scheme"] == "breqk":
                return True

    return False


def main() -> None:
    print(f"[{TEST_ID}] verifying all exported components are justified")

    root = parse_manifest(MANIFEST)
    exported_components = []
    violations = []

    for el, tag in iter_components(root):
        if not is_exported(el):
            continue
        name = android_attr(el, "name")
        exported_components.append((name, tag, el))

    print(f"  found {len(exported_components)} exported component(s)")

    if not exported_components:
        result(SKIP, "no exported components found")
        return

    for name, tag, el in exported_components:
        if name in ALLOWLIST:
            print(f"  SKIPPED: {name}  # allowlisted")
            continue

        if is_justified(el):
            print(f"  OK: {tag} '{name}' — justified")
        else:
            violations.append(f"{tag} '{name}'")
            print(f"  VIOLATION: {tag} '{name}' is exported without justification")

    print(
        f"  summary: {len(violations)} unjustified export(s) of {len(exported_components)} exported"
    )

    if violations:
        result(FAIL, f"{len(violations)} exported component(s) lack justification")
    else:
        result(
            PASS, f"all {len(exported_components)} exported components are justified"
        )


if __name__ == "__main__":
    main()

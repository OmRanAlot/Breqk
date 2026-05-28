"""
test_012_accessibility_service_xml_resources_exist.py
=====================================================

WHAT:
  For every <service> declaring <meta-data android:name="android.accessibilityservice">,
  the referenced @xml/<resource> file exists in res/xml/ and parses as valid XML.

WHY:
  A missing or malformed accessibility config XML causes the service to fail
  registration. Android silently ignores the service and the user sees no
  accessibility toggle in Settings — the app appears broken.

HOW:
  1. Parse AndroidManifest.xml.
  2. Find all <service> elements with <meta-data android:name="android.accessibilityservice">.
  3. Extract the @xml/<resource> reference.
  4. Assert the file exists at res/xml/<resource>.xml and parses.

OUTPUTS:
  PASS — all accessibility config XMLs exist and parse.
  FAIL — one or more accessibility config XMLs are missing or malformed.
  WARN — (not used).
  SKIP — no accessibility services declared.

EXTEND:
  - To allowlist a service: add its android:name to ALLOWLIST below.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, SKIP, result, time_guard, parse_manifest, android_attr
from _paths import MANIFEST, RES_XML_DIR

# ── CONFIG ─────────────────────────────────────────────────────────────
ALLOWLIST: set = set()
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] verifying accessibility service XML resources exist and parse")

    with time_guard(Path(__file__)):
        root = parse_manifest(MANIFEST)
        services = []

        for svc in root.iter("service"):
            for meta in svc.iter("meta-data"):
                if android_attr(meta, "name") == "android.accessibilityservice":
                    resource = android_attr(meta, "resource")
                    services.append((android_attr(svc, "name"), resource))

        print(f"  found {len(services)} accessibility service(s) with config metadata")

        if not services:
            result(SKIP, "no accessibility services with config metadata found")
            return

        violations = []
        for svc_name, resource_ref in services:
            if svc_name in ALLOWLIST:
                print(f"  SKIPPED: {svc_name}  # allowlisted")
                continue

            # resource_ref is like "@xml/reels_intervention_service_config"
            if not resource_ref.startswith("@xml/"):
                violations.append(
                    f"{svc_name}: resource ref '{resource_ref}' not @xml/ format"
                )
                print(
                    f"  VIOLATION: {svc_name} has unexpected resource ref: {resource_ref}"
                )
                continue

            xml_name = resource_ref.replace("@xml/", "") + ".xml"
            xml_path = RES_XML_DIR / xml_name
            print(f"  checking: {svc_name} -> {xml_name}")

            if not xml_path.exists():
                violations.append(f"{svc_name}: {xml_name} missing")
                print(f"  MISSING: {xml_path}")
                continue

            # Try to parse
            try:
                ET.parse(xml_path)
                print(f"  OK: {xml_name} exists and parses")
            except ET.ParseError as e:
                violations.append(f"{svc_name}: {xml_name} malformed: {e}")
                print(f"  VIOLATION: {xml_name} is malformed XML: {e}")

        print(
            f"  summary: {len(violations)} violation(s) of {len(services)} accessibility services"
        )

        if violations:
            result(
                FAIL,
                f"{len(violations)} accessibility config XML(s) missing or malformed",
            )
        else:
            result(
                PASS, f"all {len(services)} accessibility config XMLs exist and parse"
            )


if __name__ == "__main__":
    main()

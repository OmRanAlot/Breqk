"""
test_002_manifest_classes_have_services.py
==========================================
WHY:
  Reverse of test_001. A Java file that extends Service or AccessibilityService
  but has no <service> declaration in the manifest is dead code — it will never
  be started by Android. Catches orphans after renames or copy-paste additions.

WHAT:
  Every Java file under com/Break/ that extends Service or AccessibilityService
  has a corresponding <service> entry in AndroidManifest.xml.

HOW:
  Grep all .java files for "extends (Accessibility)?Service".
  Extract simple class name from filename.
  Cross-check against android:name values collected from manifest <service> elements.

ERRORS:
  "MyOrphanService.java extends Service but no <service> in manifest"
    -> Either add a <service> entry to AndroidManifest.xml or delete the unused file.
       Without a manifest entry this class will never run.

  False positives: abstract base classes that are never directly instantiated.
    -> Add the class name to ALLOWLIST below.
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, WARN, result, time_guard, parse_manifest, android_attr
from _paths import MANIFEST, JAVA_SRC

ANDROID_NS = "http://schemas.android.com/apk/res/android"
SERVICE_PATTERN = re.compile(r"\bextends\s+(AccessibilityService|Service)\b")

# Abstract helpers or test stubs that legitimately extend Service without manifest entry
ALLOWLIST = {"ServiceHelper"}


def main():
    with time_guard(Path(__file__)):
        root = parse_manifest(MANIFEST)
        declared = set()
        for el in root.iter("service"):
            name = el.get(f"{{{ANDROID_NS}}}name", "")
            if name:
                declared.add(name.split(".")[-1])

        orphans = []
        for java_file in JAVA_SRC.rglob("*.java"):
            text = java_file.read_text(encoding="utf-8", errors="ignore")
            if not SERVICE_PATTERN.search(text):
                continue
            class_name = java_file.stem
            if class_name in ALLOWLIST or class_name in declared:
                continue
            orphans.append(java_file.name)

        if orphans:
            for name in orphans:
                print(
                    f"  ORPHAN: {name} extends Service but has no <service> in manifest"
                )
            result(
                WARN,
                f"{len(orphans)} Service class(es) have no manifest entry (dead code)",
            )
        else:
            result(PASS, "All Service classes are registered in the manifest")


if __name__ == "__main__":
    main()

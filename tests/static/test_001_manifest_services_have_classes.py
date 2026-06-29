"""
test_001_manifest_services_have_classes.py
==========================================
WHY:
  Every <service> in AndroidManifest.xml must have a matching Java class.
  A missing class causes Android to throw ClassNotFoundException at runtime and
  the service silently fails to register. This is the exact bug class that hit
  ContentFilterService when it was added to the manifest before the Java file existed.

WHAT:
  Every android:name in a <service> element resolves to a real .java file under
  android/app/src/main/java/.

HOW:
  Parse manifest with ElementTree, extract android:name from every <service>,
  convert class name to a filesystem path (handles ".ShortName" and full names),
  assert the file exists.

ERRORS:
  "Service .ContentFilterService declared but class not found"
    -> The Java file is missing. Create the class or remove the <service> entry.
       The app will crash when Android tries to bind the accessibility service.

  "Service com.Break.foo.Bar declared but class not found"
    -> Spelling mismatch between manifest name and actual package/filename.
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
    class_name_to_path,
)
from _paths import MANIFEST, JAVA_SRC


def main():
    with time_guard(Path(__file__)):
        root = parse_manifest(MANIFEST)
        missing = []

        for service in root.iter("service"):
            name = android_attr(service, "name")
            if not name:
                continue
            expected = class_name_to_path(name, JAVA_SRC)
            if not expected.exists():
                missing.append((name, expected))

        if missing:
            for name, path in missing:
                print(f"  MISSING: Service '{name}' -> {path}")
            result(
                FAIL,
                f"{len(missing)} service(s) declared in manifest with no matching Java class",
            )
        else:
            result(
                PASS, "All manifest <service> entries have matching Java class files"
            )


if __name__ == "__main__":
    main()

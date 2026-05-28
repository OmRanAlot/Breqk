"""
test_018_main_activity_launchmode_singletask.py
================================================

WHAT:
  MainActivity uses android:launchMode="singleTask". Deep-link routing
  depends on this to avoid creating duplicate activity instances.

WHY:
  Without singleTask, each breqk:// deep link from the widget creates a
  new MainActivity instance, leading to multiple React Native roots and
  undefined behavior (stale state, JS bridge confusion, OOM).

HOW:
  1. Parse AndroidManifest.xml.
  2. Find <activity android:name=".MainActivity">.
  3. Assert android:launchMode="singleTask".

OUTPUTS:
  PASS — MainActivity has launchMode="singleTask".
  FAIL — launchMode is missing or not "singleTask".
  WARN — (not used).
  SKIP — (not used).

EXTEND:
  - No allowlist needed.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, result, time_guard, parse_manifest, android_attr
from _paths import MANIFEST

# ── CONFIG ─────────────────────────────────────────────────────────────
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] verifying MainActivity has launchMode='singleTask'")

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

    launch_mode = android_attr(main_activity, "launchMode")
    print(f"  android:launchMode = '{launch_mode}'")

    if launch_mode == "singleTask":
        result(PASS, "MainActivity has launchMode='singleTask'")
    else:
        print(f"  VIOLATION: expected 'singleTask', got '{launch_mode}'")
        result(
            FAIL, f"MainActivity launchMode is '{launch_mode}', expected 'singleTask'"
        )


if __name__ == "__main__":
    main()

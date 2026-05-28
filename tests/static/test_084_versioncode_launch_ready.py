"""
test_084_versioncode_launch_ready.py
=====================================

WHAT:
  versionCode >= 100 in android/app/build.gradle.

WHY:
  versionCode = 1 is the default and signals the app hasn't been prepared
  for launch. Play Store requires monotonically increasing versionCode for
  updates. Starting at 1 leaves almost no room for internal testing rounds.
  This is launch blocker B5.

HOW:
  1. Read android/app/build.gradle.
  2. Extract versionCode.
  3. Assert >= 100.

OUTPUTS:
  PASS — versionCode >= 100.
  FAIL — versionCode < 100 (B5 launch blocker).
  WARN — (not used).
  SKIP — build.gradle not found or versionCode not parseable.

EXTEND:
  - To change the threshold: edit MIN_VERSION_CODE below.

**Currently expected to FAIL — B5 launch blocker (versionCode = 1).**
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, SKIP, result, parse_versioncode
from _paths import BUILD_GRADLE

# ── CONFIG ─────────────────────────────────────────────────────────────
MIN_VERSION_CODE = 100
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] verifying versionCode >= {MIN_VERSION_CODE} (B5)")

    if not BUILD_GRADLE.exists():
        result(SKIP, f"build.gradle not found at {BUILD_GRADLE}")
        return

    text = BUILD_GRADLE.read_text(encoding="utf-8", errors="ignore")
    vc = parse_versioncode(text)
    print(f"  versionCode = {vc}")

    if vc is None:
        result(SKIP, "could not parse versionCode from build.gradle")
    elif vc >= MIN_VERSION_CODE:
        result(PASS, f"versionCode = {vc} (>= {MIN_VERSION_CODE})")
    else:
        result(FAIL, f"versionCode = {vc} (< {MIN_VERSION_CODE}) — B5 launch blocker")


if __name__ == "__main__":
    main()

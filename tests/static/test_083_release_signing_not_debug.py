"""
test_083_release_signing_not_debug.py
======================================

WHAT:
  Release build does not reuse signingConfigs.debug.

WHY:
  Shipping a release APK signed with the debug keystore means anyone can
  build a fake update. The debug keystore password is public knowledge
  ("android"). This is launch blocker B8.

HOW:
  1. Read android/app/build.gradle.
  2. Parse the release buildType block.
  3. Assert signingConfig is NOT signingConfigs.debug.

OUTPUTS:
  PASS — release uses a non-debug signing config.
  FAIL — release uses signingConfigs.debug (B8 launch blocker).
  WARN — (not used).
  SKIP — build.gradle not found or release block not parseable.

EXTEND:
  - This test should FAIL until a release keystore is configured.

**Currently expected to FAIL — B8 launch blocker.**
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, SKIP, result, parse_release_signing_config
from _paths import BUILD_GRADLE

# ── CONFIG ─────────────────────────────────────────────────────────────
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] verifying release signing does not use debug keystore (B8)")

    if not BUILD_GRADLE.exists():
        result(SKIP, f"build.gradle not found at {BUILD_GRADLE}")
        return

    text = BUILD_GRADLE.read_text(encoding="utf-8", errors="ignore")
    signing = parse_release_signing_config(text)
    print(f"  release signingConfig: signingConfigs.{signing}")

    if signing is None:
        result(SKIP, "could not parse release signing config from build.gradle")
    elif signing == "debug":
        result(FAIL, "release build uses signingConfigs.debug — B8 launch blocker")
    else:
        result(PASS, f"release uses signingConfigs.{signing} (not debug)")


if __name__ == "__main__":
    main()

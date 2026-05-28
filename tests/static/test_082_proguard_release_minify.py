"""
test_082_proguard_release_minify.py
====================================

WHAT:
  enableProguardInReleaseBuilds = true in android/app/build.gradle.

WHY:
  Without ProGuard/R8 minification, the release APK contains readable
  Java bytecode that can be trivially decompiled. This is a security
  baseline for any production Android app.

HOW:
  1. Read android/app/build.gradle.
  2. Find enableProguardInReleaseBuilds.
  3. Assert it equals true.

OUTPUTS:
  PASS — ProGuard is enabled for release builds.
  FAIL — ProGuard is disabled (security baseline violation).
  WARN — (not used).
  SKIP — build.gradle not found.

EXTEND:
  - This test should FAIL until ProGuard is enabled (known launch blocker).

**Currently expected to FAIL — security baseline not yet met.**
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, SKIP, result, parse_gradle_kv
from _paths import BUILD_GRADLE

# ── CONFIG ─────────────────────────────────────────────────────────────
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] verifying ProGuard is enabled for release builds")

    if not BUILD_GRADLE.exists():
        result(SKIP, f"build.gradle not found at {BUILD_GRADLE}")
        return

    text = BUILD_GRADLE.read_text(encoding="utf-8", errors="ignore")
    value = parse_gradle_kv(text, "enableProguardInReleaseBuilds")
    print(f"  enableProguardInReleaseBuilds = {value}")

    if value == "true":
        result(PASS, "ProGuard enabled for release builds")
    else:
        result(FAIL, f"enableProguardInReleaseBuilds = {value} (should be true) — security baseline")


if __name__ == "__main__":
    main()

"""
test_021_settingsmodule_methods_exist_in_java.py
=================================================

WHAT:
  Every NativeModules.SettingsModule.<x>( invocation in JS/TS has a matching
  @ReactMethod ... <x>( in bridge/SettingsModule.java.

WHY:
  Same rationale as test_020 — a JS call to a non-existent @ReactMethod
  throws a "method not found" error at runtime.

HOW:
  1. Scan all JS/TS files for NativeModules.SettingsModule.<method>( calls.
  2. Parse bridge/SettingsModule.java for @ReactMethod method names.
  3. Report any JS-side method that has no Java counterpart.

OUTPUTS:
  PASS — all JS bridge calls have matching Java @ReactMethod.
  FAIL — one or more JS calls reference missing Java methods.
  WARN — (not used).
  SKIP — SettingsModule.java not found.

EXTEND:
  - To allowlist a method: add to ALLOWLIST below.
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
    find_react_methods,
    find_js_bridge_calls,
)
from _paths import PROJECT_ROOT, BRIDGE_DIR

# ── CONFIG ─────────────────────────────────────────────────────────────
ALLOWLIST: set = set()
TEST_ID = Path(__file__).stem
MODULE_NAME = "SettingsModule"


def main() -> None:
    print(
        f"[{TEST_ID}] verifying JS SettingsModule calls have matching Java @ReactMethod"
    )

    java_file = BRIDGE_DIR / f"{MODULE_NAME}.java"
    if not java_file.exists():
        result(SKIP, f"{MODULE_NAME}.java not found at {java_file}")
        return

    java_methods = set(find_react_methods(java_file))
    print(f"  Java @ReactMethod count: {len(java_methods)}")
    print(f"  Java methods: {sorted(java_methods)}")

    js_calls = find_js_bridge_calls(PROJECT_ROOT, MODULE_NAME)
    print(f"  JS bridge call count: {len(js_calls)}")
    print(f"  JS calls: {sorted(js_calls)}")

    if not js_calls:
        result(SKIP, f"no JS calls to {MODULE_NAME} found")
        return

    violations = []
    for method in sorted(js_calls):
        if method in ALLOWLIST:
            print(f"  SKIPPED: {method}  # allowlisted")
            continue
        if method not in java_methods:
            violations.append(method)
            print(
                f"  MISSING_JAVA: {method}() — called from JS but no @ReactMethod in Java"
            )

    print(f"  summary: {len(violations)} missing of {len(js_calls)} JS calls")

    if violations:
        result(
            FAIL,
            f"{len(violations)} JS SettingsModule call(s) have no matching Java @ReactMethod",
        )
    else:
        result(
            PASS,
            f"all {len(js_calls)} JS SettingsModule calls have matching Java methods",
        )


if __name__ == "__main__":
    main()

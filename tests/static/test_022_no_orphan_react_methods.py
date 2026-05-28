"""
test_022_no_orphan_react_methods.py
====================================

WHAT:
  WARN: every @ReactMethod in bridge/*.java is called at least once from JS.
  Catches dead bridge code.

WHY:
  Orphaned @ReactMethod indicates dead code after a refactor. While not
  breaking, it increases maintenance cost and attack surface (each bridge
  method is callable from the RN layer).

HOW:
  1. Scan all bridge/*.java files for @ReactMethod method names.
  2. For each method, check if it appears in any JS/TS file as a call.
  3. WARN on unused methods.

OUTPUTS:
  PASS — all @ReactMethod methods are called from JS.
  WARN — one or more @ReactMethod methods appear unused from JS.
  FAIL — (not used — this is a soft signal).
  SKIP — no bridge Java files found.

EXTEND:
  - To allowlist a method: add to ALLOWLIST below.
  - Dynamic dispatch (NativeModules[name].foo) is not detected — documented limitation.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, WARN, SKIP, result, time_guard, find_react_methods, grep_js
from _paths import PROJECT_ROOT, BRIDGE_DIR

# ── CONFIG ─────────────────────────────────────────────────────────────
ALLOWLIST: set = {
    "getName"
}  # getName is framework-required, never called from JS directly
TEST_ID = Path(__file__).stem


def main() -> None:
    print(f"[{TEST_ID}] checking for orphaned @ReactMethod methods in bridge/*.java")

    if not BRIDGE_DIR.exists():
        result(SKIP, f"bridge directory not found at {BRIDGE_DIR}")
        return

    java_files = list(BRIDGE_DIR.glob("*.java"))
    if not java_files:
        result(SKIP, "no Java files in bridge directory")
        return

    all_methods = {}
    for jf in java_files:
        methods = find_react_methods(jf)
        for m in methods:
            all_methods[m] = jf.name

    print(
        f"  found {len(all_methods)} @ReactMethod methods across {len(java_files)} bridge files"
    )

    orphans = []
    for method, source in sorted(all_methods.items()):
        if method in ALLOWLIST:
            print(f"  SKIPPED: {method} ({source})  # allowlisted")
            continue

        # Check if called from JS
        hits = grep_js(PROJECT_ROOT, rf"\b{method}\s*\(")
        if not hits:
            orphans.append(f"{method} ({source})")
            print(f"  ORPHAN: {method}() in {source} — not called from any JS/TS file")

    print(
        f"  summary: {len(orphans)} orphan(s) of {len(all_methods)} @ReactMethod methods"
    )

    if orphans:
        result(
            WARN,
            f"{len(orphans)} @ReactMethod method(s) appear unused from JS (dead bridge code)",
        )
    else:
        result(PASS, f"all {len(all_methods)} @ReactMethod methods are called from JS")


if __name__ == "__main__":
    main()

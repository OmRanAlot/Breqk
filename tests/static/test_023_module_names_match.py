"""
test_023_module_names_match.py
===============================

WHAT:
  getName() return value in each *Module.java matches the JS
  NativeModules.<Name> access string.

WHY:
  If getName() returns "VPNModule" but JS accesses NativeModules.VpnModule,
  the bridge silently returns undefined and all calls fail.

HOW:
  1. For each bridge/*.java file, extract the getName() return string.
  2. Check that NativeModules.<name> appears in JS/TS files.
  3. Report mismatches.

OUTPUTS:
  PASS — all module getName() values match JS access patterns.
  FAIL — one or more modules have getName() mismatches.
  WARN — (not used).
  SKIP — no bridge modules found.

EXTEND:
  - To allowlist a module: add to ALLOWLIST below.
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, SKIP, result, time_guard, grep_js
from _paths import PROJECT_ROOT, BRIDGE_DIR

# ── CONFIG ─────────────────────────────────────────────────────────────
ALLOWLIST: set = {"BreqkReactPackage"}  # Not a module, skip
TEST_ID = Path(__file__).stem


def extract_getname(java_file: Path) -> str:
    """Extract the return value of getName() from a Java bridge module.
    Handles both direct string returns and constant returns (e.g. return MODULE_NAME).
    """
    text = java_file.read_text(encoding="utf-8", errors="ignore")
    # Direct string literal: return "ModuleName";
    m = re.search(r'getName\s*\(\s*\)\s*\{[^}]*return\s+"([^"]+)"', text, re.DOTALL)
    if m:
        return m.group(1)
    # Constant reference: return MODULE_NAME; — look up the constant's value
    mc = re.search(r'getName\s*\(\s*\)\s*\{[^}]*return\s+(\w+)', text, re.DOTALL)
    if mc:
        const_name = mc.group(1)
        mv = re.search(rf'String\s+{re.escape(const_name)}\s*=\s*"([^"]+)"', text)
        if mv:
            return mv.group(1)
    return ""


def main() -> None:
    print(
        f"[{TEST_ID}] verifying bridge module getName() matches JS NativeModules access"
    )

    if not BRIDGE_DIR.exists():
        result(SKIP, f"bridge directory not found at {BRIDGE_DIR}")
        return

    java_files = [f for f in BRIDGE_DIR.glob("*Module.java") if f.stem not in ALLOWLIST]

    if not java_files:
        result(SKIP, "no *Module.java files in bridge directory")
        return

    print(f"  found {len(java_files)} bridge module(s)")
    violations = []

    for jf in java_files:
        name = extract_getname(jf)
        if not name:
            violations.append(f"{jf.name}: could not extract getName() return value")
            print(f"  VIOLATION: {jf.name} — cannot find getName() return string")
            continue

        print(f"  {jf.name}: getName() = '{name}'")

        # Check JS uses module — direct access (NativeModules.Name) or destructuring ({ Name } = NativeModules)
        escaped = re.escape(name)
        hits = (
            grep_js(PROJECT_ROOT, rf"NativeModules\.{escaped}\b")
            or grep_js(PROJECT_ROOT, rf"\{{\s*[^}}]*\b{escaped}\b[^}}]*\}}\s*=\s*NativeModules")
        )
        if hits:
            print(f"  OK: {name} found in JS/TS in {len(hits)} location(s)")
        else:
            violations.append(
                f"{jf.name}: getName()='{name}' but no NativeModules.{name} in JS"
            )
            print(f"  VIOLATION: NativeModules.{name} not found in any JS/TS file")

    print(f"  summary: {len(violations)} mismatch(es) of {len(java_files)} modules")

    if violations:
        result(FAIL, f"{len(violations)} module getName() mismatch(es)")
    else:
        result(PASS, f"all {len(java_files)} module getName() values match JS access")


if __name__ == "__main__":
    main()

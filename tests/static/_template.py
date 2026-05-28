"""
test_NNN_short_name.py
======================

WHAT:
  <One short paragraph describing what this test asserts.>

WHY:
  <Why this check matters — what bug class it prevents.>

HOW:
  1. <step>
  2. <step>
  3. <step>

OUTPUTS:
  PASS — <what is true when the test passes>.
  WARN — <soft signal — what triggers it, why it isn't a hard fail>.
  FAIL — <hard signal — what triggers it, what to do about it>.
  SKIP — <prerequisite missing — when this fires>.

EXTEND:
  - To allowlist a false positive: add to ALLOWLIST below.
  - To change a threshold: edit constants in CONFIG below.
  - Source paths come from _paths.py — add new paths there, not here.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _harness import PASS, FAIL, WARN, SKIP, result, time_guard
from _paths import PROJECT_ROOT  # plus whatever else you need

# ── CONFIG (mold these for your check) ─────────────────────────────────
ALLOWLIST: set = set()  # symbols / files / classes to ignore
THRESHOLD: int = 0  # numeric threshold(s), if any
TEST_ID = Path(__file__).stem  # used in banner/print prefixes


def main() -> None:
    print(f"[{TEST_ID}] <one-line restatement of WHAT>")

    with time_guard(Path(__file__)):
        # ── HOW: step 1 ────────────────────────────────────────────────────
        # print("  step 1: <what we're doing>")

        # ── HOW: step 2 — gather candidates ────────────────────────────────
        candidates: list = []
        # print(f"  scanned: {n} files; candidates: {len(candidates)}")

        # ── HOW: step 3 — evaluate ─────────────────────────────────────────
        violations: list = []
        for c in candidates:
            if c in ALLOWLIST:
                print(f"  SKIPPED: {c}  # allowlisted")
                continue
            # if violated:
            #     violations.append(c)
            #     print(f"  VIOLATION: {c}")

        # ── summary ────────────────────────────────────────────────────────
        print(f"  summary: {len(violations)} violation(s) of {len(candidates)} scanned")

        # ── RESULT (exactly one) ───────────────────────────────────────────
        if not candidates:
            result(SKIP, "no candidates found (prerequisite missing)")
        elif violations:
            result(FAIL, f"{len(violations)} violation(s) — see VIOLATION lines above")
        else:
            result(PASS, f"all {len(candidates)} candidates passed the check")


if __name__ == "__main__":
    main()

# Current Task

**Date Started:** 2026-06-30
**Status:** `[~] In progress — On-device mindful viewing coach for YouTube`

## Goal

Gate YouTube **at launch** with an intent-reflection overlay. The user types what
they came to watch; a deterministic Java engine returns `approve | probe |
challenge` from intent specificity + mode (`chill|balanced|strict`) + session
stats; a small **on-device** LLM only phrases the message (never decides the
verdict). Fully offline — no backend, no API key.

## Decisions (confirmed with user)

- **LLM hosting:** fully on-device (small phone-runnable model, e.g. Gemma 3 1B
  int4 via MediaPipe), behind a swappable interface so a custom-trained model can
  drop in later. No network.
- **Verdict authority:** local deterministic Java rules; LLM writes copy only.
- **Trigger point:** on YouTube launch (once per session).

## Plan (phases)

- **Phase 1 (DONE):** Verdict engine — pure Java, no Android deps, TDD.
- **Phase 2 (DONE):** Session tracking (`CoachSessionTracker` + BreakPrefs keys).
- **Phase 4 (DONE early):** Coach overlay UI — wait→type→verdict flow.
- **Phase 3:** On-device LLM message generator (swap in behind `CoachCopy`).
- **Phase 5:** Launch-trigger wiring in `ReelsInterventionService` (make it fire).
- **Phase 6:** RN/Settings (mode picker in Customize) + surfacing.
- **Phase 7:** Tests, docs, LOGGING.md `[COACH]` tag.

## Progress

Phase 1 complete and verified (`./gradlew :app:testDebugUnitTest` → BUILD
SUCCESSFUL). New package `com.Break.coach` (dir `android/app/.../com/breqk/coach`):

- `Mode.java` — enum chill/balanced/strict; `fromString` falls back to BALANCED.
- `Verdict.java` — APPROVE/PROBE/CHALLENGE with tier(0/1/2) + clamping `fromTier`.
- `Specificity.java` — SPECIFIC/VAGUE.
- `SessionStats.java` — immutable {videosWatched, sessionMinutes, overridesToday}.
- `IntentClassifier.java` — deterministic specificity heuristic (banned phrases,
  filler-word filtering, digit / long-token signals).
- `VerdictEngine.java` — escalation pipeline:
  specificity → session escalation → mode adjust → force-challenge → clamp.
- Tests: `IntentClassifierTest`, `VerdictEngineTest` (full matrix + boundaries).
- `android/app/build.gradle` — added `testImplementation junit:junit:4.13.2`.

### Resolved spec ambiguity

The spec lists both a generic "escalate one tier" rule and strict-mode bumps. To
avoid double-counting `sessionMinutes>20` (which would push a *specific* intent
straight to CHALLENGE in strict, contradicting the spec's own
"strict: session_minutes>20 → approve→probe"), the STRICT bump keys on
`overridesToday>=2` only; the minutes signal is handled once by the shared
session-escalation step. Centralized + fully covered by tests.

## Phase 2 + overlay UI (DONE — compiles, `:app:compileDebugJavaWithJavac` green)

- `BreakPrefs.java` — added `coach_*` key constants + `COACH_SESSION_GAP_MS` (30m),
  `DEFAULT_COACH_MODE`, and `isCoachEnabled/setCoachEnabled/getCoachMode/setCoachMode`.
- `CoachSessionTracker.java` (TAG `COACH`) — session boundary (new session when
  YouTube foregrounds after >30m absence), show-once gate, `currentStats(now)`,
  `incrementVideosWatched()`, `recordOverride(now)` with midnight rollover,
  `mode(context)`.
- `CoachCopy.java` — templated approve/probe/challenge message + followup (the
  fail-open fallback; the Phase 3 LLM swaps in behind `forVerdict`).
- `res/layout/overlay_intent_coach.xml` — two-phase overlay (wait ring → intent
  input → verdict/followup), reuses delay-overlay drawables.
- `res/drawable/coach_input_bg.xml` — EditText field background.
- `IntentCoachOverlay.java` (TAG `COACH`) — controller for the full flow:
  WAIT (ring fills over `delay_time_seconds`, button counts down) → auto-advance
  to INTENT (focusable window so the soft keyboard opens; user types why) →
  VERDICT (APPROVE lets through; PROBE/CHALLENGE require a followup answer,
  CHALLENGE needs ≥12 chars; proceeding records an override). "Back to Reality"
  exits to home with no override.
- `docs/LOGGING.md` — added `COACH` tag row + the 8 `coach_*` SharedPreferences rows.

## Next

Phase 5 — wire the trigger so the overlay actually fires: in
`ReelsInterventionService`, on transition INTO `com.google.android.youtube` from
another package, call `new CoachSessionTracker(ctx).onYouTubeForeground(now)` and,
if `shouldShowCoach(ctx)`, show `IntentCoachOverlay` (debounced so in-app
navigation doesn't re-prompt). Then Phase 3 (on-device LLM) + Phase 6 (mode picker
in Customize). NOTE: overlay is built but NOT yet reachable until Phase 5 wiring.

## Side change (2026-06-30)

Made the Home "Scroll Budget" card tappable → navigates to `Customize` so the
user can edit the budget. `HomeScrollBudgetCard.js` now takes an optional
`onPress` (wraps the card in `TouchableOpacity`, disabled when no handler);
`home.js` passes `navigation.navigate('Customize')`. No new log tags/keys.

## Side change (2026-07-08) — test suites repaired

Both test stacks now run green; they are complementary, not either/or:

- **Python** (`tests/static/`, run `python tests/static/run_all.py`) — static
  audit of manifest↔Java wiring, JS↔Java bridge, prefs hygiene, security.
  Was broken by the `com/Break` → `com/breqk` directory rename (sources still
  declare `package com.Break;`). Fixed `_paths.py` (`JAVA_SRC` → `com/breqk`)
  and `_harness.py:class_name_to_path` (maps logical package `com.Break` onto
  the on-disk dir). Result: 24 PASS / 0 FAIL / 5 WARN / 2 SKIP.
- **Jest** (`tests/unit/`, run `npm run test:jest`) — JS logic + App render. package.json
  had NO jest config: added `preset: react-native`, `setupFiles` →
  new `jest.setup.js` (mocks VPNModule/SettingsModule via self-populating
  Proxy + react-native-webview), `transformIgnorePatterns` for RN ecosystem
  libs, and test/module ignore for the vendored `everything-claude-code/`
  repo (its 110 non-Jest suites were polluting `npm test`).
  Result: 3 suites / 34 tests pass in ~4s.

## Side change (2026-07-08) — mode creation UI redesign

Reworked the mode editor (`components/Modes/ModeEditorModal.js`) per user request:

- **SVG icons replace emojis** — new `components/shared/ModeIcons.js`
  (`ModeIcon` + `MODE_ICON_KEYS`, Feather-style strokes via react-native-svg;
  same keys as before so saved modes render unchanged). Used by the editor's
  icon picker/preview and the ModeCard on `ModesScreen.js`.
- **App Open Intercept is now a box** — added apps listed as rows
  (Monogram tile + label + remove ✕), centered dashed **+** button opens an
  inline picker of remaining `MANAGED_APPS` (7 apps, up from the old
  Instagram/YouTube-only toggles).
- **Forced pause duration is conditional** — the stepper only renders while
  at least one app has `app_open_intercept: true`.
- **Reels Detection limited to Instagram + YouTube** (labels "Reels
  Detection" / "Shorts Detection", same shared `reels_detection` key).
- **No alarm permission prompt** — removed `maybePromptExactAlarm` from
  `ModesScreen.js` (and the now-dead `shouldPromptForExactAlarm` helper +
  tests from `scheduleWindow.js`). Scheduled modes rely on the native
  inexact-alarm fallback in `ModeManager.setExactAlarm`; the app never asks
  for the Android "Alarms & reminders" permission during mode creation.
- Data model unchanged (`breqk_modes` JSON). New JS log prefix `[ModeEditor]`
  added to `docs/LOGGING.md` (plus the previously missing `[ModesScreen]`).
- Verified: `npx jest tests/unit` → 3 suites / 31 tests pass; eslint clean on
  all touched files.

## Side change (2026-07-11) — fixed inconsistent YouTube launch intercept

Root-caused and fixed per `.claude/plan/youtube-launch-intercept-inconsistent.md`:
the coach's relaunch detector compared against `coachLastForegroundPackage`, the
last *real* foreground package — but the Home launcher is deliberately filtered
out as a system-overlay package (Reels `[STICKY-FIX]`), so leaving YouTube via
Home never cleared it. Every re-open then looked like an internal window change
and the coach silently never fired, until some unrelated app happened to be
opened in between (the "~5 min later" symptom).

- `ReelsInterventionService.maybeTriggerYouTubeCoach()` — rewritten to detect a
  relaunch via a **time gap** since `coach_last_yt_foreground` (new constant
  `BreakPrefs.COACH_RELAUNCH_GAP_MS` = 1.5s) instead of the pinned-package
  heuristic. Removed the now-dead `coachLastForegroundPackage` field.
- **Cadence changed (decided: Option A)** — coach now fires on **every** genuine
  relaunch, not once per 30-min session. `CoachSessionTracker.shouldShowCoach()`
  gates on a short re-show cooldown (`BreakPrefs.COACH_RESHOW_COOLDOWN_MS` = 60s,
  keyed off new `coach_last_shown_at` pref) instead of the old
  `coach_shown_for_session` once-only flag (kept for stats only).
  `COACH_SESSION_GAP_MS` (30m) still governs session stats boundaries only.
- **Coach-miss fallback (defense in depth)** — `AppUsageMonitor`'s hard
  suppression of the delay overlay for YouTube (`coachOwnsYouTube`) is now
  time-boxed via new pref `coach_overlay_visible` (set/cleared by
  `IntentCoachOverlay.show()/dismiss()`) + `BreakPrefs.COACH_FALLBACK_GRACE_MS`
  (4s): if the coach never attaches within the grace window, the ordinary delay
  overlay fires instead of leaving YouTube unintercepted. New log line
  `AppUsageMonitor` `[COACH_FALLBACK]`.
- `docs/LOGGING.md` — added `coach_overlay_visible`, `coach_last_shown_at` rows,
  updated `coach_shown_for_session` description, added `[COACH_FALLBACK]` to the
  `AppUsageMonitor` row.
- Not yet done: unit test for the pure relaunch-gap helper (plan's test-plan
  item 1) and manual on-device repro verification (item 2) — no test harness
  currently isolates this Java logic from the AccessibilityService; flagging for
  follow-up rather than fabricating an untested claim.

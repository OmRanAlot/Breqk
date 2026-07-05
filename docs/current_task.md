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

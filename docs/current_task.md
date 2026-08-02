# Current Task

**Date Started:** 2026-06-30
**Status:** `[x] Done — Home is the only mode editor; lock + editor page removed`

## 2026-08-02 — Deletion prevention never fired (case-sensitivity bug)

**Bug (user):** the deletion-prevention lock overlay never appeared when opening
Settings → Apps → Break → Uninstall. 100% miss rate, not intermittent.

**Root cause:** `UninstallScreenDetector.collectText()` returns
`sb.toString().toLowerCase()`, but the app-name probe compared against the
mixed-case literal `"Break"`. That condition could never be true, so `hasBreak`
was permanently false and `detected = hasBreak && hasUninstall &&
hasAppInfoMarker` was permanently false — `isOnBreakUninstallScreen()` always
returned false and `ReelsInterventionService` never entered the show branch.
The other two probes were unaffected because they already used lowercase
literals (`"uninstall"`, and the all-lowercase `APP_INFO_MARKERS`).

Everything downstream was already correct: the `uninstall_lock_enabled` opt-in
gate, the `com.android.settings` dispatch, the 500ms debounce, the accessibility
config, and `UninstallLockOverlay.show()`.

**Changes — native:**

- `uninstall/UninstallScreenDetector.java` — the app-name probe no longer uses a
  hardcoded literal at all. New `resolveIdentityTokens(Context)` derives the
  lowercased launcher label (`@string/app_name`) **and** the package id at
  runtime and matches on either, cached in a `volatile String[]`. A rename of
  `app_name` or `applicationId` can no longer silently disable the feature (the
  repo already carries `Break`/`breqk` naming drift). Signature changed to
  `isOnBreakUninstallScreen(Context ctx, AccessibilityNodeInfo root)`; falls back
  to the package id if `loadLabel()` throws.
- `ReelsInterventionService.java:539` — sole call site passes `this`.

**Logging:** TAG `REELS_WATCH` and prefix `[UNINSTALL_WATCH]` unchanged, no new
SharedPreferences keys → no `docs/LOGGING.md` edit needed. Two message strings
changed (`Found 'Break' in node text=` → `Found app identity '<token>' in node
text=`, plus a new `app identity tokens=[...]` line); LOGGING.md documents tags
and prefixes, not individual messages.

**Verified:** `./gradlew :app:compileDebugJavaWithJavac` clean (only a
pre-existing deprecation note).
**Not yet run on a device.** Worth exercising: enable deletion prevention in
Customize, then Settings → Apps → Break and confirm
`adb logcat -s REELS_WATCH | findstr UNINSTALL_WATCH` now reports
`hasBreak=true ... -> detected=true` and the overlay appears.

**Deliberately out of scope (user chose Phase 2 only)** — still open:

- Uninstall started from a **launcher long-press** or the **Play Store** is never
  inspected; only `com.android.settings` is watched, so those flows reach
  `com.google.android.packageinstaller` / `com.android.vending` unblocked.
- The BFS never recycles child nodes (node leak per scan), and `MAX_NODES = 500`
  may be exhausted before all three signals are found on dense OEM Settings trees.

## 2026-07-24 — Home screen edits the ACTIVE mode; lock + mode-editor removed

**Bug (user):** editing settings from the home screen while Default mode was
active "didn't save to the mode." Root cause: the `default` mode ships its own
`policy_overrides` (e.g. IG intercept=false, reels=true), and native resolution
(`isFeatureEnabled` / `getEffectiveSettingInt`) reads the active mode's overrides
FIRST. But the home screens wrote to the **base** `app_policies` / prefs, which
the active mode masked — the write landed but was invisible.

**Decision (user):** remove the read-only lock and the separate mode-editor page.
The home screens edit **whatever mode is active** (activate-to-edit); the Modes
screen keeps only a tiny metadata sheet (name/icon/color/schedule). Per-app
blocking + reels detection + forced-pause are per-mode; scroll budget, browser
safety, deletion prevention, and the settings-lock stay global/shared.

**Changes — native:**

- `prefs/BreakPrefs.java` — `isBaseSettingsEditable()` / `assertBaseSettingsEditable()`
  are now **always-true stubs** (the lock is gone; nothing to gate since writes go
  into the active mode).
- **No data migration needed.** The active mode's `policy_overrides` are read
  FIRST and are already what the user experiences (base was masked all along), so
  Default's curated/edited overrides stay authoritative; base remains a fallback
  for keys a mode doesn't override. (An earlier "fold base into Default" migration
  was written and then removed — on a fresh install it would have clobbered
  Default's curated overrides with the generic all-true base seed.)
- `bridge/SettingsModule.java` — `saveModes` now re-derives blocked_apps
  (`syncBlockedAppsFromPolicies` + `dispatchBlockedAppsReload`) so an edit to the
  ACTIVE mode's `policy_overrides` takes effect live (activation already synced; a
  pure in-place edit did not). Gate guards on the base setters are now inert.

**Changes — JS:**

- `shared/activeModeSettings.js` (NEW, `[ActiveModeSettings]`) —
  `saveAppSettingsToActiveMode(pkg, policy, delaySecs)` folds an app's boolean
  policy keys into `modes[active].policy_overrides[pkg]` and the mode-wide
  forced-pause into `setting_overrides.delay_time_seconds`, then `saveModes`.
- `AppDetail/AppDetail.js` — removed the mode gate/banner; `handleSave` now writes
  via the helper (single write path) instead of base `setAppFeature`. Message /
  frequency / post-limit / free-break stay global as before.
- `Customize/customize.js` — removed the mode gate/banner; scroll-budget re-sync
  always runs (global setting).
- `Modes/ModeMetaSheet.js` + `.styles.js` (NEW, `[ModeMeta]`) — metadata-only
  editor (name/icon/color/schedule/delete). Replaces `ModeEditorModal`.
- `Modes/ModesScreen.js` — switcher + "Rename & schedule" opens the metadata sheet;
  `handleSave` merges only metadata, preserving each mode's overrides.
- **Deleted:** `Modes/ModeEditorModal.js` (+styles), `shared/useDefaultModeGate.js`,
  `shared/ModeGateBanner.js` (+styles). `TimePickerSheet.styles.js` palette import
  redirected to `ModeMetaSheet.styles`.
- `docs/LOGGING.md` — dropped `[ModeGate]` / `[ModeEditor]`, added
  `[ActiveModeSettings]`, `[ModeMeta]`.

**Verified:** eslint 0 errors (3 pre-existing warnings), jest 38/38, static
`test_021` PASS / `test_041` SKIP, `:app:compileDebugJavaWithJavac` clean.
**Not yet run on a device** — worth exercising: (1) editing IG/YT from Home while a
non-default mode is active persists into that mode and takes effect live,
(2) forced-pause is one value per mode, (3) a fresh install still shows Default's
curated behavior (IG no intercept, YT no shorts detection).

## 2026-07-14 — Modes actually take over; base settings gated to Default mode

**Bug found:** `BreakPrefs.getEffectiveSettingInt()` — the function that resolves a
mode's `setting_overrides` on top of the base prefs — had **zero callers**. Every
settings read went straight to raw SharedPreferences, so a mode's
`delay_time_seconds` (Study and Bedtime both ship 20s) was written, shown in the
mode editor, and then silently ignored at runtime. Only `policy_overrides` worked,
because `isFeatureEnabled()` did consult the active mode.

**Decision (user):** a mode's settings TAKE OVER while it is active, and the base
settings screens (Customize + per-app AppDetail) are editable **only in Default
mode**. Anything else would let the user "change" a value the active mode masks —
an edit that appears to do nothing.

**Changes — native:**

- `prefs/BreakPrefs.java` — `getModeSettingOverrideInt()` (NEW; the single place
  the `setting_overrides` JSON is walked), `getEffectiveSettingInt()` refactored
  onto it, and `getGlobalDelaySecs()` + `getEffectivePopupDelayMinutes()` now
  resolve through the active mode. Precedence: **active mode → per-app → base
  global** (the mode is the outermost layer). New `isBaseSettingsEditable()` /
  `assertBaseSettingsEditable()` gate (`[MODE_GATE]`).
- `monitor/AppUsageMonitor.java` — cached delay/popup values now read effectively;
  new `reloadSettings()` (`[RELOAD]`) so a mode switch cannot leave the previous
  mode's delay in memory. Wired to the `UPDATE_BLOCKED_APPS` dispatch that
  `ModeManager.activate()` already sends (`service/BreakVpnService.java`).
- `monitor/UsageStatsQuery.java` (NEW) — the UsageStatsManager query bodies,
  extracted because `AppUsageMonitor` crossed the 1500-line hard limit
  (test_060). Thin delegates keep VPNModule's bridge calls unchanged.
- `bridge/SettingsModule.java` + `bridge/VPNModule.java` — every base-settings
  writer now rejects while a non-default mode is active (Promise setters reject
  with `MODE_ACTIVE`). New `getBaseSettingsEditable()` bridge method.
  **Deliberately NOT gated:** `saveBlockedApps` / `setBlockedApps` (a derived
  cache that Home re-seeds on every launch — gating it would break monitoring),
  the Modes screen itself, and the safety features (uninstall lock, settings lock,
  content-filter double-safe — they carry their own deliberate friction and must
  not become bypassable by toggling a mode).

**Changes — JS:**

- `shared/useDefaultModeGate.js` (NEW) — the gate hook. Polls every 10s because a
  SCHEDULED mode can activate from an AlarmManager alarm with no JS event while
  the user is sitting on the settings screen.
- `shared/ModeGateBanner.js` + styles (NEW) — "Bedtime is controlling these
  settings. Switch to Default mode to change them", in the mode's own colour.
- `Customize/customize.js` — banner + disabled controls; the load-time
  `setScrollBudget` re-sync is skipped while gated (native would reject it).
- `AppDetail/AppDetail.js` — banner replaces the form, Save bar hidden. **Removed**
  the old write-into-the-active-mode path that this rule supersedes.
- `Home/ActiveModeBanner.js` + styles (NEW) — the one-line "Bedtime mode" text in
  the status strip was far too quiet for something that overrides every setting
  and freezes the settings screens. Now a mode-coloured card: schedule window,
  effective pause, intercepted apps, and an **End** button — the escape hatch back
  to Default.

**Verification:** static 24 PASS / 0 FAIL, jest 40/40, `compileDebugJavaWithJavac`
clean. **Not yet run on a device** — the schedule-driven gate transition (a mode
activating while Customize is open) is the case worth exercising by hand.

**Known trade-off:** while a scheduled mode like Bedtime is active (22:00–07:00
daily), NO base setting can be changed without ending the mode. That is the
intended friction; the Home banner's End button is the only relief.

## 2026-07-13 — YouTube double-intercept fix + coach toggle & cadence

**Bug:** the YouTube launch intercept fired twice — the normal delay overlay
(AppUsageMonitor) AND the typing coach stacked. **Decision (user):** the coach is
YouTube's App Open Intercept STYLE — exactly one surface ever fires:

- **Coach ON (default):** typing gate at launch **and re-fires every X minutes**
  while the user stays in YouTube. X = the existing per-app "Re-show overlay"
  interval (`popup_delay_min`; once-per-open sentinel disables re-fire). The
  delay overlay is suppressed for YouTube (`[COACH_OWNS]` in AppUsageMonitor).
- **Coach OFF:** normal delay overlay, exactly like Instagram.
- Either way, App Open Intercept disabled for YouTube → no launch gate at all.

**Changes:**

- `coach/YouTubeCoachGate.java` (NEW) — coach trigger logic extracted from
  `ReelsInterventionService` (file went >1500 lines): launch gate (relaunch-gap
  detection, now also fed by an in-memory last-YT-event timestamp so mid-session
  window churn can't fake a relaunch) + throttled every-X-min re-fire
  (`[COACH_REFIRE]`, active-window check guards against PiP).
- `ReelsInterventionService.java` — coach section replaced with a single
  `coachGate.onAccessibilityEvent(...)` forward; old yield-to-delay-overlay
  precedence removed (inverted).
- `monitor/PopupDecision.java` — pure helpers `coachOwnsYouTubeIntercept()` +
  `shouldRefireCoach()`; tests in `PopupDecisionTest` (29/29 pass).
- `monitor/AppUsageMonitor.java` — delay overlay suppressed for YouTube when the
  coach is enabled.
- `prefs/BreakPrefs.java` — `getEffectivePopupDelayMinutes()` (per-app → global).
- `bridge/SettingsModule.java` — `getCoachEnabled` / `setCoachEnabled`.
- `AppDetail.js` + `InterceptCustomization.js` — "Typing Coach" toggle (YouTube
  only) inside the intercept box; saved via the manual Save flow.
- `docs/LOGGING.md` — `[COACH_REFIRE]`, `[COACH_OWNS]`, SettingsModule `[COACH]`.

**Verified:** `:app:compileDebugJavaWithJavac` clean, `:app:testDebugUnitTest`
29/29, `npm test` (jest + static audit) all PASS, eslint 0 errors.

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

## Side change (2026-07-13) — per-app settings now save via a manual Save button

Fixed the "YouTube per-app settings don't save" bug and reworked the AppDetail
save model from auto-save to explicit Save.

- **Root cause:** the intercept box (message/countdown/frequency) persisted
  through a 1.5s debounce plus a "flush on unmount" effect whose cleanup re-ran
  on *every keystroke*, racing the debounce and the navigation-blur auto-lock.
  YouTube's only editable content lives in that box (its lone toggle is Shorts
  Detection), so it was the visible casualty while toggle-based apps looked fine.
- **New model (`components/AppDetail/AppDetail.js`):** every control edits LOCAL
  state and marks the screen dirty; nothing writes natively until **Save**.
  `handleSave()` replays all prior side effects in order (per-key `setAppFeature`,
  active-mode override propagation + `activateMode`, `saveFreeBreakEnabled`,
  Instagram `saveHomeFeedPostLimit`, `setAppInterceptSettings`, `startMonitoring`),
  then arms the Settings Change Lock for this scope via `settingsLock.startLock()`.
- **Leave guard:** `navigation` `beforeRemove` listener prompts Save / Discard /
  Keep editing when dirty (covers header back, hardware back, swipe).
- **`components/Customize/useSettingsLock.js`:** added opt-out third arg
  `{ autoLockOnLeave }` (default true preserves the global Customize behavior) and
  a new `startLock()` method. AppDetail passes `autoLockOnLeave: false` so the
  lock arms on Save, not on plain exit.
- **`InterceptCustomization.js`:** now purely presentational — setters + `onEdit()`
  to mark dirty; removed the debounce/timer/flush props.
- Sticky Save bar added (`AppDetail.styles.js`: `saveBar`/`saveButton*`).
- No log tags or SharedPreferences keys changed → no `docs/LOGGING.md` edit.
- Verified: eslint clean on all four touched files (one pre-existing inline-style
  warning). Not yet done: on-device manual verification of the save + lock flow.

## Side change (2026-07-13) — YouTube App-Open Intercept re-show interval fixed

Fixed the YouTube-only glitch where the App Open Intercept overlay ("Is this
intentional?" delay overlay) re-showed **every time the previous one closed**
instead of honoring the per-app "Re-show every X min" setting (`popup_delay_min`).

- **Root cause:** the mindful-viewing coach (`IntentCoachOverlay`, a different
  process) uses a FOCUSABLE window for intent entry, which pauses YouTube's
  activity. `AppUsageMonitor.getCurrentForegroundApp()` then reads YouTube as
  backgrounded and returns null. The null-foreground session-rearm branch
  (`[SESSION_REARM]`) — guarded only by `!isOverlayActive`, which does NOT cover
  the coach — treated that as the user leaving and cleared
  `appOpenTimestamps` / `lastPopupShownTimestamps` for YouTube every coach cycle.
  With `lastPopupTime` perpetually null, `shouldShowFirstPopup` re-fired on each
  reopen. YouTube-only because only YouTube drives the coach.
- **Fix (`AppUsageMonitor.java`):** new `isOwnInterceptionOwning(pkg)` helper
  (delay overlay active, or — YouTube — `coach_overlay_visible` / within
  `COACH_FALLBACK_GRACE_MS`). The session-rearm now preserves the session while
  our own interception surface owns the screen, so the X-min timer survives the
  coach's focusable window. Genuine leave+reopen still re-arms (confirmed spec:
  fresh open always intercepts).
- **Hardening:** extracted the show/no-show decision into pure
  `com.Break.monitor.PopupDecision` (mirrors `ScrollBudgetLogic`). Added
  `normalizeDelayMinutes` (passes the once sentinel through, else clamps 0–60) —
  the per-app path previously applied no clamp, so a malformed stored value could
  yield a ~0ms interval. `AppUsageMonitor`'s inline `shouldShow*` computation now
  calls `PopupDecision`.
- **Tests:** `android/app/src/test/java/com/breqk/monitor/PopupDecisionTest.java`
  (18 cases: normalize/clamp, once-never-repeats, repeat fires exactly at X min,
  within-interval suppressed). `./gradlew :app:testDebugUnitTest` → BUILD
  SUCCESSFUL.
- No new log tags/prefixes or SharedPreferences keys (reuses `[SESSION_REARM]`)
  → no `docs/LOGGING.md` edit. Not yet done: on-device manual repro verification.

### Follow-up (2026-07-13) — YouTube: coach + delay overlay were double-firing

After the re-show fix, YouTube still showed two launch intercepts (the coach AND
the App Open Intercept delay overlay) plus a ~5s delay. Root cause: both surfaces
intercept YouTube launch. The `coachOwnsYouTube` gate suppressed the delay overlay
for `COACH_FALLBACK_GRACE_MS` (~4s) on every open (the ~5s delay), then released
once the coach closed, so the delay overlay fired as a second, stacked intercept.

Decision (confirmed with user): **App Open Intercept is YouTube's single launch
intercept; the coach yields to it.**

- `AppUsageMonitor.java` — removed the `coachOwnsYouTube` time-boxed suppression
  block; the delay overlay now fires promptly for YouTube (`if (isBlocked &&
  !isOverlayActive)`), like any other app. Removed the `[COACH_FALLBACK]` log line
  (dead) and updated its `docs/LOGGING.md` row to document `[SESSION_REARM]`.
- `ReelsInterventionService.maybeTriggerYouTubeCoach()` — early-return (coach
  yields) when `app_open_intercept` is enabled for YouTube, so the coach and the
  delay overlay never stack. The coach still owns YouTube launch when App Open
  Intercept is OFF for YouTube (feature preserved, not removed).
- `isOwnInterceptionOwning()` retained: the delay overlay is focusable and sets
  `isOverlayActive`, so the session-preserve guard still holds via that flag.
- Verified: `./gradlew :app:compileDebugJavaWithJavac` → BUILD SUCCESSFUL.
  Not yet done: on-device manual verification (expect a single delay overlay
  ~0.5s after opening YouTube, no coach, honoring the per-app re-show interval).

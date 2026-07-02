# Current Task

**Date Started:** 2026-06-28
**Status:** `[x] Fixed overnight mode-switch-back bug; added Testing mode + disableable mode notifications`

## Mode switching bug fix + Testing mode + mode notifications — 2026-06-28

**Request (user):** "the mode switching is buggy... it can switch to bedtime mode
automatically but cant switch back to default once the bedtime mode is up. create
another mode called testing so I can test the timers myself. Also make
notifications (which can be disabled) for when a mode switches and when bedtime
mode is on (or any mode of the user's choice)."

**Root cause of the switch-back bug (two compounding issues):**
1. `ModeManager.registerScheduleAlarms` had an overnight adjustment
   `if (endMillis <= startMillis) endMillis += 24h`. `getNextAlarmTime()` already
   returns the next *future* occurrence, so when the START alarm fired at 22:00 and
   re-registered, START rolled to tomorrow 22:00 and END to tomorrow 07:00 — then
   the adjustment shoved END to the *day after* 07:00. The END alarm never fired
   the next morning, so Bedtime stayed active and never fell back to default.
   **Fix:** removed the adjustment; register START/END independently.
2. `SettingsModule.saveModes` never re-registered alarms, so schedule edits made in
   the UI only took effect after a reboot/cold-start. **Fix:** call
   `ModeManager.reregisterAllAlarms` after every save.

**Testing mode (quick near-future schedule):**
- `BreakPrefs.createDefaultModesIfNeeded` seeds a `testing` mode: 5s delay,
  persistent notification, IG intercept, and a schedule starting ~2 min after
  first install (new `hhmmFromNow(int)` helper). Mirrored in JS `DEFAULT_MODES`.
- `ModeEditorModal` gained a **"⚡ Quick test (start in 2 min)"** button that arms a
  fresh now+2min → now+4min schedule (all days) on any mode — so you can repeatedly
  watch auto-switch-on and auto-switch-back without waiting for a real time window.

**Notifications (global toggle + per-mode persistent):**
- New pref `mode_notifs_enabled` (default true) + `BreakPrefs.isModeNotifsEnabled`/
  `setModeNotifsEnabled`. `ModeNotifier.notifyModeStarted`/`notifyModeEnded` now
  gate on it.
- `ModeNotifier.showOngoing`/`clearOngoing` post/clear an ongoing (non-dismissible)
  "<Mode> active" badge on a new low-importance channel. `ModeManager.activate`
  shows it for modes with `setting_overrides.persistent_notification` and clears the
  previous mode's badge on every transition.
- Bridge: `SettingsModule.getModeNotifsEnabled`/`setModeNotifsEnabled`.
- UI: global **"Mode notifications"** toggle on `ModesScreen`; per-mode **"Show
  notification while active"** toggle in `ModeEditorModal`.
- `docs/LOGGING.md` — added `mode_notifs_enabled` key + updated `MODE_NOTIFY` row.

**Verification:** needs on-device build. Manual check: (1) arm Testing via Quick
test, confirm it auto-switches on at the start time and **back to default** at the
end time (this is the core bug); (2) toggle global notifications off → no mode
alerts; (3) enable a mode's persistent notification → ongoing badge shows while
active and clears on switch. Static parity tests SKIP locally (harness path
constant points at a different package dir) — not affected by this change.

---

## Prior: Edit-Mode screen manifest-driven per-app block selection
**Status:** `[x] Edit-Mode screen is now manifest-driven with per-app block selection; active-mode edits resync natively`

## Edit-Mode screen: full per-app info + app block selection + cross-screen persistence — 2026-06-26

**Request (user):** "whenever I am in a mode (default, bedtime, etc.) and I make a
change in either the home screen or edit mode screen, it persists throughout and
reflects everywhere. Change the edit mode screen so it has the same information as
the home screen per app changes. Also allow the user to select which apps to block
in the edit mode."

**What was wrong:** `ModeEditorModal.js` (the edit-mode screen) hardcoded
`APPS = [Instagram, YouTube]` and `FEATS = [app_open_intercept, reels_detection]`
— only 2 of 7 managed apps and 2 features. AppDetail (the Home-side editor) is
manifest-driven and already writes per-app changes into the active mode's
`policy_overrides` + re-activates natively. So edits in the two screens diverged.
Also `ModesScreen.handleSave` saved the modes JSON but never re-activated the
active mode, so native `blocked_apps`/overlay settings didn't resync after editing
the active mode.

**Changes:**
- `ModeEditorModal.js` — now imports `MANAGED_APPS` (manifest) + `Monogram`.
  Replaced the hardcoded APPS/FEATS section with one block per managed app: a
  per-app "manage in this mode" master Switch (`toggleAppManaged` — ON seeds
  `app_open_intercept: true` so the app is blocked by default; OFF deletes the
  app's `policy_overrides` entry = excluded). When managed, reveals an App Open
  Intercept toggle + every manifest toggle feature + any stepper
  (`session_post_limit`, via `adjustModeStepper`, mirroring AppDetail). Presence
  of the pkg key (not truthiness) gates the block, so an app can stay managed with
  intercept off (Reels-only), matching Home's "Managed Apps" rows.
- `ModeEditorModal.styles.js` — added `appsSectionCaption`, `appHeaderRow`,
  `appHeaderLabel`, `appFeatures`, `miniStepper*`; removed dead `appLabel`.
- `ModesScreen.js` — `handleSave` now re-activates the mode
  (`VPNModule.activateMode(activeModeId)`) when the edited mode is the active one,
  so blocked_apps + overlay resync immediately (same path AppDetail uses).

**No Java changes** — `policy_overrides` already supports arbitrary apps/keys and
`BreakPrefs.syncBlockedAppsFromPolicies` iterates active-mode overrides.

**Verification:** app-scoped eslint on the 3 changed JS files = 0 errors (5
pre-existing warnings: unused Animated/SettingsModule/BackIcon, inline-style,
dot-notation). Needs on-device check: edit the active (e.g. Default) mode, toggle
an app's block + Reels, save → confirm Home "Managed Apps" + AppDetail reflect it
and the overlay fires accordingly without reopening the app.

---

## Prior: Intervention overlay forced to portrait — 2026-06-25
**Status:** `[x] Intervention overlay now forced to portrait even when host app is landscape`

## Overlays render landscape over a landscape video — 2026-06-25

**Bug (user):** first reported as "whenever i watch a youtube video in landscape
mode and the intercept appears, the intercept appears in landscape even though it
should ALWAYS show in portrait", then clarified: "when I watch a long form video
in landscape mode, the intercept pops-up (since I have it so it'll pop up every
10 minutes)".

**Two overlays were affected — both now forced to portrait:**

1. **`AppUsageMonitor.delay_overlay` (the long-form / "every X min" recurring
   intercept — the one the user actually sees).** This is the
   recurring delay overlay re-shown while a mode-intercepted app (YouTube) stays
   foreground (`KEY_OVERLAY_INTERVAL_SECONDS`). It used `MATCH_PARENT` +
   `TYPE_APPLICATION_OVERLAY`, so over a fullscreen landscape video it filled the
   wide screen and laid out landscape.
   **Fix:** added `lockOverlayToPortrait()`; wrap + counter-rotate content when
   `Display.getRotation()` is landscape; track real window root in new field
   `overlayWindowRoot` and remove THAT in `removeOverlay()` / old-overlay cleanup.

2. **`InterventionOverlay` (Shorts budget intercept).** Same root cause / same fix
   (`lockToPortrait()`, `overlayRoot` field) — see details below.

**Root cause:** `InterventionOverlay.show()` set
`params.screenOrientation = SCREEN_ORIENTATION_PORTRAIT`, but that flag has no
effect on a `TYPE_ACCESSIBILITY_OVERLAY` window. Accessibility overlays cannot
dictate display orientation — they inherit the foreground app's orientation, so
in YouTube fullscreen landscape the overlay filled the wide screen and laid out
landscape.

**Fix (`InterventionOverlay.java`):** Removed the ineffective `screenOrientation`
line. Added `lockToPortrait()`: when `Display.getRotation()` is `ROTATION_90/270`
(landscape), wrap the inflated content in a full-screen `FrameLayout`, size the
content to swapped (portrait) dimensions, center it, and `setRotation(±90°)` so
its bounding box fills the landscape window while content reads upright. Track
the real window root in new field `overlayRoot` (the wrapper in landscape, the
content itself in portrait) and remove THAT in `dismiss()` — removing the rotated
child would fail since it's not a window root.

**Verification:** needs on-device check — play a YouTube Short fullscreen in
landscape, exhaust the scroll budget, confirm the "Time is up!" sheet appears
upright/portrait. If it shows upside-down on a device, flip the sign of `degrees`
in `lockToPortrait()`.

---

## Onboarding doesn't persist selections into the policy model — 2026-06-24
**Status:** `[x] Onboarding now writes app_policies + active mode (selections were being ignored)`

## Onboarding doesn't persist selections into the policy model — 2026-06-24

**Bug (user):** "the onboarding doesnt properly set the user up since it doesnt
get all the information."

**Root cause:** The app is governed by `app_policies` + the active mode's
`policy_overrides` (`BreakPrefs.isFeatureEnabled` / `syncBlockedAppsFromPolicies`).
On first run `MainApplication.kt` seeds default IG/YT/TikTok policies and
auto-activates the Default mode. But `PermissionsScreen.handleComplete()` only
wrote the legacy `blocked_apps` set + `intercept_settings`. Result: the user's
app selection (deselecting YouTube, adding X/Reddit, breath on/off) never
reached the policy model, so Home + the runtime monitor used the seeded defaults,
and the next policy sync overwrote the onboarding `blocked_apps`.

**Fix (`PermissionsScreen.js`):** `handleComplete()` now builds an `app_policies`
map from the selections (`app_open_intercept` ← breath toggle; reels/scroll/
free_break ← managed). Deselected offered apps are written all-off. It saves via
`SettingsModule.saveAppPolicies`, mirrors the policies into the active mode's
`policy_overrides`, and re-activates the mode (`VPNModule.activateMode`) to
resync `blocked_apps` — the same path `AppDetail` uses. Added module helpers
`getActiveModeId()` / `getModesObject()`. Dropped the redundant
`setBlockedApps` call (policies now derive it).

**Verification:** needs on-device check — select a non-default set in onboarding
(e.g. deselect YouTube, add Reddit), finish, confirm Home shows exactly those
apps and YouTube is no longer managed.

### Follow-up: scroll threshold + budget added to onboarding — 2026-06-24

User: "add scroll threshold and budget to onboarding." Onboarding previously
left `scroll_threshold` / `scroll_allowance_minutes` / `scroll_window_minutes`
at native defaults. Added a 4th setup step ("Set your limits") between the breath
step and the permission screens:
- `theme.js` — `SCROLL_THRESHOLD_OPTIONS` [3,5,10] (default 5),
  `SCROLL_ALLOWANCE_OPTIONS` [5,10,15] min (default 5), fixed
  `SCROLL_WINDOW_MINUTES` = 60.
- `PermissionsScreen.js` — new `renderScrollLimits` (two reused `Segmented`
  controls), `scrollThreshold`/`scrollAllowance` state, step indices shifted
  (`TOTAL_STEPS` 9→10, `FIRST_PERMISSION_STEP` 4→5, `PROTECT_STEP` 7→8, new
  `SCROLL_STEP` 4), step labels "of 3"→"of 4", welcome dots 4→5. Persisted in
  `handleComplete` via `SettingsModule.saveScrollThreshold` +
  `saveScrollBudget(allowance, 60)`.

**Verify on device:** pick non-default limits, finish, confirm Customize/AppDetail
show the chosen threshold + allowance and the Reels intervention fires at them.

---

## Modes feature pass (branch: bug-fixing) — 2026-06-24

**Scope (user request):** AM/PM schedule times; make modes actually override
settings; per-mode recurring overlay (e.g. Bedtime: 15s overlay every 5s);
clarify + fix "Forced Pause Duration"; per-mode custom message.

**What "Forced Pause Duration" is:** the intercept overlay countdown
(`setting_overrides.delay_time_seconds`). It was silently ignored because the
overlay read `getEffectiveDelaySecs()`, which only checked per-app/global — never
the active mode. Fixed.

**Changes:**
- `BreakPrefs.java` — `getEffectiveDelaySecs` now checks active-mode override
  FIRST; new `getEffectiveMessage` (mode → per-app → global → fallback); new
  `recurring_overlay` / `overlay_interval_seconds` accessors + private mode
  setting-override helpers. Bedtime defaults seeded (delay 15, message,
  recurring 5s); `DEFAULT_MODES_VERSION` → 3.
- `AppUsageMonitor.java` — overlay uses `getEffectiveMessage`; new
  `[RECURRING_OVERLAY]` re-show branch (interval measured from last dismissal via
  `popupCooldown`, gated to blocked-app foreground).
- `ModeEditorModal.js` (+styles) — custom-message field, recurring-overlay
  toggle + interval stepper, AM/PM `TimePicker` (stores 24h "HH:mm").
- `ModesScreen.js` — DEFAULT_MODES bedtime seed; summary shows AM/PM + recurring.
- `docs/LOGGING.md` — `[RECURRING_OVERLAY]` documented.

**Override precedence:** active mode > per-app intercept setting > global.

**Verification:** eslint on changed JS = 0 errors (pre-existing warnings only).
Java not yet built on-device — needs manual verify: Bedtime auto-activates at
10PM, overlay countdown matches mode value, recurring overlay pulses on IG/YT,
custom message renders, AM/PM picker round-trips.

---

## Previous: per-app intercept "Once per open" re-showing every ~10 min

## Active bug fix (branch: bug-fixing)

**Symptom:** Per-app App Open Intercept set to "Once per open" still re-showed
the overlay every ~10 min.

**Root cause:** `AppDetail.js` flush-on-unmount `useEffect` listed the intercept
field values in its dependency array. React runs the cleanup with the PREVIOUS
render's captured values before re-running. Tapping "Once per open" armed a
debounced save with `once`, but the cleanup immediately fired with the stale
`repeat / 10` closure and overwrote `popup_delay_min` with `10`
(`DEFAULT_POPUP_DELAY_MINUTES`). Native monitor then re-showed every 10 min.

**Fix:** Hold latest intercept values in `interceptValuesRef` (updated each
render); change the flush effect to empty deps so cleanup runs ONLY on true
unmount, reading the ref. Persistence + native sentinel logic were already
correct. Note: previously mis-saved apps self-heal on the next correct save —
re-select "Once per open" once if a stale `10` was persisted.

**Verification:** app-scoped eslint clean (1 pre-existing unrelated warning).
Manual: re-select "Once per open", reopen AppDetail → still "Once"; inspect
`intercept_settings` JSON → `popup_delay_min: 2147483647`; stay in app 12+ min →
no re-show.

---

## Prior session — Codebase optimization (branch: optimization)
**Status:** `[~] In progress — file-size reduction + redundancy removal`

## Goal

Go through the codebase to remove redundancy and improve clarity. No file over
~600 lines unless the logic is genuinely cohesive/non-reusable. Behavior must be
unchanged. Builds are intentionally skipped this session (per user); JS changes
are verified with app-scoped eslint only.

## Plan (phases)

- **Phase 0 (done):** Baseline build was green (exit 0). Jest is not a useful
  signal here (vendored everything-claude-code suites dominate). App-scoped lint
  baseline: 1 pre-existing error, ~20 warnings.
- **Phase 1 (in progress):** Extract styles/sub-components from the 6 large JS
  screens, pruning dead style keys along the way.
- **Phase 2:** Consolidate shared JS utils (icons, formatters, logging).
- **Phase 3:** Java extraction (AppUsageMonitor, ReelsInterventionService,
  BreakPrefs, VPNModule).
- **Phase 5:** Final verification + docs.

Borderline Java files (YouTubeDetector 648, ScreenTimeTracker 623,
SettingsModule 602) are intentionally left as-is (cohesive; 600 treated soft).

## Progress

- `components/Customize/customize.js`: 1445 → 614 lines. Extracted
  `customize.styles.js` (dead Modes/Schedule/AppCard style keys removed),
  `ScrollBudgetSection.js`, `InterceptMessageSection.js`, `DeletionInfoModal.js`.
- `components/Home/home.js`: 1190 → 655 lines. Extracted `home.styles.js`
  (dead footer/primary/secondary/caption keys removed), `HomeScrollBudgetCard.js`,
  `FreeBreakCard.js`.
- `components/AppDetail/AppDetail.js`: 1016 → 557. Extracted `AppDetail.styles.js`,
  `InterceptCustomization.js`, `ApplyAllModal.js`.
- `components/Permissions/PermissionsScreen.js`: 878 → 600. Extracted
  `PermissionsScreen.styles.js`, `permissionSteps.js`.
- `components/Modes/ModeEditorModal.js`: 667 → 387. `components/Modes/ModesScreen.js`:
  623 → 447. Each extracted a co-located `*.styles.js`.
- Created shared `components/common/format.js` (formatTime/formatCount/
  formatBudgetTime) — removed duplicate copies across screens.
- Deleted dead file `components/Home/homeStyle.js` (311 lines, no importers).
- Committed as `e1a99a0`. App-scoped eslint: 0 new errors (1 pre-existing in
  BlockerInterstitial.tsx, untouched), 5 fewer warnings than baseline.

## Phase 3 (Java) — investigated, intentionally NOT split

All four large Java files fall under the "logic needs to be in one file"
exception, especially under a no-build constraint:

- `BreakPrefs.java` (903): a comment at the widget section literally reads
  "consolidated from WidgetPrefs" — the team deliberately merged a separate
  prefs class INTO this one. It is the single source of truth for all pref
  keys (its own header states "make key discovery trivial"). Splitting reverses
  that decision. Left as-is.
- `AppUsageMonitor.java` (1381) and `ReelsInterventionService.java` (1312):
  overlay + accessibility hot paths, tightly coupled to instance fields. A blind
  extraction (no compile/runtime check) risks subtle lifecycle/timing regressions.
- `VPNModule.java` (1165): a React Native `@ReactModule`; its `@ReactMethod`
  bridge methods must remain in the module class.

Recommendation: if these should still be split, do it in a session with the
Android build enabled so each extraction is compile-verified.

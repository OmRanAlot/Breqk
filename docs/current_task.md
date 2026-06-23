# Current Task

**Date Started:** 2026-06-22
**Status:** `[~] Implemented (not built/run) — Settings Change Lock (replaces Commitment Cooldown)`

## What was implemented

Replaced the per-key "Commitment Cooldown" loosening queue with a simpler,
opt-in **Settings Change Lock**. When enabled (default OFF, user-picked duration
24h–1wk), each settings **scope** locks after the user edits it and leaves the
screen: opening it again shows a read-only countdown card until the timer
expires, then it's editable again, and editing + leaving restarts that scope's
timer. Scopes are **independent** — one toggle governs the feature, but the
`global` (Customize) scope and each per-app (AppDetail) scope each track their
own `lockUntil`. Editing global never starts an app's timer and vice-versa.

Model: ONE `lockUntil` timestamp per scope (no pending queue, no promotion
alarm, no notification). A scope is locked iff `enabled && now < lockUntil[scope]`
— a pure read-time check.

The feature toggle is itself a commitment: enabling it (or changing its
duration) arms the global lock on exit, and while ANY scope is locked the
toggle + duration picker are read-only (`anyLocked` flag from
`SettingsLockManager.isAnyLocked`). So a user can't simply disable the feature
to shortcut a wait — it only becomes editable again once every active timer
ends. (Ultimate escape remains `pm clear` / reinstall, per the documented
threat model.)

| File | Change |
|------|--------|
| `android/.../prefs/BreakPrefs.java` | New keys `settings_lock_enabled`, `settings_lock_duration_ms`, `settings_lock_until` + DEFAULT/MIN/MAX constants (24h / 24h / 7d). Additive. (Old `config_cooldown_*` / `pending_config_changes` keys left declared but unused.) |
| `android/.../lock/SettingsLockManager.java` | **New.** `isEnabled/setEnabled`, `getDurationMs/setDurationMs` (clamped), `isLocked(scope)`, `getLockUntil(scope)`, `startLock(scope)`, `getStateJson(scope)`. Tag `SETTINGS_LOCK`. |
| `android/.../bridge/SettingsModule.java` | Guarded setters reverted to **direct writes** (cooldown routing removed). Removed `saveConfigCooldown/getConfigCooldown/getPendingChanges/cancelPendingChange` + `resultMap`. New bridge methods `getSettingsLockState`, `setSettingsLockEnabled`, `getSettingsLockEnabled`, `setSettingsLockDuration`, `startSettingsLock`. |
| `android/.../cooldown/*`, `AndroidManifest.xml` | **Deleted** `CooldownManager.java` + `CooldownExpiryReceiver.java`; removed its `<receiver>` registration. |
| `components/Customize/useSettingsLock.js` | **New.** Per-scope hook: reads state, ticks countdown, `markDirty()`, and starts the lock on blur/background/unmount when dirty + enabled. |
| `components/Customize/SettingsLockGate.js` | **New.** Read-only countdown card shown while a scope is locked. |
| `components/Customize/SettingsLockSection.js` | **New.** Opt-in toggle + duration picker + enable-confirmation modal (Prevent-Deletion-style). |
| `components/Customize/customize.js` | Removed `PendingChangesBanner`; wired `useSettingsLock('global')`, gates the protective sections when locked, always shows `SettingsLockSection`. |
| `components/AppDetail/AppDetail.js` | Wired `useSettingsLock(packageName)`; gates the per-app body when locked; `markDirty()` on feature/stepper/intercept edits. |
| `components/Permissions/PermissionsScreen.js` | Removed the onboarding cooldown step; `TOTAL_STEPS` 10→9. |
| `components/Customize/PendingChangesBanner.js` | **Deleted.** |
| `docs/LOGGING.md` | Swapped `COOLDOWN`→`SETTINGS_LOCK` tag, `[COOLDOWN]`→`[SETTINGS_LOCK]`, `[PendingChangesBanner]`→`[SettingsLock]`, and the 3 prefs keys. |

**Verify (not yet built/run):**
- `npm run android`. In Customize, enable **Settings Change Lock** (confirm modal), pick a short duration (use a debug override for QA), change a setting, leave & return → read-only countdown card (`adb logcat -s SETTINGS_LOCK`).
- Open an app's (Instagram) AppDetail while global is locked → still editable (independent scopes); editing + leaving locks only that app.
- Let the timer expire → screen editable again without restart.
- Toggle the feature OFF → every scope instantly editable.
- Threat-model note: clock-forward and `pm clear` bypasses are accepted/out-of-scope.

Lint: `npx eslint` on changed JS → 0 errors (pre-existing warnings only).
No app-level JUnit/Robolectric harness exists, so native logic is covered by the
manual QA plan above rather than automated tests.

---

## Earlier Task (2026-06-16)
**Status:** `[x] Break onboarding flow — implemented from claude.ai/design handoff`

## What was implemented

Rebuilt the first-run onboarding (`PermissionsScreen`) as the 8-screen flow from the
"Break Onboarding" design handoff: Welcome → Apps to manage → Intercept message →
Per-app breath (on/off + 5/15/30s) → 3 mandatory permission screens (Accessibility,
Usage Access, Display Over Apps) → Done. Warm off-white palette and layouts match the
mockup. Permission screens have no skip and only advance once the permission is actually
granted (verified on foreground return). Selections persist and monitoring starts in
`handleComplete()`; the `onComplete` contract is unchanged so `App.tsx` needs no edits.

| File | Change |
|------|--------|
| `components/Permissions/PermissionsScreen.js` | **Rewrite.** 8-step state machine + per-step renderers; persists blocked apps, intercept message, and per-app breath via existing bridges, then `startMonitoring()`. Logging prefix `[PermissionsScreen]` (unchanged). |
| `components/Permissions/onboarding/theme.js` | **New.** Break design tokens, app catalog/monograms, message presets, breath defaults. |
| `components/Permissions/onboarding/icons.js` | **New.** Inline SVG icons (shield, target, bars, layers, check) matching design path data. |
| `components/Permissions/onboarding/components.js` | **New.** Shared UI: PillButton, StepHeader, ProgressDots, ReassuranceCard, AppSelectRow, Toggle, Segmented, Monogram. |
| `android/.../bridge/VPNModule.java` | Added `requestAccessibilityPermission(Promise)` (opens `ACTION_ACCESSIBILITY_SETTINGS`) + `accessibility` flag in `checkPermissions` (private `isAccessibilityServiceEnabled()` matches on package name). Existing `TAG`, no new prefixes/keys. |

**Verify (not yet built/run):**
- `npm run android`, fresh install → onboarding shows all 8 screens in order.
- Apps default to Instagram/TikTok/YouTube selected; breath defaults IG 15s, TikTok 30s, YouTube off.
- Each permission screen opens the correct system settings and only advances after the grant.
- "Open Break" persists selections and starts monitoring; Home loads.

Lint clean on the new/changed JS. (Existing Jest `App.test.tsx` fails on a pre-existing
RN-ESM transform issue, unrelated to this change.)

---

## Previous Task (2026-06-09)
**Status:** `[~] UI only — Delay overlay redesign (overlay image.png)`

## What was implemented

Redesigned the native blocked-app delay overlay to match `stitch_screens/overlay image.png`.
UI only — all click handlers, countdown gating, intent dispatch, and per-app policy
logic are unchanged. Colors come from the existing RN `overlayTheme` in `design/tokens.ts`.

| File | Change |
|------|--------|
| `res/layout/delay_overlay.xml` | **Rewrite.** Bg `#121212`; bold `title` + optional `subtitle` (gone by default); center `ProgressBar @id/countdownRing` (determinate, fills over wait) with static pause glyph + "BREATHING SPACE" label; bottom buttons gain leading icons; ghost label → "Wait (Xs)". Removed unused `pulse_ring` View; kept `countdownText` budget variant. |
| `res/drawable/circular_countdown.xml` | **New.** Determinate ring progress drawable: faint bg ring + white clockwise sweep from 12 o'clock. |
| `res/drawable/ic_pause.xml`, `ic_back.xml`, `ic_hourglass.xml` | **New.** Vector glyphs for the ring center and the two buttons. |
| `monitor/AppUsageMonitor.java` | Import `ProgressBar`; find `countdownRing`/`subtitle`; subtitle empty-by-default with one-line opt-in; replaced ripple `startRippleAnimation(View)` with `startCountdownRing(ProgressBar,int)` filling 0→max over `effectiveDelaySecs`; button label `"Continue (Wait Xs)"` → `"Wait (Xs)"`; refreshed stale comments. No log tags / prefs keys changed. |

**Verify (not yet built/run):**
- `npm run android`, open a blocked app → overlay shows the new design.
- Center ring fills from empty to full over the configured delay; finishes as the "Wait (Xs)" button enables to "Continue".
- "Back to Reality" still sends home; "Continue" still allows the app for the session.

Note: `res/drawable/breathing_circle_bg.xml` is now unreferenced (was the old pulse ring).

---

## Previous Task (2026-06-04)
**Status:** `[~] In progress — Instagram home-feed scroll metrics (FEED_SCROLL)`

## What was implemented

Pure-measurement tracking of Instagram **home-feed** scrolling only (not Reels,
Explore, DMs, or Stories). Counts posts scrolled past + pixel distance and logs
them under a dedicated, isolatable tag so `adb logcat -s FEED_SCROLL` shows ONLY
this data. No overlay/intervention from this feature.

| File | Change |
|------|--------|
| `shortform/metrics/HomeFeedScrollMeter.java` | **New.** In-memory meter: posts-passed via `getFromIndex()` delta, pixels via `getScrollDeltaY()` (API 28+ guarded). Logs `[SCROLL]` per event + `[SESSION]` summary on reset. Tag `FEED_SCROLL`. |
| `shortform/platform/instagram/InstagramViewIds.java` | Added strict `HOME_FEED_RECYCLER_ID` constant (`feed_main_recycler_view`); `HOME_FEED_IDS[0]` now references it. Additive — runtime values unchanged. |
| `ReelsInterventionService.java` | Added `homeFeedScrollMeter` field; unconditional `meterHomeFeedScroll(event)` call on IG scroll (independent of intervention/free-break gates); strict home-feed-ID filter helper; meter reset at the 3 existing reset sites (app-switch, entered-reels, onInterrupt). |
| `docs/LOGGING.md` | Added `FEED_SCROLL` tag row, `[SCROLL]`/`[SESSION]` marker rows, and tag to the "See only Break logs" filter. |

**Verify (not yet built/run):**
- `npm run android`, then `adb logcat -c && adb logcat -s FEED_SCROLL`.
- Scroll IG home feed → `[SCROLL]` lines with rising posts/pixels.
- Open Explore / Reels / DMs → no new `FEED_SCROLL` lines.
- Leave Instagram → one `[SESSION]` summary line.

---

## Previous Task (2026-05-26)

**Status:** `[~] In progress — Deletion-prevention info modal + drop Device Admin`

### What was implemented

- `components/Customize/customize.js` — Enabling the "Prevent deletion" toggle now opens a confirmation modal first (what it does, risks/limitations, and a privacy note that Break collects no data and has no server/network connection). Enable persists `uninstall_lock_enabled`; Cancel reverts. Turning the toggle off stays direct.
- `components/Permissions/PermissionsScreen.js` — Removed the Device Admin onboarding screen (now a 5-screen flow); deletion prevention lives solely on the Customize toggle.
- Dropped Device Admin entirely: removed `activateDeviceAdmin`/`isDeviceAdminActive`/`deviceAdmin` from `VPNModule.java`, deleted `BreakDeviceAdminReceiver.java`, `res/xml/device_admin.xml`, the manifest `<receiver>`, `tests/static/test_014`, and tidied `_paths.py` / `test_010` / `LOGGING.md` / `TASKS.md`.

**Not yet done (not built/run):**
- `npm run lint` and `npm run android` build + on-device verification of the modal and onboarding flow.

---

## Earlier Task (2026-05-20)

**Status:** `[x] Done — Home-screen app-open intercept customization card`

### What was implemented

Added an `InterceptSettingsCard` to the Home screen letting users configure all three
aspects of the app-open intercept overlay without navigating to Customize:

| Setting | Control | Native key |
|---------|---------|-----------|
| Overlay message | `TextInput` (120-char max) | `delay_message` |
| Countdown duration | Slider 5–30 s | `delay_time_seconds` |
| Re-show frequency | Segmented: Once per open / Every X min | `popup_delay_minutes` |

"Once per open" writes the sentinel `Integer.MAX_VALUE` (JS: `2147483647`) to
`popup_delay_minutes`. `AppUsageMonitor`'s existing cooldown branch treats this as
"never re-show once shown", which mirrors the desired behaviour without a new key.

| File | Change |
|------|--------|
| `prefs/BreakPrefs.java` | Added `POPUP_DELAY_ONCE_SENTINEL = Integer.MAX_VALUE` |
| `bridge/VPNModule.java` | Added `getDelayMessage`, `getDelayTimeSeconds`, `getPopupDelayMinutes` getters; fixed `setPopupDelayMinutes` to persist to SharedPreferences |
| `monitor/AppUsageMonitor.java` | Added `loadInterceptSettingsFromPrefs()` called on `startMonitoring()`; fixed `setPopupDelayMinutes` clamp to pass-through the sentinel |
| `components/Home/home.js` | New `InterceptSettingsCard` component + styles; debounced save (1.5 s) flushes on unmount |
| `docs/LOGGING.md` | Added `[GET_MESSAGE]`, `[GET_DELAY_TIME]`, `[GET_POPUP_DELAY]`, `[LOAD_INTERCEPT]` entries |

**Not yet done (not built/run):**
- `npm run android` build + on-device verification
- Test "Once per open": dismiss overlay, reopen within seconds → no re-show; force-stop + reopen → re-shows
- Test "Every X min" at 1 min: reopen at 30 s (no popup), 65 s (popup)

---

## Previous Task (2026-05-16)

**Status:** `[x] Done — Dedicated deletion-prevention lock screen (opt-in)`
**Priority:** Feature — replace placeholder uninstall overlay with real 30s friction screen

## What was implemented

Replaced the placeholder uninstall overlay with a dedicated full-screen lock screen:
near-opaque/blurred backdrop, rotating motivational anti-deletion messages, and a
"Keep Break" button hidden for 30s (no countdown shown). Gated behind a new opt-in
"Prevent deletion" toggle in Customize — does nothing unless the user enables it.

| File | Change |
|------|--------|
| `android/.../prefs/BreakPrefs.java` | Added `isUninstallLockEnabled` / `setUninstallLockEnabled` (default false) |
| `android/.../ReelsInterventionService.java` | Settings branch now early-returns + dismisses if setting is off |
| `android/.../res/layout/overlay_uninstall_lock.xml` | Opaque light-theme (#FAFAFA) layout: headline, rotating message, delayed Keep + discouraged delete-anyway buttons |
| `android/.../res/drawable/charcoal_pill_button_bg.xml` | New: solid charcoal pill (primary Keep button, light theme) |
| `android/.../res/drawable/muted_pill_button_bg.xml` | New: faint outline pill (low-emphasis delete-anyway button) |
| `android/.../uninstall/UninstallLockOverlay.java` | Rewritten: opaque (no blur), 4s message rotation, both buttons revealed after 30s, delete-anyway dismisses + 90s reshow suppression so OS uninstall can proceed |
| `android/.../bridge/SettingsModule.java` | Added `saveUninstallLockEnabled` / `getUninstallLockEnabled` @ReactMethods |
| `components/Customize/customize.js` | New "Deletion Prevention" section with opt-in toggle |

Behavior: opaque #FAFAFA surface matching the RN app theme (no blur). After the
30s wait both buttons appear — "Keep Break" (charcoal, primary → HOME) and
"Give up and delete Break anyway" (muted, discouraged → dismiss + suppress
reshow 90s so the system uninstall completes).

Log filter: `adb logcat -s REELS_WATCH | findstr "UNINSTALL_WATCH"`

**Not yet done (not built/run):**
- `npm run android` build + on-device verification
- Test on Samsung/Xiaomi (OEM-translated "Uninstall" string may differ)
- Confirm the 90s suppression window is long enough to finish the OS uninstall confirm on the test device

---

## Previous Task (2026-05-12)

---

## Previous Task (paused)

**Date Started:** 2026-05-05
**Status:** `[ ] In progress — implementing 24h Uninstall Lock`
**Priority:** Feature work — opt-in deletion friction (`.claude/plan/24h-delete-lock.md`)

> Previous task (Custom Managed Apps Picker — 2026-05-04) is paused mid-Phase 1 (no files actually modified yet); resume by reverting this file from git.
> Earlier task (B15 — YouTube "Lock In" overlay persistence) remains paused; same recovery — revert this file.

---

## Task Description

Add an opt-in **Uninstall Protection** feature with a mandatory **24-hour cooldown** before Break can be deleted.

Flow:
1. User toggles ON → multi-step consent → Device Admin enforced → lock active.
2. User taps "Delete Break" → 24h countdown starts → cannot be cancelled.
3. After 24h → user is walked through Device Admin deactivation → uninstall.

Plan file: `c:\Users\omran\code\DoomScrollStopper\.claude\plan\24h-delete-lock.md`

---

## Implementation Steps (per plan §12 — build order)

| # | Step | Status |
|---|------|--------|
| 1 | Add `BreakPrefs` constants + duration + consent version | `[x] done 2026-05-05` |
| 2 | Create `UninstallLockManager` with twin-clock logic | `[x] done 2026-05-05` |
| 3 | Create `UninstallExpiryReceiver` + manifest registration + notif channel | `[x] done 2026-05-05` |
| 4 | Update `BreakDeviceAdminReceiver.onDisableRequested()` w/ live countdown | `[x] done 2026-05-05` |
| 5 | Add 5 `@ReactMethod`s to `VPNModule` | `[x] done 2026-05-05` |
| 6 | Create `useUninstallLock.js` hook | `[x] done 2026-05-05` |
| 7 | Create `EnableLockConsentModal.js` | `[x] done 2026-05-05` |
| 8 | Create `DeleteRequestCountdown.js` w/ `BackHandler` block | `[x] done 2026-05-05` |
| 9 | Create `DeleteReadyScreen.js` | `[x] done 2026-05-05` |
| 10 | Insert routing in `App.tsx` | `[x] done 2026-05-05` |
| 11 | Add Danger Zone section to Customize (extract `Customize/DangerZone.js`) | `[x] done 2026-05-05` |
| 12 | Manual QA via `BuildConfig.DEBUG` 60-second override | `[ ]` |
| 13 | QA tamper paths (clock, reboot, force-stop, clear data) | `[ ]` |
| 14 | Update `docs/TASKS.md` | `[x] done 2026-05-05` |

---

## Files Touched So Far

(updating as work progresses)

---

## Notes / Decisions

- Honest threat model in plan §2: the two **Open** Android-level bypasses (Device Admin deactivate, clear app data) are explicitly disclosed in the consent screen — we do not claim protection beyond what Android allows.
- `customize.js` is currently 1107 lines (already over the 800-line cap). Per plan §13, the new Danger Zone section will be extracted to `components/Customize/DangerZone.js`.
- Twin-clock design: `request_at_wall` + `request_at_boot` + `request_boot_id` to detect within-boot clock tampering. Post-reboot we trust wall clock only.
- AlarmManager: prefer `setExactAndAllowWhileIdle()`; fall back to `setAndAllowWhileIdle()` if `canScheduleExactAlarms()` returns false (Android 14+).
- `cancelDelete()` is intentionally implemented to throw `UnsupportedOperationException` — there is no cancel API.

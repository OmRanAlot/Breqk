# Current Task

**Date Started:** 2026-05-26
**Status:** `[~] In progress — Deletion-prevention info modal + drop Device Admin`

## What was implemented

- `components/Customize/customize.js` — Enabling the "Prevent deletion" toggle now opens a confirmation modal first (what it does, risks/limitations, and a privacy note that Breqk collects no data and has no server/network connection). Enable persists `uninstall_lock_enabled`; Cancel reverts. Turning the toggle off stays direct.
- `components/Permissions/PermissionsScreen.js` — Removed the Device Admin onboarding screen (now a 5-screen flow); deletion prevention lives solely on the Customize toggle.
- Dropped Device Admin entirely: removed `activateDeviceAdmin`/`isDeviceAdminActive`/`deviceAdmin` from `VPNModule.java`, deleted `BreqkDeviceAdminReceiver.java`, `res/xml/device_admin.xml`, the manifest `<receiver>`, `tests/static/test_014`, and tidied `_paths.py` / `test_010` / `LOGGING.md` / `TASKS.md`.

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
| `prefs/BreqkPrefs.java` | Added `POPUP_DELAY_ONCE_SENTINEL = Integer.MAX_VALUE` |
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
"Keep Breqk" button hidden for 30s (no countdown shown). Gated behind a new opt-in
"Prevent deletion" toggle in Customize — does nothing unless the user enables it.

| File | Change |
|------|--------|
| `android/.../prefs/BreqkPrefs.java` | Added `isUninstallLockEnabled` / `setUninstallLockEnabled` (default false) |
| `android/.../ReelsInterventionService.java` | Settings branch now early-returns + dismisses if setting is off |
| `android/.../res/layout/overlay_uninstall_lock.xml` | Opaque light-theme (#FAFAFA) layout: headline, rotating message, delayed Keep + discouraged delete-anyway buttons |
| `android/.../res/drawable/charcoal_pill_button_bg.xml` | New: solid charcoal pill (primary Keep button, light theme) |
| `android/.../res/drawable/muted_pill_button_bg.xml` | New: faint outline pill (low-emphasis delete-anyway button) |
| `android/.../uninstall/UninstallLockOverlay.java` | Rewritten: opaque (no blur), 4s message rotation, both buttons revealed after 30s, delete-anyway dismisses + 90s reshow suppression so OS uninstall can proceed |
| `android/.../bridge/SettingsModule.java` | Added `saveUninstallLockEnabled` / `getUninstallLockEnabled` @ReactMethods |
| `components/Customize/customize.js` | New "Deletion Prevention" section with opt-in toggle |

Behavior: opaque #FAFAFA surface matching the RN app theme (no blur). After the
30s wait both buttons appear — "Keep Breqk" (charcoal, primary → HOME) and
"Give up and delete Breqk anyway" (muted, discouraged → dismiss + suppress
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

Add an opt-in **Uninstall Protection** feature with a mandatory **24-hour cooldown** before Breqk can be deleted.

Flow:
1. User toggles ON → multi-step consent → Device Admin enforced → lock active.
2. User taps "Delete Breqk" → 24h countdown starts → cannot be cancelled.
3. After 24h → user is walked through Device Admin deactivation → uninstall.

Plan file: `c:\Users\omran\code\DoomScrollStopper\.claude\plan\24h-delete-lock.md`

---

## Implementation Steps (per plan §12 — build order)

| # | Step | Status |
|---|------|--------|
| 1 | Add `BreqkPrefs` constants + duration + consent version | `[x] done 2026-05-05` |
| 2 | Create `UninstallLockManager` with twin-clock logic | `[x] done 2026-05-05` |
| 3 | Create `UninstallExpiryReceiver` + manifest registration + notif channel | `[x] done 2026-05-05` |
| 4 | Update `BreqkDeviceAdminReceiver.onDisableRequested()` w/ live countdown | `[x] done 2026-05-05` |
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

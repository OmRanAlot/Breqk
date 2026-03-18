# Breqk — Logging Dictionary

A reference for all log tags, message prefixes, filter commands, and conventions across the native Android and React Native layers.

---

## Quick Start

### See everything (noisy)
```bash
adb logcat
```

### See only Breqk logs
```bash
adb logcat -s AppUsageMonitor ScreenTimeTracker VPNModule MyVpnService REELS_WATCH BROWSER_WATCH SettingsModule BreqkWidget ACC_PERM_GATE
```

### All errors across Breqk tags
```bash
adb logcat -s VPNModule:E AppUsageMonitor:E ScreenTimeTracker:E MyVpnService:E *:S
```

### React Native JS logs (Metro terminal)
JS logs appear in the **Metro bundler terminal** (`npm start`) automatically.
To filter by prefix:
```bash
# macOS / Linux
npx react-native start 2>&1 | grep "\[Home\]"

# Windows PowerShell
npx react-native start | Select-String '\[Home\]'
```

---

## Android Native Log Tags

Each Java class uses a fixed `TAG` constant as the first argument to `Log.d()` / `Log.i()` / `Log.w()` / `Log.e()`.
Filter with: `adb logcat -s <TAG>`

| TAG | File | What it covers |
|-----|------|----------------|
| `AppUsageMonitor` | `AppUsageMonitor.java` | App detection loop, overlay show/dismiss, cooldown, scroll budget enforcement |
| `ScreenTimeTracker` | `ScreenTimeTracker.java` | Daily screen time totals, per-app usage, unlock count, notification count |
| `VPNModule` | `VPNModule.java` | JS↔Android bridge: permissions, monitoring, blocked apps, budget status, wellbeing stats |
| `MyVpnService` | `MyVpnService.java` | Foreground service lifecycle, intent dispatch, scroll budget persistence |
| `REELS_WATCH` | `ReelsInterventionService.java` | Reels/Shorts scroll detection, intervention popup |
| `BROWSER_WATCH` | `PornBlockerService.java` | Browser URL extraction, blocking, cooldown |
| `SettingsModule` | `SettingsModule.java` | SharedPreferences reads/writes: blocked apps, monitoring toggle, scroll budget config |
| `BreqkWidget` | `BreqkWidgetProvider.java` | Home screen widget update events |
| `ACC_PERM_GATE` | `AccessibilityPermissionActivity.java` | Accessibility permission gate screen lifecycle |

---

## Per-File Log Prefix Reference

### VPNModule.java — Tag: `VPNModule`

| Prefix | Meaning |
|--------|---------|
| `[INIT]` | Module instantiated, blocked apps loaded |
| `[START]` | `startMonitoring()` called from JS |
| `[STOP]` | `stopMonitoring()` called from JS |
| `[SET_BLOCKED]` | `setBlockedApps()` updating both monitor instances |
| `[LOAD_BLOCKED]` | Loading blocked apps from SharedPreferences into monitor |
| `[SET_MESSAGE]` | `setDelayMessage()` / `setDelayTime()` updating overlay text |
| `[SET_POPUP_DELAY]` | `setPopupDelayMinutes()` updating re-show interval |
| `[SET_SCROLL_THRESHOLD]` | `setScrollThreshold()` updating scroll sensitivity |
| `[WELLBEING]` | `getDigitalWellbeingStats()` — comprehensive today stats |
| `[TOP_APPS_TODAY]` | `getTopAppsToday()` — per-app usage for Home dashboard |
| `[SCROLL_BUDGET]` | `setScrollBudget()` / `getScrollBudgetStatus()` calls |

Filter:
```bash
adb logcat -s VPNModule
# Key messages: [INIT], permission check, startMonitoring, stopMonitoring, [WELLBEING], [TOP_APPS_TODAY], [SCROLL_BUDGET]
```

---

### ScreenTimeTracker.java — Tag: `ScreenTimeTracker`

| Prefix | Meaning |
|--------|---------|
| `[INIT]` | Tracker instantiated |
| `[SCREEN_TIME]` | `getScreenTimeStats()` — 24h rolling total (legacy API) |
| `[COMPREHENSIVE]` | `getComprehensiveStats()` — single-pass screen time + unlocks + notifications |
| `[PER_APP]` | `getPerAppStats()` — per-app breakdown for top-apps list |
| `[UNLOCK_COUNT]` | `getUnlockCount()` — KEYGUARD_HIDDEN events; warns when API < 28 |
| `[NOTIF_COUNT]` | `getNotificationCount()` — NOTIFICATION_SEEN events; warns on OEM restriction |

Common warning patterns:
- `[UNLOCK_COUNT] API 27 < 28 (P) — KEYGUARD_HIDDEN unavailable, returning -1` → device is below Android 9; unlock count will be `null` in JS.
- `[NOTIF_COUNT] No NOTIFICATION_SEEN events found (OEM restriction likely)` → manufacturer restricts this event; notification count will be `null` in JS.
- `[PER_APP] Skipping system app: com.android.systemui` → expected; system apps are filtered.

Filter:
```bash
adb logcat -s ScreenTimeTracker
# Debug stats: grep -E "\[COMPREHENSIVE\]|\[UNLOCK_COUNT\]|\[NOTIF_COUNT\]"
```

---

### AppUsageMonitor.java — Tag: `AppUsageMonitor`

| Prefix | Meaning |
|--------|---------|
| `[MONITOR]` | Polling loop tick, foreground app detection |
| `[OVERLAY]` | Overlay shown / dismissed |
| `[BLOCKED]` | Blocked app opened, intervention triggered |
| `[ALLOW]` | User tapped "Continue" — app added to session allowlist |
| `[COOLDOWN]` | Popup cooldown active, suppressing re-trigger |
| `[SCROLL_BUDGET]` | Scroll time accumulation and budget-exhausted events *(scroll-time-limit feature)* |
| `[REELS_STATE]` | Reels state reads from SharedPreferences (isCurrentlyInReels check, gates scroll budget) |
| `POPUP_MARKER` | Inline marker logged just before overlay is shown |

Filter:
```bash
adb logcat -s AppUsageMonitor
# Overlay only: grep -E "POPUP_MARKER|removeOverlay|cooldown"
```

---

### MyVpnService.java — Tag: `MyVpnService`

| Prefix | Meaning |
|--------|---------|
| `[CREATE]` | Service `onCreate` start + loaded saved blocked apps |
| `[CMD]` | Every Intent action received (START_VPN, STOP_VPN, UPDATE_BLOCKED_APPS, SET_SCROLL_BUDGET, etc.) |
| `[BUDGET]` | Scroll budget loaded into monitor on service start |
| `[PREF]` | SharedPreferences save/load for blocked apps |
| `[LIFECYCLE]` | `onDestroy` / service stop |
| `[MONITOR]` | Monitor start/stop inside the service |

Filter:
```bash
adb logcat -s MyVpnService
# Intent dispatch: grep "\[CMD\]"
# Budget: grep "\[BUDGET\]"
```

---

### SettingsModule.java — Tag: `SettingsModule`

| Prefix | Meaning |
|--------|---------|
| `[INIT]` | Module registered |
| `[SAVE]` | Settings written to SharedPreferences |
| `[LOAD]` | Settings read from SharedPreferences |

Filter:
```bash
adb logcat -s SettingsModule
```

---

### ReelsInterventionService.java — Tag: `REELS_WATCH`

| Prefix | Meaning |
|--------|---------|
| `SCROLL_DECISION` | Per-scroll event: confirmed in Reels?, budget exhausted?, action taken |
| `[BUDGET]` | Scroll budget check result — exhausted or OK, overlay shown |
| `[REELS_STATE]` | Reels state persistence: enter/exit Reels, heartbeat refresh, SharedPreferences writes |
| `STATE_CHANGED` | Reels/Shorts layout enter/exit detection |

Logs one line per scroll event in `SCROLL_DECISION` format:
```
SCROLL_DECISION pkg=<packageName> fastPath=<bool> confirmedInReels=<bool> budgetExhausted=<bool> interventionShowing=<bool>
```

The popup triggers based on scroll budget exhaustion (time-based), NOT scroll count.
Budget state is read from SharedPreferences (`scroll_budget_exhausted_at`).

Also logs ViewPager visibility checks and screen bounds.

Filter:
```bash
# Per-scroll decisions only
adb logcat -s REELS_WATCH | grep SCROLL_DECISION

# Budget decisions (exhausted / OK)
adb logcat -s REELS_WATCH | grep BUDGET

# Full output (bounds, visibility)
adb logcat -s REELS_WATCH
```

---

### PornBlockerService.java — Tag: `BROWSER_WATCH`

Filter:
```bash
adb logcat -s BROWSER_WATCH
```

---

### AccessibilityPermissionActivity.java — Tag: `ACC_PERM_GATE`

Filter:
```bash
adb logcat -s ACC_PERM_GATE
```

---

### BreqkWidgetProvider.java — Tag: `BreqkWidget`

Filter:
```bash
adb logcat -s BreqkWidget
```

---

## Special Markers

These markers appear inline inside log messages for grep-based filtering across tags.

| Marker | Location | Filter command |
|--------|----------|----------------|
| `POPUP_MARKER` | `AppUsageMonitor.java` | `adb logcat \| grep POPUP_MARKER` |
| `SCROLL_DECISION` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| grep SCROLL_DECISION` |
| `[BUDGET]` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| grep BUDGET` |
| `[REELS_STATE]` | `ReelsInterventionService.java`, `AppUsageMonitor.java` | `adb logcat -s REELS_WATCH AppUsageMonitor \| grep REELS_STATE` |
| `[INIT]` | `VPNModule.java`, `SettingsModule.java`, `ScreenTimeTracker.java` | `adb logcat \| grep "\[INIT\]"` |
| `[CREATE]` | `MyVpnService.java` | `adb logcat \| grep "\[CREATE\]"` |
| `[CMD]` | `MyVpnService.java` | `adb logcat -s MyVpnService \| grep "\[CMD\]"` |
| `[BUDGET]` | `MyVpnService.java` | `adb logcat -s MyVpnService \| grep "\[BUDGET\]"` |
| `[COMPREHENSIVE]` | `ScreenTimeTracker.java` | `adb logcat -s ScreenTimeTracker \| grep "\[COMPREHENSIVE\]"` |
| `[PER_APP]` | `ScreenTimeTracker.java` | `adb logcat -s ScreenTimeTracker \| grep "\[PER_APP\]"` |
| `[UNLOCK_COUNT]` | `ScreenTimeTracker.java` | `adb logcat -s ScreenTimeTracker \| grep "\[UNLOCK_COUNT\]"` |
| `[NOTIF_COUNT]` | `ScreenTimeTracker.java` | `adb logcat -s ScreenTimeTracker \| grep "\[NOTIF_COUNT\]"` |
| `[WELLBEING]` | `VPNModule.java` | `adb logcat -s VPNModule \| grep "\[WELLBEING\]"` |
| `[TOP_APPS_TODAY]` | `VPNModule.java` | `adb logcat -s VPNModule \| grep "\[TOP_APPS_TODAY\]"` |
| `DEBUG:` | `VPNSwitch.js` | In Metro output, search for `DEBUG:` |

---

## React Native / JavaScript Log Prefixes

JS logs use `console.log('[Prefix] message')` so you can grep Metro output.

| Prefix | File | What it covers |
|--------|------|----------------|
| `[Home]` | `components/Home/home.js` | Stats loading, scroll budget init/polling, monitoring start, navigation |
| `[useDigitalWellbeing]` | `components/Home/useDigitalWellbeing.js` | Cache hits/misses, fetch duration, raw stat values, top apps count |
| `[Customize]` | `components/Customize/customize.js` | Settings load/save, toggle states, preview actions, preset selection |
| `[PermissionsScreen]` | `components/Permissions/PermissionsScreen.js` | Permission request flow, screen advancement |
| `[BlockerInterstitial]` | `components/BlockerInterstitial/BlockerInterstitial.tsx` | Overlay mount, countdown, button taps, budget-exhausted variant |
| `[App]` | `App.tsx` | Root navigation, modal state, event listener setup |
| `[WebView]` | `components/Browser/BrowserScreen.js` | Browser events (DEV builds only) |
| `[Progress]` | `components/Progress/progress.js` | Progress screen data loading |
| *(no prefix)* | `components/VPNSwitch/VPNSwitch.js` | Search for `DEBUG:` or specific strings like `startMonitoring` |

### Filter JS logs in Metro terminal

```bash
# All Home screen logs
npx react-native start 2>&1 | grep "\[Home\]"

# Digital wellbeing hook (cache hits, fetch times, stat values)
npx react-native start 2>&1 | grep "\[useDigitalWellbeing\]"

# All permission flow logs
npx react-native start 2>&1 | grep "\[PermissionsScreen\]"

# All customize screen logs
npx react-native start 2>&1 | grep "\[Customize\]"

# Everything with a prefix
npx react-native start 2>&1 | grep -E "\[(Home|useDigitalWellbeing|Customize|PermissionsScreen|BlockerInterstitial|App|WebView|Progress)\]"

# Windows PowerShell
npx react-native start | Select-String '\[Home\]'
```

---

## Useful adb Combos

### Watch overlay events in real time
```bash
adb logcat -s AppUsageMonitor | grep -E "POPUP_MARKER|removeOverlay|cooldown"
```

### Watch the full detection pipeline (detection → overlay → dismiss)
```bash
adb logcat -s AppUsageMonitor VPNModule MyVpnService
```

### Debug Home screen stats (screen time, unlocks, notifications, top apps)
```bash
adb logcat -s ScreenTimeTracker VPNModule | grep -E "\[COMPREHENSIVE\]|\[PER_APP\]|\[UNLOCK_COUNT\]|\[NOTIF_COUNT\]|\[WELLBEING\]|\[TOP_APPS_TODAY\]"
```

### Watch scroll budget enforcement
```bash
adb logcat -s AppUsageMonitor MyVpnService | grep -E "budget|BUDGET|exhausted"
```

### Watch Reels intervention only
```bash
adb logcat -s REELS_WATCH
```

### Watch Reels state sharing (ReelsInterventionService ↔ AppUsageMonitor)
```bash
adb logcat -s REELS_WATCH AppUsageMonitor | grep REELS_STATE
```

### Watch settings persistence
```bash
adb logcat -s SettingsModule
```

### Clear logcat buffer before a test run
```bash
adb logcat -c && adb logcat -s AppUsageMonitor REELS_WATCH VPNModule
```

### Save logs to file for sharing/debugging
```bash
adb logcat -s AppUsageMonitor VPNModule MyVpnService REELS_WATCH > session_logs.txt
```

---

## SharedPreferences Keys (`breqk_prefs`)

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `blocked_apps` | StringSet | `{}` | Package names being blocked |
| `monitoring_enabled` | boolean | `true` | Master on/off toggle |
| `redirect_instagram_to_browser` | boolean | `true` | Redirect Instagram to safe WebView |
| `delay_message` | String | *default message* | Custom overlay message text |
| `delay_time_seconds` | int | `15` | Countdown duration (5–30s) |
| `popup_delay_minutes` | int | `10` | Cooldown between overlay re-shows |
| `scroll_threshold` | int | `4` | Scroll count before Reels intervention (1–20) |
| `scroll_allowance_minutes` | int | `5` | Budget: allowed scroll time per window (0 = always block) |
| `scroll_window_minutes` | int | `60` | Budget: window duration |
| `scroll_budget_exhausted_at` | long | `0` | Timestamp when budget was exhausted (0 = not exhausted) |
| `scroll_window_start_time` | long | `0` | When current budget window started |
| `scroll_time_used_ms` | long | `0` | Accumulated scroll time in current window |
| `is_in_reels` | boolean | `false` | Whether user is currently viewing Reels/Shorts (written by ReelsInterventionService) |
| `is_in_reels_timestamp` | long | `0` | When `is_in_reels` was last updated (for staleness check, refreshed every 2s) |
| `is_in_reels_package` | String | `""` | Which app is in Reels (e.g. `com.instagram.android`) |

---

## Sentinel Values

| Value | Meaning |
|-------|---------|
| `-1` (native int) | Metric unavailable (API too low or OEM restriction) |
| `null` (JS) | Same as `-1`, after conversion in `useDigitalWellbeing.js` |
| `'—'` (JS string) | Displayed in UI when a metric is null/unavailable |

---

## Log Level Reference

| Level | Method | When used |
|-------|--------|-----------|
| Debug | `Log.d(TAG, msg)` | Normal operation flow (most logs) |
| Info | `Log.i(TAG, msg)` | Key state changes (overlay shown, monitoring toggled) |
| Warning | `Log.w(TAG, msg)` | Unexpected but recoverable states |
| Error | `Log.e(TAG, msg)` | Failures (permission denied, null views, exceptions) |

```bash
# Only errors across the whole system
adb logcat *:E

# Specific tag at a specific level (silences everything else)
adb logcat AppUsageMonitor:I *:S
```

---

## Adding New Logs

Follow these conventions when adding logs so they appear in the right filter commands:

**Java:**
```java
private static final String TAG = "YourClassName"; // top of class
Log.d(TAG, "[METHOD_NAME] what happened: " + variable);
Log.i(TAG, "[METHOD_NAME] key state change: " + state);
Log.w(TAG, "[METHOD_NAME] unexpected state: " + context);
Log.e(TAG, "[METHOD_NAME] error: " + e.getMessage());
```

**JavaScript / TypeScript:**
```js
const LOG_PREFIX = '[ComponentName]';
console.log(LOG_PREFIX, 'what happened', variable);
console.warn(LOG_PREFIX, 'unexpected state');
console.error(LOG_PREFIX, 'failure', error);
```

When adding a new Java class or JS component:
1. Add its TAG / prefix to the tables above
2. Add a focused filter command in the relevant section
3. If it introduces a new special marker, add it to the Special Markers table

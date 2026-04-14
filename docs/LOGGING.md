# Breqk — Logging Dictionary

A reference for all log tags, message prefixes, filter commands, and conventions across the native Android and React Native layers.

> **Windows Users:** All filter examples in this document support Windows PowerShell and cmd.exe. Look for **PowerShell** and **cmd.exe** labeled sections for platform-specific commands. PowerShell examples use `Select-String` and cmd.exe examples use `findstr`.

---

## Quick Start

### See everything (noisy)
```bash
adb logcat
```

### See only Breqk logs
```bash
adb logcat -s AppUsageMonitor ScreenTimeTracker VPNModule MyVpnService REELS_WATCH BROWSER_WATCH SettingsModule BreqkWidget ACC_PERM_GATE AppNameResolver ServiceHelper DEVICE_ADMIN BreqkPrefs MODE_MGR MODE_SCHED APP_ROUTER LAUNCH_INTERCEPT CONTENT_FILTER
```

### All errors across Breqk tags
```bash
adb logcat -s VPNModule:E AppUsageMonitor:E ScreenTimeTracker:E MyVpnService:E ServiceHelper:E AppNameResolver:E *:S
```

### React Native JS logs (Metro terminal)
JS logs appear in the **Metro bundler terminal** (`npm start`) automatically.
To filter by prefix:
```powershell
# Windows PowerShell
npx react-native start | Select-String '\[Home\]'

# Windows cmd.exe (findstr)
npx react-native start | findstr "[Home]"
```

---

## Android Native Log Tags

Each Java class uses a fixed `TAG` constant as the first argument to `Log.d()` / `Log.i()` / `Log.w()` / `Log.e()`.
Filter with: `adb logcat -s <TAG>`

| TAG | File | What it covers |
|-----|------|----------------|
| `AppUsageMonitor` | `AppUsageMonitor.java` | App detection loop, overlay show/dismiss, cooldown, scroll budget read-only sync |
| `ScreenTimeTracker` | `ScreenTimeTracker.java` | Daily screen time totals, per-app usage, unlock count, notification count |
| `VPNModule` | `VPNModule.java` | JS↔Android bridge: permissions, monitoring, blocked apps, budget status, wellbeing stats |
| `VPNModule:FreeBreak` | `VPNModule.java` | Free break start/end/status — separate sub-tag for easy isolation |
| `MyVpnService` | `MyVpnService.java` | Foreground service lifecycle, intent dispatch, scroll budget persistence |
| `REELS_WATCH` | `ReelsInterventionService.java` | Reels/Shorts scroll detection, intervention popup, scroll budget accumulation & enforcement (sole writer), grace period on entry |
| `BROWSER_WATCH` | `ContentFilterService.java` | Browser URL extraction, blocking, cooldown |
| `SettingsModule` | `SettingsModule.java` | SharedPreferences reads/writes: blocked apps, monitoring toggle, scroll budget config |
| `BreqkWidget` | `BreqkWidgetProvider.java` | Home screen widget update events |
| `ACC_PERM_GATE` | `AccessibilityPermissionActivity.java` | Accessibility permission gate screen lifecycle |
| `AppNameResolver` | `AppNameResolver.java` | Package name to app label resolution (LRU cache) |
| `ServiceHelper` | `ServiceHelper.java` | Foreground service start compatibility helper |
| `DEVICE_ADMIN` | `BreqkDeviceAdminReceiver.java` | Device Admin activation, deactivation, and disable-request events |
| `BreqkPrefs` | `BreqkPrefs.java` | Per-app policy reads/writes, mode resolution, migration, blocked_apps sync |
| `MODE_MGR` | `ModeManager.java` | Mode activation/deactivation, schedule alarm registration, blocked_apps sync |
| `MODE_SCHED` | `ModeSchedulerReceiver.java` | AlarmManager schedule start/end intents, BOOT_COMPLETED re-registration |
| `APP_ROUTER` | `AppEventRouter.java` | Event routing to LaunchInterceptor + ContentFilter; config cache hits/misses |
| `LAUNCH_INTERCEPT` | `LaunchInterceptor.java` | 15s mindfulness overlay on fresh app open; fresh-launch check; dismiss/record |
| `CONTENT_FILTER` | `ContentFilter.java` | Short-form ejection (GLOBAL_ACTION_BACK) for Reels/Shorts/TikTok; debounce |

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
| `[EVENTS]` | `getPerAppForegroundTimeFromEvents()` — UsageEvents-based accurate per-app foreground time |
| `[UNLOCK_COUNT]` | `getUnlockCount()` — KEYGUARD_HIDDEN events; warns when API < 28 |
| `[NOTIF_COUNT]` | `getNotificationCount()` — NOTIFICATION_SEEN events; warns on OEM restriction |

Common warning patterns:
- `[UNLOCK_COUNT] API 27 < 28 (P) — KEYGUARD_HIDDEN unavailable, returning -1` → device is below Android 9; unlock count will be `null` in JS.
- `[NOTIF_COUNT] No NOTIFICATION_SEEN events found (OEM restriction likely)` → manufacturer restricts this event; notification count will be `null` in JS.
- `[PER_APP] Skipping system app: com.android.systemui` → expected; system apps are filtered.

Filter:
```powershell
adb logcat -s ScreenTimeTracker

# PowerShell - debug stats
adb logcat -s ScreenTimeTracker | Select-String '\[COMPREHENSIVE\]|\[UNLOCK_COUNT\]|\[NOTIF_COUNT\]'

# cmd.exe alternative
adb logcat -s ScreenTimeTracker | findstr "[COMPREHENSIVE]" OR findstr "[UNLOCK_COUNT]" OR findstr "[NOTIF_COUNT]"
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
| `[ScrollBudget]` | Read-only budget sync from SharedPreferences (no longer accumulates — see REELS_WATCH) |
| `[REELS_STATE]` | Reels state reads from SharedPreferences (isCurrentlyInReels check, gates scroll budget) |
| `POPUP_MARKER` | Inline marker logged just before overlay is shown |
| `[AUTO_DISMISS]` | Overlay auto-dismissed because user left the blocked app (navigated home, switched apps, incoming call) |
| `[SAFETY_DISMISS]` | Overlay auto-dismissed after exceeding max duration (customDelayTimeSeconds + 30s failsafe) |
| `[HOME_DISMISS]` | Overlay force-dismissed via the fast path (DISMISS_OVERLAY intent from ReelsInterventionService → MyVpnService → AppUsageMonitor), before the 1s polling tick fires |
| `[FG_DETECT]` | Enhanced foreground detection — background transitions, launcher resolution |

Filter:
```powershell
adb logcat -s AppUsageMonitor

# PowerShell - overlay only
adb logcat -s AppUsageMonitor | Select-String 'POPUP_MARKER|removeOverlay|cooldown|AUTO_DISMISS|SAFETY_DISMISS'

# cmd.exe alternative (find overlay markers)
adb logcat -s AppUsageMonitor | findstr "POPUP_MARKER AUTO_DISMISS SAFETY_DISMISS"
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
```powershell
adb logcat -s MyVpnService

# PowerShell - intent dispatch
adb logcat -s MyVpnService | Select-String '\[CMD\]'

# PowerShell - budget
adb logcat -s MyVpnService | Select-String '\[BUDGET\]'

# cmd.exe alternatives
adb logcat -s MyVpnService | findstr "[CMD]"
adb logcat -s MyVpnService | findstr "[BUDGET]"
```

---

### SettingsModule.java — Tag: `SettingsModule`

| Prefix | Meaning |
|--------|---------|
| `[INIT]` | Module registered |
| `[SAVE]` | Settings written to SharedPreferences |
| `[LOAD]` | Settings read from SharedPreferences |
| `[POLICY]` | Per-app policy CRUD (getAppPolicies, saveAppPolicies, setAppFeature) |
| `[MODE]` | Mode CRUD (getModes, saveModes, activateMode, deactivateMode) |

Filter:
```bash
adb logcat -s SettingsModule
# Policy-only: adb logcat -s SettingsModule | findstr "POLICY"
# Mode-only: adb logcat -s SettingsModule | findstr "MODE"
```

---

### BreqkPrefs.java — Tag: `BreqkPrefs`

| Prefix | Meaning |
|--------|---------|
| `[POLICY]` | Per-app policy parsing, saving, feature resolution (base + mode override) |
| `[POLICY_RELOAD]` | Dispatches `UPDATE_BLOCKED_APPS` intent to `MyVpnService` after a policy/mode write so live monitors pick up the change without a restart. Also emitted by `VPNModule` when its `SharedPreferences.OnSharedPreferenceChangeListener` re-syncs its private `appMonitor` instance. |
| `[MODE]` | Mode JSON parsing, saving, default creation |
| `[MIGRATION]` | Legacy blocked_apps → per-app policy migration |

Filter:
```bash
adb logcat -s BreqkPrefs
# Policy resolution: adb logcat -s BreqkPrefs | findstr "isFeatureEnabled"
# Migration: adb logcat -s BreqkPrefs | findstr "MIGRATION"
```

**End-to-end policy-reload chain** — when a Customize toggle commits, you should see this sequence across tags:
```bash
adb logcat -s BreqkPrefs:V SettingsModule:V VPNModule:V MyVpnService:V ReactNativeJS:V
```
Expected log order (single toggle):
1. `ReactNativeJS  [Customize] app feature toggle …` — JS captures the tap
2. `ReactNativeJS  [Saver] committing N pending write(s)` — debounce window elapsed
3. `SettingsModule [POLICY] setAppFeature pkg=… …`
4. `BreqkPrefs     [POLICY] Synced blocked_apps from policies …`
5. `BreqkPrefs     [POLICY_RELOAD] dispatched UPDATE_BLOCKED_APPS size=N`
6. `MyVpnService   [CMD] UPDATE_BLOCKED_APPS size=N` — service receives intent
7. `VPNModule      [POLICY_RELOAD] VPNModule.appMonitor re-synced size=N` — second monitor in sync

---

### ModeManager.java — Tag: `MODE_MGR`

| Prefix | Meaning |
|--------|---------|
| `[ACTIVATE]` | Mode activated (includes source: manual/schedule) |
| `[DEACTIVATE]` | Mode deactivated, reverted to base policies |
| `[SCHEDULE]` | AlarmManager alarm registration/cancellation, schedule start/end handling |
| `[SYNC]` | UPDATE_BLOCKED_APPS intent sent to MyVpnService after policy change |

Filter:
```bash
adb logcat -s MODE_MGR
# Schedule-only: adb logcat -s MODE_MGR | findstr "SCHEDULE"
```

---

### ModeSchedulerReceiver.java — Tag: `MODE_SCHED`

| Prefix | Meaning |
|--------|---------|
| `[RECEIVE]` | Broadcast received (alarm or boot) |
| `[BOOT]` | BOOT_COMPLETED — alarm re-registration + current schedule check |
| `[START]` | Schedule start alarm fired for a mode |
| `[END]` | Schedule end alarm fired for a mode |

Filter:
```bash
adb logcat -s MODE_SCHED
```

---

### ReelsInterventionService.java — Tag: `REELS_WATCH`

| Prefix | Meaning |
|--------|---------|
| `SCROLL_DECISION` | Per-scroll event: confirmed in Reels?, budget exhausted?, action taken |
| `[GRACE]` | Grace period lifecycle: started on Reels/Shorts entry, ended on first scroll. Defers budget/heartbeat so user can watch the first video freely |
| `[BUDGET]` | Scroll budget check result — exhausted or OK, overlay shown |
| `[REELS_STATE]` | Reels state persistence: enter/exit Reels, heartbeat refresh, SharedPreferences writes |
| `[STILL_IN_REELS]` | Heartbeat foreground verification — checks active window package + Reels layout before accumulating budget |
| `[APP_SWITCH]` | Detected app switch while in Reels (e.g., Home button, recents) — triggers Reels state reset to prevent false positives. Suppressed when `interventionShowing=true` (sticky-fix) or when class is a framework type |
| `[STICKY-FIX]` | App-switch suppressed because the budget-exhausted intervention overlay is currently visible — overlay stays until user taps a button. Also logs dynamic launcher package resolution |
| `[DISMISS_CALL]` | Every call to `dismissIntervention()` with caller site — use to trace unexpected overlay dismissals |
| `[SHORTS_ACTIVE]` | YouTube Shorts detection state change — fires only on transitions (false→true or true→false). Shows which tier confirmed it. |
| `[SHORTS_CLASS]` | YouTube Activity/Fragment class name logged on every STATE_CHANGED event — use to discover Shorts-specific class names for TIER0 |
| `[SHORTS_TEXT]` | Text or content description matching "Shorts" found in accessibility tree (TIER3 text scan) |
| `STATE_CHANGED` | Reels/Shorts layout enter/exit detection |
| `TIER0` | YouTube Shorts detected via Activity class name (O(1), no tree traversal) |
| `TIER1` / `TIER2` | YouTube Shorts detection tier results (known IDs / structural heuristic) |
| `TIER3` | YouTube Shorts detected via visible text scan ("Shorts" in getText/getContentDescription) |
| `YT_TREE_DUMP` | YouTube accessibility tree dump when all Shorts detection tiers fail — now includes text and contentDesc fields (rate-limited 10s) |
| `return_to_feed` | User tapped "Return to Feed" — GLOBAL_ACTION_BACK fired, budget preserved |
| `lock_in` | User tapped "Lock In" — GLOBAL_ACTION_HOME fired, reels state reset |

Logs one line per scroll event in `SCROLL_DECISION` format:
```
SCROLL_DECISION pkg=<packageName> fastPath=<bool> confirmedInReels=<bool> budgetExhausted=<bool> interventionShowing=<bool>
```

The popup triggers based on scroll budget exhaustion (time-based), NOT scroll count.
ReelsInterventionService is the **sole writer** of scroll budget state (`scroll_time_used_ms`,
`scroll_budget_exhausted_at`, `scroll_window_start_time`) via its heartbeat. AppUsageMonitor
only reads these values for the JS bridge. Budget overlay is triggered when budget exhausts
(via heartbeat) or on first scroll after grace period if already exhausted.

**Grace period:** On each fresh entry into Reels/Shorts, a grace period begins. The heartbeat
and budget accumulation are deferred until the user scrolls for the first time. This lets the
user watch the first video they land on (e.g., YouTube auto-opening to Shorts, a friend's link,
or tapping a video from the home feed) without being blocked.

Also logs ViewPager visibility checks and screen bounds.

Filter:
```powershell
# Is YouTube Shorts currently active? (state transitions only — clean signal)
adb logcat -s REELS_WATCH | Select-String 'SHORTS_ACTIVE'

# Discover YouTube Activity class name for Shorts (TIER0 discovery)
adb logcat -s REELS_WATCH | Select-String 'SHORTS_CLASS'

# See text-based Shorts signals found in accessibility tree (TIER3)
adb logcat -s REELS_WATCH | Select-String 'SHORTS_TEXT'

# Grace period lifecycle (entry grace → first scroll ends grace)
adb logcat -s REELS_WATCH | Select-String 'GRACE'

# Per-scroll decisions only
adb logcat -s REELS_WATCH | Select-String 'SCROLL_DECISION'

# Budget decisions (exhausted / OK)
adb logcat -s REELS_WATCH | Select-String 'BUDGET'

# YouTube Shorts view ID discovery (when detection fails; also shows text/contentDesc now)
adb logcat -s REELS_WATCH | Select-String 'YT_TREE_DUMP'

# YouTube Shorts detection tier results
adb logcat -s REELS_WATCH | Select-String 'TIER'

# Full output (bounds, visibility)
adb logcat -s REELS_WATCH
```

**cmd.exe alternatives:**
```batch
REM Is YouTube Shorts active? (state transitions only)
adb logcat -s REELS_WATCH | findstr "SHORTS_ACTIVE"

REM Discover Shorts Activity class name
adb logcat -s REELS_WATCH | findstr "SHORTS_CLASS"

REM Text-based Shorts signals (TIER3)
adb logcat -s REELS_WATCH | findstr "SHORTS_TEXT"

REM Grace period lifecycle
adb logcat -s REELS_WATCH | findstr "GRACE"

REM Per-scroll decisions only
adb logcat -s REELS_WATCH | findstr "SCROLL_DECISION"

REM Budget decisions
adb logcat -s REELS_WATCH | findstr "BUDGET"

REM YouTube Shorts view ID discovery (includes text/contentDesc fields)
adb logcat -s REELS_WATCH | findstr "YT_TREE_DUMP"

REM YouTube Shorts tier results
adb logcat -s REELS_WATCH | findstr "TIER"
```

---

### ContentFilterService.java — Tag: `BROWSER_WATCH`

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

### BreqkDeviceAdminReceiver.java — Tag: `DEVICE_ADMIN`

Filter:
```bash
adb logcat -s DEVICE_ADMIN
```

**DEV / TESTING — ADB bypass to remove Device Admin and uninstall:**
```bash
# Step 1: deactivate Device Admin (works on debug builds via ADB shell privileges)
adb shell dpm remove-active-admin com.breqk/.BreqkDeviceAdminReceiver

# Step 2: now uninstall normally
adb uninstall com.breqk
```

---

### BreqkWidgetProvider.java — Tag: `BreqkWidget`

Filter:
```bash
adb logcat -s BreqkWidget
```

---

## Special Markers

These markers appear inline inside log messages for filtering across tags.

| Marker | Location | PowerShell Filter | cmd.exe Filter |
|--------|----------|-------------------|-----------------|
| `POPUP_MARKER` | `AppUsageMonitor.java` | `adb logcat \| Select-String 'POPUP_MARKER'` | `adb logcat \| findstr "POPUP_MARKER"` |
| `SCROLL_DECISION` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'SCROLL_DECISION'` | `adb logcat -s REELS_WATCH \| findstr "SCROLL_DECISION"` |
| `[GRACE]` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'GRACE'` | `adb logcat -s REELS_WATCH \| findstr "GRACE"` |
| `[BUDGET]` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'BUDGET'` | `adb logcat -s REELS_WATCH \| findstr "BUDGET"` |
| `TIER0` / `TIER1` / `TIER2` / `TIER3` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'TIER'` | `adb logcat -s REELS_WATCH \| findstr "TIER"` |
| `[SHORTS_ACTIVE]` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'SHORTS_ACTIVE'` | `adb logcat -s REELS_WATCH \| findstr "SHORTS_ACTIVE"` |
| `[SHORTS_CLASS]` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'SHORTS_CLASS'` | `adb logcat -s REELS_WATCH \| findstr "SHORTS_CLASS"` |
| `[SHORTS_TEXT]` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'SHORTS_TEXT'` | `adb logcat -s REELS_WATCH \| findstr "SHORTS_TEXT"` |
| `YT_TREE_DUMP` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'YT_TREE_DUMP'` | `adb logcat -s REELS_WATCH \| findstr "YT_TREE_DUMP"` |
| `[REELS_STATE]` | `ReelsInterventionService.java`, `AppUsageMonitor.java` | `adb logcat -s REELS_WATCH AppUsageMonitor \| Select-String 'REELS_STATE'` | `adb logcat -s REELS_WATCH AppUsageMonitor \| findstr "REELS_STATE"` |
| `[STILL_IN_REELS]` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'STILL_IN_REELS'` | `adb logcat -s REELS_WATCH \| findstr "STILL_IN_REELS"` |
| `[APP_SWITCH]` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'APP_SWITCH'` | `adb logcat -s REELS_WATCH \| findstr "APP_SWITCH"` |
| `[STICKY-FIX]` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'STICKY-FIX'` | `adb logcat -s REELS_WATCH \| findstr "STICKY-FIX"` |
| `[DISMISS_CALL]` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'DISMISS_CALL'` | `adb logcat -s REELS_WATCH \| findstr "DISMISS_CALL"` |
| `[INIT]` | `VPNModule.java`, `SettingsModule.java`, `ScreenTimeTracker.java` | `adb logcat \| Select-String '\[INIT\]'` | `adb logcat \| findstr "[INIT]"` |
| `[CREATE]` | `MyVpnService.java` | `adb logcat \| Select-String '\[CREATE\]'` | `adb logcat \| findstr "[CREATE]"` |
| `[CMD]` | `MyVpnService.java` | `adb logcat -s MyVpnService \| Select-String '\[CMD\]'` | `adb logcat -s MyVpnService \| findstr "[CMD]"` |
| `[BUDGET]` | `MyVpnService.java` | `adb logcat -s MyVpnService \| Select-String '\[BUDGET\]'` | `adb logcat -s MyVpnService \| findstr "[BUDGET]"` |
| `[COMPREHENSIVE]` | `ScreenTimeTracker.java` | `adb logcat -s ScreenTimeTracker \| Select-String '\[COMPREHENSIVE\]'` | `adb logcat -s ScreenTimeTracker \| findstr "[COMPREHENSIVE]"` |
| `[PER_APP]` | `ScreenTimeTracker.java` | `adb logcat -s ScreenTimeTracker \| Select-String '\[PER_APP\]'` | `adb logcat -s ScreenTimeTracker \| findstr "[PER_APP]"` |
| `[EVENTS]` | `ScreenTimeTracker.java` | `adb logcat -s ScreenTimeTracker \| Select-String '\[EVENTS\]'` | `adb logcat -s ScreenTimeTracker \| findstr "[EVENTS]"` |
| `[UNLOCK_COUNT]` | `ScreenTimeTracker.java` | `adb logcat -s ScreenTimeTracker \| Select-String '\[UNLOCK_COUNT\]'` | `adb logcat -s ScreenTimeTracker \| findstr "[UNLOCK_COUNT]"` |
| `[NOTIF_COUNT]` | `ScreenTimeTracker.java` | `adb logcat -s ScreenTimeTracker \| Select-String '\[NOTIF_COUNT\]'` | `adb logcat -s ScreenTimeTracker \| findstr "[NOTIF_COUNT]"` |
| `[WELLBEING]` | `VPNModule.java` | `adb logcat -s VPNModule \| Select-String '\[WELLBEING\]'` | `adb logcat -s VPNModule \| findstr "[WELLBEING]"` |
| `[TOP_APPS_TODAY]` | `VPNModule.java` | `adb logcat -s VPNModule \| Select-String '\[TOP_APPS_TODAY\]'` | `adb logcat -s VPNModule \| findstr "[TOP_APPS_TODAY]"` |
| `[FREE_BREAK]` | `VPNModule.java`, `ReelsInterventionService.java`, `MyVpnService.java` | `adb logcat \| Select-String '\[FREE_BREAK\]'` | `adb logcat \| findstr "[FREE_BREAK]"` |
| `FREE_BREAK_ALLOW` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| Select-String 'FREE_BREAK_ALLOW'` | `adb logcat -s REELS_WATCH \| findstr "FREE_BREAK_ALLOW"` |
| `[HOME_DISMISS]` | `ReelsInterventionService.java`, `MyVpnService.java`, `AppUsageMonitor.java` | `adb logcat \| Select-String '\[HOME_DISMISS\]'` | `adb logcat \| findstr "[HOME_DISMISS]"` |
| `DEBUG:` | `VPNSwitch.js` | In Metro output, use `Select-String 'DEBUG:'` | In Metro output, use `findstr "DEBUG:"` |

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

**PowerShell:**
```powershell
# All Home screen logs
npx react-native start | Select-String '\[Home\]'

# Digital wellbeing hook (cache hits, fetch times, stat values)
npx react-native start | Select-String '\[useDigitalWellbeing\]'

# All permission flow logs
npx react-native start | Select-String '\[PermissionsScreen\]'

# All customize screen logs
npx react-native start | Select-String '\[Customize\]'

# Everything with a prefix
npx react-native start | Select-String '\[(Home|useDigitalWellbeing|Customize|PermissionsScreen|BlockerInterstitial|App|WebView|Progress)\]'
```

**cmd.exe (findstr):**
```batch
REM All Home screen logs
npx react-native start | findstr "[Home]"

REM Digital wellbeing hook
npx react-native start | findstr "[useDigitalWellbeing]"

REM All permission flow logs
npx react-native start | findstr "[PermissionsScreen]"

REM All customize screen logs
npx react-native start | findstr "[Customize]"
```

---

## Useful adb Combos

### Uninstall the app quickly
```powershell
# Remove Device Admin (no root needed on debug builds)
adb shell dpm remove-active-admin com.breqk/.BreqkDeviceAdminReceiver

# Then uninstall normally
adb uninstall com.breqk
```


### Watch overlay events in real time
```powershell
# PowerShell
adb logcat -s AppUsageMonitor | Select-String 'POPUP_MARKER|removeOverlay|cooldown'

# cmd.exe
adb logcat -s AppUsageMonitor | findstr "POPUP_MARKER"
```

### Watch the full detection pipeline (detection → overlay → dismiss)
```bash
adb logcat -s AppUsageMonitor VPNModule MyVpnService
```

### Debug Home screen stats (screen time, unlocks, notifications, top apps)
```powershell
# PowerShell
adb logcat -s ScreenTimeTracker VPNModule | Select-String '\[COMPREHENSIVE\]|\[PER_APP\]|\[UNLOCK_COUNT\]|\[NOTIF_COUNT\]|\[WELLBEING\]|\[TOP_APPS_TODAY\]'

# cmd.exe
adb logcat -s ScreenTimeTracker VPNModule | findstr "[COMPREHENSIVE]" OR findstr "[PER_APP]" OR findstr "[WELLBEING]"
```

### Watch scroll budget enforcement
```powershell
# PowerShell
adb logcat -s AppUsageMonitor MyVpnService | Select-String 'budget|BUDGET|exhausted'

# cmd.exe
adb logcat -s AppUsageMonitor MyVpnService | findstr "budget BUDGET exhausted"
```

### Watch Reels intervention only
```bash
adb logcat -s REELS_WATCH
```

### Watch Reels state sharing (ReelsInterventionService ↔ AppUsageMonitor)
```powershell
# PowerShell
adb logcat -s REELS_WATCH AppUsageMonitor | Select-String 'REELS_STATE'

# cmd.exe
adb logcat -s REELS_WATCH AppUsageMonitor | findstr "REELS_STATE"
```

### Watch home-screen overlay dismissal (fast path + fallback)
```powershell
# PowerShell — see the full dismiss chain when pressing Home
adb logcat -s REELS_WATCH MyVpnService AppUsageMonitor | Select-String 'HOME_DISMISS|AUTO_DISMISS|APP_SWITCH'

# cmd.exe
adb logcat -s REELS_WATCH MyVpnService AppUsageMonitor | findstr "HOME_DISMISS AUTO_DISMISS APP_SWITCH"
```

Expected sequence when pressing Home while an overlay is visible:
1. `REELS_WATCH    [APP_SWITCH] Detected app switch while in Reels: newPkg=<launcher>` — AccessibilityEvent fires (~0ms)
2. `REELS_WATCH    [HOME_DISMISS] resetReelsState: dismissing intervention overlay if visible` — reels overlay dismissed
3. `REELS_WATCH    [HOME_DISMISS] Sending DISMISS_OVERLAY intent to MyVpnService` — intent dispatched
4. `MyVpnService   [HOME_DISMISS] DISMISS_OVERLAY received — dismissing delay overlay` — service picks up intent
5. `AppUsageMonitor [HOME_DISMISS] dismissOverlayIfShowing: force-dismissing overlay for <pkg>` — delay overlay dismissed
6. *(~1s later)* `AppUsageMonitor [AUTO_DISMISS] System/launcher app detected: <launcher>` — existing polling fallback also fires (harmless)

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
| `free_break_enabled` | boolean | `false` | Feature toggle: whether the 20-min free break button is visible on Home |
| `free_break_active` | boolean | `false` | Whether a free break is currently running (written by VPNModule) |
| `free_break_start_time` | long | `0` | Epoch ms when the current break started (0 = no active break) |
| `free_break_last_used_date` | String | `""` | "yyyy-MM-dd" of last break usage; resets at midnight (new calendar day) |

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

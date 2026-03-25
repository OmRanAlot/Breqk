# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Start Metro bundler (run in one terminal)
npm start

# Build and deploy to Android device/emulator (run in second terminal)
npm run android

# Lint
npm run lint

# Tests
npm test
```

For Android-only builds (from `android/` directory):
```bash
./gradlew clean
./gradlew build
```

## Architecture Overview

**Breqk** is a React Native Android app that blocks distracting apps by showing a mandatory delay overlay when a blocked app is opened.

### Layer Separation

```
React Native UI (TypeScript/JS)
    ↕ NativeModules bridge
VPNModule.java + SettingsModule.java + ScreenTimeTracker.java
    ↕ SharedPreferences ("breqk_prefs")
MyVpnService.java (foreground service) → AppUsageMonitor.java
    ↕ UsageStatsManager (Android API)
WindowManager overlay (shown when blocked app is foregrounded)
    ↕
ReelsInterventionService.java (AccessibilityService)
    ↕ AccessibilityEvent
Instagram Reels / YouTube Shorts scroll detection
```

### Entry Points & Cold Start Flow

1. **AccessibilityPermissionActivity** — Gate screen; blocks access until ReelsInterventionService is enabled in system accessibility settings
2. **MainActivity** (ReactActivity) — Standard Android host once past gate
3. **App.tsx** — Root component; calls `checkPermissions()`, routes to `PermissionsScreen` or `Home`
4. **PermissionsScreen** — 5-screen onboarding (Welcome → Usage Access → Overlay → VPN → Success)
5. **Home** — Main dashboard once all permissions granted

Background monitoring starts separately:
1. `MyVpnService.onCreate()` → loads blocked apps from SharedPreferences → creates AppUsageMonitor
2. `MyVpnService.onStartCommand(START_VPN)` → starts 1s polling loop
3. `AppUsageMonitor.monitorApps()` → shows overlay when blocked app detected in foreground

### Native Modules (android/app/src/main/java/com/breqk/)

| File | Purpose |
|------|---------|
| **VPNModule.java** | Primary JS↔Android bridge. Manages permissions, starts/stops monitoring, exposes screen time stats, emits `onAppDetected` / `onBlockedAppOpened` events to JS. Has its own `AppUsageMonitor` instance. |
| **AppUsageMonitor.java** | Core polling loop (every 1s) using `UsageStatsManager`. Shows delay overlay via `WindowManager` when a blocked app is in foreground. Manages per-session allowlist and 10-min cooldown. Tracks scroll budget. |
| **MyVpnService.java** | Foreground service for background persistence. Owns a second `AppUsageMonitor` instance. Handles `UPDATE_BLOCKED_APPS`, `SET_DELAY_MESSAGE`, `SET_SCROLL_BUDGET` intents. **Does not tunnel VPN traffic** — VPN binding is used purely for process persistence. |
| **SettingsModule.java** | SharedPreferences bridge. Persists blocked apps list, monitoring toggle, redirect flag, scroll budget config, scroll threshold. |
| **ScreenTimeTracker.java** | Aggregates 24-hour foreground time across all apps via `UsageStatsManager`. Returns total screen time, unlock count, notification count, per-app usage. |
| **ReelsInterventionService.java** | AccessibilityService monitoring Instagram Reels / YouTube Shorts. Counts scrolls; shows intervention popup after scroll threshold is reached. |
| **ContentFilterService.java** | Browser-based URL blocking service. |
| **MainActivity.java** | Standard ReactActivity host. Handles VPN permission results via `onActivityResult()`. |
| **AccessibilityPermissionActivity.java** | Gate screen requiring ReelsInterventionService to be enabled before entering the app. |
| **BreqkWidgetProvider.java** | Broadcast receiver for home screen widget updates. |
| **AppBlockerPackage.java** | Registers all native modules with the React Native bridge. |
| **BreqkPrefs.java** | Centralized SharedPreferences constants and cached accessor. Single source of truth for all pref keys. |
| **AppNameResolver.java** | Package name → app label resolution with LRU cache (100 entries). Used by AppUsageMonitor and ScreenTimeTracker. |
| **ServiceHelper.java** | `startForegroundServiceCompat()` — compatibility helper for starting foreground services across API levels. |

### Important: Dual Monitor Instances

There are **two** `AppUsageMonitor` instances: one in `VPNModule` and one in `MyVpnService`. Both must have their blocked apps lists synced. When `VPNModule.setBlockedApps()` is called from JS, it updates both instances via SharedPreferences + intent dispatch.

### VPNModule API (callable from JS via NativeModules)

```js
VPNModule.checkPermissions()              // → { usage: bool, overlay: bool }
VPNModule.startMonitoring()
VPNModule.stopMonitoring()
VPNModule.setBlockedApps(appArray)        // updates both monitor instances
VPNModule.setDelayMessage(text)
VPNModule.setDelayTime(seconds)           // 5–30s countdown
VPNModule.setPopupDelayMinutes(minutes)   // cooldown between re-showing overlay
VPNModule.setScrollThreshold(count)       // scrolls before Reels intervention fires
VPNModule.setScrollBudget(allowanceMin, windowMin)
VPNModule.getScrollBudgetStatus()         // → { allowanceMinutes, windowMinutes, usedMinutes, remainingMinutes, windowStartTime }
VPNModule.getDigitalWellbeingStats()      // → { totalScreenTimeMin, unlockCount, notificationCount }
VPNModule.getTopAppsToday(limit)          // → [{ packageName, appName, usageTimeMin }]
VPNModule.requestPermissions()
VPNModule.requestOverlayPermission()
VPNModule.requestVpnPermission()
VPNModule.openUsageAccessSettings()
VPNModule.openPermissionsSettings()
```

### SettingsModule API

```js
SettingsModule.getBlockedApps(callback)
SettingsModule.saveBlockedApps(appArray)
SettingsModule.getMonitoringEnabled(callback)
SettingsModule.saveMonitoringEnabled(bool)
SettingsModule.getRedirectInstagramToBrowser(callback)
SettingsModule.saveRedirectInstagramToBrowser(bool)
SettingsModule.getScrollBudget(callback)                   // → { allowanceMinutes, windowMinutes }
SettingsModule.saveScrollBudget(allowance, window)
SettingsModule.getScrollThreshold(callback)
SettingsModule.saveScrollThreshold(threshold)
SettingsModule.updateWidgetStats(focusScore, timeSaved, appsBlocked, monitoringEnabled)
```

### Native Events Emitted to JS

| Event | Payload | Trigger |
|-------|---------|---------|
| `onAppDetected` | `{ packageName, appName }` | Any foreground app change |
| `onBlockedAppOpened` | `{ packageName, appName }` | Blocked app specifically opened (overlay will show) |

### SharedPreferences Keys (`breqk_prefs`)

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `blocked_apps` | StringSet | `{}` | Package names being blocked |
| `monitoring_enabled` | boolean | true | Master on/off toggle |
| `redirect_instagram_to_browser` | boolean | true | Redirect Instagram to safe WebView |
| `delay_message` | String | "Take a moment..." | Custom overlay message |
| `delay_time_seconds` | int | 15 | Countdown duration (5–30s) |
| `popup_delay_minutes` | int | 10 | Cooldown between overlay re-shows |
| `scroll_threshold` | int | 4 | Scrolls before Reels intervention (1–20) |
| `scroll_allowance_minutes` | int | 5 | Budget: allowed scroll time per window (0 = always block) |
| `scroll_window_minutes` | int | 60 | Budget: window duration |
| `is_in_reels` | boolean | false | Whether user is currently viewing Reels/Shorts (written by ReelsInterventionService) |
| `is_in_reels_timestamp` | long | 0 | When `is_in_reels` was last updated (staleness check; refreshed every 2s) |
| `is_in_reels_package` | String | "" | Which app is in Reels (e.g. com.instagram.android) |

### React Native Components (components/)

| File | Purpose |
|------|---------|
| **App.tsx** | Root. Stack navigator (Home → Customize → Browser). Handles deep links `breqk://browser/:platform`. Checks permissions on mount. |
| **Home/home.js** | Dashboard: screen time, unlocks, notifications, top 5 apps, monitoring toggle. Uses `useDigitalWellbeing` hook. |
| **Home/useDigitalWellbeing.js** | Custom hook. Fetches/caches digital wellbeing stats (5-min TTL). Returns `{ stats, topApps, loading, error, refresh }`. Auto-refreshes on app foreground. Converts native `-1` sentinel → `null`. |
| **Home/homeStyle.js** | StyleSheet for Home (Stitch dark design system). |
| **Customize/customize.js** | Settings: intervention mode toggles, scroll budget config (allowance/window pickers), intercept message editor, delay slider, preview modal. |
| **BlockerInterstitial/BlockerInterstitial.tsx** | Full-screen countdown overlay. Two expanding pulse rings + static text + "Back to Reality" / "Continue (Wait Xs)" buttons. Budget-exhausted variant shows countdown to next window. |
| **BlockerInterstitial/index.ts** | Re-export of `BlockerInterstitial`. |
| **Permissions/PermissionsScreen.js** | 5-screen onboarding (Welcome → Usage Access → Overlay → VPN → Success). Tether light design system. |
| **Progress/progress.js** | Streak counter, weekly bar chart, achievement badges. Currently placeholder data. |
| **Browser/BrowserScreen.js** | WebView wrapper for Instagram/YouTube in distraction-free mode. CSS injections hide Reels/Shorts. Supports deep links. |
| **Browser/injections.js** | `INSTAGRAM_CSS`, `YOUTUBE_CSS`, `buildInjectionScript()` — CSS/JS injected into WebView to hide distracting feeds. |
| **TopBar/TopBar.js** | Shared header component used across screens. |
| **VPNSwitch/VPNSwitch.js** | Monitoring on/off toggle component. |
| **components/index.ts** | Module re-exports. |

### Navigation Structure

```
Stack Navigator (App.tsx)
├── Home (initial route)
│   └── gear icon → Customize
└── Deep link: breqk://browser/:platform → Browser

Permission gate: AccessibilityPermissionActivity (before MainActivity)
Onboarding: PermissionsScreen (if Usage Stats / Overlay not granted)
```

### Design System

Two palettes are in use — both sourced from `design/tokens.ts`:

| Palette | Background | Text | Accent | Used In |
|---------|-----------|------|--------|---------|
| **Stitch (Dark)** | Navy `#1A1B41` | Mint `#F1FFE7` | Seafoam `#C2E7DA` | Home, BrowserScreen |
| **Tether (Light)** | Off-white `#F8F8F6` | Charcoal `#1A1A1A` | Light borders | PermissionsScreen, Customize |

`design/tokens.ts` exports: colors, typography (Display → caption), spacing (xs=4px → xxxl=64px), radii, shadows, animation durations, icon defaults.

### Android Resources (android/app/src/main/res/layout/)

| File | Purpose |
|------|---------|
| **delay_overlay.xml** | Full-screen overlay for blocked app intervention. Black FrameLayout with pulse ring, question text, two action buttons (Back to Reality / Continue). Hidden countdown TextView for budget-exhausted variant. |
| **overlay_reels_intervention.xml** | Bottom-sheet popup for Reels/Shorts intervention. "Still here intentionally?" + "Lock In" / "Take a Break" buttons. |
| **activity_accessibility_permission.xml** | Gate screen layout requesting ReelsInterventionService permission. |
| **widget_breqk.xml** | Home screen widget: focus score, time saved, apps blocked, monitoring status. |

### Android Build Config

- `minSdk=24` (Android 7.0), `compileSdk=35`, `targetSdk=35`
- Hermes JS engine enabled
- Kotlin 2.1.20

### Required Android Permissions

`PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `BIND_VPN_SERVICE`, `QUERY_ALL_PACKAGES`

### Features Implemented

1. **App Blocking** — Detects blocked apps and shows mandatory countdown overlay
2. **Custom Overlay** — Editable message + countdown (5–30s)
3. **Session Allowlist** — "Continue" button allows app for current session with 10-min cooldown
4. **Screen Time Tracking** — Daily total, unlock count, notification count via UsageStatsManager
5. **Per-App Usage** — Top 5 apps by usage time with progress bars
6. **Reels/Shorts Intervention** — AccessibilityService counts scrolls; shows popup after threshold
7. **Safe Browser** — WebView for Instagram/YouTube with CSS hiding Reels/Shorts feeds
8. **Instagram Redirect** — Opens Instagram directly in safe browser instead of native app
9. **Monitoring Toggle** — Enable/disable blocking, persisted across restarts
10. **Scroll Budget** — *(In Progress)* Time-limited scrolling with rolling windows and lockout overlay; plan at `.claude/plan/scroll-time-limit.md`

### Known Issues & Important Gotchas

- **Dual Monitor Sync** — When adding new settings that affect `AppUsageMonitor`, always update both the VPNModule instance and dispatch an intent to MyVpnService. See `VPNModule.setBlockedApps()` as the reference pattern.
- **Overlay Permission Guard** — `AppUsageMonitor.startMonitoring()` silently exits if `SYSTEM_ALERT_WINDOW` not granted. Always check permissions before starting.
- **Reels False Positives** — `ReelsInterventionService` uses `isFullScreenReelsViewPager()` to gate scroll counting; requires element to cover ≥90% width, ≥70% height, and be <200px from top of screen.
- **-1 Sentinel Values** — Native layer returns `-1` for unavailable metrics (e.g., notification count on Android <28). `useDigitalWellbeing.js` converts these to `null` before exposing to JS.
- **Progress Screen** — Currently shows placeholder/hardcoded data; not connected to real stats.
- **Preset Modes** — Deep Work / Sleep / Reading / Detox listed in Customize but not fully wired.
- **Widget** — Basic structure in place (BreqkWidgetProvider + widget_breqk.xml) but not fully functional.

### Logging Architecture

See **LOGGING.md** (quick reference) and **docs/LOG_DICTIONARY.md** (full dictionary) for all tags, filter commands, and conventions.

Three-tier system:
1. **Android Native** — `Log.d/i/w/e(TAG, ...)` with file-level `TAG` constants
2. **React Native JS** — `console.log('[Prefix] ...')` via Metro
3. **Centralized Dictionary** — all tags, prefixes, special markers, and adb/Metro filter commands

Key special log markers:
- `POPUP_MARKER` — when overlay is shown
- `SCROLL_DECISION` — per-scroll event decision (BLOCK / ALLOW / IGNORE) in Reels service
- `[INIT]` — module/service initialization
- `[BUDGET]` — scroll budget calculations
- `[COMPREHENSIVE]` — full stats query
- `[WELLBEING]` — digital wellbeing API call

### Performance Notes

- Monitor polling: 1000ms interval
- Stats cache TTL: 5 minutes (warm cache bypassed on app foreground)
- Accessibility scroll debounce: 600ms between counted scrolls
- Overlay show debounce: 500ms to prevent double-trigger
- Scroll budget state persisted every ~10s to survive service restarts

## Rules

Comment and document everything you are doing.
Try to use already existing code within the codebase.
If something is being repeated/called 3+ times, create the necessary function, variable, or file as required.
Allow the code to be future proof; assume that the code will be read by humans or other agents.
Allow for easy customizations within the direct file for testing/debugging purposes.
Add logging everywhere and at every step. Ensure the system to filter through logs is intuitive and easy. Update LOGGING.md as a dictionary on how to look through the logs.

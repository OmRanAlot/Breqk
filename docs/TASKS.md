# TASKS.md — Break Launch Playbook
> **Last updated:** 2026-06-18  
> **Purpose:** Single source of truth for all tasks, architecture, and known issues. Read this first before making any changes.

---

## Table of Contents
- [Task Tracker](#task-tracker)
- [Codebase Directory](#codebase-directory)
- [Architecture Overview](#architecture-overview)
- [Feature Inventory](#feature-inventory)
- [Bug Inventory](#bug-inventory)
- [Fragility Map](#fragility-map)
- [Monetization Plan](#monetization-plan)
- [Growth Strategy](#growth-strategy)
- [Key Design Decisions](#key-design-decisions)
- [Future Features](#future-features-not-urgent)

---

## Task Tracker

### 🔴 P0 — Launch Blockers (Must fix before any public release)

- [ ] **B15 (PRIORITY 1): YouTube "Lock In" overlay persists on home / long-form after leaving Shorts**
  - Files: `android/app/src/main/java/com/Break/shortform/detection/YouTubeDetector.java`, `ReelsInterventionService.java`, `shortform/budget/BudgetHeartbeat.java`
  - Symptom: With scroll budget exhausted on YouTube only, opening a Short and then leaving it (tap Home tab, open a long-form video, etc.) leaves the "Time is up! Lock In" overlay attached on the YouTube home feed and long-form video player. It never dismisses on its own.
  - Root cause: `YouTubeDetector` Tier 3 (`scanNodeForShortsText`, lines 256-287) walks the accessibility tree for any "shorts" text — and matches YouTube's persistent **bottom-nav "Shorts" tab** even when the user has clearly left the Shorts player. `ReelsInterventionService.handleReelsScrollEvent()` and `isStillInReels()` therefore think the user is still in Shorts → no `resetReelsState()` → no `dismissIntervention()`. Compounded by empty `SHORTS_CLASS_NAMES` (Tier 0 never fires), `STICKY-FIX-HEARTBEAT` (heartbeat refuses to dismiss while overlay is up), and the framework-class STATE_CHANGED early-return that swallows real exit transitions.
  - Fix: see plan `.claude/plan/youtube-shorts-overlay-persistence-fix.md`. Three layers: (1) add `YouTubeDetector.detectStrict()` (Tier 1+2 only, no text walk) and use it for "still-in" / exit checks; (2) tighten Tier 3 with bounds check (reject nodes < 30% screen height or in bottom 15%) + seekbar-absence sanity; (3) replace `BudgetHeartbeat` `STICKY-FIX-HEARTBEAT` unconditional-true with a 2-tick failure counter using the strict detector.
  - Effort: 2-3 hours including manual QA on a real device.

- [x] **B17: Mode `setting_overrides` were never applied — modes did not take over** ✅ 2026-07-14
  - Files: `prefs/BreakPrefs.java`, `monitor/AppUsageMonitor.java`, `monitor/UsageStatsQuery.java` (new), `service/BreakVpnService.java`, `bridge/SettingsModule.java`, `bridge/VPNModule.java`, `components/shared/useDefaultModeGate.js` + `ModeGateBanner.js` (new), `components/Customize/customize.js`, `components/AppDetail/AppDetail.js`, `components/Home/ActiveModeBanner.js` (new)
  - Root cause: `BreakPrefs.getEffectiveSettingInt()` — the resolver for a mode's `setting_overrides` — had **zero callers**. Every settings read hit raw SharedPreferences, so Study/Bedtime's `delay_time_seconds: 20` was persisted, shown in the mode editor, and then ignored at runtime. Only `policy_overrides` worked (via `isFeatureEnabled`).
  - Fix applied: all settings reads now resolve through the active mode (precedence: **active mode → per-app → base global**). `AppUsageMonitor.reloadSettings()` re-reads its cached values on every mode transition, wired to the `UPDATE_BLOCKED_APPS` dispatch that `ModeManager.activate()` already sends.
  - Also: **base settings are now editable ONLY in Default mode.** Customize + AppDetail go read-only behind a `ModeGateBanner` ("Switch to Default mode to change these settings") whenever another mode is active, and every native setter enforces the same rule independently (`[MODE_GATE]`; Promise setters reject with `MODE_ACTIVE`). Not gated: the Modes screen, the derived `blocked_apps` cache, and the safety locks (uninstall / settings / content-filter double-safe). Home's one-line mode text became a mode-coloured `ActiveModeBanner` with an **End** button — the escape hatch back to Default.
  - Note: `UsageStatsQuery.java` extracted from `AppUsageMonitor` (which crossed the 1500-line hard limit; test_060).
  - Verification: static 24 PASS / 0 FAIL, jest 40/40, Java compiles clean. **Device QA still pending** — exercise a scheduled mode activating while Customize is open.

- [x] **B16: YouTube intercept fired twice (delay overlay + typing coach stacked)** ✅ 2026-07-13
  - Files: `coach/YouTubeCoachGate.java` (new), `ReelsInterventionService.java`, `monitor/AppUsageMonitor.java`, `monitor/PopupDecision.java`, `prefs/BreakPrefs.java`, `bridge/SettingsModule.java`, `components/AppDetail/*`
  - Fix applied: the typing coach is now YouTube's App Open Intercept STYLE — exactly one surface fires. Coach ON (toggle in AppDetail → App Open Intercept): typing gate at launch + re-fires every X minutes (per-app "Re-show overlay" interval; once-per-open disables re-fire); AppUsageMonitor suppresses the delay overlay (`[COACH_OWNS]`). Coach OFF: normal delay overlay, like Instagram. Trigger logic extracted to `YouTubeCoachGate` (service was >1500 lines). Unit tests: `PopupDecisionTest` 29/29.

- [x] **B2: Migrate BreakVpnService away from VpnService** ✅ 2026-04-30
  - File: `android/app/src/main/java/com/Break/service/BreakVpnService.java`
  - Fix applied: Changed `extends VpnService` → `extends Service`. Removed `BIND_VPN_SERVICE` permission from AndroidManifest. Replaced VPN service declaration with a plain foreground service (`foregroundServiceType=specialUse`). Removed VPN intent-filter. Updated notification channel to `"BreakMonitoring"`, notification text to `"Break Active"`. Renamed actions to `START_MONITORING`/`STOP_MONITORING`. Stubbed `requestVpnPermission()` as a no-op in `VPNModule.java`.

- [ ] **B3: Set up agentic review system
  - File: `android/app/src/main/java/com/Break/service/BreakVpnService.java`
  - Problem: We need to set up a system that can review the app and provide feedback on the code.
  - Fix: Set up emulator and run the app on it with instagram installed and logged in. Create script to run the app and provide feedback on the code. Use agents to run tests and see what the issue is and provide feedback on the code.
  - Effort: 2 days

- [ ] **B5: Set real versionCode/versionName**
  - File: `android/app/build.gradle` (lines 86-87)
  - Problem: `versionCode 1` and `versionName "1.0"` — Play Store requires incrementing versionCode on every upload.
  - Fix: Set `versionCode 100` (gives room for patches: 101, 102…) and `versionName "1.0.0"`. Consider a date-based scheme like `versionCode = YYMMDDHH`.
  - Effort: 15 minutes

- [ ] **B8: Generate release signing keystore**
  - File: `android/app/build.gradle` (line 106)
  - Problem: Release builds use the debug keystore. Once uploaded to Play Store, you can never change the key pair.
  - Fix: Generate a production keystore with `keytool -genkeypair -v -keystore Break-release.keystore -alias Break -keyalg RSA -keysize 2048 -validity 10000`. Create a `keystore.properties` file (gitignored) and reference it in `build.gradle`. Use Play App Signing if possible.
  - Effort: 30 minutes

- [ ] **B6: Remove empty TouchableOpacity in Home screen**
  - File: `components/Home/home.js` (lines 556-562)
  - Problem: Renders an empty, pressable view with no content or handler. Tapping it does nothing.
  - Fix: Delete lines 556-562 entirely (the `<View><TouchableOpacity></TouchableOpacity></View>` block).
  - Effort: 5 minutes

- [x] **B9: Disable VERBOSE_LOGGING for production** ✅ 2026-04-15
  - File: `android/app/src/main/java/com/Break/ReelsInterventionService.java`
  - Fix applied: `logVerbose()` helper gates all chatty scroll/tree-traversal `Log.d` calls behind `BuildConfig.DEBUG`. Release builds emit zero SCROLL_DECISION noise.

### 🟡 P1 — High Priority (Fix before v1.1)

- [x] **B1: Extract shared YouTube Shorts view IDs into a single constant file** ✅ 2026-04-15
  - Fix applied: Moved to `com.Break.reels.ShortFormIds`. Both `ContentFilter` and `ReelsInterventionService` now reference `ShortFormIds.YOUTUBE_SHORTS_VIEW_IDS`. A single file to update when YouTube changes IDs.

- [ ] **Integrate Firebase Crashlytics**
  - Files: `android/app/build.gradle`, `android/build.gradle`, new `google-services.json`
  - Problem: Zero crash reporting. When users report "it doesn't work", you have no data.
  - Fix: Add `com.google.firebase:firebase-crashlytics` dependency. Add `apply plugin: 'com.google.gms.google-services'` and `apply plugin: 'com.google.firebase.crashlytics'`. Create Firebase project and download `google-services.json`.
  - Effort: 2 hours

- [ ] **Integrate Firebase Analytics (basic events)**
  - Same files as Crashlytics (bundle together)
  - Events to track: `app_open`, `reels_blocked`, `shorts_blocked`, `mode_activated`, `free_break_started`, `paywall_shown`, `purchase_completed`
  - Effort: 1 hour (after Crashlytics is set up)

- [ ] **Add error boundary to React Native app**
  - File: `App.tsx`
  - Problem: A JS crash in any screen takes down the entire app. Native services keep running, but the user can't access settings.
  - Fix: Wrap the navigator in a custom `ErrorBoundary` component that shows a "Something went wrong — tap to restart" screen and logs the error.
  - Effort: 1 hour

- [ ] **B10: Make adult content domain list configurable**
  - File: `android/app/src/main/java/com/Break/ContentFilterService.java` (lines 55-68)
  - Problem: Hardcoded list, no user customization, `lower.contains(domain)` can false-positive.
  - Fix: Move to SharedPreferences or a remote config. Use proper URL parsing instead of `contains()`.
  - Effort: 3 hours

- [ ] **B11: Add TikTok to per-app policies UI**
  - File: `components/Customize/customize.js` (lines 270-273)
  - Problem: Only Instagram and YouTube are shown. TikTok is handled natively but has no settings toggle.
  - Fix: Add `{ packageName: 'com.zhiliaoapp.musically', label: 'TikTok' }` to the APPS array. Verify SettingsModule supports it.
  - Effort: 30 minutes

- [ ] **Add a TikTok browser-mode opener with JS/CSS scroll lock**
  - Files: `components/Browser/BrowserScreen.js`, `components/Browser/injections.js`
  - Problem: We need an easy fallback path that opens the browser version of a blocked app and removes scrolling so the user stays in a more intentional flow.
  - Fix: Add a one-tap browser entry point for blocked apps, inject JS/CSS to disable scroll, and verify the flow works reliably on TikTok first.
  - Effort: 2-4 hours

- [x] **Rename "VPN" terminology throughout the codebase** ✅ 2026-04-30
  - Fix applied (as part of B2): Notification text → "Break Active". Channel ID → "BreakMonitoring". Actions → START_MONITORING/STOP_MONITORING. `BreakVpnService` → `service/BreakVpnService`. `VPNModule.requestVpnPermission()` stubbed as no-op. AndroidManifest cleaned of all VPN references. Class name `VPNModule` retained for JS bridge backward compatibility.

### 🟢 P2 — Growth Features (Weeks 2-4)

- [ ] **Implement paywall with Google Play Billing**
  - New files: `BreakBilling.java`, `PaywallScreen.js`
  - Free tier: 1 app + app-open intercept only + default mode
  - Premium ($3.99/mo or $29.99/yr): Unlimited apps, Reels/Shorts blocking, custom modes, scroll budget, free break, full dashboard
  - Gate: `BreakPrefs.isPremium()` checked by ContentFilter, ModeManager, scroll budget logic
  - Effort: 3 days

- [ ] **Build dark mode**
  - Files: `components/Home/home.js`, `components/Customize/customize.js`, new `theme.js`
  - Extract color palette into a `theme.js` with light/dark variants. Use React Context to propagate. Respect system setting + manual toggle in Customize.
  - Effort: 1 day

- [ ] **Build usage streak tracker**
  - Files: New `BreakPrefs` keys, `home.js` (new card), new `StreakCard.js` component
  - Track consecutive days where total screen time < user-defined goal. Show streak count + emoji on Home. "🔥 7-day streak!"
  - Effort: 1 day

- [ ] **Build "Share my stats" card generator**
  - New component: `ShareCard.js`
  - Generate a branded image with streak count, screen time, days tracked. Share via Android share intent.
  - Effort: 2 days

- [x] **Add mode start/end notifications** ✅ 2026-06-18
  - File: `android/app/src/main/java/com/breqk/mode/ModeNotifier.java`
  - Fix applied: Created `ModeNotifier` utility class with `notifyModeStarted()` and `notifyModeEnded()`. Posts dismissible notifications on a dedicated `BreakModeAlerts` channel so users can mute mode alerts independently of the monitoring notification. Stable per-mode notification ID replaces the start alert with the end alert instead of stacking. `ModeManager` calls notifier on both scheduled (AlarmManager) and manual mode transitions.
  - Log tag: `MODE_NOTIFY` — filter: `adb logcat -s MODE_NOTIFY`

- [ ] **Build daily/weekly summary notification**
  - New file: `NotificationHelper.java`
  - "You saved 45min today by skipping Reels" — push notification at 9pm daily.
  - Effort: 1 day

- [ ] **Refactor customize.js (B12)**
  - File: `components/Customize/customize.js` (1,723 lines)
  - Break into: `ModeSection.js`, `ScrollBudgetSection.js`, `InterceptMessageSection.js`, `AppPolicyCard.js`
  - Effort: 2 hours

### 🔵 P3 — Moat Features (Month 2+)

- [ ] **Focus sessions** — "I want to be off my phone for 2 hours" with progress ring + completion celebration
- [ ] **Friend accountability** — share invite link, see each other's streaks, notification if friend relapses
- [ ] **Widget redesign** — show today's screen time + streak on home screen
- [ ] **Scheduled modes with geofencing** — auto-activate Study Mode at the library
- [ ] **iOS private beta** via Screen Time API (full rewrite of native layer)

### ⚪ Future Features (Not Urgent)

- [ ] Require a short "why do you want to open this app?" check-in before launching a blocked app
- [ ] Show a reminder of today's to-do list or goals before opening a blocked app
- [ ] Display a specific motivational image before opening a blocked app
- [ ] Add a simple browser-version launcher for blocked apps with injected JS/CSS to remove scrolling
- [ ] **Redirect on block** — instead of just showing a delay overlay, let the user configure a redirect destination (an app package name OR a URL) that launches immediately when a blocked app is intercepted. Example: blocking Instagram redirects to Kindle, blocking TikTok redirects to Duolingo. UI: per-app "Redirect to" picker in Customize. Native: `LaunchInterceptor` fires an Intent to open the redirect target after a short pause rather than just waiting. Unlocks a powerful habit-replacement pattern ("when I reach for Instagram, open my book instead").

---

## Codebase Directory

### Project Root
```
DoomScrollStopper/
├── App.tsx                          # RN entry point, navigation setup, permission gate
├── package.json                     # React Native 0.80.2, React 19.1.0
├── TASKS.md                         # ← YOU ARE HERE
├── LOGGING.md                       # Logging tag reference
│
├── components/
│   ├── Home/
│   │   ├── home.js                  # Dashboard: stats, scroll budget, free break, top apps (898 lines)
│   │   └── useDigitalWellbeing.js   # Hook: polls VPNModule for stats with 5-min TTL cache
│   ├── Customize/
│   │   ├── customize.js             # Settings: per-app toggles, modes, budget, message (1,723 lines) ⚠️ NEEDS SPLIT
│   │   └── useDebouncedSaver.js     # Hook: coalesces rapid toggle writes (7s window)
│   ├── Permissions/
│   │   └── PermissionsScreen.js     # Onboarding: permission request flow
│   └── BlockerInterstitial/
│       └── BlockerInterstitial.js   # Preview of the delay overlay (used in Customize)
│
└── android/app/src/main/
    ├── AndroidManifest.xml           # Service declarations, permissions, receivers
    ├── java/com/Break/
    │   ├── ReelsInterventionService.java      # 🔴 CORE — AccessibilityService: Reels/Shorts detection, scroll budget, overlay
    │   ├── MainApplication.kt                 # RN application class, module registration
    │   ├── MainActivity.java                  # RN host activity
    │   │
    │   ├── accessibility/
    │   │   └── AccessibilityPermissionActivity.java  # Permission gate shown before MainActivity
    │   ├── bridge/
    │   │   ├── VPNModule.java                 # RN bridge: monitoring, permissions, free break, modes, stats
    │   │   ├── SettingsModule.java             # RN bridge: settings read/write, blocked apps, modes
    │   │   └── BreakReactPackage.java          # Registers VPNModule + SettingsModule with RN
    │   ├── mode/
    │   │   ├── ModeManager.java                # Mode lifecycle, AlarmManager scheduling
    │   │   ├── ModeNotifier.java               # Posts start/end notifications for mode transitions
    │   │   └── ModeSchedulerReceiver.java      # BroadcastReceiver for AlarmManager mode triggers
    │   ├── monitor/
    │   │   ├── AppUsageMonitor.java            # Foreground polling (1s) + delay overlay injection
    │   │   ├── AppNameResolver.java            # LRU cache for package → app name resolution
    │   │   ├── LaunchInterceptor.java          # 15s mindfulness overlay on app launch
    │   │   ├── ScreenTimeTracker.java          # Digital wellbeing: screen time, unlocks, notifications
    │   │   └── ServiceHelper.java              # startForegroundServiceCompat() wrapper
    │   ├── prefs/
    │   │   └── BreakPrefs.java                 # SharedPreferences hub, policy resolution, migrations
    │   ├── service/
    │   │   └── BreakVpnService.java            # Foreground service — keeps AppUsageMonitor alive (plain Service, not VPN)
    │   ├── shortform/
    │   │   ├── AppConfig.java                  # Per-app configuration
    │   │   ├── AppEventRouter.java             # Event dispatcher with 5s config cache
    │   │   ├── ContentFilter.java              # Surgical Reels/Shorts EJECTION via GLOBAL_ACTION_BACK
    │   │   ├── FrameworkClassFilter.java       # Filters out Android framework accessibility events
    │   │   ├── FullScreenCheck.java            # Geometry constants for full-screen detection
    │   │   ├── budget/
    │   │   │   ├── BudgetHeartbeat.java        # Periodic check: still in reels? still over budget?
    │   │   │   ├── BudgetState.java            # Immutable scroll-budget snapshot
    │   │   │   ├── HomeFeedCounter.java        # Counts home-feed post scrolls (non-Reels)
    │   │   │   └── ScrollBudgetLogic.java      # Core budget arithmetic
    │   │   ├── detection/
    │   │   │   ├── InstagramDetector.java      # Instagram Reels view-ID detection
    │   │   │   ├── ShortFormDetector.java      # Interface for all platform detectors
    │   │   │   └── YouTubeDetector.java        # YouTube Shorts detection (Tier 1/2/3)
    │   │   ├── intervention/
    │   │   │   ├── InterventionOverlay.java    # Shows/dismisses the budget-exhausted popup
    │   │   │   └── ShortFormStateMachine.java  # State machine for scroll intervention flow
    │   │   ├── metrics/
    │   │   │   └── HomeFeedScrollMeter.java    # Meters non-Reels home feed scrolls
    │   │   └── platform/
    │   │       ├── FilterHandler.java          # Interface for per-platform filter handlers
    │   │       ├── Platform.java               # Enum of supported platforms
    │   │       ├── PlatformRegistry.java       # Maps package names → FilterHandler instances
    │   │       ├── facebook/
    │   │       │   ├── FacebookDetector.java
    │   │       │   ├── FacebookFilterHandler.java
    │   │       │   └── FacebookViewIds.java
    │   │       ├── instagram/
    │   │       │   ├── InstagramFilterHandler.java
    │   │       │   └── InstagramViewIds.java
    │   │       ├── snapchat/
    │   │       │   ├── SnapchatDetector.java
    │   │       │   ├── SnapchatFilterHandler.java
    │   │       │   └── SnapchatViewIds.java
    │   │       ├── tiktok/
    │   │       │   ├── TikTokDetector.java
    │   │       │   ├── TikTokFilterHandler.java
    │   │       │   └── TikTokViewIds.java
    │   │       └── youtube/
    │   │           ├── YouTubeFilterHandler.java
    │   │           └── YouTubeViewIds.java
    │   ├── uninstall/
    │   │   ├── UninstallLockOverlay.java       # Shows overlay blocking uninstall attempt
    │   │   └── UninstallScreenDetector.java    # Detects when user navigates to uninstall screen
    │   └── widget/
    │       └── BreakWidgetProvider.java        # Home screen widget provider
    │
    └── res/
        ├── layout/
        │   ├── activity_accessibility_permission.xml  # Permission gate UI
        │   ├── delay_overlay.xml                      # App-open delay overlay
        │   ├── overlay_reels_intervention.xml          # Reels/Shorts budget exhausted overlay
        │   └── widget_Break.xml                       # Home screen widget layout
        └── xml/
            ├── reels_intervention_service_config.xml   # AccessibilityService config for ReelsInterventionService
            ├── content_filter_accessibility_config.xml  # AccessibilityService config for ContentFilterService
            └── widget_Break_info.xml                   # Widget metadata
```

---

## Architecture Overview

### Data Flow

```
User toggles setting in Customize
  → SettingsModule.setAppFeature() writes to SharedPreferences (Break_prefs)
  → BreakPrefs.syncBlockedAppsFromPolicies() updates legacy blocked_apps set
  → SharedPreferences listener in VPNModule re-syncs its AppUsageMonitor
  → UPDATE_BLOCKED_APPS intent sent to BreakVpnService
  → BreakVpnService's AppUsageMonitor updates its blocked apps set
  → ReelsInterventionService reads BreakPrefs.isFeatureEnabled() on next event (5s cache)
```

### Event Processing Pipeline

```
Android OS fires AccessibilityEvent
  → ReelsInterventionService.onAccessibilityEvent()
    → AppEventRouter.onAccessibilityEvent()
      → [1] TYPE_WINDOW_STATE_CHANGED → LaunchInterceptor.onWindowStateChanged()
      → [2] TYPE_VIEW_SCROLLED / TYPE_WINDOW_CONTENT_CHANGED → ContentFilter.onContentChanged()
    → [3] (existing) handleReelsScrollEvent() for scroll budget tracking
```

### Two Independent Intervention Systems

| System | Trigger | Action | Config Flag |
|--------|---------|--------|-------------|
| **ContentFilter** (ejection) | Reels/Shorts detected via view IDs + full-screen check | `GLOBAL_ACTION_BACK` — kicks user out of Reels | `blockShortForm` / `FEATURE_BLOCK_SHORT_FORM` |
| **ReelsInterventionService** (budget) | Scroll budget exhausted after N minutes of Reels | Shows overlay popup — user must wait or leave | `reelsDetection` / `FEATURE_REELS_DETECTION` (scroll budget) |

These are **independent features**. Both can be active for the same app at the same time. ContentFilter fires regardless of budget state.

### Policy Resolution Chain

```
BreakPrefs.isFeatureEnabled(context, packageName, featureKey):
  1. Check active mode's policy_overrides for this package+feature
  2. If found → return the override value
  3. If not → check base per-app policy (app_policies JSON)
  4. If not → return false (default)
```

### Dual Monitor Architecture (⚠️ Known Risk — see B3)

```
VPNModule (React Native bridge)
  └── AppUsageMonitor instance #1   ← used ONLY for getAppName(), usage stats queries
                                       NOT started for monitoring

BreakVpnService (foreground service)
  └── AppUsageMonitor instance #2   ← runs the actual 1s polling loop
                                       manages delay overlay
                                       
Both must have synchronized blocked apps lists!
Sync mechanism: SharedPreferences.OnSharedPreferenceChangeListener + UPDATE_BLOCKED_APPS intent
```

---

## Feature Inventory

| Feature | Status | Native File | JS File | Config Key |
|---------|--------|-------------|---------|------------|
| App-open 15s pause | ✅ Working | LaunchInterceptor.java | customize.js | `FEATURE_LAUNCH_POPUP` / `app_open_intercept` |
| Instagram Reels block | ✅ Working | ContentFilter.java | customize.js | `FEATURE_BLOCK_SHORT_FORM` / `reels_detection` |
| YouTube Shorts block | ⚠️ Fragile | ContentFilter.java, ReelsInterventionService.java | customize.js | Same as above |
| TikTok full-app block | ✅ Working | ContentFilter.java | ❌ No UI toggle (B11) | Same as above |
| Scroll budget | ✅ Working | ReelsInterventionService.java, AppUsageMonitor.java | customize.js, home.js | `scroll_allowance_minutes`, `scroll_window_minutes` |
| Free break (20-min daily) | ✅ Working | VPNModule.java | home.js, customize.js | `free_break_enabled`, `free_break_active` |
| Custom modes (Study, Bedtime) | ✅ Working | ModeManager.java, BreakPrefs.java | customize.js | `modes` JSON, `active_mode` |
| Scheduled modes (AlarmManager) | ✅ Working | ModeManager.java, ModeSchedulerReceiver.java | customize.js | Mode schedule JSON |
| Browser adult content blocker | ✅ Working | ContentFilterService.java | — (no UI) | Hardcoded domain list |
| Deletion prevention (uninstall pause overlay) | ✅ Working | UninstallScreenDetector.java, UninstallLockOverlay.java | customize.js (Prevent deletion toggle) | `uninstall_lock_enabled` |
| 24-hour Uninstall Lock | ✅ Implemented 2026-05-05 | UninstallLockManager.java, UninstallExpiryReceiver.java | DangerZone.js, useUninstallLock.js | `uninstall_lock_enabled`, `delete_request_at_wall`, `delete_request_expires_at` |
| Settings Change Lock (opt-in per-scope edit lock) | ✅ Implemented 2026-06-22 (not built/run) — replaced Commitment Cooldown | lock/SettingsLockManager.java, bridge/SettingsModule.java | Customize/useSettingsLock.js, SettingsLockGate.js, SettingsLockSection.js, customize.js, AppDetail/AppDetail.js | `settings_lock_enabled`, `settings_lock_duration_ms`, `settings_lock_until` |
| Screen time dashboard | ✅ Working | ScreenTimeTracker.java | home.js, useDigitalWellbeing.js | UsageStatsManager |
| Top apps by usage | ✅ Working | ScreenTimeTracker.java | home.js | UsageEvents |
| Unlock count | ✅ Working (API 28+) | ScreenTimeTracker.java | home.js | KEYGUARD_HIDDEN events |
| Notification count | ⚠️ OEM-dependent | ScreenTimeTracker.java | home.js | NOTIFICATION_SEEN events |
| Home screen widget | 🟡 Partial | widget_Break.xml, widget_Break_info.xml | — | Layout exists, minimal logic |

---

## Bug Inventory

### 🔴 Critical (Launch Blockers)

| ID | Bug | File | Line(s) | Status |
|----|-----|------|---------|--------|
| B15 | YouTube "Lock In" overlay persists on home / long-form after leaving Shorts (Tier 3 matches bottom-nav Shorts tab) | shortform/detection/YouTubeDetector.java + ReelsInterventionService.java | 256-287, 510-521, 991-998 | [ ] TODO (P0 #1) |
| B2 | BreakVpnService extends VpnService but never tunnels — Play Store rejection risk | service/BreakVpnService.java | — | [x] FIXED 2026-04-30 |
| B3 | Dual AppUsageMonitor instances can desync — intermittent overlay failure | bridge/VPNModule.java + service/BreakVpnService.java | 81, 55 | [ ] TODO |
| B4 | Null action in onStartCommand crashes on OS service restart | service/BreakVpnService.java | — | [x] FIXED 2026-04-30 |
| B5 | versionCode=1 blocks future Play Store updates | build.gradle | 86-87 | [ ] TODO |
| B8 | Release builds use debug keystore — permanent key lock-in | build.gradle | 106 | [ ] TODO |

### 🟡 High

| ID | Bug | File | Line(s) | Status |
|----|-----|------|---------|--------|
| B1 | YOUTUBE_SHORTS_VIEW_IDS duplicated between two files | ContentFilter.java + ReelsInterventionService.java | 86-94 | [x] FIXED 2026-04-15 |
| B6 | Empty TouchableOpacity renders invisible tappable area | home.js | 556-562 | [ ] TODO |
| B7 | source.recycle() before slow path — fragile control flow | ContentFilter.java | 211 | [ ] TODO |
| B9 | VERBOSE_LOGGING=true in production — battery drain | ReelsInterventionService.java | — | [x] FIXED 2026-04-15 |
| B10 | Hardcoded adult content domains, no user config, false-positive risk | ContentFilterService.java | 55-68 | [ ] TODO |

### 🟢 Low

| ID | Bug | File | Line(s) | Status |
|----|-----|------|---------|--------|
| B11 | TikTok missing from per-app policies UI | customize.js | 270-273 | [ ] TODO |
| B14 | Scroll-budget overlay disappears ~2s after showing — heartbeat's isStillInReels() sees overlay as active window (pkg=com.Break), not Instagram | ReelsInterventionService.java | ~1560 | [x] FIXED 2026-04-13 |
| B12 | customize.js is 1,723 lines — needs splitting | customize.js | all | [ ] TODO |
| B13 | No React Native error boundary | App.tsx | — | [ ] TODO |

---

## Fragility Map

> **How to read:** 🔴 = breaks easily (external dependency changes), 🟡 = moderate risk, 🟢 = stable. "Fragility" measures how likely the component is to break WITHOUT any code changes on our side.

| Component | Fragility | Why | Mitigation |
|-----------|-----------|-----|------------|
| **YouTube Shorts detection** | 🔴 **HIGH** | YouTube frequently changes view IDs in app updates. The `YOUTUBE_SHORTS_VIEW_IDS` array becomes stale every few months. Tier 2 heuristic (`reel_time_bar`) is a fallback but also fragile. | Monitor `adb logcat -s REELS_WATCH` after YouTube updates. Add Crashlytics breadcrumbs for detection success rate. Consider a remote-config list of IDs. |
| **Instagram Reels detection** | 🟡 **MEDIUM** | Instagram has been stable with `clips_viewer_view_pager` and `clips_viewer_pager`, but one rename would break detection. | Same monitoring strategy. Two ID variants already provide resilience. |
| **TikTok detection** | 🟢 **LOW** | The entire app is short-form video — no view ID detection needed. ContentFilter ejects on any scroll event when blockShortForm=true. | Basically unbreakable unless TikTok fundamentally changes their app architecture. |
| **Browser content filter** | 🟡 **MEDIUM** | Depends on browser URL bar view IDs (16 browsers mapped). Browser updates can change these. Chrome is stable; smaller browsers are less predictable. | The fallback path uses event.getText() for URL extraction. Consider using AccessibilityNodeInfo text traversal. |
| **AccessibilityService itself** | 🟡 **MEDIUM** | Android OS updates can change AccessibilityService behavior. Android 14 tightened background service restrictions. Android 15+ may add further restrictions. | Test on latest Android beta before each OS release. |
| **SharedPreferences as IPC** | 🟢 **LOW** | Multiple processes/threads write to `Break_prefs`. SharedPreferences uses apply() (async) which is safe for single-process. Multi-process access would be unsafe but we're single-process. | Stay single-process. If ever going multi-process, migrate to DataStore or ContentProvider. |
| **AlarmManager scheduling** | 🟢 **LOW** | ModeManager correctly handles Android 12+ exact alarm permission checks and falls back to inexact alarms. | Already handles SecurityException gracefully. |
| **React Native bridge** | 🟢 **LOW** | Standard ReactMethod pattern. RN 0.80.2 is stable. | Keep bridge methods simple. Avoid complex data types across the bridge. |
| **Full-screen bounds check** | 🟢 **LOW** | Four-signal validation (visibility + width + height + top offset) is robust. Thresholds (90% width, 70% height, 200px top) account for status/nav bars. | Only breaks if an app renders Reels in a non-standard container. Hasn't happened yet. |

### External Dependencies & Their Risk

| Dependency | Version | Risk | Notes |
|------------|---------|------|-------|
| React Native | 0.80.2 | 🟢 Low | Current stable. No need to upgrade immediately. |
| React | 19.1.0 | 🟢 Low | Latest. No compatibility issues noted. |
| @react-navigation/* | 7.x | 🟢 Low | Stable major version. |
| @react-native-community/slider | 4.5.7 | 🟢 Low | Simple component, rarely breaks. |
| react-native-webview | 13.16.1 | 🟢 Low | Used for "Open Instagram (Safe Mode)" browser. |
| react-native-svg | 15.15.3 | 🟢 Low | Used for icons only. |

---

## Monetization Plan

### Free vs Premium Split

```
FREE TIER                          PREMIUM ($3.99/mo or $29.99/yr)
──────────────────────────         ──────────────────────────────────
✅ 1 app (Instagram OR YouTube)        ✅ Unlimited apps
✅ App-open intercept only             ✅ Reels/Shorts surgical blocking - ONLY IG(5m/60m)
✅ Default mode only                   ✅ Custom modes (Study, Bedtime, etc.)
✅ Scroll budget - ONLY IG(5m/60m)     ✅ Scroll budget + Free Break
❌ No usage dashboard                  ✅ Full digital wellbeing dashboard
❌ No streak tracking                  ✅ Streak tracking + share cards
```

### Implementation Checklist

- [ ] Add Google Play Billing Library dependency
- [ ] Create `BreakBilling.java` — product fetch, purchase flow, entitlement cache
- [ ] Add `BreakPrefs.isPremium()` — checks cached entitlement
- [ ] Gate ContentFilter (Reels ejection) behind premium check
- [ ] Gate ModeManager (custom modes) behind premium check
- [ ] Gate scroll budget UI and logic behind premium check
- [ ] Build PaywallScreen.js — feature comparison, purchase button, restore button
- [ ] Handle subscription lifecycle: renew, cancel, grace period, billing retry
- [ ] Test on Play Console internal test track

### Revenue Targets

| Metric | Month 1 | Month 3 | Month 6 |
|--------|---------|---------|---------|
| Installs | 500 | 3,000 | 15,000 |
| Conversion | 8% | 6% | 5% |
| Premium subs | 40 | 180 | 750 |
| MRR | $160 | $720 | $3,000 |

---

## Growth Strategy

### Content Pillars

| Pillar | Platform | Format | Frequency |
|--------|----------|--------|-----------|
| "Before vs After" | TikTok, Reels, Shorts | 15-30s vertical video | 3x/week |
| Screen time reveals | Instagram Stories, X | Screenshot + caption | Daily |
| Build in public | X (Twitter) | Text + screenshot thread | 2x/week |
| Founder story | TikTok, Reddit | 60s storytelling video | 1x/week |
| Educational | YouTube, Reddit | 3-5min explainer | 2x/month |

### Launch Channels

- **Reddit:** r/digitalminimalism, r/nosurf, r/androidapps, r/productivity — lead with value, link app only when asked
- **Product Hunt:** Tuesday launch, GIF demos, engage maker community 2 weeks before
- **X (Twitter):** Build-in-public threads, engage @HumaneByDesign, @CalNewport communities
- **TikTok:** Hook format: "POV: you try to open Instagram but your custom app blocks you"

### Viral Mechanics to Build

- [ ] Share card generator — branded image with streak + screen time
- [ ] Referral system — "Invite a friend → both get 1 week Premium free"
- [ ] Weekly push — "You were in the top 10% of focused users this week"

---

## Key Design Decisions

### Why AccessibilityService instead of VPN-based blocking?
- VPN approach (like DNS filtering) blocks the ENTIRE app. We need to surgically block Reels while keeping DMs, profiles, etc. working.
- AccessibilityService can inspect the UI tree and detect specific views (Reels viewer).
- AccessibilityService is also used for the app-open interception (detecting window state changes).
- Trade-off: Fragile to app updates (view IDs change). But the alternative (VPN) can't distinguish Reels from the rest of Instagram.

### Why SharedPreferences instead of a database?
- The data is small (a few KB of JSON policies and flags).
- SharedPreferences is synchronous and available from both the RN bridge and native services.
- It's the simplest IPC mechanism for our single-process architecture.
- Migration path: If we ever need structured queries or multi-process access, move to Room + ContentProvider.

### Why two AppUsageMonitor instances?
- **Historical accident**, not a design choice. VPNModule created one for usage stats queries. BreakVpnService created another for the polling loop.
- The correct fix is to make VPNModule's instance query-only (no polling) and have BreakVpnService own the only running monitor. This is already the case in practice — VPNModule's monitor is never started for monitoring.
- The risk is blocked-apps desync. Mitigated by SharedPreferences listener + UPDATE_BLOCKED_APPS intent.

### Why the 5-second config cache in AppEventRouter?
- Accessibility events fire hundreds of times per second during scroll.
- Reading SharedPreferences on every event would be a performance disaster.
- 5 seconds is a good balance: settings changes take effect within 5s (imperceptible to user, perceptible to developer during testing).

### Why debounced saves (7s) in Customize?
- Users rapidly toggle switches during initial setup. Each toggle triggers a SharedPreferences write + service restart.
- Without debouncing: 10 rapid toggles = 10 writes + 10 service notifications in 2 seconds.
- With 7s debounce: 10 rapid toggles = 1 coalesced write after the user stops toggling.
- Flush on blur/background ensures no writes are ever dropped.

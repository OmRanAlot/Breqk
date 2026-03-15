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

**DoomScrollStopper** is a React Native Android app that blocks distracting apps by showing a mandatory delay overlay when a blocked app is opened.

### Layer Separation

```
React Native UI (TypeScript/JS)
    ↕ NativeModules bridge
VPNModule.java + SettingsModule.java
    ↕ SharedPreferences ("doomscroll_prefs")
MyVpnService.java (foreground service) → AppUsageMonitor.java
    ↕ UsageStatsManager (Android API)
WindowManager overlay (shown when blocked app is foregrounded)
```

### Native Modules (android/app/src/main/java/com/doomscrollstopper/)

- **VPNModule.java** — Primary JS↔Android bridge. Manages permissions, starts/stops monitoring, exposes screen time stats, emits `onAppDetected` / `onBlockedAppOpened` events to JS.
- **SettingsModule.java** — Persists blocked apps list and monitoring toggle via SharedPreferences.
- **AppUsageMonitor.java** — Core polling loop (every 1s) using `UsageStatsManager`. Shows delay overlay via `WindowManager` when a blocked app is in foreground. Manages per-session allowlist and 10-min cooldown.
- **MyVpnService.java** — Foreground service that keeps the app alive in background. Has its own `AppUsageMonitor` instance. Loads blocked apps from SharedPreferences on startup. **Note:** Does not actually tunnel VPN traffic — the VPN service binding is used purely for process persistence.
- **ScreenTimeTracker.java** — Aggregates 24-hour foreground time across all apps.

### Important: Dual Monitor Instances

There are **two** `AppUsageMonitor` instances: one in `VPNModule` and one in `MyVpnService`. Both must have their blocked apps lists synced. When `VPNModule.setBlockedApps()` is called from JS, it updates both instances.

### React Native Components (components/)

- **App.tsx** — Root; checks permissions on mount, shows `PermissionsScreen` if missing, otherwise renders bottom tab nav (Home / Customize / Progress).
- **Home** — Daily stats (focus score, apps blocked, time saved). Polls native module for real-time events. Caches app usage with 5-min TTL.
- **Customize** — Focus mode presets (Deep Work, Sleep, Reading, Detox), delay slider (5–120s), custom message editor, monitoring toggle.
- **Progress** — Streak counter, weekly bar chart, achievement badges.
- **PermissionsScreen** — Requests Usage Stats, Overlay, and optional VPN permissions in sequence.
- **BlockerInterstitial** — The overlay countdown screen shown when a blocked app is detected.

### Design System

Tokens live in `design/tokens.ts`. Dark-mode only. Primary color: Sage Teal `#5B9A8B`. Background: `#1D201F`. Text: `#F1FFE7`.

### Android Build Config

- `minSdk=24`, `compileSdk=35`, `targetSdk=35`
- Hermes JS engine enabled
- Kotlin 2.1.20

### Required Android Permissions

`PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `BIND_VPN_SERVICE`, `QUERY_ALL_PACKAGES`

## Rules
Comment and document everything you are doing.
Try to use already exisintg code within the codebase. 
If something is being repeated/called 3+ times, create the nessecary function, variable, or file as required.
Allow the code to be future proof, assume that the code will be read by humans or other agents.
Allow for easy customizations within the direct file for testing/debugging purposes.
Add logging everywhere and at every step. Ensure the system to filter through logs are intutive and easy. Update a md file as a dictionary on how to look through the logs.
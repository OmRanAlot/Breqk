# Breqk — Logging Dictionary

A reference for filtering and reading logs across the native Android and React Native layers.

---

## Quick Start

### See everything (noisy)
```bash
adb logcat
```

### See only Breqk logs
```bash
adb logcat -s AppUsageMonitor VPNModule MyVpnService REELS_WATCH BROWSER_WATCH SettingsModule BreqkWidget ACC_PERM_GATE
```

### React Native JS logs (Metro terminal)
JS logs appear in the **Metro bundler terminal** (`npm start`) automatically.
To filter by prefix, pipe through grep:
```bash
npx react-native start 2>&1 | grep "\[Home\]"
```

---

## Android Native Log Tags

Each Java class uses a fixed `TAG` string as the first argument to `Log.d()` / `Log.i()` / `Log.e()`.
Filter with: `adb logcat -s <TAG>`

| TAG | File | What it covers |
|-----|------|---------------|
| `AppUsageMonitor` | `AppUsageMonitor.java` | App detection loop, overlay show/dismiss, cooldown tracking |
| `VPNModule` | `VPNModule.java` | JS↔Android bridge: permissions, monitoring start/stop, blocked apps sync |
| `MyVpnService` | `MyVpnService.java` | Foreground service lifecycle (onCreate, onStartCommand, onDestroy) |
| `REELS_WATCH` | `ReelsInterventionService.java` | Reels/Shorts scroll detection, intervention popup |
| `BROWSER_WATCH` | `PornBlockerService.java` | Browser URL extraction, blocking, cooldown |
| `SettingsModule` | `SettingsModule.java` | SharedPreferences reads and writes for blocked apps list |
| `BreqkWidget` | `BreqkWidgetProvider.java` | Home screen widget update events |
| `ACC_PERM_GATE` | `AccessibilityPermissionActivity.java` | Accessibility permission gate screen lifecycle |

---

## Android — Focused Filter Commands

### App detection & overlay
```bash
adb logcat -s AppUsageMonitor
```
Key messages to look for:
- `POPUP_MARKER showing delay overlay for <appName>` — overlay is about to appear
- `removeOverlay` — overlay dismissed
- `cooldown active` — why a repeated open was skipped

### Reels scroll decisions (one line per scroll event)
```bash
adb logcat -s REELS_WATCH | grep SCROLL_DECISION
```
Format:
```
SCROLL_DECISION pkg=<packageName> intent=<intent> shard=<shard> count=<count> threshold=<threshold> decision=<BLOCK|ALLOW|IGNORE>
```
Full REELS_WATCH output (includes ViewPager visibility and bounds):
```bash
adb logcat -s REELS_WATCH
```

### VPN/bridge layer
```bash
adb logcat -s VPNModule
```
Key messages:
- `[INIT] VPNModule initialized`
- `permission check` — which permissions are granted
- `startMonitoring` / `stopMonitoring` — monitoring state changes

### Service lifecycle
```bash
adb logcat -s MyVpnService
```
Key messages:
- `[CREATE] MyVpnService onCreate`
- `[CREATE] Loaded savedBlockedApps`

### Browser blocking
```bash
adb logcat -s BROWSER_WATCH
```

### Settings persistence
```bash
adb logcat -s SettingsModule
```

### Accessibility gate
```bash
adb logcat -s ACC_PERM_GATE
```

---

## React Native / JavaScript Log Prefixes

JS logs use `console.log('[Prefix] message')` so you can grep the Metro output.

| Prefix | File | What it covers |
|--------|------|---------------|
| `[Home]` | `components/Home/home.js` | Stats loading, monitoring start, app detected events, navigation |
| `[Customize]` | `components/Customize/customize.js` | Settings load/save, toggle states, preview actions |
| `[PermissionsScreen]` | `components/Permissions/PermissionsScreen.js` | Permission request flow, screen advancement |
| `[WebView]` | `components/Browser/BrowserScreen.js` | Browser events (DEV builds only) |
| `[BlockerInterstitial]` | `components/BlockerInterstitial/BlockerInterstitial.tsx` | Overlay mount, countdown, button taps |
| `[App]` | `App.tsx` | Root navigation, modal state, event listener setup |
| *(no prefix)* | `components/VPNSwitch/VPNSwitch.js` | Search for `"DEBUG:"` or specific strings like `"startMonitoring"` |

### Filter JS logs in Metro terminal

```bash
# All Home screen logs
npx react-native start 2>&1 | grep "\[Home\]"

# All permission flow logs
npx react-native start 2>&1 | grep "\[PermissionsScreen\]"

# All customize screen logs
npx react-native start 2>&1 | grep "\[Customize\]"

# Everything with a prefix
npx react-native start 2>&1 | grep -E "\[(Home|Customize|PermissionsScreen|BlockerInterstitial|App|WebView)\]"
```

---

## Special Markers

| Marker | Location | How to filter |
|--------|----------|--------------|
| `POPUP_MARKER` | `AppUsageMonitor.java` | `adb logcat \| grep POPUP_MARKER` |
| `SCROLL_DECISION` | `ReelsInterventionService.java` | `adb logcat -s REELS_WATCH \| grep SCROLL_DECISION` |
| `[INIT]` | `VPNModule.java`, `SettingsModule.java` | `adb logcat \| grep "\[INIT\]"` |
| `[CREATE]` | `MyVpnService.java` | `adb logcat \| grep "\[CREATE\]"` |
| `DEBUG:` | `VPNSwitch.js` | In Metro output, search for `DEBUG:` |

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

### Watch Reels only
```bash
adb logcat -s REELS_WATCH
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

## Log Level Reference

| Level | Method | When used in this codebase |
|-------|--------|-----------------------------|
| Debug | `Log.d(TAG, msg)` | Normal operation flow (most logs) |
| Info | `Log.i(TAG, msg)` | Key state changes (overlay shown, monitoring toggled) |
| Warning | `Log.w(TAG, msg)` | Unexpected but recoverable states |
| Error | `Log.e(TAG, msg)` | Failures (permission denied, null views, exceptions) |

To show only errors across the whole app:
```bash
adb logcat *:E
```

To show a specific tag at a specific level:
```bash
adb logcat AppUsageMonitor:I *:S
```
(`*:S` silences everything else)

---

## Adding New Logs

Follow these conventions when adding logs:

**Java:**
```java
private static final String TAG = "YourClassName"; // top of class
Log.d(TAG, "[METHOD_NAME] what happened: " + variable);
Log.e(TAG, "[METHOD_NAME] error: " + e.getMessage());
```

**JavaScript/TypeScript:**
```js
const LOG_PREFIX = '[ComponentName]';
console.log(LOG_PREFIX, 'what happened', variable);
console.warn(LOG_PREFIX, 'unexpected state');
console.error(LOG_PREFIX, 'failure', error);
```

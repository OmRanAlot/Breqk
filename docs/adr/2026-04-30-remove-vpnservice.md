# ADR: Remove VpnService — Migrate BreqkVpnService to a Plain Foreground Service

**Date:** 2026-04-30  
**Status:** Accepted  
**Branch:** `dev`  
**Bugs closed:** B2, B4

## Context

`BreqkVpnService` (originally `MyVpnService`) extended `android.net.VpnService` purely for one reason: foreground service longevity. No VPN tunnel was ever established. The class never called `establish()`, never opened a `ParcelFileDescriptor`, and never routed any network traffic. The inheritance was a historical accident — early prototypes explored VPN-based DNS blocking before the project pivoted to an AccessibilityService-based approach.

This misuse caused three concrete problems:

1. **Play Store rejection risk.** Google reviews VPN-permission apps with extra scrutiny and manual review. Apps that declare `BIND_VPN_SERVICE` but do not function as a VPN routinely fail policy review or are removed post-publish.

2. **Misleading UX.** `VpnService` forces Android to display a system VPN consent dialog on first use, show a persistent VPN key icon (🔑) in the status bar, and mark the notification channel as VPN-associated. Users interpreted this as a network proxy and uninstalled the app.

3. **Unnecessary permission surface.** `android.permission.BIND_VPN_SERVICE` is a privileged permission. Holding it without need is a violation of least-privilege and a red flag in security audits.

A related latent crash (B4) existed in the same class: `onStartCommand()` passed `intent.getAction()` directly into a `switch` statement without guarding against null. The Android OS delivers a null intent when it restarts a `START_STICKY` service after process death, which would throw a `NullPointerException` on every OS-initiated restart.

## Decision

**Migrate `BreqkVpnService` from `android.net.VpnService` to `android.app.Service`.**

Specific changes:

| Location | Change |
|----------|--------|
| `service/BreqkVpnService.java` | `extends VpnService` → `extends Service` |
| `AndroidManifest.xml` | Removed `<uses-permission android:name="android.permission.BIND_VPN_SERVICE" />` |
| `AndroidManifest.xml` | Replaced VPN `<service>` declaration (with `android:permission="BIND_VPN_SERVICE"` and VPN `<intent-filter>`) with a plain foreground service using `android:foregroundServiceType="specialUse"` |
| `service/BreqkVpnService.java` | Notification channel ID `"BreqkVPN"` → `"BreqkMonitoring"`; notification text `"VPN Active"` → `"Breqk Active"` |
| `service/BreqkVpnService.java` | Action strings `START_VPN` / `STOP_VPN` → `START_MONITORING` / `STOP_MONITORING` |
| `service/BreqkVpnService.java` | Added null guards for `intent` and `action` in `onStartCommand()` before the switch (B4) |
| `bridge/VPNModule.java` | `requestVpnPermission()` stubbed as a no-op that immediately resolves `true`; `@ReactMethod` signature retained for JS backward compatibility |
| `bridge/VPNModule.java` | Removed `import android.net.VpnService` |
| `MainActivity.java` | Removed `VPN_REQUEST_CODE` constant and VPN-specific `onActivityResult()` branch |

The class is intentionally still named `BreqkVpnService` rather than renamed to `BreqkMonitorService`. Renaming would require updating `AndroidManifest.xml`, all intent constructors, and any persisted intents in AlarmManager. The name is internal-only, carries no user-visible meaning, and the cosmetic rename can be done in a future cleanup pass.

## Consequences

**Positive:**
- No VPN permission dialog on first launch.
- No VPN key icon in the status bar.
- Notification reads "Breqk Active" — accurate and unambiguous.
- `BIND_VPN_SERVICE` removed from the manifest — smaller permission surface, cleaner Play Store review.
- `onStartCommand()` now survives OS-initiated restarts without crashing (B4).
- Build passes: `assembleDebug` — 0 errors, 4 pre-existing deprecation warnings unrelated to this change.

**Neutral:**
- `foregroundServiceType="specialUse"` is still required for Android 14+ background monitoring and is already declared.
- The JS bridge method `requestVpnPermission()` remains as a no-op stub. Removing it would require a coordinated JS + native change; the stub is the minimal safe fix.

**No behavior change:**
- `AppUsageMonitor` polling loop, delay overlay, blocked-app sync, scroll budget, free break, and mode activation are all unaffected. Only the base class and manifest declaration changed.

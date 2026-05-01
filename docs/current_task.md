# Current Task

**Date Started:** 2026-04-30  
**Status:** `[x] Complete`  
**Date Completed:** 2026-04-30

---

## Task Description

B2 + B4: Migrate `BreqkVpnService` away from `android.net.VpnService` to a plain `android.app.Service`.

---

## Objective

Eliminate Play Store rejection risk from misuse of the VPN permission, and stop showing the system VPN dialog / key icon to users. B4 (null-action NPE guard) was included in the same changeset.

---

## Changes Made

| File | Change |
|------|--------|
| `service/BreqkVpnService.java` | `extends VpnService` → `extends Service`; notification channel `"BreqkMonitoring"`; text `"Breqk Active"`; actions renamed to `START_MONITORING`/`STOP_MONITORING`; null guards for intent + action; removed stray extra `}` (compile error) |
| `AndroidManifest.xml` | Removed `BIND_VPN_SERVICE` permission; replaced VPN `<service>` declaration with plain foreground service (`foregroundServiceType=specialUse`); removed VPN `<intent-filter>` |
| `bridge/VPNModule.java` | `requestVpnPermission()` stubbed as no-op; updated all comment references from `MyVpnService` → `BreqkVpnService` |
| `docs/TASKS.md` | Marked B2 and B4 complete with date |

---

## Verification

Run to confirm build passes:
```bash
cd android && ./gradlew assembleDebug
```

Manual checks after install:
- No VPN permission dialog on first launch
- No VPN key icon in status bar
- Notification reads "Breqk Active"
- App-open intercept and Reels detection still work

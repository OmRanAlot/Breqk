# Current Task

**Date Started:** 2026-04-18  
**Status:** `[x] In Progress`

---

## Task Description

Wire frontend per-app toggles to native intercept decisions

---

## Objective

Turning off **App Open Intercept** or **Reels Detection** for a specific app (e.g., Instagram) must disable that intercept for only that app, live at runtime within <500ms. Other apps (e.g., YouTube) must remain unaffected. Same applies symmetrically: re-enabling should restore intercept instantly.

---

## Root Causes Fixed

1. **Write debouncing (7s)**: `reels_detection` toggle was only sent to native after a 7s debounce — removed; now writes immediately.
2. **Bridge ordering race**: `VPNModule.startMonitoring()` was called before `setAppFeature` returned — now `await`-ed.
3. **Stale cached `blockedApps` set**: `AppUsageMonitor` checked a cached in-memory `Set` that could be stale — replaced with a direct live call to `BreqkPrefs.isFeatureEnabled()` on every tick.

---

## Progress

### Step 1: `SettingsModule.setAppFeature` returns a Promise
- [x] Added `Promise` parameter to `@ReactMethod` signature
- [x] Wraps `BreqkPrefs.setAppFeature()` in try/catch, resolves on success

### Step 2: `AppUsageMonitor` reads policy directly
- [x] Replaced `blockedApps.contains(foregroundApp)` with `BreqkPrefs.isFeatureEnabled(context, foregroundApp, FEATURE_APP_OPEN_INTERCEPT)`
- [x] Added `[INTERCEPT_DECISION]` log line per tick

### Step 3: `ReelsInterventionService` log line
- [x] Added `[INTERCEPT_DECISION]` log before the gate check

### Step 4: JS handler rewrite
- [x] `handleAppFeatureToggle` converted to `async`
- [x] Removed `saver.schedule(...)` debouncer for per-app toggles
- [x] `VPNModule.startMonitoring()` called only after `await setAppFeature` resolves
- [x] Removed duplicate immediate write that was `app_open_intercept`-only

---

## Files Changed

```
components/Customize/customize.js
android/app/src/main/java/com/breqk/SettingsModule.java
android/app/src/main/java/com/breqk/AppUsageMonitor.java
android/app/src/main/java/com/breqk/ReelsInterventionService.java
```

---

## Blockers / Notes

- `BreqkPrefs.isFeatureEnabled()` does a JSON parse every 500ms tick. Acceptable for now; revisit with a TTL cache if profiling shows it's a hotspot.
- `blockedApps` cached Set still maintained (used by legacy telemetry / status screens / intent payloads) — just no longer authoritative for intercept decisions.

---

## Related References

- **Plan**: `C:\Users\omran\.claude\plans\swift-chasing-cascade.md`
- **TASKS.md**: Update checkbox + date on completion

---

## Next Steps

1. Manual E2E test (see Verification below)
2. Check `adb logcat | findstr INTERCEPT_DECISION` for expected log lines
3. Update `docs/TASKS.md` on pass

---

## Verification

```
adb logcat | findstr INTERCEPT_DECISION
```

Expected:
- Toggle Instagram off → next AppUsageMonitor tick: `app_open_intercept=false` for Instagram
- Open YouTube → `app_open_intercept=true` → overlay fires
- Toggle Instagram Reels off → next scroll event: `reels_detection=false` → no popup

---

## Definition of Done

- [ ] Code complete
- [ ] TASKS.md updated
- [ ] No console warnings/errors
- [ ] No Bugs/Major issues

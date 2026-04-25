# Current Task

**Date Started:** 2026-04-22  
**Status:** `[x] Complete`  
**Date Completed:** 2026-04-23

---

## Task Description

Reorganize `android/app/src/main/java/com/breqk/` from a flat structure (~21 root-level files) into a proper sub-package hierarchy. Zero behavior change — pure structural refactor.

---

## Objective

Separate files by concern into sub-packages (`bridge/`, `monitor/`, `mode/`, `prefs/`, `service/`, `widget/`, `accessibility/`, `deviceadmin/`, `shortform/`). Split Instagram/YouTube view IDs into per-platform files. Add boilerplate-only scaffolding for TikTok/Facebook/Snapchat. Introduce a PlatformRegistry pattern.

---

## Plan

See full plan: `.claude/plan/codebase-reorganization.md`

---

## Progress

### Steps 1–4: Sub-package moves + renames
- [x] Created sub-packages: `accessibility/`, `bridge/`, `deviceadmin/`, `mode/`, `monitor/`, `prefs/`, `service/`, `widget/`
- [x] Moved all files to correct sub-packages (updated package declarations + imports)
- [x] `ContentFilterService` deleted (legacy dead code)
- [x] `WidgetPrefs` folded into `BreqkPrefs` static methods, deleted
- [x] `MyVpnService` → `service/BreqkVpnService` (renamed + moved, all call sites updated)
- [x] `BreqkWidgetProvider` → `widget/BreqkWidgetProvider` (moved, manifest updated)
- [x] All root originals deleted
- [x] Build passes: `./gradlew assembleDebug` → BUILD SUCCESSFUL

### Step 5: Rename `reels/` → `shortform/`
- [x] Update all package declarations `com.breqk.reels.*` → `com.breqk.shortform.*`
- [x] Rename `ReelsStateMachine` → `ShortFormStateMachine`
- [x] Move `AppEventRouter` + `ContentFilter` into `shortform/`
- [x] Promote `AppEventRouter.AppConfig` inner class to top-level `AppConfig`

### Step 6: Per-platform split (`InstagramViewIds`, `YouTubeViewIds`, `FilterHandler`, `PlatformRegistry`)
- [x] Split `ShortFormIds.java` → `InstagramViewIds.java` + `YouTubeViewIds.java`
- [x] Move geometry constants to `FullScreenCheck`
- [x] Create `FilterHandler` interface
- [x] Create `InstagramFilterHandler`, `YouTubeFilterHandler`
- [x] Create `Platform` enum + `PlatformRegistry`
- [x] Remove `MONITORED_PACKAGES` from `AppEventRouter`
- [x] Delete `ShortFormIds.java`, root `AppEventRouter.java`, root `ContentFilter.java`

### Step 7: Boilerplate stubs for TikTok / Facebook / Snapchat
- [x] TikTokViewIds, TikTokDetector, TikTokFilterHandler
- [x] FacebookViewIds, FacebookDetector, FacebookFilterHandler
- [x] SnapchatViewIds, SnapchatDetector, SnapchatFilterHandler

### Step 8: Rename `AppBlockerPackage` → `BreqkReactPackage`
- [x] Created `bridge/BreqkReactPackage.java`
- [x] Updated `MainApplication.kt` import + instantiation
- [x] Deleted root `AppBlockerPackage.java`

---

## Files Changed (Steps 1–4)

```
android/app/src/main/AndroidManifest.xml
android/app/src/main/java/com/breqk/MainApplication.kt
android/app/src/main/java/com/breqk/MainActivity.java
android/app/src/main/java/com/breqk/AppBlockerPackage.java
android/app/src/main/java/com/breqk/ReelsInterventionService.java
android/app/src/main/java/com/breqk/AppEventRouter.java
android/app/src/main/java/com/breqk/accessibility/AccessibilityPermissionActivity.java
android/app/src/main/java/com/breqk/bridge/VPNModule.java
android/app/src/main/java/com/breqk/bridge/SettingsModule.java
android/app/src/main/java/com/breqk/deviceadmin/BreqkDeviceAdminReceiver.java
android/app/src/main/java/com/breqk/mode/ModeManager.java
android/app/src/main/java/com/breqk/mode/ModeSchedulerReceiver.java
android/app/src/main/java/com/breqk/monitor/AppUsageMonitor.java
android/app/src/main/java/com/breqk/monitor/AppNameResolver.java
android/app/src/main/java/com/breqk/monitor/LaunchInterceptor.java
android/app/src/main/java/com/breqk/monitor/ScreenTimeTracker.java
android/app/src/main/java/com/breqk/monitor/ServiceHelper.java
android/app/src/main/java/com/breqk/prefs/BreqkPrefs.java
android/app/src/main/java/com/breqk/service/BreqkVpnService.java
android/app/src/main/java/com/breqk/widget/BreqkWidgetProvider.java
android/app/src/main/java/com/breqk/reels/budget/BudgetState.java
android/app/src/main/java/com/breqk/reels/budget/HomeFeedCounter.java
```

---

## Blockers / Notes

- `ReelsInterventionService` pinned at `com.breqk` root (R-03 risk: FQN persisted in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`)
- `AppBlockerPackage` rename deferred to Step 8 (needs `MainApplication.kt` update)

---

## Related References

- **Plan**: `.claude/plan/codebase-reorganization.md`
- **TASKS.md**: Update checkbox + date on completion

---

## Definition of Done

- [x] All steps complete
- [ ] `./gradlew assembleDebug` passes (run to verify)
- [ ] TASKS.md updated
- [x] No duplicate class files in root

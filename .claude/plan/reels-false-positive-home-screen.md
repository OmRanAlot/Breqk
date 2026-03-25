# Plan: Per-App YouTube Shorts / Instagram Reels Time Tracking

## Status of Previous Plan (False Positives)

The original false-positive fixes described in this file have been **fully implemented**:
- `isStillInReels()` — heartbeat self-validation (line ~1120)
- App-switch detection via non-target `TYPE_WINDOW_STATE_CHANGED` events (lines ~308-316)
- Heartbeat foreground check before accumulating budget (line ~1071)
- Accessibility service XML config already has no `packageNames` filter

No further work needed on false positives. This plan now covers **per-app time tracking**.

---

## Problem

YouTube Shorts and Instagram Reels time is tracked as a **single shared counter** (`scroll_time_used_ms`). The `is_in_reels_package` SharedPreferences key records which app is active, but that info is **never used to split time by app**.

This means:
1. Users cannot see how much time was spent on YouTube Shorts vs Instagram Reels
2. A single shared budget governs both platforms — exhausting it on one blocks both
3. No structured session logs exist (start/end/duration per platform)

## Task Type
- [x] Backend (Android native)
- [x] Frontend
- [x] Fullstack

## Current Architecture (How It Works Today)

### YouTube Shorts Detection (3-tier system in ReelsInterventionService.java)

| Tier | Method | Lines | Description |
|------|--------|-------|-------------|
| **Fast path** | Scroll event source ID match | ~409-421 | Checks scroll source against `YOUTUBE_SHORTS_VIEW_IDS` (7 IDs: `reel_player_page_container`, `shorts_container`, etc.) |
| **Tier 2** | Secondary signal IDs | ~796-848 | Presence of `reel_like_button`, `shorts_like_button`, `reel_comment_button` + absence of `youtube_controls_seekbar` |
| **Tier 3** | Diagnostic tree dump | ~851-854 | `YT_TREE_DUMP` logged every 10s when detection fails (for debugging YouTube app updates) |

### Budget Accumulation Flow

1. Shorts detected → `persistReelsState(true, "com.google.android.youtube")` writes to SharedPreferences
2. `startReelsHeartbeat("com.google.android.youtube")` begins (every 2000ms)
3. Each heartbeat tick calls `accumulateScrollBudget()` → adds 2000ms to **global** `scroll_time_used_ms`
4. `isStillInReels()` validates user is still watching before accumulating

### Relevant SharedPreferences Keys (BreqkPrefs.java)

| Key | Type | Purpose |
|-----|------|---------|
| `scroll_time_used_ms` | long | **Global** accumulated time (both platforms combined) |
| `scroll_window_start_time` | long | When current budget window started |
| `scroll_budget_exhausted_at` | long | When budget was marked exhausted |
| `scroll_allowance_minutes` | int | Allowed minutes per window (default 5) |
| `scroll_window_minutes` | int | Window duration (default 60) |
| `is_in_reels_package` | String | Which app is currently in Reels — **exists but unused for splitting** |

### Key Code Locations

| What | File | Lines |
|------|------|-------|
| YouTube Shorts view IDs | `ReelsInterventionService.java` | ~110-118 |
| YouTube secondary signal IDs | `ReelsInterventionService.java` | ~124-132 |
| Fast-path YouTube detection | `ReelsInterventionService.java` | ~409-421 |
| `isShortsLayout()` (3-tier detection) | `ReelsInterventionService.java` | ~769-858 |
| `isStillInReels()` (heartbeat validation) | `ReelsInterventionService.java` | ~1120-1147 |
| `startReelsHeartbeat()` | `ReelsInterventionService.java` | ~1059-1090 |
| `accumulateScrollBudget()` | `ReelsInterventionService.java` | ~1169-1228 |
| `persistReelsState()` | `ReelsInterventionService.java` | ~1034-1044 |
| `isScrollBudgetExhausted()` | `ReelsInterventionService.java` | ~533-560 |
| `isCurrentlyInReels()` (AppUsageMonitor) | `AppUsageMonitor.java` | ~1157-1179 |
| `getScrollBudgetStatus()` (JS bridge) | `AppUsageMonitor.java` | ~1117-1131 |
| SharedPreferences key constants | `BreqkPrefs.java` | ~47-55 |

---

## Technical Solution

### Phase 1: Per-App Time Counters (Observability) — LOW RISK

Add per-app counters alongside the existing global counter. The global budget continues to drive enforcement; per-app keys are informational.

#### Step 1: Add per-app SharedPreferences keys (BreqkPrefs.java)

New constants:
```java
// Per-app scroll time tracking (informational — global budget still drives enforcement)
public static final String KEY_SCROLL_TIME_USED_MS_YOUTUBE = "scroll_time_used_ms_com.google.android.youtube";
public static final String KEY_SCROLL_TIME_USED_MS_INSTAGRAM = "scroll_time_used_ms_com.instagram.android";
```

#### Step 2: Modify `accumulateScrollBudget()` (ReelsInterventionService.java ~1169)

After incrementing global `scroll_time_used_ms`, also increment the per-app counter using `currentReelsPackage`:

```java
// Existing: increment global counter
long newUsed = currentUsed + REELS_HEARTBEAT_INTERVAL_MS;
prefs.edit().putLong(BreqkPrefs.KEY_SCROLL_TIME_USED_MS, newUsed).apply();

// NEW: increment per-app counter
String perAppKey = "scroll_time_used_ms_" + currentReelsPackage;
long appUsed = prefs.getLong(perAppKey, 0L);
prefs.edit().putLong(perAppKey, appUsed + REELS_HEARTBEAT_INTERVAL_MS).apply();
Log.d(TAG, "[BUDGET] Per-app accumulate: pkg=" + currentReelsPackage
        + " appUsed=" + (appUsed + REELS_HEARTBEAT_INTERVAL_MS) + "ms");
```

#### Step 3: Reset per-app counters when window resets

In the window-reset logic (where `scroll_time_used_ms` is reset to 0), also reset both per-app keys:

```java
prefs.edit()
    .putLong(BreqkPrefs.KEY_SCROLL_TIME_USED_MS, 0L)
    .putLong(BreqkPrefs.KEY_SCROLL_TIME_USED_MS_YOUTUBE, 0L)
    .putLong(BreqkPrefs.KEY_SCROLL_TIME_USED_MS_INSTAGRAM, 0L)
    .putLong(BreqkPrefs.KEY_SCROLL_WINDOW_START_TIME, now)
    .apply();
```

#### Step 4: Expose per-app breakdown via JS bridge (AppUsageMonitor.java)

Modify `getScrollBudgetStatus()` to include per-app data:

```java
// Add to the returned WritableMap:
map.putDouble("youtubeUsedMinutes", prefs.getLong(BreqkPrefs.KEY_SCROLL_TIME_USED_MS_YOUTUBE, 0L) / 60000.0);
map.putDouble("instagramUsedMinutes", prefs.getLong(BreqkPrefs.KEY_SCROLL_TIME_USED_MS_INSTAGRAM, 0L) / 60000.0);
```

#### Step 5: Surface per-app breakdown in Home dashboard (home.js)

Display YouTube Shorts and Instagram Reels time separately in the stats section. Use the new `youtubeUsedMinutes` and `instagramUsedMinutes` fields from `getScrollBudgetStatus()`.

#### Step 6: Update logging (LOGGING.md)

Document new log lines:
- `[BUDGET] Per-app accumulate: pkg=... appUsed=...ms`
- New fields in `getScrollBudgetStatus()` response

### Phase 2: Independent Per-App Budgets (Optional — MEDIUM RISK)

Replace the single budget with per-app variants. This is a larger change.

#### Step 1: Add per-app budget config keys (BreqkPrefs.java)

```java
public static final String KEY_SCROLL_ALLOWANCE_YOUTUBE = "scroll_allowance_minutes_youtube";
public static final String KEY_SCROLL_ALLOWANCE_INSTAGRAM = "scroll_allowance_minutes_instagram";
public static final String KEY_SCROLL_BUDGET_EXHAUSTED_YOUTUBE = "scroll_budget_exhausted_at_youtube";
public static final String KEY_SCROLL_BUDGET_EXHAUSTED_INSTAGRAM = "scroll_budget_exhausted_at_instagram";
```

#### Step 2: Modify `isScrollBudgetExhausted()` (ReelsInterventionService.java ~533)

Check the **current app's** budget instead of the global one:
```java
private boolean isScrollBudgetExhausted(String packageName) {
    String exhaustedKey = packageName.equals(PKG_YOUTUBE)
        ? BreqkPrefs.KEY_SCROLL_BUDGET_EXHAUSTED_YOUTUBE
        : BreqkPrefs.KEY_SCROLL_BUDGET_EXHAUSTED_INSTAGRAM;
    // ... check per-app exhaustion
}
```

#### Step 3: Add per-app budget config in Customize screen (customize.js)

Separate sliders/pickers for YouTube Shorts allowance and Instagram Reels allowance.

#### Step 4: Update SettingsModule to persist per-app budgets

New bridge methods: `saveScrollBudgetYouTube()`, `saveScrollBudgetInstagram()`.

### Phase 3: Session Logging (Optional — LOW RISK)

#### Step 1: Log structured session events

On heartbeat start and `resetReelsState()`, write structured log entries:

```java
Log.i(TAG, "[SESSION] START pkg=" + packageName + " timestamp=" + System.currentTimeMillis());
Log.i(TAG, "[SESSION] END pkg=" + currentReelsPackage + " duration=" + sessionDurationMs + "ms");
```

#### Step 2: Persist session history (optional)

Store recent sessions in SharedPreferences as a JSON array for the JS layer to display session history.

---

## Key Files

| File | Phase | Operation | Description |
|------|-------|-----------|-------------|
| `android/.../BreqkPrefs.java` | 1 | Modify | Add per-app SharedPreferences key constants |
| `android/.../ReelsInterventionService.java` | 1 | Modify | Per-app counter in `accumulateScrollBudget()`, reset in window reset |
| `android/.../AppUsageMonitor.java` | 1 | Modify | Expose per-app breakdown in `getScrollBudgetStatus()` |
| `components/Home/home.js` | 1 | Modify | Display per-app time breakdown |
| `LOGGING.md` | 1 | Modify | Document new log lines |
| `android/.../ReelsInterventionService.java` | 2 | Modify | Per-app budget enforcement in `isScrollBudgetExhausted()` |
| `android/.../SettingsModule.java` | 2 | Modify | Per-app budget bridge methods |
| `components/Customize/customize.js` | 2 | Modify | Per-app budget config UI |

## Risks and Mitigation

| Risk | Phase | Mitigation |
|------|-------|------------|
| YouTube changing view IDs breaks Shorts detection | All | 3-tier detection with `YT_TREE_DUMP` diagnostics already in place; detection test mode could be added |
| Per-app counters drift from global counter | 1 | Per-app are informational only; global drives enforcement. Drift is harmless. |
| Additional SharedPreferences writes (every 2s heartbeat) | 1 | One extra `putLong` per tick — negligible overhead |
| Per-app budgets add UI/config complexity | 2 | Phase 2 is optional; Phase 1 provides value without config changes |
| `AppUsageMonitor.cachedInReels` appears to be dead code | — | Investigate before Phase 2; may be reserved for future use or safe to remove |

## Known Fragility: YouTube View IDs

YouTube frequently changes view IDs across app updates (noted in code comments at lines ~99-103). Current IDs:

**Primary (7):** `reel_player_page_container`, `shorts_container`, `shorts_video_player`, `reel_recycler`, `shorts_shelf_container`, `shorts_surface_view`, `shorts_player_container`

**Secondary signals (4):** `reel_like_button`, `shorts_like_button`, `reel_comment_button`, `reel_time_bar`

**Negative signal:** `youtube_controls_seekbar` (absence = likely Shorts)

When detection breaks, `YT_TREE_DUMP` fires every 10s in logcat. Filter: `adb logcat -s REELS_WATCH | grep YT_TREE_DUMP`.

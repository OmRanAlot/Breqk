# Plan: YouTube Shorts Bugs & Budget-Zero Overlay Fix

## Task Type
- [x] Backend (Android native)
- [ ] Frontend (React Native)

## Bugs Identified

### BUG 1: Dual Budget Accumulation (CRITICAL — budget depletes ~3x faster than expected)
**Where:** `ReelsInterventionService.accumulateScrollBudget()` (line 1044) AND `AppUsageMonitor` polling loop (line 267-288)
**Problem:** BOTH services write to `scroll_time_used_ms` in SharedPreferences independently:
- ReelsInterventionService heartbeat adds 2000ms every 2s
- AppUsageMonitor polling adds 1000ms every 1s
- Combined: ~3000ms accumulated per 2s of actual viewing → budget depletes ~1.5x too fast
**Evidence:** ReelsInterventionService comment (line 219-222) says "Budget tracking is done HERE (not in AppUsageMonitor)" but AppUsageMonitor still accumulates at line 272.
**Fix:** Remove budget accumulation from AppUsageMonitor. Keep it only in ReelsInterventionService (the reliable always-running service). AppUsageMonitor should only READ budget state, not WRITE to `scroll_time_used_ms`.

### BUG 2: No Immediate Overlay When Budget Hits Zero Mid-Viewing (HIGH)
**Where:** `ReelsInterventionService.accumulateScrollBudget()` (line 1081-1084) and `handleReelsScrollEvent()` (line 447)
**Problem:** When the heartbeat accumulation exhausts the budget, it only sets `scroll_budget_exhausted_at` in SharedPreferences. The intervention overlay only triggers on the NEXT scroll event (line 439-453). If the user is passively watching a Reel/Short without scrolling, the overlay never appears until they swipe.
**Fix:** After marking budget as exhausted in `accumulateScrollBudget()`, immediately call `triggerIntervention()` on the main handler.

### BUG 3: AppUsageMonitor Budget Overlay Requires App to be in Blocked List (MEDIUM)
**Where:** `AppUsageMonitor` line 293: `if (isBlocked && !isOverlayActive)`
**Problem:** The budget-exhausted overlay from AppUsageMonitor (line 346) only fires if the foreground app is in the blocked apps list. If Instagram/YouTube aren't explicitly blocked by the user, the AppUsageMonitor budget overlay never shows — only the ReelsInterventionService overlay (on scroll) would fire.
**Fix:** Since we're moving budget enforcement fully to ReelsInterventionService (Bug 1 fix), this becomes moot. Remove the budget-exhausted overlay logic from AppUsageMonitor entirely — ReelsInterventionService handles it.

### BUG 4: YouTube Shorts Fast-Path May Miss Scroll Events from Child Views (LOW-MEDIUM)
**Where:** `handleReelsScrollEvent()` fast path (line 370-382)
**Problem:** The fast path checks if `event.getSource().getViewIdResourceName()` matches `YOUTUBE_SHORTS_VIEW_IDS`. But YouTube scroll events can originate from child views (video player, content container, RecyclerView items) whose IDs aren't in the array. These fall through to the slow path (full tree traversal), which is correct but expensive.
**Impact:** Performance only — detection still works via slow path + Tier 2. No functional bug.
**Fix:** No code change needed for correctness. The three-tier system handles this. (Optional: could add RecyclerView IDs to fast path for performance.)

### BUG 5: `interventionShowing` Flag Not Reset When Budget Resets (MEDIUM)
**Where:** `ReelsInterventionService` — `interventionShowing` field (line 178)
**Problem:** If the budget window expires while the user is still in Reels (e.g., they kept watching after the overlay was dismissed), `interventionShowing` stays `true` from the previous cycle. When the new window's budget exhausts, the check at line 447 (`budgetExhausted && !interventionShowing`) fails and no overlay shows.
**Fix:** Reset `interventionShowing = false` in `accumulateScrollBudget()` when a window reset occurs (line 1058-1064).

### BUG 6: Overlay Not Showing When Budget is Pre-Exhausted (allowance=0) on Entry (MEDIUM)
**Where:** `ReelsInterventionService.handleReelsScrollEvent()` lines 416-421
**Problem:** When the user enters Reels with allowance=0 (or pre-exhausted budget), the first scroll starts the heartbeat and sets `wasInReelsLayout=true`, but `isScrollBudgetExhausted()` only reads SharedPreferences where `exhausted_at` may be 0 (no heartbeat tick has accumulated yet). The overlay fires only after the first heartbeat tick (2s delay). For allowance=0, the user sees 2s of content before the overlay.
**Fix:** After entering Reels (line 418-420), immediately check budget and trigger overlay if already exhausted or if allowance=0.

## Implementation Steps

### Step 1: Remove Budget Accumulation from AppUsageMonitor
**File:** `AppUsageMonitor.java`
- Remove lines 267-288 (the `scrollBudgetEnabled && isBlocked && inReelsNow` accumulation block)
- Remove the `scrollPersistCounter` logic (no longer needed here)
- Keep `loadScrollBudgetFromPrefs()` and `isScrollBudgetExhausted()` as READ-ONLY
- Keep `getScrollBudgetStatus()` and `getTimeUntilNextScroll()` (read-only APIs for JS bridge)
- Remove `showBudgetExhaustedOverlay()` and `startBudgetCountdown()` and `cancelBudgetCountdown()` methods — budget overlay is now solely owned by ReelsInterventionService
- Remove the budget-exhausted overlay check at line 346-354
- Expected: Budget only accumulates in ReelsInterventionService heartbeat

### Step 2: Add Immediate Overlay Trigger on Budget Exhaustion in ReelsInterventionService
**File:** `ReelsInterventionService.java`
- In `accumulateScrollBudget()`, after line 1083 (`budgetExhaustedAt = now`), add:
  ```java
  if (!interventionShowing) {
      interventionShowing = true;
      Log.i(TAG, "[BUDGET] Budget just exhausted via heartbeat — triggering immediate intervention");
      triggerIntervention(packageName); // need to pass package from heartbeat
  }
  ```
- Modify `startReelsHeartbeat()` to store the current package name as a field so `accumulateScrollBudget()` can access it
- This ensures overlay appears immediately when budget hits zero, even without a scroll

### Step 3: Reset `interventionShowing` on Window Reset
**File:** `ReelsInterventionService.java`
- In `accumulateScrollBudget()`, inside the window-expired block (line 1058-1064), add:
  ```java
  interventionShowing = false;
  dismissIntervention(); // remove any lingering overlay from previous window
  ```

### Step 4: Immediate Budget Check on Reels Entry
**File:** `ReelsInterventionService.java`
- After `startReelsHeartbeat(packageName)` at line 419, add an immediate budget check:
  ```java
  // Check if budget is already exhausted when entering Reels
  if (isScrollBudgetExhausted(System.currentTimeMillis()) && !interventionShowing) {
      interventionShowing = true;
      triggerIntervention(packageName);
  }
  ```
- Also run one immediate `accumulateScrollBudget()` tick (for allowance=0 case)

### Step 5: Sync AppUsageMonitor's In-Memory State from SharedPreferences
**File:** `AppUsageMonitor.java`
- In the polling loop, after the window-reset check (line 252-258), reload budget state from SharedPreferences:
  ```java
  // Sync budget state written by ReelsInterventionService
  scrollTimeUsedMs = prefs.getLong("scroll_time_used_ms", scrollTimeUsedMs);
  budgetExhaustedAt = prefs.getLong("scroll_budget_exhausted_at", budgetExhaustedAt);
  ```
- This keeps the JS bridge APIs (`getScrollBudgetStatus()`) accurate

## Key Files
| File | Operation | Description |
|------|-----------|-------------|
| `ReelsInterventionService.java` | Modify | Add immediate overlay on exhaustion, reset on window rollover, check on entry |
| `AppUsageMonitor.java` | Modify | Remove budget accumulation/overlay, keep as read-only for JS bridge |

## Risks and Mitigation
| Risk | Mitigation |
|------|------------|
| Removing AppUsageMonitor overlay breaks blocked-app budget enforcement | ReelsInterventionService overlay now handles all budget enforcement; it uses TYPE_ACCESSIBILITY_OVERLAY which doesn't need SYSTEM_ALERT_WINDOW |
| Race condition between heartbeat writes and polling reads | SharedPreferences is thread-safe for reads; only one writer (ReelsInterventionService) after fix |
| YouTube view ID changes break detection | Three-tier detection (Tier 1/2/3) already handles this; YT_TREE_DUMP diagnostic aids recovery |

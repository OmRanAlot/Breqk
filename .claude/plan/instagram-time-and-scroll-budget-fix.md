# Plan: Fix Instagram Time Inflation + Scroll Budget Not Updating

## Bug 1: Instagram time in "Today's Top Apps" shows ~2h30m instead of ~40m

### Root Cause
`ScreenTimeTracker.getPerAppStats()` (and `getComprehensiveStats()`) use `queryUsageStats(INTERVAL_DAILY)`,
which returns **daily buckets**. `getTotalTimeInForeground()` returns the total for the **entire bucket
period**, not just the portion overlapping the query range. When Android returns yesterday's bucket alongside
today's, and the `getLastTimeUsed()` filter doesn't catch it (e.g., app used near midnight), the code sums
both days' usage → inflated time.

### Fix: Use UsageEvents for accurate per-app time

Replace the `queryUsageStats` approach in `getPerAppStats()` (and `getComprehensiveStats()`) with
`UsageEvents` (MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND events) to compute exact foreground time
within the precise startTime→endTime range. This is what Android's Digital Wellbeing uses internally.

### Implementation Steps

**Step 1**: Add a new private method `getPerAppForegroundTimeFromEvents(long startTime, long endTime)` in `ScreenTimeTracker.java`
- Query `usageStatsManager.queryEvents(startTime, endTime)`
- Track per-app foreground sessions using MOVE_TO_FOREGROUND (type 1) and MOVE_TO_BACKGROUND (type 2) events
- For each app: when MOVE_TO_FOREGROUND, record the timestamp; when MOVE_TO_BACKGROUND, add (bg_time - fg_time) to that app's total
- Handle edge case: if app is still in foreground at `endTime`, use `endTime` as the end of the session
- Return `Map<String, Long>` of packageName → foreground ms

**Step 2**: Update `getPerAppStats()` to use the events-based method instead of `queryUsageStats`
- Replace the `queryUsageStats` + `usageMap` grouping logic with `getPerAppForegroundTimeFromEvents()`
- Keep the `isUserFacingApp()` filter, sorting, and limit logic unchanged

**Step 3**: Update `getComprehensiveStats()` to use events-based total
- Sum all per-app foreground times from `getPerAppForegroundTimeFromEvents()` for the total
- OR keep using `queryUsageStats` for total but apply the same events-based calculation

### Key Files
| File | Operation | Description |
|------|-----------|-------------|
| `android/.../ScreenTimeTracker.java` | Modify | Add events-based foreground time calculation, update getPerAppStats() and getComprehensiveStats() |

---

## Bug 2: Scroll budget not updating with time spent scrolling on Instagram

### Root Cause
`AppUsageMonitor.java:265` accumulation condition:
```java
if (scrollBudgetEnabled && isBlocked && isAllowed && inReelsNow)
```
Requires `isAllowed` (user tapped "Continue" on overlay). If user reaches Reels without triggering
the overlay (monitoring off, cooldown active, or app already open), budget never accumulates.
Additionally, switching away clears `allowedThisSession` (line 378).

### Fix: Remove `isAllowed` from budget accumulation condition

The scroll budget should track Reels time whenever the user is in Reels on a blocked app,
regardless of whether they went through the overlay.

### Implementation Steps

**Step 1**: Change accumulation condition in `AppUsageMonitor.java` (line 265)
- From: `if (scrollBudgetEnabled && isBlocked && isAllowed && inReelsNow)`
- To: `if (scrollBudgetEnabled && isBlocked && inReelsNow)`
- Update the comment above (lines 261-263) to reflect the change

**Step 2**: Verify budget exhaustion still triggers overlay correctly
- The overlay trigger at line 344: `if (scrollBudgetEnabled && isScrollBudgetExhausted() && inReelsNow)`
  does NOT check `isAllowed`, so it will still fire when budget is exhausted — no change needed

**Step 3**: Test edge cases
- User opens Instagram → goes to Reels → budget should start accumulating immediately
- User leaves Reels → budget stops accumulating
- Budget exhausts → overlay fires → user taps "Return to Feed" → re-entering Reels re-triggers overlay

### Key Files
| File | Operation | Description |
|------|-----------|-------------|
| `android/.../AppUsageMonitor.java:265` | Modify | Remove `isAllowed` from scroll budget accumulation condition |

---

## Risks and Mitigation

| Risk | Mitigation |
|------|------------|
| UsageEvents may not have MOVE_TO_FOREGROUND/BACKGROUND on very old devices | Keep API 22+ guard; these events exist since API 21 |
| Events query may be slow for full-day range | Only query midnight→now (same range as current code) |
| Removing `isAllowed` from budget condition may accumulate time when overlay is showing | The overlay covers the app — user can't scroll while overlay is visible, so this is a non-issue |

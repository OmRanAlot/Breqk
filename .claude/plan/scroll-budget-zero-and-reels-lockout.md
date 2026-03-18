# Implementation Plan: Scroll Budget Zero + Reels Time-Based Lockout

## Summary

Three changes:
1. Allow scroll budget allowance to be set to **0 minutes** (popup always shows immediately)
2. ReelsInterventionService popup should only fire when scroll budget time is exhausted (not on scroll count threshold)
3. When budget is exhausted, the Reels overlay says "Time is up" and only shows the "Lock In" button (no "Return to Feed")

## Task Type
- [x] Backend (Native — ReelsInterventionService, AppUsageMonitor, VPNModule, SettingsModule)
- [x] Frontend (customize.js stepper min value)

---

## Implementation Steps

### Step 1: Allow scroll allowance = 0 in AppUsageMonitor

**File:** `android/app/src/main/java/com/breqk/AppUsageMonitor.java`
**Lines:** ~962

Change `setScrollBudget()`:
```java
// BEFORE:
this.scrollAllowanceMinutes = Math.max(1, allowanceMin);
// AFTER:
this.scrollAllowanceMinutes = Math.max(0, allowanceMin);
```

When allowance is 0, `allowanceMs = 0` and `scrollTimeUsedMs >= 0` is always true, so budget is immediately exhausted on first tick — popup always shows.

Also update the exhaustion check at line ~265 to handle `allowanceMs == 0`:
- When allowance is 0 and the user is in a blocked app, immediately exhaust the budget (the existing `scrollTimeUsedMs >= allowanceMs` comparison already handles this since `0 >= 0` is true).

### Step 2: Allow scroll allowance = 0 in customize.js stepper

**File:** `components/Customize/customize.js`
**Line:** ~181

Change `adjustAllowance`:
```javascript
// BEFORE:
const next = Math.max(1, Math.min(30, scrollAllowance + delta));
// AFTER:
const next = Math.max(0, Math.min(30, scrollAllowance + delta));
```

### Step 3: Integrate scroll budget into ReelsInterventionService

**File:** `android/app/src/main/java/com/breqk/ReelsInterventionService.java`

Currently the service triggers the intervention popup based on a **scroll count threshold**. The new behavior:
- The popup should ONLY show when the **scroll budget time is exhausted** (i.e., `canScroll == false` / `budgetExhaustedAt > 0`)
- Read budget status from SharedPreferences (`breqk_prefs`):
  - `scroll_budget_exhausted_at` (long) — if > 0, budget is exhausted
  - `scroll_window_minutes` (int) + `scroll_time_window_start` — to check if window has expired and budget should reset

**Changes to `handleReelsScrollEvent()`:**
- Remove scroll count threshold logic (`reelsScrollCount >= getScrollThreshold()`)
- Instead, on each confirmed Reels scroll event (after debounce), check SharedPreferences:
  ```java
  SharedPreferences prefs = getSharedPreferences("breqk_prefs", Context.MODE_PRIVATE);
  long exhaustedAt = prefs.getLong("scroll_budget_exhausted_at", 0);
  boolean budgetExhausted = exhaustedAt > 0;
  ```
  - Also check if the window has expired (budget should be considered available again):
    ```java
    long windowStart = prefs.getLong("scroll_time_window_start", 0);
    int windowMin = prefs.getInt("scroll_window_minutes", 60);
    if (exhaustedAt > 0 && windowStart > 0 && (now - windowStart) >= windowMin * 60 * 1000L) {
        budgetExhausted = false; // window has rolled over
    }
    ```
  - If `budgetExhausted` → trigger intervention
  - If not exhausted → allow scrolling (no popup)

- Remove `reelsScrollCount`, `getScrollThreshold()`, and the scroll counting logic
- Keep the debounce (still needed to avoid rapid-fire event processing)
- Keep `wasInReelsLayout` tracking (still needed for state management)

### Step 4: Update Reels intervention overlay for "Time is up" state

**File:** `android/app/src/main/java/com/breqk/ReelsInterventionService.java`
**Method:** `triggerIntervention()`

Change the overlay content when budget is exhausted:
- Title: **"Time is up!"** (instead of "Reels Detected" / "Shorts Detected")
- Only show **"Lock In"** button → fires `GLOBAL_ACTION_HOME` (exits to home screen)
- **Hide** the "Return to Feed" / "Force Close" button (set `View.GONE`)

```java
private void triggerIntervention(final String pkg) {
    mainHandler.post(() -> {
        // ... existing WindowManager setup ...

        interventionView = LayoutInflater.from(this)
                .inflate(R.layout.overlay_reels_intervention, null);

        // Set title to "Time is up!"
        TextView titleView = interventionView.findViewById(R.id.intervention_title);
        titleView.setText("Time is up!");

        // "Lock In" button → go home (this is the ONLY button shown)
        Button btnLockIn = interventionView.findViewById(R.id.btn_lock_in);
        btnLockIn.setText("Lock In");
        btnLockIn.setOnClickListener(v -> {
            dismissIntervention();
            resetReelsState();
            performGlobalAction(GLOBAL_ACTION_HOME);
        });

        // Hide the second button entirely
        Button btnTakeBreak = interventionView.findViewById(R.id.btn_take_break);
        btnTakeBreak.setVisibility(View.GONE);

        windowManager.addView(interventionView, params);
    });
}
```

### Step 5: Update overlay_reels_intervention.xml comments

**File:** `android/app/src/main/res/layout/overlay_reels_intervention.xml`

Update XML comments to reflect new behavior (title can be "Time is up!", btn_take_break may be hidden).

### Step 6: Ensure SharedPreferences keys are synced

**File:** `android/app/src/main/java/com/breqk/AppUsageMonitor.java`

Verify that `persistScrollBudgetStatus()` writes:
- `scroll_budget_exhausted_at` → already exists (line ~948)
- `scroll_time_window_start` → verify this key name matches what's used in `windowStartTime` persistence

Check that `windowStartTime` is persisted under a key that ReelsInterventionService can read. Looking at the persist method, ensure the key names match.

### Step 7: Update LOGGING.md

Add log entries for:
- `REELS_WATCH` tag: `[BUDGET] Budget exhausted, showing intervention` / `[BUDGET] Budget OK, allowing scroll`
- Remove/update references to scroll count threshold in Reels logging

---

## Key Files

| File | Operation | Description |
|------|-----------|-------------|
| `android/.../AppUsageMonitor.java:962` | Modify | Allow `scrollAllowanceMinutes = 0` (change `Math.max(1,...)` → `Math.max(0,...)`) |
| `android/.../ReelsInterventionService.java` | Major modify | Replace scroll-count-threshold with budget-exhaustion check from SharedPreferences. Update overlay to show "Time is up!" with only "Lock In" button. |
| `android/.../res/layout/overlay_reels_intervention.xml` | Minor | Update comments |
| `components/Customize/customize.js:181` | Modify | Allow allowance stepper min = 0 |
| `LOGGING.md` | Modify | Update Reels intervention log dictionary |

---

## Risks and Mitigation

| Risk | Mitigation |
|------|------------|
| SharedPreferences read latency on every scroll event in ReelsInterventionService | SharedPreferences reads are fast (cached in memory by Android). The debounce (600ms) means this only happens ~1-2 times per second max. |
| Budget state stale if AppUsageMonitor hasn't persisted recently (10s interval) | Acceptable — a few seconds of lag between budget exhaustion and Reels popup is fine. Could reduce persist interval if needed. |
| Removing scroll threshold breaks existing users who rely on it | Scroll threshold is still available via SharedPreferences but now only used if budget system is disabled. The budget system is the primary mechanism. |
| allowance=0 causes immediate exhaustion on every window reset | This is the intended behavior — "always show popup". Window resets still happen so the user gets a fresh exhaustion trigger. |

---

## Testing

1. Set scroll allowance to 0 in Customize → open Instagram Reels → popup should show immediately
2. Set scroll allowance to 1 min → scroll for 1 min → popup should show
3. Popup should show "Time is up!" title with only "Lock In" button
4. "Lock In" should exit to Android home screen
5. After window resets, scrolling should work again until budget is re-exhausted
6. Log filter: `adb logcat -s REELS_WATCH | grep BUDGET`

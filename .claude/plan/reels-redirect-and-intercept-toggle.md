# Plan: Reels Overlay "Return to Feed" Button + Fix App Open Intercept Toggle

## Task Type
- [x] Backend (Android native)
- [x] Frontend (React Native JS — toggle fix)

## Problem Summary

### Issue 1: Add "Return to Feed" button to Reels intervention overlay
The overlay (`overlay_reels_intervention.xml`) shown by `ReelsInterventionService.triggerIntervention()` when the scroll budget is exhausted currently has:
- "Lock In" button → fires `GLOBAL_ACTION_HOME` (exits to Android home screen)
- "Force Close Instagram" button → **hidden** (`View.GONE`)

**Goal**: Repurpose the hidden second button (or add a new one) to navigate the user back to Instagram/YouTube's home feed (away from Reels/Shorts) and dismiss the overlay — instead of going to the Android home screen.

### Issue 2: "App Open Intercept" toggle doesn't actually stop overlays
The toggle in `customize.js` calls `VPNModule.stopMonitoring()` which:
1. Stops VPNModule's own `appMonitor` (which is **idle anyway** — commented out)
2. Sends `STOP_VPN` intent to `MyVpnService` → stops its monitor + stops the service

**But** `Home.js` **unconditionally** calls `startMonitoring()` on every mount (line 178) without checking the `monitoring_enabled` SharedPreference. So every time the user navigates to Home (or the app comes to foreground), monitoring restarts — even if the toggle is off.

---

## Implementation Steps

### Part A: "Return to Feed" Button on Reels Overlay

#### Step 1: Update `overlay_reels_intervention.xml`
- Make `btn_take_break` visible (remove `View.GONE` assumption from comments)
- Change its text to "Return to Feed" (text will be set in code anyway)
- Keep its outline pill styling

#### Step 2: Update `ReelsInterventionService.triggerIntervention()`
In the `triggerIntervention()` method (line 669):

1. Make `btn_take_break` **visible** (`View.VISIBLE`)
2. Set its text to "Return to Feed"
3. Set its `onClickListener` to:
   a. Dismiss the overlay (`dismissIntervention()`)
   b. **Do NOT call `resetReelsState()`** — the budget is still exhausted. We only dismiss the overlay UI, but the budget exhaustion state in SharedPreferences (`scroll_budget_exhausted_at`) remains set. This means:
      - `ReelsInterventionService.isScrollBudgetExhausted()` will still return `true`
      - If the user navigates back to Reels, the very next scroll will re-trigger the intervention overlay
      - The budget only resets when the time window rolls over (handled by `AppUsageMonitor`)
   c. Use `performGlobalAction(GLOBAL_ACTION_BACK)` to navigate back from Reels
      - Instagram's Reels tab: pressing Back exits the full-screen Reels viewer and returns to the main feed
      - YouTube Shorts: pressing Back exits Shorts and returns to the main YouTube feed
   d. Reset only the overlay-related flags (`interventionShowing = false`, `wasInReelsLayout = false`) so the service can re-detect if the user enters Reels again — but do NOT clear `persistReelsState` or the budget exhaustion

**Key difference from "Lock In"**: "Lock In" calls `resetReelsState()` which clears the in-memory tracking (fine because user leaves the app entirely). "Return to Feed" keeps the budget exhausted so re-entering Reels immediately re-triggers the overlay.

**Recommended approach**: Use `GLOBAL_ACTION_BACK` — this is the simplest and most reliable way to exit the Reels/Shorts viewer and return to the app's home tab. The full-screen Reels viewer is one level deep; Back exits it.

#### Step 3: Rename "Lock In" button
- Keep "Lock In" → fires `GLOBAL_ACTION_HOME` (exit to Android home — strongest action)
- The new "Return to Feed" is the gentler option (stays in the app but leaves Reels)

**Button order** (top to bottom in the bottom sheet):
1. "Return to Feed" (primary white pill) — navigates back within the app
2. "Lock In" (outline/secondary) — exits to Android home screen

Swap visual styling: "Return to Feed" should be the primary (white fill) and "Lock In" should be the secondary (outline).

#### Budget enforcement guarantee
The scroll budget exhaustion state lives in SharedPreferences (`scroll_budget_exhausted_at > 0`). Neither button clears this value — only `AppUsageMonitor.resetScrollWindow()` does, which fires when the time window expires. This means:
- **"Return to Feed"**: user goes to Instagram/YouTube home feed. If they navigate back to Reels, the overlay fires again on the very next scroll.
- **"Lock In"**: user goes to Android home screen. If they reopen the app and go to Reels, same thing — overlay fires again.
- **Budget resets only when**: `(now - windowStartTime) >= scrollWindowMinutes * 60 * 1000` — i.e., the configured window rolls over.

### Part B: Fix "App Open Intercept" Toggle

#### Step 4: Fix `Home.js` init to respect `monitoring_enabled`
In `Home.js` `useEffect` init (line 162), before calling `startMonitoring()`:
- Read `monitoring_enabled` from `SettingsModule.getMonitoringEnabled()`
- Only call `startMonitoring(appsSet)` if `monitoring_enabled !== false`
- Set `isMonitoring` state accordingly

```js
// Inside init()
const monitoringEnabled = await new Promise((resolve) => {
    SettingsModule.getMonitoringEnabled((v) => resolve(v));
});
if (monitoringEnabled !== false) {
    await startMonitoring(appsSet);
} else {
    console.log('[Home] monitoring disabled by user — skipping start');
    setIsMonitoring(false);
}
```

#### Step 5: Fix `VPNSwitch.js` to respect `monitoring_enabled`
Check `VPNSwitch.js` — if it also unconditionally starts monitoring, add the same guard.

#### Step 6: Fix foreground-resume restart to respect toggle
In `Home.js`, the `restartMonitoring` / `debouncedRestart` logic fires when the app returns to foreground. Add a guard:
- Check `monitoring_enabled` from SharedPreferences before restarting
- Only restart if the toggle is on

---

## Key Files

| File | Operation | Description |
|------|-----------|-------------|
| `android/.../res/layout/overlay_reels_intervention.xml` | Modify | Make btn_take_break visible, update text |
| `android/.../ReelsInterventionService.java:669-718` | Modify | Wire btn_take_break to GLOBAL_ACTION_BACK, swap button styling |
| `components/Home/home.js:162-188` | Modify | Check monitoring_enabled before starting monitoring |
| `components/Home/home.js:143-158` | Modify | Check monitoring_enabled before restarting on foreground |
| `components/VPNSwitch/VPNSwitch.js` | Modify (if needed) | Same monitoring_enabled guard |

## Risks and Mitigation

| Risk | Mitigation |
|------|------------|
| `GLOBAL_ACTION_BACK` might not exit Reels in all Instagram versions | Log the action and test; fallback to launching Intent with `FLAG_ACTIVITY_CLEAR_TOP` if needed |
| Race condition: toggle saved but service not yet stopped when Home re-mounts | The `monitoring_enabled` check in init will prevent re-start; service stop is async but SharedPreferences read is synchronous on native side |
| YouTube Shorts might need a different navigation approach than Instagram Reels | Test both; BACK should work for both since they're both single-level deep viewers |
| User taps "Return to Feed" then immediately navigates back to Reels to bypass budget | Budget exhaustion state (`scroll_budget_exhausted_at`) is NOT cleared by "Return to Feed" — overlay re-fires on the very next scroll in Reels. No bypass possible. |
| `interventionShowing` flag reset too early allows double overlay | Set `interventionShowing = false` only after `dismissIntervention()` completes (already handled inside `dismissIntervention()`) |

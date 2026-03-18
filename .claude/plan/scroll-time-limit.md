# Implementation Plan: Scroll Time Limit

## Summary

Add a scroll time budget system: users get X minutes of scrolling per Y-minute window (default: 5min per 60min). When the budget is exhausted, the overlay locks them out with a live countdown until the next window opens. The Home screen gets controls to configure the budget and a live status display.

## Task Type
- [x] Frontend (Home screen UI, overlay changes)
- [x] Backend (Native time tracking, SharedPreferences, overlay logic)
- [x] Fullstack (End-to-end feature)

---

## Technical Solution

### Concept: Rolling Time Budget

- **Budget Window**: A rolling period (default 60 minutes) during which the user has a scroll allowance.
- **Scroll Allowance**: Amount of time (default 5 minutes) the user can spend in blocked apps per window.
- **Tracking**: `AppUsageMonitor` already polls every 1s. When a blocked app is in foreground AND the user tapped "Continue" (allowed this session), accumulate scroll time. When budget is exhausted, show a "locked out" overlay variant with countdown to next available scroll window.
- **Persistence**: Store `scroll_allowance_minutes` and `scroll_window_minutes` in SharedPreferences. Store accumulated usage timestamps in memory (reset each window).
- **Overlay Variant**: When budget is exhausted, overlay shows "Time's up! You can scroll again in MM:SS" instead of the interstitial question. No "Continue" button — only "Back to Reality".

### Data Flow

```
User sets budget on Home screen
  → VPNModule.setScrollBudget(allowanceMin, windowMin)
    → appMonitor.setScrollBudget(...)
    → Intent → MyVpnService → its monitor.setScrollBudget(...)
    → SharedPreferences: scroll_allowance_minutes, scroll_window_minutes

AppUsageMonitor polling loop (every 1s):
  if foreground app is blocked AND in allowedThisSession:
    accumulate scrollTimeUsedMs += 1000
    if scrollTimeUsedMs >= allowanceMs:
      remove from allowedThisSession
      show "budget exhausted" overlay with countdown
      record budgetExhaustedAt timestamp

  if budget was exhausted AND (now - windowStartTime) >= windowMs:
    reset scrollTimeUsedMs = 0
    reset windowStartTime = now

Home screen polls:
  VPNModule.getScrollBudgetStatus() → { allowanceMin, windowMin, usedMs, windowStartTime, canScroll, nextScrollAt }
```

---

## Implementation Steps

### Step 1: Add SharedPreferences Keys for Scroll Budget

**File:** `android/app/src/main/java/com/breqk/SettingsModule.java`
**Operation:** Add methods

- Add `saveScrollBudget(int allowanceMinutes, int windowMinutes)` — saves both keys
- Add `getScrollBudget(Callback)` — returns `{allowanceMinutes, windowMinutes}`
- Keys: `scroll_allowance_minutes` (default 5), `scroll_window_minutes` (default 60)

### Step 2: Add Scroll Budget Tracking to AppUsageMonitor

**File:** `android/app/src/main/java/com/breqk/AppUsageMonitor.java`
**Operation:** Major modification

New fields:
```java
// Scroll budget configuration
private int scrollAllowanceMinutes = 5;    // minutes allowed per window
private int scrollWindowMinutes = 60;       // window duration in minutes
private long scrollTimeUsedMs = 0;          // accumulated scroll time this window
private long windowStartTime = 0;           // when current window started
private long budgetExhaustedAt = 0;         // when budget ran out (0 = not exhausted)
private boolean scrollBudgetEnabled = true; // master toggle
```

New methods:
- `setScrollBudget(int allowanceMin, int windowMin)` — update config
- `getScrollBudgetStatus()` — returns WritableMap with all status fields
- `resetScrollWindow()` — resets window when expired
- `isScrollBudgetExhausted()` — check if time is up
- `getTimeUntilNextScroll()` — ms until next window opens

Modify `monitorApps()` loop:
- After line ~195 (where `allowedThisSession.contains(foregroundApp)` is checked):
  - If app is in `allowedThisSession` AND is a blocked app → increment `scrollTimeUsedMs += 1000`
  - If `scrollTimeUsedMs >= allowanceMs` → call `showBudgetExhaustedOverlay()`
  - Remove app from `allowedThisSession` so the popup system kicks in
- Add window reset check at top of loop:
  - If `windowStartTime > 0` AND `(now - windowStartTime) >= windowMs` → reset

New overlay method `showBudgetExhaustedOverlay(String appPackage)`:
- Reuses `R.layout.delay_overlay` but with different text
- Title: "Scroll time is up!"
- Message: "You can scroll again in MM:SS"
- Live countdown timer updating every 1s showing time until window resets
- Only "Back to Reality" button (no "Continue" — budget is hard-locked)
- On dismiss → send user to HOME intent

### Step 3: Add Scroll Budget Methods to VPNModule

**File:** `android/app/src/main/java/com/breqk/VPNModule.java`
**Operation:** Add bridge methods

Following the existing two-part update pattern:

```java
@ReactMethod
public void setScrollBudget(int allowanceMinutes, int windowMinutes, Promise promise)
// 1. Update appMonitor.setScrollBudget(...)
// 2. Send intent to MyVpnService with "SET_SCROLL_BUDGET" action
// 3. Save to SharedPreferences

@ReactMethod
public void getScrollBudgetStatus(Promise promise)
// Returns: { allowanceMinutes, windowMinutes, usedMs, windowStartTime, canScroll, nextScrollAtMs, remainingMs }
```

### Step 4: Handle Scroll Budget Intent in MyVpnService

**File:** `android/app/src/main/java/com/breqk/MyVpnService.java`
**Operation:** Add intent handler

- Add case for `"SET_SCROLL_BUDGET"` action in `onStartCommand()`
- Extract `allowanceMinutes` and `windowMinutes` from intent extras
- Call `monitor.setScrollBudget(allowanceMinutes, windowMinutes)`
- Load scroll budget from SharedPreferences on service `onCreate()`

### Step 5: Add Budget Exhausted Overlay Layout Variant

**File:** `android/app/src/main/res/layout/delay_overlay.xml`
**Operation:** Modify (add countdown text view) OR create new layout

Option A (preferred — reuse existing): Add a `TextView` for the countdown timer that is hidden by default and shown only when budget is exhausted. The `showBudgetExhaustedOverlay()` method will:
- Set title to "Scroll time is up!"
- Show the countdown `TextView` with live MM:SS
- Hide the "Continue" button entirely
- Show only "Back to Reality"

### Step 6: Update Home Screen — Budget Configuration UI

**File:** `components/Home/home.js`
**Operation:** Major modification

Add new state:
```javascript
const [scrollAllowance, setScrollAllowance] = useState(5);    // minutes
const [scrollWindow, setScrollWindow] = useState(60);          // minutes
const [budgetStatus, setBudgetStatus] = useState(null);
// budgetStatus = { usedMs, canScroll, nextScrollAtMs, remainingMs }
```

Add new UI section (between stats and existing content):

**"Scroll Budget" Card:**
```
┌─────────────────────────────────────────┐
│  Scroll Budget                          │
│                                         │
│  Allow [  5 ▾] minutes every [60 ▾] min│
│                                         │
│  ┌─────────────────────────────────┐    │
│  │  ● 3:42 remaining              │    │  ← green when can scroll
│  │    Progress bar [████████░░]    │    │
│  └─────────────────────────────────┘    │
│                                         │
│  OR when exhausted:                     │
│  ┌─────────────────────────────────┐    │
│  │  ○ Scroll again in 24:18       │    │  ← red/muted when locked
│  │    Progress bar [░░░░░░░░░░]   │    │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

Components:
- Two pickers/steppers for allowance (1–30 min) and window (15–120 min)
- On change → call `VPNModule.setScrollBudget(allowance, window)`
- Live status display with 1s polling interval via `VPNModule.getScrollBudgetStatus()`
- Progress bar showing % of allowance used
- Countdown text: "X:XX remaining" (green) or "Scroll again in X:XX" (red)

Load saved settings on mount:
- Call `SettingsModule.getScrollBudget()` to populate pickers

### Step 7: Update BlockerInterstitial for Budget Exhausted State

**File:** `components/BlockerInterstitial/BlockerInterstitial.tsx`
**Operation:** Minor modification (for preview/consistency)

Add optional `budgetExhausted` prop:
- When true, show "Scroll time is up!" title
- Show countdown to next window
- Hide "Continue" button
- This is mainly for the Customize preview; the actual enforcement is native overlay

### Step 8: Persist and Sync on App Restart

**Files:** `VPNModule.java`, `MyVpnService.java`
**Operation:** Modify initialization

- On `VPNModule` constructor: load `scroll_allowance_minutes` and `scroll_window_minutes` from SharedPreferences → apply to `appMonitor`
- On `MyVpnService.onCreate()`: same load → apply to its `monitor`
- On `startMonitoring()`: ensure budget state is fresh

---

## Key Files

| File | Operation | Description |
|------|-----------|-------------|
| `android/.../AppUsageMonitor.java` | Modify | Add scroll time accumulation, budget check, exhausted overlay |
| `android/.../VPNModule.java` | Modify | Add `setScrollBudget()`, `getScrollBudgetStatus()` bridge methods |
| `android/.../MyVpnService.java` | Modify | Handle `SET_SCROLL_BUDGET` intent, load on create |
| `android/.../SettingsModule.java` | Modify | Add `saveScrollBudget()`, `getScrollBudget()` |
| `android/.../res/layout/delay_overlay.xml` | Modify | Add countdown TextView for budget exhausted state |
| `components/Home/home.js` | Modify | Add scroll budget config UI + live status display |
| `components/BlockerInterstitial/BlockerInterstitial.tsx` | Modify | Add `budgetExhausted` variant for preview |

---

## Risks and Mitigation

| Risk | Mitigation |
|------|------------|
| Dual monitor desync — budget state differs between VPNModule and MyVpnService instances | Follow existing two-part update pattern (update local + send intent). Budget tracking only happens in the active monitoring instance (MyVpnService's monitor). VPNModule's monitor is read-only for status queries. |
| Service killed by Android — loses in-memory scroll time | Periodically persist `scrollTimeUsedMs` and `windowStartTime` to SharedPreferences (every 30s). Load on service restart. |
| Battery drain from Home screen polling budget status | Use 5s polling interval (not 1s) for Home screen status. Only poll when Home screen is focused. |
| User changes budget mid-window | Apply new settings immediately. If new allowance < current used time, trigger exhausted state. If new allowance > used, continue with remaining time. |
| Overlay shown over other overlays (double overlay) | Check `isOverlayShowing` flag before showing budget exhausted overlay. Reuse existing overlay guard logic. |
| Window boundary edge case — user opens app right at window reset | Use `windowStartTime` initialized on first blocked app usage (lazy start), not on app boot. This prevents "phantom windows" when app isn't being used. |

---

## Testing Strategy

1. **Unit**: Verify `isScrollBudgetExhausted()` with various time combinations
2. **Integration**: Tap "Continue" on overlay → verify scroll time accumulates → verify lockout after budget expires
3. **Edge cases**: Change budget mid-session, service restart, window rollover
4. **UI**: Home screen shows correct countdown, pickers save correctly
5. **Log filters**: `[AppUsageMonitor]` for budget tracking, `[VPNModule]` for bridge calls

---

## Log Dictionary Additions

| Tag | Message Pattern | Meaning |
|-----|----------------|---------|
| `[AppUsageMonitor]` | `Scroll budget: used Xms / Yms allowance` | Current scroll time usage |
| `[AppUsageMonitor]` | `Scroll budget exhausted! Showing lockout overlay` | Budget hit, overlay shown |
| `[AppUsageMonitor]` | `Scroll window reset. New window starts now.` | Window expired, counters reset |
| `[VPNModule]` | `setScrollBudget: allowance=X, window=Y` | Budget config updated from JS |
| `[MyVpnService]` | `SET_SCROLL_BUDGET received: allowance=X, window=Y` | Service received budget update |

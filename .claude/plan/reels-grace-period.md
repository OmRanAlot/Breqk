# Implementation Plan: Reels/Shorts Grace Period

## Problem

When the user opens YouTube or Instagram, the app can auto-navigate to Shorts/Reels (e.g., YouTube opens directly to Shorts tab, or a friend sends a Reel link). The current system immediately starts budget accumulation and can trigger the intervention overlay on entry — blocking the user before they even see the first video. This prevents legitimate use cases:

- YouTube auto-opening to Shorts tab
- Friends sending Reels/Shorts links
- Research (recipes, restaurants, tutorials)
- Browsing from the home page and tapping a video

**Desired behavior:** Allow the user to watch the **first video** they land on. The blocker should only activate on the **first scroll** (indicating intentional doom-scrolling, not passive single-video viewing).

## Task Type
- [x] Backend (Android native — `ReelsInterventionService.java`)
- [ ] Frontend (React Native)
- [x] Fullstack (minor: add configurable grace period scroll count to settings)

## Technical Solution

### Core Concept: Grace Period = Defer Budget Until First Scroll

Currently, when the user enters Reels/Shorts (detected via `TYPE_WINDOW_STATE_CHANGED`), the service immediately:
1. Calls `startReelsHeartbeat()` → begins accumulating budget time every 2s
2. Calls `accumulateScrollBudget()` → can exhaust budget instantly
3. Calls `isScrollBudgetExhausted()` → triggers intervention overlay if exhausted

**Change:** On Reels/Shorts entry, enter a **grace period** state. During grace period:
- `wasInReelsLayout = true` (so we track we're in Reels)
- `persistReelsState(true, packageName)` (so AppUsageMonitor knows)
- **DO NOT** start heartbeat or accumulate budget
- The user can watch the first video freely

**End grace period** on the **first `TYPE_VIEW_SCROLLED` event** while in grace period. At that point:
- Start the heartbeat (`startReelsHeartbeat()`)
- Begin budget accumulation (`accumulateScrollBudget()`)
- Check if budget is already exhausted → intervene immediately if so

### Why This Works

The scroll event is the perfect signal for "the user wants to see more content." One video = passive viewing. First scroll = active doom-scrolling begins. This aligns exactly with the user's mental model.

### Grace Period Resets

The grace period resets (re-activates for next entry) whenever `resetReelsState()` is called, which happens when:
- User leaves Reels/Shorts (navigates to home, DMs, etc.)
- User presses Home button
- User switches to another app
- Intervention "Lock In" sends user home

This means every fresh entry into Reels/Shorts gets a grace period — the user always gets to see the first video.

## Implementation Steps

### Step 1: Add grace period state variable to `ReelsInterventionService.java`

**File:** `android/app/src/main/java/com/breqk/ReelsInterventionService.java`
**Location:** Near the existing state variables (around line 220)

Add:
```java
/**
 * Grace period flag. When true, the user just entered Reels/Shorts and is
 * watching the first video. Budget accumulation and heartbeat are deferred
 * until the first TYPE_VIEW_SCROLLED event fires (indicating the user is
 * actively scrolling to more content).
 *
 * Resets to true on each fresh entry into Reels/Shorts.
 * Set to false on the first scroll event.
 *
 * Filter: adb logcat -s REELS_WATCH | findstr "GRACE"
 */
private boolean inGracePeriod = false;
```

### Step 2: Modify `TYPE_WINDOW_STATE_CHANGED` handling — defer heartbeat/budget on entry

**File:** `ReelsInterventionService.java`
**Location:** `handleReelsScrollEvent()`, the `TYPE_WINDOW_STATE_CHANGED` block

**Current behavior (3 places where entry is detected):**

**Place 1: TIER0 class name match (line ~455-469)**
```java
// Current:
if (!wasInReelsLayout) {
    notifyShortsState(true, "TIER0");
    persistReelsState(true, packageName);
    startReelsHeartbeat(packageName);          // ← REMOVE
    accumulateScrollBudget();                  // ← REMOVE
    if (isScrollBudgetExhausted(...)) { ... }  // ← REMOVE
}
```
```java
// New:
if (!wasInReelsLayout) {
    Log.d(TAG, "[GRACE] Entering Reels/Shorts (TIER0) — grace period started for " + packageName);
    inGracePeriod = true;
    notifyShortsState(true, "TIER0");
    persistReelsState(true, packageName);
    // Heartbeat and budget deferred until first scroll (grace period)
}
```

**Place 2: TIER1/TIER2 tree traversal (line ~485-503)**
```java
// Current:
} else if (inReelsLayout && !wasInReelsLayout) {
    if (packageName.equals(PKG_YOUTUBE)) notifyShortsState(true, "TIER1/TIER2");
    persistReelsState(true, packageName);
    startReelsHeartbeat(packageName);          // ← REMOVE
    accumulateScrollBudget();                  // ← REMOVE
    if (isScrollBudgetExhausted(...)) { ... }  // ← REMOVE
}
```
```java
// New:
} else if (inReelsLayout && !wasInReelsLayout) {
    Log.d(TAG, "[GRACE] Entering Reels/Shorts (TIER1/TIER2) — grace period started for " + packageName);
    inGracePeriod = true;
    if (packageName.equals(PKG_YOUTUBE)) notifyShortsState(true, "TIER1/TIER2");
    persistReelsState(true, packageName);
    // Heartbeat and budget deferred until first scroll (grace period)
}
```

**Place 3: First scroll entry (line ~578-594)**
```java
// Current:
if (!wasInReelsLayout) {
    persistReelsState(true, packageName);
    startReelsHeartbeat(packageName);          // ← REMOVE from here
    accumulateScrollBudget();                  // ← REMOVE from here
    if (isScrollBudgetExhausted(...)) { ... }  // ← REMOVE from here
}
```
```java
// New:
if (!wasInReelsLayout) {
    // First detection of Reels via scroll (no prior STATE_CHANGED).
    // Start grace period — it will end immediately below when we process
    // this scroll event as the "first scroll."
    inGracePeriod = true;
    persistReelsState(true, packageName);
    Log.d(TAG, "[GRACE] Entering Reels/Shorts (first-scroll) — grace period started for " + packageName);
}
```

### Step 3: End grace period on first scroll — start heartbeat + budget

**File:** `ReelsInterventionService.java`
**Location:** In `handleReelsScrollEvent()`, after the `wasInReelsLayout = true;` line (around line 595), and before the free break check

Add grace period termination logic:
```java
wasInReelsLayout = true;

// --- Grace period termination ---
// The first scroll after entering Reels/Shorts ends the grace period.
// This is where we start the heartbeat and budget accumulation.
if (inGracePeriod) {
    inGracePeriod = false;
    Log.i(TAG, "[GRACE] First scroll detected — grace period ended for " + packageName);

    // Now start what was deferred on entry:
    startReelsHeartbeat(packageName);
    accumulateScrollBudget();

    // Immediate budget check: if budget was already exhausted from a previous
    // session (or allowance=0), intervene now.
    long graceEndNow = System.currentTimeMillis();
    if (isScrollBudgetExhausted(graceEndNow) && !interventionShowing) {
        interventionShowing = true;
        Log.i(TAG, "[GRACE] Budget already exhausted after grace period — immediate intervention for " + packageName);
        triggerIntervention(packageName);
    }
    // Emit SCROLL_DECISION for this first scroll even though we just started
    // (the normal budget check below will handle subsequent scrolls)
}
```

### Step 4: Reset grace period in `resetReelsState()`

**File:** `ReelsInterventionService.java`
**Location:** `resetReelsState()` method (line ~1178)

Add `inGracePeriod = false;` to the reset:
```java
private void resetReelsState() {
    if (shortsCurrentlyDetected) notifyShortsState(false, "reset");
    wasInReelsLayout = false;
    interventionShowing = false;
    inGracePeriod = false;  // ← ADD
    lastScrollTimestamp = 0;
    currentReelsPackage = "";
    persistReelsState(false, "");
    stopReelsHeartbeat();
    Log.d(TAG, "resetReelsState: cleared (wasInReels=false, interventionShowing=false, "
            + "inGracePeriod=false, lastScrollTimestamp=0, ...)");
}
```

### Step 5: Update logging & LOGGING.md

Add `[GRACE]` log marker to `LOGGING.md` and `docs/LOG_DICTIONARY.md`:

```
# Grace period logs
adb logcat -s REELS_WATCH | findstr "GRACE"
```

Log lines emitted:
| Marker | When | Example |
|--------|------|---------|
| `[GRACE] Entering Reels/Shorts (TIER0)` | Entering via class name match | Grace period started |
| `[GRACE] Entering Reels/Shorts (TIER1/TIER2)` | Entering via tree traversal | Grace period started |
| `[GRACE] Entering Reels/Shorts (first-scroll)` | First detection via scroll event | Grace period started |
| `[GRACE] First scroll detected` | User scrolled for the first time | Grace period ended, budget starts |
| `[GRACE] Budget already exhausted after grace period` | Budget was pre-exhausted | Immediate intervention |

## Key Files

| File | Operation | Description |
|------|-----------|-------------|
| `android/app/src/main/java/com/breqk/ReelsInterventionService.java` | Modify | Add `inGracePeriod` flag, defer heartbeat/budget on entry, end grace on first scroll |
| `LOGGING.md` | Modify | Add `[GRACE]` marker documentation |

## Risks and Mitigation

| Risk | Mitigation |
|------|------------|
| User watches first video for a long time without scrolling — budget doesn't accumulate | This is intentional. The user is watching ONE video, which is legitimate use. Budget only starts when they scroll to more. |
| YouTube auto-scrolls (auto-play next) without user input | Auto-play doesn't fire `TYPE_VIEW_SCROLLED` accessibility events — it fires `TYPE_WINDOW_CONTENT_CHANGED` which is already filtered out. So auto-play won't end grace period. The user must physically swipe. |
| Grace period bypasses free break logic ordering | Grace period check happens BEFORE free break check in the scroll handler. If free break is active, the grace period still ends on first scroll but the free break allows everything anyway — no conflict. |
| `resetReelsState()` called during grace period (e.g., quick app switch) | Grace period is reset to false, and on next entry it starts fresh. Correct behavior. |

## What This Does NOT Change

- **Budget accumulation logic** — unchanged, just deferred
- **Heartbeat logic** — unchanged, just deferred
- **Free break feature** — unchanged, grace period is orthogonal
- **AppUsageMonitor / overlay for blocked apps** — unchanged (different system)
- **Scroll debounce** — unchanged
- **False-positive guards** — unchanged

## Future Enhancement (Not In Scope)

- Configurable grace video count (allow N scrolls before blocking, default 1) — could be added later via `BreqkPrefs.KEY_GRACE_SCROLL_COUNT` and a counter, but the user's request is clearly for 1 video, so we keep it simple.

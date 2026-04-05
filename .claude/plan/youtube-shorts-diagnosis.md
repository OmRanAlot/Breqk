# Plan: Fix YouTube Shorts Detection

**Status:** Root causes CONFIRMED from log67.txt analysis.

**TL;DR:** Detection works correctly. The overlay never fires because the heartbeat (which accumulates budget time) never starts after any app switch or keyboard event. Two bugs combine to cause this.

---

## Confirmed Root Causes (from log67.txt)

### Bug 1 — CRITICAL: `STATE_CHANGED` preempts the heartbeat start

**Exact sequence observed in log67.txt:**

```
17:32:31  [APP_SWITCH] newPkg=com.google.android.inputmethod.latin → resetReelsState()
           wasInReelsLayout = false  ✓

17:34:35  TYPE_WINDOW_STATE_CHANGED fires for YouTube
           isShortsLayout → TIER2 matched → true
           STATE_CHANGED handler sets: wasInReelsLayout = true  ← BUG
           (does NOT call startReelsHeartbeat)

17:34:36  TYPE_VIEW_SCROLLED fires
           confirmedInReels = true
           if (!wasInReelsLayout) { startReelsHeartbeat() }  ← SKIPPED because
           wasInReelsLayout is already true from STATE_CHANGED above
           → heartbeat never starts → budget never accumulates → overlay never shows
```

The `STATE_CHANGED` handler (lines ~368–383 in `ReelsInterventionService.java`) correctly detects Shorts and sets `wasInReelsLayout=true`, but it does NOT call `startReelsHeartbeat`. The heartbeat start lives only inside `if (!wasInReelsLayout)` in the scroll handler. Since `STATE_CHANGED` always fires before the first scroll event, `wasInReelsLayout` is always `true` by the time any scroll arrives — the heartbeat start block is permanently skipped.

**Every single scroll in the log confirms this:**
```
SCROLL_DECISION pkg=com.google.android.youtube fastPath=false confirmedInReels=true budgetExhausted=false
[BUDGET] Window expired → budget available (pending reset)
[BUDGET] Budget OK — allowing scroll in com.google.android.youtube
```
Zero `startReelsHeartbeat` calls. Zero `persistReelsState: inReels=true` calls. Budget never depletes.

**Fix — in `handleReelsScrollEvent`, STATE_CHANGED branch:**

Add the same heartbeat-start block that the scroll handler has, gated on the `false → true` transition:

```java
if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    boolean inReelsLayout = packageName.equals(PKG_INSTAGRAM)
            ? isReelsLayout(root)
            : isShortsLayout(root);
    if (root != null) root.recycle();

    if (!inReelsLayout && wasInReelsLayout) {
        Log.d(TAG, "Left Reels/Shorts via state change — resetting");
        resetReelsState();
    } else if (inReelsLayout && !wasInReelsLayout) {
        // ← ADD THIS BLOCK
        // Entering Reels/Shorts via navigation (tab switch, deep link, etc.)
        // The scroll handler's heartbeat-start block will be bypassed because
        // wasInReelsLayout will be true by the time the first scroll fires.
        // Start the heartbeat here so budget accumulation begins immediately.
        Log.d(TAG, "STATE_CHANGED: entering Reels/Shorts — starting heartbeat for " + packageName);
        persistReelsState(true, packageName);
        startReelsHeartbeat(packageName);

        // Immediate budget check: if already exhausted (or allowance=0), intervene now.
        long entryNow = System.currentTimeMillis();
        accumulateScrollBudget();
        if (isScrollBudgetExhausted(entryNow) && !interventionShowing) {
            interventionShowing = true;
            Log.i(TAG, "[BUDGET] STATE_CHANGED entry: budget exhausted — immediate intervention for " + packageName);
            triggerIntervention(packageName);
        }
    }
    wasInReelsLayout = inReelsLayout;
    Log.d(TAG, "STATE_CHANGED: wasInReelsLayout=" + wasInReelsLayout + " pkg=" + packageName);
    return;
}
```

---

### Bug 2 — SECONDARY: Keyboard (IME) triggers false APP_SWITCH reset

**Observed in log67.txt:**
```
17:32:31  [APP_SWITCH] Detected app switch while in Reels: newPkg=com.google.android.inputmethod.latin
          → resetReelsState() called
          → heartbeat stopped
          → wasInReelsLayout = false
```

`com.google.android.inputmethod.latin` is **Gboard** (the on-screen keyboard). The user is still in YouTube Shorts — the keyboard appeared as a system overlay, not a real navigation away. Yet the service treats it as leaving the app and resets all state.

This is what triggers Bug 1 in the first place: every keyboard invocation resets the heartbeat, and Bug 1 prevents it from restarting.

**Fix — in the APP_SWITCH detection logic:**

Exclude system packages that are overlays/IMEs rather than real foreground apps. Add a helper and a denylist:

```java
/** Packages that represent system overlays, not real foreground navigation.
 *  An APP_SWITCH to one of these should NOT reset Reels state.
 *  Add to this list if new false-reset packages are observed in logs. */
private static final String[] APP_SWITCH_IGNORE_PACKAGES = {
    "com.google.android.inputmethod.latin",  // Gboard
    "com.samsung.android.honeyboard",        // Samsung Keyboard
    "com.swiftkey.swiftkeyapp",              // SwiftKey
    "com.android.inputmethod.latin",         // AOSP keyboard
    "com.android.systemui",                  // Status bar, notification shade
};

private boolean isSystemOverlayPackage(String pkg) {
    if (pkg == null) return false;
    for (String ignore : APP_SWITCH_IGNORE_PACKAGES) {
        if (ignore.equals(pkg)) return true;
    }
    // Catch any IME package not explicitly listed by checking the suffix pattern
    // (not airtight but catches most vendor keyboards)
    return pkg.endsWith(".inputmethod") || pkg.contains(".inputmethod.");
}
```

Then guard the APP_SWITCH reset:
```java
// In the TYPE_WINDOW_STATE_CHANGED → app-switch detection section:
if (wasInReelsLayout && !packageName.equals(PKG_INSTAGRAM) && !packageName.equals(PKG_YOUTUBE)) {
    if (isSystemOverlayPackage(packageName)) {
        Log.d(TAG, "[APP_SWITCH] Ignoring system overlay package: " + packageName + " — Reels state preserved");
    } else {
        Log.i(TAG, "[APP_SWITCH] Detected app switch while in Reels: newPkg=" + packageName + " — resetting");
        resetReelsState();
    }
}
```

---

## Additional Observations (non-blocking)

### TIER1 misses after keyboard reset
Before the reset, TIER1 matched `reel_player_page_container`. After the reset, TIER1 misses and TIER2 handles detection via `reel_time_bar`. Both work correctly — this is just a consistency note. `reel_player_page_container` may not always be present in the accessibility tree (possibly depends on scroll position or video loading state). TIER2 is the reliable fallback.

### Fast path always misses (`fastPath=false`)
Every scroll in the log uses the slow path. The scroll event source is NOT one of the 7 IDs in `YOUTUBE_SHORTS_VIEW_IDS` — likely a child view within the Shorts player. This is a performance issue (full tree traversal on every scroll) but not a correctness bug. Consider adding the actual scroll source ID to `YOUTUBE_SHORTS_VIEW_IDS` once the fast-path source ID is identified via a log like:
```bash
adb logcat -s REELS_WATCH | grep "Fast path"
# If source ID appears and is Shorts-specific, add it to YOUTUBE_SHORTS_VIEW_IDS
```

---

## Fix Priority

| Priority | Bug | File | Impact |
|---|---|---|---|
| **P0** | STATE_CHANGED doesn't start heartbeat on entry | `ReelsInterventionService.java` | Budget never accumulates → overlay never shows |
| **P1** | Keyboard IME triggers false APP_SWITCH reset | `ReelsInterventionService.java` | Heartbeat killed while still in Shorts |
| **P2** | Fast path never fires (slow path used every scroll) | `ReelsInterventionService.java` | Performance only — CPU per scroll |

Fix P0 first. P1 is required to prevent recurrence. P2 is optional optimization.

---

## Verification After Fix

```bash
# After applying the fix, open YouTube Shorts and scroll.
# You should see these log lines — currently ABSENT from logs:

adb logcat -s REELS_WATCH | grep -E "startReelsHeartbeat|persistReelsState.*inReels=true|accumulateScrollBudget"

# After 5+ minutes of scrolling with a short budget (set allowance to 1 minute in Customize):
adb logcat -s REELS_WATCH | grep -E "triggerIntervention|BUDGET.*exhausted"
```

Expected after fix:
```
STATE_CHANGED: entering Reels/Shorts — starting heartbeat for com.google.android.youtube
[REELS_STATE] persistReelsState: inReels=true pkg=com.google.android.youtube
[BUDGET] Accumulated Xs — used=Xms allowance=Xms
[BUDGET] Budget exhausted — triggering intervention for com.google.android.youtube
triggerIntervention: showing overlay for com.google.android.youtube
```

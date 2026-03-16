# Intervention Popup Fix

Fixes two problems with the current `ReelsInterventionService` overlay:
1. Popup appears on Instagram's home screen — should only fire inside Reels
2. Buttons don't work (touch events pass through overlay to app beneath)

---

## Root Causes

### Problem 1 — Popup shows on home screen
`isReelsLayout()` uses `findAccessibilityNodeInfosByText("Original audio")` and
`findAccessibilityNodeInfosByText("Reel by")` as fallback signals. These strings can
appear in Instagram's home feed (e.g. a reel embedded in the feed), triggering the
detection outside the dedicated Reels tab.

**Fix:** Gate detection on the primary view ID `clips_viewer_view_pager` only.
Fall back to text signals only when the view ID lookup returns empty AND a scroll
event is confirmed. Never trigger on `TYPE_WINDOW_CONTENT_CHANGED` alone.

### Problem 2 — Buttons don't respond to taps
`WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE` passes all touch events through
the overlay to the app behind it. The buttons render but are untappable.

**Fix:** Remove `FLAG_NOT_FOCUSABLE`. Use `FLAG_NOT_TOUCH_MODAL` +
`FLAG_WATCH_OUTSIDE_TOUCH` instead so the overlay captures its own touches while
still letting the system handle touches outside the overlay bounds.

---

## Corrected Detection Logic

Only enter Reels-tracking mode when ALL of these are true:
- Package is `com.instagram.android`
- Event type is `TYPE_VIEW_SCROLLED` (not content change, not state change)
- `clips_viewer_view_pager` node is found in the active window

```java
private boolean isReelsLayout(AccessibilityNodeInfo root) {
    if (root == null) return false;

    // PRIMARY signal only — do not fall back to text on scroll events
    // Text fallbacks ("Reel by", "Original audio") match home feed embeds too
    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(
            "com.instagram.android:id/clips_viewer_view_pager");

    if (nodes != null && !nodes.isEmpty()) {
        for (AccessibilityNodeInfo n : nodes) n.recycle();
        return true;
    }

    return false;
}
```

Re-evaluate layout only on `TYPE_WINDOW_STATE_CHANGED` (tab navigation).
On `TYPE_VIEW_SCROLLED`, trust `wasInReelsLayout` rather than re-traversing the tree:

```java
private void handleReelsScrollEvent(AccessibilityEvent event, String packageName) {
    int eventType = event.getEventType();

    // Only re-check layout on actual tab/screen navigation — not on every scroll
    if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean inReels = isReelsLayout(root);
        if (root != null) root.recycle();

        if (!inReels && wasInReelsLayout) {
            resetReelsState();
        }
        wasInReelsLayout = inReels;
        return; // don't count navigation events as scrolls
    }

    // Ignore content changes entirely — too noisy, causes false resets
    if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

    // Only count scrolls when already confirmed inside Reels
    if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && wasInReelsLayout) {
        reelsScrollCount++;
        Log.d(TAG, "Reels scroll " + reelsScrollCount + "/" + getScrollThreshold());

        if (reelsScrollCount >= getScrollThreshold() && !interventionShowing) {
            interventionShowing = true;
            triggerIntervention(packageName);
        }
    }
}
```

---

## Corrected WindowManager Flags

```java
// BROKEN — FLAG_NOT_FOCUSABLE swallows all touch events, buttons are dead
WindowManager.LayoutParams params = new WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE       // <-- REMOVE THIS
        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
    PixelFormat.TRANSLUCENT
);

// FIXED — overlay receives its own touches, passes outside touches through
WindowManager.LayoutParams params = new WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
    PixelFormat.TRANSLUCENT
);
```

---

## Button Behaviors

### "LOCK IN" button
Dismiss the popup and deep-link the user to Instagram's home feed (stories + posts).
Reels tab is effectively vacated. Scroll counter resets to 0 so the next Reels visit
starts fresh after another 5 scrolls.

```java
btnLockIn.setOnClickListener(v -> {
    Log.i(TAG, "lock_in tapped");
    dismissIntervention();
    resetReelsState();

    // Deep-link to Instagram home feed (stories + posts, not Reels)
    Intent homeIntent = new Intent(Intent.ACTION_VIEW,
            android.net.Uri.parse("instagram://feed"));
    homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
        startActivity(homeIntent);
    } catch (Exception e) {
        // Fallback: just fire back if deep link fails
        Log.w(TAG, "lock_in: instagram://feed failed, firing back", e);
        performGlobalAction(GLOBAL_ACTION_BACK);
    }
});
```

### "Take a Break" button
Dismiss the popup, reset state, and fire the global back action to leave Reels entirely.

```java
btnTakeBreak.setOnClickListener(v -> {
    Log.i(TAG, "take_a_break tapped");
    dismissIntervention();
    resetReelsState();
    performGlobalAction(GLOBAL_ACTION_BACK);
});
```

---

## Auto-dismiss Fix

The current auto-dismiss `postDelayed` runnable is never cancelled when the user taps
a button. It fires 8 seconds later and calls `performGlobalAction(GLOBAL_ACTION_BACK)`
even after the user already chose "LOCK IN". Fix by storing a reference and cancelling it.

```java
// Class-level field
private Runnable autoDismissRunnable = null;

// In triggerIntervention(), replace anonymous postDelayed with:
autoDismissRunnable = () -> {
    if (interventionShowing) {
        Log.i(TAG, "auto-dismiss after 8s");
        dismissIntervention();
        resetReelsState();
        performGlobalAction(GLOBAL_ACTION_BACK);
    }
};
mainHandler.postDelayed(autoDismissRunnable, 8000);

// In dismissIntervention(), add before returning:
if (autoDismissRunnable != null) {
    mainHandler.removeCallbacks(autoDismissRunnable);
    autoDismissRunnable = null;
}
```

---

## Popup Recurrence

Counter resets to `0` on every dismissal (both buttons + auto-dismiss).
Popup fires again after another 5 scrolls in the same Reels session.

```
User enters Reels
└── scroll ×5 → popup appears
    ├── "LOCK IN"      → instagram://feed, counter = 0
    ├── "Take a Break" → GLOBAL_ACTION_BACK, counter = 0
    └── ignored 8s     → GLOBAL_ACTION_BACK, counter = 0

User re-enters Reels
└── scroll ×5 → popup appears again (same cycle)
```

---

## Updated overlay_reels_intervention.xml

Replace the existing button labels:

```xml
<Button
    android:id="@+id/btn_lock_in"
    android:layout_width="match_parent"
    android:layout_height="52dp"
    android:text="🔒 Lock In"
    android:textColor="#000000"
    android:textSize="16sp"
    android:textStyle="bold"
    android:backgroundTint="#FFFFFF"
    android:layout_marginBottom="10dp" />

<Button
    android:id="@+id/btn_take_break"
    android:layout_width="match_parent"
    android:layout_height="52dp"
    android:text="Take a Break"
    android:textColor="#FFFFFF"
    android:textSize="16sp"
    android:backgroundTint="#2A2A2A" />
```

And update the button IDs referenced in `triggerIntervention()`:
- `R.id.btn_keep_watching` → `R.id.btn_lock_in`
- `R.id.btn_get_out` → `R.id.btn_take_break`

---

## Summary of All Changes

| Issue | File | Change |
|---|---|---|
| Popup on home feed | `ReelsInterventionService.java` | Remove text fallbacks from `isReelsLayout()` |
| False resets from content changes | `ReelsInterventionService.java` | Skip `TYPE_WINDOW_CONTENT_CHANGED` entirely |
| Buttons untappable | `ReelsInterventionService.java` | Remove `FLAG_NOT_FOCUSABLE` from LayoutParams |
| Auto-dismiss fires after button tap | `ReelsInterventionService.java` | Store + cancel `autoDismissRunnable` on dismiss |
| "Lock In" navigates to home feed | `ReelsInterventionService.java` | Deep-link `instagram://feed` |
| "Take a Break" fires back | `ReelsInterventionService.java` | `performGlobalAction(GLOBAL_ACTION_BACK)` |
| Button labels | `overlay_reels_intervention.xml` | Rename to `btn_lock_in` / `btn_take_break` |
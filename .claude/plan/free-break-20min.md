# Plan: 20-Minute Free Scroll Break

**Feature summary**: A daily one-time 20-minute window where all Reels/Shorts interventions are completely suspended — no popups, no budget accumulation, nothing. Great for cardio sessions on the treadmill or stairmaster where mindless scrolling is genuinely fine.

---

## Behavior Spec (source of truth)

| Scenario | Behavior |
|----------|----------|
| Toggle OFF (default) | Feature invisible everywhere — no button on Home, no section in settings |
| Toggle ON, break not used today | Green "Start 20-Min Free Break" button visible on Home |
| Toggle ON, break active | Home shows green card with live countdown + "End Break Early" button; all Reels/Shorts intervention logic bypassed |
| Toggle ON, break already used today | Button visible but disabled/grayed, label: "Free Break Used Today" |
| Break timer hits 0 | Auto-ends; interventions resume; `free_break_active=false` written to SharedPreferences |
| User ends break early | Same as timer hitting 0 |
| Midnight (new day) | `usedToday` resets — button becomes active again |
| Scroll budget during break | Budget timer does NOT advance. Window reset timer still runs independently (time passes), but since nothing is accumulated, the user's remaining budget is fully preserved when break ends |
| App killed during active break | `isFreeBreakActive()` in `ReelsInterventionService` does a time-elapsed sanity check on startup — if `now - startTime > 20min`, auto-clears the flag |

---

## New SharedPreferences Keys (`breqk_prefs`)

Add these to `BreqkPrefs.java`:

| Key | Type | Default | Written by | Read by |
|-----|------|---------|-----------|---------|
| `free_break_enabled` | boolean | false | SettingsModule | VPNModule, ReelsInterventionService |
| `free_break_active` | boolean | false | VPNModule | ReelsInterventionService, AppUsageMonitor |
| `free_break_start_time` | long | 0 | VPNModule | ReelsInterventionService, VPNModule |
| `free_break_last_used_date` | String | "" | VPNModule | VPNModule (JS reads via getFreeBreakStatus) |

Constant:
```java
public static final long FREE_BREAK_DURATION_MS = 20 * 60 * 1000L; // 20 minutes = 1,200,000 ms
```

---

## File-by-File Implementation Plan

### 1. `BreqkPrefs.java` — Add new key constants

Location: `android/app/src/main/java/com/breqk/BreqkPrefs.java`

After the existing scroll budget runtime state keys (line ~50), add:

```java
// Free break (one-time 20-min daily scroll suspension)
public static final String KEY_FREE_BREAK_ENABLED      = "free_break_enabled";
public static final String KEY_FREE_BREAK_ACTIVE       = "free_break_active";
public static final String KEY_FREE_BREAK_START_TIME   = "free_break_start_time";
public static final String KEY_FREE_BREAK_LAST_USED_DATE = "free_break_last_used_date";
public static final long   FREE_BREAK_DURATION_MS      = 20 * 60 * 1000L; // 20 minutes
```

No logic changes — this is constants only.

---

### 2. `SettingsModule.java` — Persist the toggle

Location: `android/app/src/main/java/com/breqk/SettingsModule.java`

Add two `@ReactMethod` methods after `saveScrollBudget`/`getScrollBudget`:

```java
/**
 * Persists the free break feature toggle to SharedPreferences.
 * When false (default), the 20-min break button is hidden on the home screen.
 */
@ReactMethod
public void saveFreeBreakEnabled(boolean enabled) {
    Log.d(TAG, "[SAVE] saveFreeBreakEnabled called with enabled=" + enabled);
    BreqkPrefs.get(reactContext).edit()
        .putBoolean(BreqkPrefs.KEY_FREE_BREAK_ENABLED, enabled)
        .apply();
    Log.d(TAG, "[SAVE] free_break_enabled=" + enabled + " saved");
}

/**
 * Retrieves the free break feature toggle from SharedPreferences.
 * Defaults to false (feature off) so existing users are unaffected.
 */
@ReactMethod
public void getFreeBreakEnabled(com.facebook.react.bridge.Callback callback) {
    Log.d(TAG, "[GET] getFreeBreakEnabled called");
    boolean enabled = BreqkPrefs.get(reactContext)
        .getBoolean(BreqkPrefs.KEY_FREE_BREAK_ENABLED, false);
    Log.d(TAG, "[GET] free_break_enabled=" + enabled);
    callback.invoke(enabled);
}
```

---

### 3. `VPNModule.java` — Start/end break + status

Location: `android/app/src/main/java/com/breqk/VPNModule.java`

Add at the top of the class body, with the other fields:

```java
// Handler for auto-ending the free break after 20 minutes
private final android.os.Handler freeBreakHandler = new android.os.Handler(android.os.Looper.getMainLooper());
private Runnable freeBreakEndRunnable = null;
private static final String FREE_BREAK_TAG = "VPNModule:FreeBreak";
```

Add three `@ReactMethod` methods:

```java
/**
 * Starts the 20-minute free break. Sets free_break_active=true,
 * records the start timestamp, marks today's date as used, and
 * schedules an auto-end after FREE_BREAK_DURATION_MS.
 *
 * Guards:
 *  - Rejects if the break has already been used today (same calendar date).
 *  - Rejects if a break is already active.
 *
 * Also dispatches FREE_BREAK_START intent to MyVpnService so its
 * AppUsageMonitor instance also skips budget accumulation.
 */
@ReactMethod
public void startFreeBreak(Promise promise) {
    try {
        SharedPreferences prefs = BreqkPrefs.get(reactContext);

        // Guard: already active
        if (prefs.getBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false)) {
            Log.w(FREE_BREAK_TAG, "[FREE_BREAK] startFreeBreak called but break already active — rejecting");
            promise.reject("ALREADY_ACTIVE", "Free break is already running");
            return;
        }

        // Guard: already used today
        String todayDate = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(new java.util.Date());
        String lastUsedDate = prefs.getString(BreqkPrefs.KEY_FREE_BREAK_LAST_USED_DATE, "");
        if (todayDate.equals(lastUsedDate)) {
            Log.w(FREE_BREAK_TAG, "[FREE_BREAK] startFreeBreak called but break already used today (" + todayDate + ") — rejecting");
            promise.reject("ALREADY_USED_TODAY", "Free break already used today");
            return;
        }

        long now = System.currentTimeMillis();
        prefs.edit()
            .putBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, true)
            .putLong(BreqkPrefs.KEY_FREE_BREAK_START_TIME, now)
            .putString(BreqkPrefs.KEY_FREE_BREAK_LAST_USED_DATE, todayDate)
            .apply();

        Log.i(FREE_BREAK_TAG, "[FREE_BREAK] Break started at " + now + " (" + todayDate + ") — will auto-end in 20min");

        // Dispatch intent to MyVpnService so its AppUsageMonitor also pauses
        Intent breakStartIntent = new Intent(reactContext, MyVpnService.class);
        breakStartIntent.setAction("com.breqk.FREE_BREAK_START");
        ServiceHelper.startForegroundServiceCompat(reactContext, breakStartIntent);

        // Schedule auto-end on main thread
        if (freeBreakEndRunnable != null) freeBreakHandler.removeCallbacks(freeBreakEndRunnable);
        freeBreakEndRunnable = () -> {
            Log.i(FREE_BREAK_TAG, "[FREE_BREAK] 20-minute timer expired — auto-ending break");
            endFreeBreakInternal();
        };
        freeBreakHandler.postDelayed(freeBreakEndRunnable, BreqkPrefs.FREE_BREAK_DURATION_MS);

        WritableMap result = Arguments.createMap();
        result.putBoolean("success", true);
        result.putDouble("startTimeMs", (double) now);
        result.putDouble("durationMs", (double) BreqkPrefs.FREE_BREAK_DURATION_MS);
        promise.resolve(result);
    } catch (Exception e) {
        Log.e(FREE_BREAK_TAG, "[FREE_BREAK] startFreeBreak error: " + e.getMessage(), e);
        promise.reject("ERROR", e.getMessage());
    }
}

/**
 * Ends the free break early (user-initiated). Clears free_break_active,
 * cancels the auto-end timer, and notifies MyVpnService.
 */
@ReactMethod
public void endFreeBreak(Promise promise) {
    try {
        Log.i(FREE_BREAK_TAG, "[FREE_BREAK] endFreeBreak called (user-initiated early end)");
        endFreeBreakInternal();
        promise.resolve(null);
    } catch (Exception e) {
        Log.e(FREE_BREAK_TAG, "[FREE_BREAK] endFreeBreak error: " + e.getMessage(), e);
        promise.reject("ERROR", e.getMessage());
    }
}

/**
 * Returns the current free break status as a JS object:
 * {
 *   enabled: boolean,       // whether the feature toggle is on
 *   active: boolean,        // whether a break is currently running
 *   startTimeMs: number,    // epoch ms when current break started (0 if not active)
 *   durationMs: number,     // always 1200000 (20 min)
 *   remainingMs: number,    // ms until break ends (0 if not active)
 *   usedToday: boolean      // whether break was already used today
 * }
 */
@ReactMethod
public void getFreeBreakStatus(Promise promise) {
    try {
        SharedPreferences prefs = BreqkPrefs.get(reactContext);
        boolean enabled = prefs.getBoolean(BreqkPrefs.KEY_FREE_BREAK_ENABLED, false);
        boolean active = prefs.getBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false);
        long startTime = prefs.getLong(BreqkPrefs.KEY_FREE_BREAK_START_TIME, 0);
        String lastUsedDate = prefs.getString(BreqkPrefs.KEY_FREE_BREAK_LAST_USED_DATE, "");

        long now = System.currentTimeMillis();
        long remainingMs = 0;

        // Sanity check: if active but time has expired (e.g. process was killed),
        // auto-clear the flag so the next read is clean.
        if (active && startTime > 0 && (now - startTime) >= BreqkPrefs.FREE_BREAK_DURATION_MS) {
            Log.i(FREE_BREAK_TAG, "[FREE_BREAK] getFreeBreakStatus: break expired (startTime=" + startTime + ") — auto-clearing");
            prefs.edit().putBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false).apply();
            active = false;
        }

        if (active && startTime > 0) {
            remainingMs = Math.max(0, (startTime + BreqkPrefs.FREE_BREAK_DURATION_MS) - now);
        }

        String todayDate = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(new java.util.Date());
        boolean usedToday = todayDate.equals(lastUsedDate);

        WritableMap map = Arguments.createMap();
        map.putBoolean("enabled", enabled);
        map.putBoolean("active", active);
        map.putDouble("startTimeMs", (double) startTime);
        map.putDouble("durationMs", (double) BreqkPrefs.FREE_BREAK_DURATION_MS);
        map.putDouble("remainingMs", (double) remainingMs);
        map.putBoolean("usedToday", usedToday);

        Log.d(FREE_BREAK_TAG, "[FREE_BREAK] getFreeBreakStatus: enabled=" + enabled
            + " active=" + active + " remainingMs=" + remainingMs + " usedToday=" + usedToday);
        promise.resolve(map);
    } catch (Exception e) {
        Log.e(FREE_BREAK_TAG, "[FREE_BREAK] getFreeBreakStatus error: " + e.getMessage(), e);
        promise.reject("ERROR", e.getMessage());
    }
}

/**
 * Internal helper: clears the free break active flag and notifies MyVpnService.
 * Called by both the auto-end timer and the user-initiated endFreeBreak().
 */
private void endFreeBreakInternal() {
    // Cancel any pending auto-end timer
    if (freeBreakEndRunnable != null) {
        freeBreakHandler.removeCallbacks(freeBreakEndRunnable);
        freeBreakEndRunnable = null;
    }
    BreqkPrefs.get(reactContext).edit()
        .putBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false)
        .apply();
    Log.i(FREE_BREAK_TAG, "[FREE_BREAK] Break ended — free_break_active=false");

    // Notify MyVpnService
    Intent breakEndIntent = new Intent(reactContext, MyVpnService.class);
    breakEndIntent.setAction("com.breqk.FREE_BREAK_END");
    ServiceHelper.startForegroundServiceCompat(reactContext, breakEndIntent);
}
```

---

### 4. `MyVpnService.java` — Handle FREE_BREAK_START / END intents

Location: `android/app/src/main/java/com/breqk/MyVpnService.java`

In `onStartCommand()`, in the existing intent action `switch` / `if-else` chain, add two new cases:

```java
} else if ("com.breqk.FREE_BREAK_START".equals(action)) {
    Log.i(TAG, "[FREE_BREAK] FREE_BREAK_START received — budget accumulation paused");
    // SharedPreferences already has free_break_active=true (written by VPNModule).
    // No additional in-memory state needed; AppUsageMonitor reads prefs directly.

} else if ("com.breqk.FREE_BREAK_END".equals(action)) {
    Log.i(TAG, "[FREE_BREAK] FREE_BREAK_END received — budget accumulation resumed");
    // SharedPreferences already has free_break_active=false (written by VPNModule).
    // AppUsageMonitor will resume normal behavior on its next poll tick.
}
```

Note: `AppUsageMonitor` reads SharedPreferences on every tick, so no in-memory flag is needed on the service side — the prefs flag is sufficient.

---

### 5. `AppUsageMonitor.java` — Skip budget accumulation during free break

Location: `android/app/src/main/java/com/breqk/AppUsageMonitor.java`

Find the method that accumulates the scroll budget (the 1s polling loop method — look for `PREF_SCROLL_TIME_USED_MS` or `scroll_time_used_ms`). At the very start of that budget-accumulation step, add the free break guard:

```java
// [FREE_BREAK] Skip budget accumulation entirely when a free break is active.
// The user has one free 20-minute window per day where Reels/Shorts time
// does not count against their budget. VPNModule writes free_break_active to prefs.
if (isFreeBreakActive()) {
    Log.d(TAG, "[FREE_BREAK] AppUsageMonitor: free break active — skipping budget accumulation");
    return; // or continue; depending on loop structure
}
```

Add the helper method to `AppUsageMonitor`:

```java
/**
 * Returns true if a free break is currently active and hasn't expired.
 * Reads directly from SharedPreferences (no in-memory state to sync).
 * Includes a time-elapsed sanity check: if the 20-minute window has
 * passed but the flag wasn't cleared, this method auto-clears it.
 */
private boolean isFreeBreakActive() {
    SharedPreferences prefs = BreqkPrefs.get(context);
    boolean active = prefs.getBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false);
    if (!active) return false;

    long startTime = prefs.getLong(BreqkPrefs.KEY_FREE_BREAK_START_TIME, 0);
    long now = System.currentTimeMillis();
    if (startTime > 0 && (now - startTime) >= BreqkPrefs.FREE_BREAK_DURATION_MS) {
        // Break expired — auto-clear so we don't permanently suppress the budget
        prefs.edit().putBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false).apply();
        Log.i(TAG, "[FREE_BREAK] AppUsageMonitor: break expired (startTime=" + startTime + ") — auto-cleared");
        return false;
    }
    return true;
}
```

---

### 6. `ReelsInterventionService.java` — Bypass all intervention logic during free break

Location: `android/app/src/main/java/com/breqk/ReelsInterventionService.java`

#### 6a. Add `isFreeBreakActive()` helper

Add this private method near the other helper methods:

```java
/**
 * Returns true if a free break is currently active and hasn't expired.
 * Reads from SharedPreferences. Includes stale-flag auto-cleanup.
 *
 * Called before ANY budget check or accumulation to give the user their
 * uninterrupted 20-minute scroll window.
 *
 * Log tag: adb logcat -s REELS_WATCH | grep FREE_BREAK
 */
private boolean isFreeBreakActive() {
    SharedPreferences prefs = BreqkPrefs.get(this);
    boolean active = prefs.getBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false);
    if (!active) return false;

    long startTime = prefs.getLong(BreqkPrefs.KEY_FREE_BREAK_START_TIME, 0);
    long now = System.currentTimeMillis();
    if (startTime > 0 && (now - startTime) >= BreqkPrefs.FREE_BREAK_DURATION_MS) {
        prefs.edit().putBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false).apply();
        Log.i(TAG, "[FREE_BREAK] ReelsInterventionService: break expired — auto-cleared");
        return false;
    }
    Log.d(TAG, "[FREE_BREAK] Free break active — elapsed=" + (now - startTime) + "ms remaining=" +
        (BreqkPrefs.FREE_BREAK_DURATION_MS - (now - startTime)) + "ms");
    return true;
}
```

#### 6b. Guard `handleReelsScrollEvent()` — skip budget check and accumulation

In `handleReelsScrollEvent()`, right after `wasInReelsLayout = true;` and BEFORE the debounce check, add:

```java
// [FREE_BREAK] If a free break is active, allow all scrolling without any
// intervention or budget accumulation. The heartbeat will also skip accumulation.
if (isFreeBreakActive()) {
    Log.d(TAG, "SCROLL_DECISION pkg=" + packageName
        + " action=FREE_BREAK_ALLOW (break active, skipping all budget/intervention logic)");
    return;
}
```

#### 6c. Guard `accumulateScrollBudget()` — skip budget addition

In the `accumulateScrollBudget()` method, at the very top:

```java
// [FREE_BREAK] Do not accumulate scroll budget during an active free break.
if (isFreeBreakActive()) {
    Log.d(TAG, "[FREE_BREAK] accumulateScrollBudget: skipping — free break active");
    return;
}
```

#### 6d. Guard `isScrollBudgetExhausted()` — always return false during break

At the very start of `isScrollBudgetExhausted(long now)`:

```java
// [FREE_BREAK] During an active free break, the budget is never exhausted —
// the user has unrestricted access.
if (isFreeBreakActive()) {
    Log.d(TAG, "[BUDGET] [FREE_BREAK] Budget check skipped — free break active → returning not exhausted");
    return false;
}
```

#### 6e. Guard the heartbeat runnable — skip budget accumulation ticks

The `reelsHeartbeatRunnable` calls `accumulateScrollBudget()` on every 2s tick. Since step 6c already guards that method, no additional change is needed — the guard inside `accumulateScrollBudget()` is sufficient. But add a log line to the heartbeat to confirm it's visible:

In the heartbeat runnable, after the `isStillInReels()` check, log the free break state for traceability:

```java
if (isFreeBreakActive()) {
    Log.d(TAG, "[FREE_BREAK] Heartbeat tick: free break active — budget accumulation skipped this tick");
    // Re-schedule and return early (still keep heartbeat running to maintain is_in_reels state)
    mainHandler.postDelayed(reelsHeartbeatRunnable, REELS_HEARTBEAT_INTERVAL_MS);
    return;
}
```

Note: The heartbeat should **keep running** during the break (to maintain `is_in_reels` state), it just shouldn't accumulate budget or trigger interventions.

---

### 7. `customize.js` — Free break toggle in settings

Location: `components/Customize/customize.js`

#### 7a. Add state variable

After the `reelsDetection` state declaration:

```js
// "20-Min Free Break" — one daily 20-min window of unrestricted scrolling
const [freeBreakEnabled, setFreeBreakEnabled] = useState(false);
```

#### 7b. Load from SharedPreferences in the `useEffect` load block

In the existing `load()` async function, add `getFreeBreakEnabled` to the parallel `Promise.all`:

```js
const [monitoring, redirect, freeBreak] = await Promise.all([
    new Promise((resolve) => SettingsModule.getMonitoringEnabled((v) => resolve(v))),
    new Promise((resolve) => SettingsModule.getRedirectInstagramToBrowser((v) => resolve(v))),
    new Promise((resolve) => SettingsModule.getFreeBreakEnabled((v) => resolve(v))),
]);
setIsMonitoringEnabled(monitoring !== false);
setReelsDetection(redirect !== false);
setFreeBreakEnabled(freeBreak === true);
console.log('[Customize] settings loaded — monitoring:', monitoring, 'redirect:', redirect, 'freeBreak:', freeBreak);
```

#### 7c. Add toggle handler

After `handleReelsToggle`:

```js
const handleFreeBreakToggle = (value) => {
    console.log('[Customize] free break toggle →', value);
    setFreeBreakEnabled(value);
    SettingsModule.saveFreeBreakEnabled(value);
    showSaved();
};
```

#### 7d. Add UI below the Reels Detection toggle row

In the JSX, inside the "Intervention Modes" section `<View>`, after the Reels Detection `<Switch>` row and its closing `</View>`:

```jsx
{/* Divider before free break toggle — only shown when Reels Detection is on */}
{reelsDetection && (
    <>
        <View style={styles.divider} />

        {/* Toggle: 20-Min Free Break */}
        <View style={styles.toggleRow}>
            <View style={{ flex: 1, marginRight: 12 }}>
                <Text style={styles.toggleLabel}>20-Min Free Break</Text>
                <Text style={styles.toggleCaption}>
                    Once per day, tap the Home button to scroll for 20 minutes
                    without any interruptions or budget counting.
                </Text>
            </View>
            <Switch
                value={freeBreakEnabled}
                onValueChange={handleFreeBreakToggle}
                trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                thumbColor="#FFFFFF"
                ios_backgroundColor="#D6D6D6"
                accessibilityLabel="20-Minute Free Break toggle"
            />
        </View>
    </>
)}
```

#### 7e. Add `toggleCaption` style to the StyleSheet

```js
toggleCaption: {
    fontSize: 12,
    color: L.muted,
    marginTop: 2,
    lineHeight: 17,
},
```

---

### 8. `home.js` — Free break button on Home screen

Location: `components/Home/home.js`

#### 8a. Add state variables

After the `budgetStatus` state declarations:

```js
// ── Free break state ──────────────────────────────────────────────────────
// freeBreakStatus = { enabled, active, startTimeMs, durationMs, remainingMs, usedToday }
const [freeBreakStatus, setFreeBreakStatus] = useState(null);
```

#### 8b. Add useEffect to load and poll free break status

After the scroll budget `useEffect`:

```js
// Poll free break status every 5s (sub-second polling unnecessary — 5s countdown
// is fine for a 20-minute timer). Also refresh when app returns to foreground.
useEffect(() => {
    const appStateRefBreak = { current: AppState.currentState };

    const pollFreeBreak = async () => {
        try {
            const status = await VPNModule.getFreeBreakStatus();
            setFreeBreakStatus(status);
            if (status.active) {
                console.log('[Home] free break active — remainingMs=' + status.remainingMs);
            }
        } catch (e) {
            console.warn('[Home] getFreeBreakStatus failed:', e);
        }
    };

    pollFreeBreak(); // initial fetch
    const interval = setInterval(pollFreeBreak, 5000);

    const sub = AppState.addEventListener('change', (nextState) => {
        if (appStateRefBreak.current.match(/inactive|background/) && nextState === 'active') {
            pollFreeBreak();
        }
        appStateRefBreak.current = nextState;
    });

    return () => {
        clearInterval(interval);
        sub?.remove();
    };
}, []);
```

#### 8c. Add handlers

After the `debouncedRestart` callback:

```js
// ── Free break handlers ───────────────────────────────────────────────────

const handleStartFreeBreak = async () => {
    console.log('[Home] starting 20-min free break');
    try {
        await VPNModule.startFreeBreak();
        const status = await VPNModule.getFreeBreakStatus();
        setFreeBreakStatus(status);
        console.log('[Home] free break started — remainingMs=' + status.remainingMs);
    } catch (e) {
        console.error('[Home] startFreeBreak failed:', e);
    }
};

const handleEndFreeBreak = async () => {
    console.log('[Home] ending free break early');
    try {
        await VPNModule.endFreeBreak();
        const status = await VPNModule.getFreeBreakStatus();
        setFreeBreakStatus(status);
        console.log('[Home] free break ended early');
    } catch (e) {
        console.error('[Home] endFreeBreak failed:', e);
    }
};
```

#### 8d. Add UI card in the ScrollView content

Location: Between the Scroll Budget card (`{budgetStatus && ...}`) and the Top Apps section. This ensures it's visible without scrolling on most screens.

```jsx
{/* ── Free Break card / button ──────────────────────────────── */}
{/* Only shown when the 20-Min Free Break feature toggle is ON in settings */}
{freeBreakStatus?.enabled && (() => {
    const { active, usedToday, remainingMs } = freeBreakStatus;

    if (active) {
        // Active break: green card with live countdown + early-end option
        return (
            <View style={styles.freeBreakCard}>
                <View style={styles.freeBreakCardHeader}>
                    <Text style={styles.freeBreakCardTitle}>Free Break Active</Text>
                    <View style={[styles.budgetDot, { backgroundColor: '#4CAF50' }]} />
                </View>
                <Text style={styles.freeBreakCountdown}>
                    {formatBudgetTime(remainingMs)} remaining
                </Text>
                <Text style={styles.freeBreakSubtext}>
                    Scroll freely — no interruptions until the timer ends.
                </Text>
                <TouchableOpacity
                    style={styles.freeBreakEndButton}
                    onPress={handleEndFreeBreak}
                    activeOpacity={0.75}
                    accessibilityRole="button"
                    accessibilityLabel="End free break early"
                >
                    <Text style={styles.freeBreakEndButtonText}>End Break Early</Text>
                </TouchableOpacity>
            </View>
        );
    }

    if (usedToday) {
        // Already used today: disabled pill
        return (
            <TouchableOpacity
                style={[styles.freeBreakButton, styles.freeBreakButtonDisabled]}
                disabled={true}
                activeOpacity={1}
                accessibilityRole="button"
                accessibilityLabel="Free break already used today"
                accessibilityState={{ disabled: true }}
            >
                <Text style={styles.freeBreakButtonTextDisabled}>
                    Free Break Used Today
                </Text>
            </TouchableOpacity>
        );
    }

    // Available: primary action pill
    return (
        <TouchableOpacity
            style={styles.freeBreakButton}
            onPress={handleStartFreeBreak}
            activeOpacity={0.85}
            accessibilityRole="button"
            accessibilityLabel="Start 20-minute free break"
        >
            <Text style={styles.freeBreakButtonText}>Start 20-Min Free Break</Text>
        </TouchableOpacity>
    );
})()}
```

#### 8e. Add styles

Add to the `StyleSheet.create({})` block (after the budget styles, before the footer styles):

```js
// ── Free Break ──────────────────────────────────────────────────────────
freeBreakCard: {
    backgroundColor: '#F0FAF1',          // very light green tint
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#A5D6A7',              // green border
    padding: 16,
    gap: 8,
},
freeBreakCardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
},
freeBreakCardTitle: {
    fontSize: 11,
    fontWeight: '600',
    color: '#2E7D32',                    // dark green
    textTransform: 'uppercase',
    letterSpacing: 1.2,
},
freeBreakCountdown: {
    fontSize: 26,
    fontWeight: '300',
    color: '#1A1A1A',
    fontVariant: ['tabular-nums'],
    letterSpacing: -0.5,
},
freeBreakSubtext: {
    fontSize: 12,
    color: '#555555',
    lineHeight: 17,
},
freeBreakEndButton: {
    marginTop: 4,
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderRadius: 9999,
    borderWidth: 1,
    borderColor: '#A5D6A7',
    alignSelf: 'flex-start',
},
freeBreakEndButtonText: {
    fontSize: 13,
    fontWeight: '500',
    color: '#2E7D32',
},
freeBreakButton: {
    backgroundColor: '#1A1A1A',
    borderRadius: 9999,
    paddingVertical: 16,
    paddingHorizontal: 32,
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 2,
},
freeBreakButtonText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '500',
},
freeBreakButtonDisabled: {
    backgroundColor: 'rgba(0,0,0,0.08)',
    shadowOpacity: 0,
    elevation: 0,
},
freeBreakButtonTextDisabled: {
    color: 'rgba(0,0,0,0.35)',
    fontSize: 16,
    fontWeight: '500',
},
```

---

### 9. `LOGGING.md` — Update log dictionary

Add a new section for free break logs:

```markdown
## Free Break

Tag: `REELS_WATCH` (ReelsInterventionService), `VPNModule:FreeBreak` (VPNModule), `AppUsageMonitor` (AppUsageMonitor)
Prefix in message: `[FREE_BREAK]`

### Filter commands

```bash
# All free break events across all sources
adb logcat | grep "\[FREE_BREAK\]"

# Only ReelsInterventionService free break events
adb logcat -s REELS_WATCH | grep FREE_BREAK

# VPNModule free break start/end events
adb logcat -s VPNModule:FreeBreak

# Scroll decisions that were allowed due to free break
adb logcat -s REELS_WATCH | grep FREE_BREAK_ALLOW
```

### Key log lines

| Log line | Meaning |
|----------|---------|
| `[FREE_BREAK] Break started at <ts>` | User tapped "Start 20-Min Free Break" |
| `[FREE_BREAK] Break ended` | Break ended (timer or early end) |
| `[FREE_BREAK] break expired — auto-cleared` | Stale flag cleanup on service startup |
| `SCROLL_DECISION ... action=FREE_BREAK_ALLOW` | Scroll event bypassed due to active break |
| `[FREE_BREAK] accumulateScrollBudget: skipping` | Budget accumulation skipped during break |
| `[FREE_BREAK] Budget check skipped — free break active` | isScrollBudgetExhausted returned false |
```

---

## Implementation Order (critical-path sequence)

1. **`BreqkPrefs.java`** — Add constants first (all other files depend on them)
2. **`SettingsModule.java`** — Add get/save for the feature toggle
3. **`VPNModule.java`** — Add startFreeBreak / endFreeBreak / getFreeBreakStatus + endFreeBreakInternal
4. **`MyVpnService.java`** — Handle the two new intent actions
5. **`AppUsageMonitor.java`** — Add `isFreeBreakActive()` + guard in budget accumulation
6. **`ReelsInterventionService.java`** — Add `isFreeBreakActive()` + three guards
7. **`customize.js`** — Add state + handler + toggle UI
8. **`home.js`** — Add state + handlers + card/button UI
9. **`LOGGING.md`** — Update dictionary

---

## Edge Cases & How They're Handled

| Edge Case | Handling |
|-----------|----------|
| App killed while break is active | `isFreeBreakActive()` checks `now - startTime > 20min` on every call and auto-clears the stale flag |
| Device rebooted during break | Same as above — `startTime` persists in SharedPreferences; stale flag cleared on next read |
| User tries to start break when no Reels Detection | The button is only shown when `freeBreakStatus.enabled === true`, which requires `freeBreakEnabled` to be saved — the toggle only appears when `reelsDetection` is on in Customize |
| Break started but ReelsInterventionService is not running | Not a problem — when the service restarts, it reads `free_break_active` from SharedPreferences and the elapsed-time check handles expiry |
| Two calls to `startFreeBreak()` | Server-side guard: promise rejects with `ALREADY_ACTIVE` if `free_break_active=true` |
| User changes device date/time | The `usedToday` check uses the current system date — if the user manipulates the clock, they could potentially reset the daily limit. Acceptable tradeoff for a wellness app; not worth hardening against clock manipulation. |
| Budget window reset happens during break | The window timer runs independently. When the break ends, the user resumes with their full budget for the current window (since nothing was accumulated during the break). If the window expired during the break, `isScrollBudgetExhausted()` already returns false (window-expired path), so behavior is correct either way. |

---

## SESSION_ID (for /ccg:execute use)
- CODEX_SESSION: N/A (plan generated by Claude directly from codebase context)
- GEMINI_SESSION: N/A

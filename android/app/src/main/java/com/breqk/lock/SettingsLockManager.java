package com.Break.lock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.Break.prefs.BreakPrefs;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * SettingsLockManager
 * -------------------
 * Implements the opt-in "Settings Change Lock".
 *
 * Model (deliberately simple — ONE timestamp per scope, no pending queue):
 *   - A SCOPE is either {@code "global"} (the Customize screen) or a managed app's
 *     package name (e.g. {@code "com.instagram.android"}).
 *   - {@link #startLock(Context, String)} stamps {@code lockUntil[scope] = now + duration}.
 *     The JS layer calls this when the user edits a scope and leaves the screen.
 *   - A scope is LOCKED iff {@code enabled && now < effectiveLockUntil(scope)}. While
 *     locked the scope's screen is read-only and shows a countdown.
 *   - Scopes are INDEPENDENT: locking "global" never affects a per-app scope, and
 *     vice-versa. One toggle ({@code enabled}) governs whether locking happens at all.
 *
 * Re-arm cycle (grace window):
 *   When a lock expires, a GRACE window (user-set, default 8h; 0 = "None") opens.
 *   If the user changes nothing before the grace ends, the lock RE-ARMS for the
 *   full duration, and the cycle repeats indefinitely from the same stamped base:
 *     stamped lockUntil → [grace][locked][grace][locked]…
 *   Editing during grace restarts the cycle via the normal startLock path (a new
 *   base timestamp). grace == 0 disables re-arming: once expired, the scope stays
 *   unlocked until the next edit (the original behavior). All of this is derived
 *   at READ TIME from the single stored timestamp — see {@link #computeCycle}.
 *
 * There is no AlarmManager / notification: the lock is a pure read-time check, so
 * expiry needs no wakeup — the next time the screen reads {@link #isLocked} after
 * the deadline, it is simply unlocked (or re-armed, per the cycle math).
 *
 * Threat model: resists IMPULSIVE in-app edits only. Like the rest of the app it
 * does not defend against {@code adb shell pm clear} or a forward clock change.
 * The feature is escapable on purpose (the user can disable it anytime), mirroring
 * the deletion-prevention toggle.
 *
 * Logging: SETTINGS_LOCK
 */
public final class SettingsLockManager {

    private static final String TAG = "SETTINGS_LOCK";

    /** Canonical scope id for the global Customize screen. */
    public static final String SCOPE_GLOBAL = "global";

    private SettingsLockManager() {}

    // ── Feature toggle ─────────────────────────────────────────────────────────

    /** Whether the settings-change lock feature is enabled (default false). */
    public static boolean isEnabled(Context context) {
        return BreakPrefs.get(context).getBoolean(BreakPrefs.KEY_SETTINGS_LOCK_ENABLED, false);
    }

    /** Enables or disables the feature. Disabling makes every scope editable again. */
    public static void setEnabled(Context context, boolean enabled) {
        BreakPrefs.get(context).edit()
                .putBoolean(BreakPrefs.KEY_SETTINGS_LOCK_ENABLED, enabled)
                .apply();
        Log.d(TAG, "setEnabled=" + enabled);
    }

    // ── Lock duration ──────────────────────────────────────────────────────────

    /** Current lock length in ms, clamped to [24h, 7d]. Defaults to 24h. */
    public static long getDurationMs(Context context) {
        long stored = BreakPrefs.get(context)
                .getLong(BreakPrefs.KEY_SETTINGS_LOCK_DURATION_MS, BreakPrefs.DEFAULT_SETTINGS_LOCK_MS);
        return clampDuration(stored);
    }

    /** Persists the lock length (clamped). Takes effect on the NEXT lock that starts. */
    public static void setDurationMs(Context context, long ms) {
        long clamped = clampDuration(ms);
        BreakPrefs.get(context).edit()
                .putLong(BreakPrefs.KEY_SETTINGS_LOCK_DURATION_MS, clamped)
                .apply();
        Log.d(TAG, "setDurationMs=" + clamped);
    }

    private static long clampDuration(long ms) {
        if (ms < BreakPrefs.MIN_SETTINGS_LOCK_MS) return BreakPrefs.MIN_SETTINGS_LOCK_MS;
        if (ms > BreakPrefs.MAX_SETTINGS_LOCK_MS) return BreakPrefs.MAX_SETTINGS_LOCK_MS;
        return ms;
    }

    // ── Grace window (re-arm cycle) ────────────────────────────────────────────

    /**
     * Grace window in ms, clamped to [0, 24h]. 0 means "None" — no auto re-arm.
     * Defaults to 8h.
     */
    public static long getGraceMs(Context context) {
        long stored = BreakPrefs.get(context)
                .getLong(BreakPrefs.KEY_SETTINGS_LOCK_GRACE_MS, BreakPrefs.DEFAULT_SETTINGS_LOCK_GRACE_MS);
        return clampGrace(stored);
    }

    /** Persists the grace window (clamped). Applies to cycle math immediately. */
    public static void setGraceMs(Context context, long ms) {
        long clamped = clampGrace(ms);
        BreakPrefs.get(context).edit()
                .putLong(BreakPrefs.KEY_SETTINGS_LOCK_GRACE_MS, clamped)
                .apply();
        Log.d(TAG, "setGraceMs=" + clamped);
    }

    private static long clampGrace(long ms) {
        if (ms < 0) return 0;
        if (ms > BreakPrefs.MAX_SETTINGS_LOCK_GRACE_MS) return BreakPrefs.MAX_SETTINGS_LOCK_GRACE_MS;
        return ms;
    }

    // ── Re-arm cycle math ──────────────────────────────────────────────────────

    /**
     * Snapshot of where a scope sits in its lock/grace cycle at one instant.
     * Derived purely from the stamped base timestamp — nothing here is stored.
     */
    public static final class CycleState {
        /** Scope is currently read-only. */
        public final boolean locked;
        /** Epoch-ms when the current lock segment ends (0 when not locked). */
        public final long lockUntil;
        /** Scope is inside a grace window that will re-arm when it ends. */
        public final boolean inGrace;
        /** Epoch-ms when the grace window ends and the lock re-arms (0 if n/a). */
        public final long graceEndsAt;

        CycleState(boolean locked, long lockUntil, boolean inGrace, long graceEndsAt) {
            this.locked = locked;
            this.lockUntil = lockUntil;
            this.inGrace = inGrace;
            this.graceEndsAt = graceEndsAt;
        }
    }

    /**
     * Pure cycle derivation. The cycle anchors on {@code base} (the stamped
     * lockUntil = end of the FIRST lock segment) and then repeats
     * [grace][duration][grace][duration]… forever, so the state at any instant is
     * a modulo computation — the app can be closed for a week and still land in
     * the right segment.
     *
     * @param now        current epoch ms
     * @param base       stamped lockUntil for the scope (0 = never locked)
     * @param durationMs lock segment length (> 0)
     * @param graceMs    grace segment length (0 = no re-arm)
     */
    public static CycleState computeCycle(long now, long base, long durationMs, long graceMs) {
        if (base <= 0) {
            // Never locked: no cycle to derive.
            return new CycleState(false, 0L, false, 0L);
        }
        if (now < base) {
            // Inside the first (stamped) lock segment.
            return new CycleState(true, base, false, 0L);
        }
        if (graceMs <= 0) {
            // Re-arm disabled: expired means unlocked until the next edit.
            return new CycleState(false, 0L, false, 0L);
        }
        long cycle = graceMs + durationMs;
        long elapsed = now - base;
        long k = elapsed / cycle; // completed full cycles since the base expired
        long pos = elapsed % cycle; // position inside the current cycle
        long cycleStart = base + k * cycle;
        if (pos < graceMs) {
            // Grace segment: editable, but re-arms at graceEndsAt.
            return new CycleState(false, 0L, true, cycleStart + graceMs);
        }
        // Re-armed lock segment.
        return new CycleState(true, cycleStart + cycle, false, 0L);
    }

    /** Cycle state for {@code scope} right now (feature toggle NOT considered). */
    public static CycleState getCycleState(Context context, String scope) {
        return computeCycle(
                System.currentTimeMillis(),
                getLockUntil(context, scope),
                getDurationMs(context),
                getGraceMs(context));
    }

    // ── Per-scope lock state ────────────────────────────────────────────────────

    /** Epoch-ms instant when {@code scope} unlocks, or 0 if it has no active lock. */
    public static long getLockUntil(Context context, String scope) {
        try {
            JSONObject map = readMap(context);
            return map.optLong(scope, 0L);
        } catch (JSONException e) {
            Log.w(TAG, "getLockUntil parse failed: " + e.getMessage());
            return 0L;
        }
    }

    /**
     * True iff the feature is on AND {@code scope} is within a lock segment
     * (the first stamped lock OR a re-armed one from the grace cycle).
     */
    public static boolean isLocked(Context context, String scope) {
        if (!isEnabled(context)) return false;
        return getCycleState(context, scope).locked;
    }

    /**
     * True iff the feature is on AND ANY scope (global or any app) is still locked
     * (including re-armed lock segments). Used to keep the feature toggle itself
     * read-only while a lock is active, so the user can't disable the feature to
     * shortcut a wait on any screen.
     */
    public static boolean isAnyLocked(Context context) {
        if (!isEnabled(context)) return false;
        long now = System.currentTimeMillis();
        long duration = getDurationMs(context);
        long grace = getGraceMs(context);
        try {
            JSONObject map = readMap(context);
            Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                long base = map.optLong(keys.next(), 0L);
                if (computeCycle(now, base, duration, grace).locked) return true;
            }
        } catch (JSONException e) {
            Log.w(TAG, "isAnyLocked parse failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Starts (or restarts) the lock for {@code scope}: lockUntil = now + duration.
     * No-op when the feature is disabled. The JS layer only calls this on leaving a
     * scope it actually edited.
     */
    public static void startLock(Context context, String scope) {
        if (!isEnabled(context)) {
            Log.d(TAG, "startLock skipped (feature off) scope=" + scope);
            return;
        }
        long until = System.currentTimeMillis() + getDurationMs(context);
        try {
            JSONObject map = readMap(context);
            map.put(scope, until);
            BreakPrefs.get(context).edit()
                    .putString(BreakPrefs.KEY_SETTINGS_LOCK_UNTIL, map.toString())
                    .apply();
            Log.d(TAG, "startLock scope=" + scope + " until=" + until);
        } catch (JSONException e) {
            Log.w(TAG, "startLock write failed: " + e.getMessage());
        }
    }

    /**
     * State for ONE scope as a flat JSON string for the bridge:
     * {@code {"enabled":bool,"locked":bool,"anyLocked":bool,"lockUntil":epochMs,
     *   "baseLockUntil":epochMs,"durationMs":ms,"graceMs":ms,"inGrace":bool,
     *   "graceEndsAt":epochMs}}.
     * {@code lockUntil} is the EFFECTIVE unlock instant for the current lock
     * segment (first or re-armed); {@code baseLockUntil} is the raw stamped value
     * so the JS layer can run the same cycle math for live ticking between reads.
     */
    public static String getStateJson(Context context, String scope) {
        boolean enabled = isEnabled(context);
        long base = getLockUntil(context, scope);
        CycleState cycle = getCycleState(context, scope);
        JSONObject out = new JSONObject();
        try {
            out.put("enabled", enabled);
            out.put("locked", enabled && cycle.locked);
            out.put("anyLocked", isAnyLocked(context));
            out.put("lockUntil", cycle.lockUntil);
            out.put("baseLockUntil", base);
            out.put("durationMs", getDurationMs(context));
            out.put("graceMs", getGraceMs(context));
            out.put("inGrace", enabled && cycle.inGrace);
            out.put("graceEndsAt", cycle.graceEndsAt);
        } catch (JSONException e) {
            Log.w(TAG, "getStateJson failed: " + e.getMessage());
        }
        return out.toString();
    }

    private static JSONObject readMap(Context context) throws JSONException {
        SharedPreferences prefs = BreakPrefs.get(context);
        String json = prefs.getString(BreakPrefs.KEY_SETTINGS_LOCK_UNTIL, "{}");
        return new JSONObject(json == null || json.isEmpty() ? "{}" : json);
    }
}

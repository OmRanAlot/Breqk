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
 *   - A scope is LOCKED iff {@code enabled && now < lockUntil[scope]}. While locked
 *     the scope's screen is read-only and shows a countdown.
 *   - Scopes are INDEPENDENT: locking "global" never affects a per-app scope, and
 *     vice-versa. One toggle ({@code enabled}) governs whether locking happens at all.
 *
 * There is no AlarmManager / notification: the lock is a pure read-time check, so
 * expiry needs no wakeup — the next time the screen reads {@link #isLocked} after
 * the deadline, it is simply unlocked.
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

    /** True iff the feature is on AND {@code scope} is still within its lock window. */
    public static boolean isLocked(Context context, String scope) {
        if (!isEnabled(context)) return false;
        return System.currentTimeMillis() < getLockUntil(context, scope);
    }

    /**
     * True iff the feature is on AND ANY scope (global or any app) is still locked.
     * Used to keep the feature toggle itself read-only while a lock is active, so the
     * user can't disable the feature to shortcut a wait on any screen.
     */
    public static boolean isAnyLocked(Context context) {
        if (!isEnabled(context)) return false;
        long now = System.currentTimeMillis();
        try {
            JSONObject map = readMap(context);
            Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                if (now < map.optLong(keys.next(), 0L)) return true;
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
     * {@code {"enabled":bool,"locked":bool,"lockUntil":epochMs,"durationMs":ms}}.
     */
    public static String getStateJson(Context context, String scope) {
        boolean enabled = isEnabled(context);
        long until = getLockUntil(context, scope);
        boolean locked = enabled && System.currentTimeMillis() < until;
        JSONObject out = new JSONObject();
        try {
            out.put("enabled", enabled);
            out.put("locked", locked);
            out.put("anyLocked", isAnyLocked(context));
            out.put("lockUntil", until);
            out.put("durationMs", getDurationMs(context));
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

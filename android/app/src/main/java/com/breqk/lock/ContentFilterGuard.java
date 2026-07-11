package com.Break.lock;

import android.content.Context;
import android.util.Log;

import com.Break.prefs.BreakPrefs;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * ContentFilterGuard
 * ------------------
 * Opt-in "double-safe" disable for the browser content filter.
 *
 * When the guard is ON, turning the content filter off is a TWO-step commitment:
 *
 *   PROTECTED ──requestDisable()──▶ PENDING_WAIT ──(lock duration elapses)──▶
 *   CONFIRM_WINDOW ──confirmDisable()──▶ DISABLED
 *
 *   - PENDING_WAIT: the filter STAYS ACTIVE. The wait equals the settings-lock
 *     duration (default 24h) captured at request time as {@code readyAt}.
 *   - CONFIRM_WINDOW: the user may confirm the final disable. Its length equals
 *     the settings-lock grace window, or {@link BreakPrefs#CF_INTERNAL_CONFIRM_WINDOW_MS}
 *     (4h) when grace is "None" (0).
 *   - If the confirm window passes untouched, the pending disable is DISCARDED
 *     (lazily, on the next read) and the filter returns to PROTECTED — the first
 *     barrier re-instates itself automatically.
 *   - cancelDisable() clears a pending disable at any point.
 *   - Re-ENABLING the filter is always instant and also clears any pending state.
 *
 * Like SettingsLockManager, everything is a pure read-time derivation from two
 * stored timestamps — no AlarmManager. The filter service keeps reading
 * {@code content_filter_enabled} untouched: that flag only flips on the final
 * confirm, so BrowserBarContentFilter needs no knowledge of this class.
 *
 * Threat model: resists impulsive in-app disables only (same as the settings
 * lock). Not hardened against pm clear or clock changes.
 *
 * Logging: CF_GUARD
 */
public final class ContentFilterGuard {

    private static final String TAG = "CF_GUARD";

    // State names shared with the JS layer (see useContentFilterGuard.js).
    public static final String STATE_OFF_GUARD = "GUARD_OFF"; // double-safe not enabled
    public static final String STATE_PROTECTED = "PROTECTED";
    public static final String STATE_PENDING_WAIT = "PENDING_WAIT";
    public static final String STATE_CONFIRM_WINDOW = "CONFIRM_WINDOW";
    public static final String STATE_DISABLED = "DISABLED"; // filter itself is off

    private ContentFilterGuard() {}

    // ── Feature toggle ─────────────────────────────────────────────────────────

    /** Whether the double-safe guard is enabled (default false). */
    public static boolean isDoubleSafeEnabled(Context context) {
        return BreakPrefs.get(context).getBoolean(BreakPrefs.KEY_CF_DOUBLE_SAFE_ENABLED, false);
    }

    /**
     * Enables/disables the guard. Disabling is REFUSED while a pending disable is
     * in flight — otherwise flipping this toggle off would shortcut the wait.
     *
     * @return true if the write happened, false if refused.
     */
    public static boolean setDoubleSafeEnabled(Context context, boolean enabled) {
        if (!enabled && getPendingDisableAt(context) > 0) {
            Log.w(TAG, "setDoubleSafeEnabled(false) refused — pending disable in flight");
            return false;
        }
        BreakPrefs.get(context).edit()
                .putBoolean(BreakPrefs.KEY_CF_DOUBLE_SAFE_ENABLED, enabled)
                .apply();
        Log.d(TAG, "setDoubleSafeEnabled=" + enabled);
        return true;
    }

    // ── Pending-disable timestamps ─────────────────────────────────────────────

    private static long getPendingDisableAt(Context context) {
        return BreakPrefs.get(context).getLong(BreakPrefs.KEY_CF_PENDING_DISABLE_AT, 0L);
    }

    private static long getPendingReadyAt(Context context) {
        return BreakPrefs.get(context).getLong(BreakPrefs.KEY_CF_PENDING_READY_AT, 0L);
    }

    private static void clearPending(Context context, String reason) {
        BreakPrefs.get(context).edit()
                .putLong(BreakPrefs.KEY_CF_PENDING_DISABLE_AT, 0L)
                .putLong(BreakPrefs.KEY_CF_PENDING_READY_AT, 0L)
                .apply();
        Log.d(TAG, "pending disable cleared (" + reason + ")");
    }

    /**
     * Confirm-window length right now: the settings-lock grace window, or the 4h
     * internal fallback when grace is "None".
     */
    public static long getConfirmWindowMs(Context context) {
        long grace = SettingsLockManager.getGraceMs(context);
        return grace > 0 ? grace : BreakPrefs.CF_INTERNAL_CONFIRM_WINDOW_MS;
    }

    // ── State machine ──────────────────────────────────────────────────────────

    /**
     * Current state name. Lazily discards an expired pending disable (the
     * automatic "re-instill the first barrier" behavior).
     */
    public static String getState(Context context) {
        if (!BreakPrefs.isContentFilterEnabled(context)) return STATE_DISABLED;
        if (!isDoubleSafeEnabled(context)) return STATE_OFF_GUARD;
        long pendingAt = getPendingDisableAt(context);
        if (pendingAt <= 0) return STATE_PROTECTED;
        long now = System.currentTimeMillis();
        long readyAt = getPendingReadyAt(context);
        if (now < readyAt) return STATE_PENDING_WAIT;
        if (now < readyAt + getConfirmWindowMs(context)) return STATE_CONFIRM_WINDOW;
        // Confirm window expired untouched → auto re-instate the first barrier.
        clearPending(context, "confirm window expired — barrier re-instated");
        return STATE_PROTECTED;
    }

    /**
     * Step 1 of the double-safe disable. Only valid from PROTECTED. Stamps
     * readyAt = now + settings-lock duration (captured now, so a later duration
     * change never shifts an in-flight wait).
     *
     * @return true if the request was accepted.
     */
    public static boolean requestDisable(Context context) {
        String state = getState(context);
        if (!STATE_PROTECTED.equals(state)) {
            Log.w(TAG, "requestDisable refused — state=" + state);
            return false;
        }
        long now = System.currentTimeMillis();
        long readyAt = now + SettingsLockManager.getDurationMs(context);
        BreakPrefs.get(context).edit()
                .putLong(BreakPrefs.KEY_CF_PENDING_DISABLE_AT, now)
                .putLong(BreakPrefs.KEY_CF_PENDING_READY_AT, readyAt)
                .apply();
        Log.d(TAG, "requestDisable accepted — readyAt=" + readyAt);
        return true;
    }

    /**
     * Step 2 of the double-safe disable. Only valid inside CONFIRM_WINDOW.
     * Actually flips {@code content_filter_enabled} off.
     *
     * @return true if the filter was disabled.
     */
    public static boolean confirmDisable(Context context) {
        String state = getState(context);
        if (!STATE_CONFIRM_WINDOW.equals(state)) {
            Log.w(TAG, "confirmDisable refused — state=" + state);
            return false;
        }
        clearPending(context, "disable confirmed");
        BreakPrefs.setContentFilterEnabled(context, false);
        Log.d(TAG, "content filter DISABLED via double-safe confirm");
        return true;
    }

    /** Cancels an in-flight pending disable. Always allowed, always instant. */
    public static void cancelDisable(Context context) {
        clearPending(context, "cancelled by user");
    }

    /** Re-enabling the filter is instant; also discards any pending disable. */
    public static void onFilterEnabled(Context context) {
        if (getPendingDisableAt(context) > 0) {
            clearPending(context, "filter re-enabled");
        }
    }

    /**
     * Whether a DIRECT disable (the plain switch) is allowed. False while the
     * guard is on — the two-step flow is then the only way to turn the filter off.
     */
    public static boolean isDirectDisableAllowed(Context context) {
        return !isDoubleSafeEnabled(context);
    }

    /**
     * Full guard state as a flat JSON string for the bridge:
     * {@code {"doubleSafeEnabled":bool,"filterEnabled":bool,"state":str,
     *   "pendingDisableAt":epochMs,"readyAt":epochMs,"confirmWindowMs":ms,
     *   "confirmEndsAt":epochMs,"now":epochMs}}.
     * {@code now} lets the JS layer offset device-clock drift when ticking.
     */
    public static String getStateJson(Context context) {
        String state = getState(context); // may lazily clear expired pending
        long pendingAt = getPendingDisableAt(context);
        long readyAt = getPendingReadyAt(context);
        long confirmWindowMs = getConfirmWindowMs(context);
        JSONObject out = new JSONObject();
        try {
            out.put("doubleSafeEnabled", isDoubleSafeEnabled(context));
            out.put("filterEnabled", BreakPrefs.isContentFilterEnabled(context));
            out.put("state", state);
            out.put("pendingDisableAt", pendingAt);
            out.put("readyAt", readyAt);
            out.put("confirmWindowMs", confirmWindowMs);
            out.put("confirmEndsAt", readyAt > 0 ? readyAt + confirmWindowMs : 0L);
            out.put("now", System.currentTimeMillis());
        } catch (JSONException e) {
            Log.w(TAG, "getStateJson failed: " + e.getMessage());
        }
        return out.toString();
    }
}

package com.breqk.reels.budget;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.breqk.BreqkPrefs;

public class BudgetState {
    private static final String TAG = "REELS_WATCH";

    private static final String PREF_SCROLL_ALLOWANCE_MIN = "scroll_allowance_minutes";
    private static final String PREF_SCROLL_WINDOW_MIN = "scroll_window_minutes";
    private static final String PREF_SCROLL_WINDOW_START = "scroll_window_start_time";
    private static final String PREF_SCROLL_TIME_USED_MS = "scroll_time_used_ms";
    private static final String PREF_SCROLL_EXHAUSTED_AT = "scroll_budget_exhausted_at";

    private final Context context;

    private int allowanceMinutes = 5;
    private int windowMinutes = 60;
    private long windowStartTime = 0;
    private long scrollTimeUsedMs = 0;
    private long exhaustedAt = 0;

    public BudgetState(Context context) {
        this.context = context;
    }

    public void load(SharedPreferences prefs) {
        allowanceMinutes = prefs.getInt(PREF_SCROLL_ALLOWANCE_MIN, 5);
        windowMinutes = prefs.getInt(PREF_SCROLL_WINDOW_MIN, 60);
        windowStartTime = prefs.getLong(PREF_SCROLL_WINDOW_START, 0);
        scrollTimeUsedMs = prefs.getLong(PREF_SCROLL_TIME_USED_MS, 0);
        exhaustedAt = prefs.getLong(PREF_SCROLL_EXHAUSTED_AT, 0);
    }

    public void tick(long intervalMs) {
        scrollTimeUsedMs += intervalMs;
    }

    public boolean isWindowExpired(long now) {
        long windowMs = windowMinutes * 60 * 1000L;
        return windowStartTime > 0 && (now - windowStartTime) >= windowMs;
    }

    public void resetWindow(long now) {
        windowStartTime = now;
        scrollTimeUsedMs = 0;
        exhaustedAt = 0;
    }

    public boolean isExhausted(long now) {
        if (isFreeBreakActive(now)) {
            Log.d(TAG, "[BUDGET] [FREE_BREAK] isScrollBudgetExhausted: free break active → returning false");
            return false;
        }

        if (exhaustedAt == 0) {
            return checkExhaustion(now);
        }

        if (isWindowExpired(now)) {
            Log.d(TAG, "[BUDGET] Window expired (windowStart=" + windowStartTime
                    + " windowMin=" + windowMinutes
                    + " elapsed=" + (now - windowStartTime) + "ms) → budget available (pending reset)");
            return false;
        }

        Log.d(TAG, "[BUDGET] Budget exhausted (exhaustedAt=" + exhaustedAt
                + " windowStart=" + windowStartTime
                + " windowMin=" + windowMinutes + ")");
        return true;
    }

    public boolean checkExhaustion(long now) {
        if (exhaustedAt > 0) return true;
        
        long allowanceMs = allowanceMinutes * 60 * 1000L;
        if (scrollTimeUsedMs >= allowanceMs) {
            exhaustedAt = now;
            Log.i(TAG, "[BUDGET] Scroll budget EXHAUSTED at " + exhaustedAt);
            return true;
        }
        return false;
    }

    public void flush(SharedPreferences prefs) {
        prefs.edit()
                .putLong(PREF_SCROLL_TIME_USED_MS, scrollTimeUsedMs)
                .putLong(PREF_SCROLL_WINDOW_START, windowStartTime)
                .putLong(PREF_SCROLL_EXHAUSTED_AT, exhaustedAt)
                .apply();
        Log.d(TAG, "[BUDGET] Flushed state to prefs. used=" + scrollTimeUsedMs + " exhaustedAt=" + exhaustedAt);
    }

    public boolean isFreeBreakActive(long now) {
        SharedPreferences prefs = BreqkPrefs.get(context);
        boolean active = prefs.getBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false);
        if (!active) return false;

        long startTime = prefs.getLong(BreqkPrefs.KEY_FREE_BREAK_START_TIME, 0);
        if (startTime > 0 && (now - startTime) >= BreqkPrefs.FREE_BREAK_DURATION_MS) {
            prefs.edit().putBoolean(BreqkPrefs.KEY_FREE_BREAK_ACTIVE, false).apply();
            Log.i(TAG, "[FREE_BREAK] isFreeBreakActive: break expired (startTime=" + startTime
                    + ") — auto-cleared, elapsed=" + (now - startTime) + "ms");
            return false;
        }

        long remainingMs = (startTime + BreqkPrefs.FREE_BREAK_DURATION_MS) - now;
        Log.d(TAG, "[FREE_BREAK] isFreeBreakActive: true — remainingMs=" + remainingMs);
        return true;
    }

    public long getWindowStartTime() {
        return windowStartTime;
    }
}

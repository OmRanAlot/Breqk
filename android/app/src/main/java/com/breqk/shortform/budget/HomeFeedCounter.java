package com.breqk.shortform.budget;

import android.content.SharedPreferences;
import android.util.Log;

import com.breqk.prefs.BreqkPrefs;

/**
 * HomeFeedCounter
 * ----------------
 * Tracks how many home-feed post swipes the user has made in the current
 * Instagram session. Count-based only â€” independent of BudgetState/time.
 *
 * State is in-memory: resets on service restart and on explicit reset() call.
 * The post limit is read live from SharedPreferences so the user can adjust
 * it in Customize without restarting anything.
 *
 * Log filter: adb logcat -s REELS_WATCH | findstr "HOME_FEED"
 */
public class HomeFeedCounter {

    private static final String TAG = "REELS_WATCH";

    private int count = 0;

    /**
     * Increments the count and returns true when the limit is reached.
     * Reads the limit fresh from prefs on each call so runtime changes take effect immediately.
     */
    public boolean increment(SharedPreferences prefs) {
        count++;
        int limit = getLimit(prefs);
        Log.d(TAG, "[HOME_FEED] count=" + count + " limit=" + limit);
        return count >= limit;
    }

    public void reset() {
        if (count > 0) {
            Log.d(TAG, "[HOME_FEED] counter reset (was " + count + ")");
        }
        count = 0;
    }

    public int getCount() {
        return count;
    }

    public int getLimit(SharedPreferences prefs) {
        return prefs.getInt(BreqkPrefs.KEY_HOME_FEED_POST_LIMIT, BreqkPrefs.DEFAULT_HOME_FEED_POST_LIMIT);
    }
}

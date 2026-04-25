package com.breqk.shortform.intervention;

import android.util.Log;

public class ShortFormStateMachine {
    private static final String TAG = "REELS_WATCH";
    private static final String PKG_YOUTUBE = "com.google.android.youtube";

    private boolean wasInReelsLayout = false;
    private boolean shortsCurrentlyDetected = false;
    private boolean inGracePeriod = false;

    public boolean isInReels() {
        return wasInReelsLayout;
    }

    public boolean isInGracePeriod() {
        return inGracePeriod;
    }

    /**
     * Called when the app detects the user has navigated into Reels/Shorts.
     * Starts the grace period.
     */
    public void enterReels(String packageName, String tier) {
        if (!wasInReelsLayout) {
            inGracePeriod = true;
            Log.d(TAG, "[GRACE] Entering Reels/Shorts (" + tier + ") — grace period started for " + packageName);
        }
        wasInReelsLayout = true;

        if (packageName.equals(PKG_YOUTUBE)) {
            notifyShortsState(true, tier);
        }
    }

    /**
     * Called when the app detects the user has explicitly scrolled within Reels/Shorts.
     * Ends the grace period on the *first* scroll.
     *
     * @return true if the grace period JUST ended, meaning heartbeat/budget should start.
     */
    public boolean processScroll(String packageName) {
        if (inGracePeriod) {
            inGracePeriod = false;
            Log.i(TAG, "[GRACE] First scroll detected — grace period ended for " + packageName);
            return true;
        }
        return false;
    }

    /**
     * Called when the user leaves the Reels/Shorts layout or the service handles a dismiss/reset.
     * Clears all state flags.
     */
    public void reset(String reason) {
        if (shortsCurrentlyDetected) {
            notifyShortsState(false, reason);
        }
        wasInReelsLayout = false;
        inGracePeriod = false;
    }

    /**
     * Emits the [SHORTS_ACTIVE] transition log specifically for YouTube Shorts.
     */
    private void notifyShortsState(boolean active, String tier) {
        if (active == shortsCurrentlyDetected) return;
        shortsCurrentlyDetected = active;
        if (active) {
            Log.i(TAG, "[SHORTS_ACTIVE] active=true  tier=" + tier + " pkg=" + PKG_YOUTUBE);
        } else {
            Log.i(TAG, "[SHORTS_ACTIVE] active=false reason=" + tier);
        }
    }
}

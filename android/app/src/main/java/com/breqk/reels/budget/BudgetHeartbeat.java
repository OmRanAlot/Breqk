package com.breqk.reels.budget;

import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

public class BudgetHeartbeat {
    private static final String TAG = "REELS_WATCH";
    public static final long REELS_HEARTBEAT_INTERVAL_MS = 1000;

    public interface HeartbeatCallback {
        boolean isStillInReels(String packageName);
        void onBudgetExhausted(String packageName);
        void onHeartbeatInvalid();
        void persistReelsState(boolean active, String packageName);
    }

    private final BudgetState budgetState;
    private final Handler mainHandler;
    private final HeartbeatCallback callback;
    private final SharedPreferences prefs;

    private Runnable runnable;
    private String currentPackage;
    private int tickCount = 0;

    public BudgetHeartbeat(BudgetState budgetState, Handler mainHandler, SharedPreferences prefs, HeartbeatCallback callback) {
        this.budgetState = budgetState;
        this.mainHandler = mainHandler;
        this.callback = callback;
        this.prefs = prefs;
    }

    public void start(String packageName) {
        stop();
        currentPackage = packageName;
        tickCount = 0;

        runnable = new Runnable() {
            @Override
            public void run() {
                if (!callback.isStillInReels(packageName)) {
                    Log.d(TAG, "[REELS_STATE] Heartbeat: user no longer in Reels — stopping heartbeat");
                    callback.onHeartbeatInvalid();
                    return;
                }

                callback.persistReelsState(true, packageName);

                long now = System.currentTimeMillis();

                if (budgetState.isFreeBreakActive(now)) {
                    Log.d(TAG, "[FREE_BREAK] accumulateScrollBudget: skipping — free break active");
                } else {
                    if (budgetState.isWindowExpired(now)) {
                        Log.i(TAG, "[BUDGET] Scroll window expired — resetting");
                        budgetState.resetWindow(now);
                    } else if (budgetState.getWindowStartTime() == 0) {
                        budgetState.resetWindow(now);
                        Log.d(TAG, "[BUDGET] New scroll window started");
                    }

                    budgetState.tick(REELS_HEARTBEAT_INTERVAL_MS);

                    if (budgetState.checkExhaustion(now)) {
                        callback.onBudgetExhausted(packageName);
                    }
                }

                // Flush every 4 seconds to keep prefs updated for AppUsageMonitor (which checks for 5s staleness)
                tickCount++;
                if (tickCount % 4 == 0) {
                    budgetState.flush(prefs);
                }

                mainHandler.postDelayed(this, REELS_HEARTBEAT_INTERVAL_MS);
            }
        };

        mainHandler.postDelayed(runnable, REELS_HEARTBEAT_INTERVAL_MS);
        Log.d(TAG, "[REELS_STATE] Heartbeat started (interval=" + REELS_HEARTBEAT_INTERVAL_MS + "ms) for " + packageName);
    }

    public void stop() {
        if (runnable != null) {
            mainHandler.removeCallbacks(runnable);
            runnable = null;
            budgetState.flush(prefs);
            Log.d(TAG, "[REELS_STATE] Heartbeat stopped");
        }
    }
}

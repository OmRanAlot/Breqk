package com.breqk;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * ServiceHelper
 * --------------
 * Compatibility helper for starting foreground services across API levels.
 * Replaces the repeated if(SDK >= O) pattern that appeared in 4+ locations.
 *
 * Used by: VPNModule, MainActivity
 *
 * Logging tag: ServiceHelper
 * Filter: adb logcat -s ServiceHelper
 * Prefix: [START_SERVICE]
 */
public final class ServiceHelper {

    private static final String TAG = "ServiceHelper";

    /**
     * Starts a service using startForegroundService on API 26+ or startService below.
     *
     * @param context Application or activity context
     * @param intent  Service intent with action set
     */
    public static void startForegroundServiceCompat(Context context, Intent intent) {
        String action = intent.getAction() != null ? intent.getAction() : "null";
        Log.d(TAG, "[START_SERVICE] Starting service action=" + action + " API=" + Build.VERSION.SDK_INT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
            Log.d(TAG, "[START_SERVICE] Used startForegroundService (API >= O)");
        } else {
            context.startService(intent);
            Log.d(TAG, "[START_SERVICE] Used startService (API < O)");
        }
    }

    // Prevent instantiation
    private ServiceHelper() {}
}

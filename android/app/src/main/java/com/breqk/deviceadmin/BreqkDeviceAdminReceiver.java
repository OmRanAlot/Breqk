package com.breqk.deviceadmin;

/*
 * BreqkDeviceAdminReceiver
 * ────────────────────────────────────────────────────────────────────────────
 * Device Administrator receiver for Breqk.
 *
 * Purpose:
 *   Registers Breqk as a Device Administrator so that uninstalling the app
 *   requires the user to first manually deactivate Device Admin. This adds
 *   deliberate friction against impulsive deletion.
 *
 * Key lifecycle events handled:
 *   onEnabled       — Device Admin activated by user
 *   onDisabled      — Device Admin deactivated (user confirmed removal)
 *   onDisableRequested — User initiated deactivation; return retention message
 *
 * DEV / TESTING (ADB bypass — works on debug builds):
 *   adb shell dpm remove-active-admin com.breqk/.BreqkDeviceAdminReceiver
 *   adb uninstall com.breqk
 *
 * Logcat tag: DEVICE_ADMIN
 */

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BreqkDeviceAdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "DEVICE_ADMIN";

    // ── Lifecycle callbacks ───────────────────────────────────────────────────

    /**
     * Called when the user activates Breqk as a Device Administrator.
     * Logs the activation so it is visible in logcat.
     */
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "[ENABLED] Breqk Device Admin activated — uninstall protection ON");
    }

    /**
     * Called when Device Admin is successfully deactivated.
     * At this point the app can be uninstalled normally.
     */
    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.i(TAG, "[DISABLED] Breqk Device Admin deactivated — uninstall protection OFF");
    }

    /**
     * Called when the user tries to deactivate Device Admin.
     * Returns a retention message displayed in the system confirmation dialog.
     * This does NOT prevent deactivation — it only adds an extra confirmation step.
     *
     * @return Message shown in the "Are you sure?" system dialog
     */
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        Log.i(TAG, "[DISABLE_REQUESTED] User attempting to deactivate Breqk Device Admin");
        // This message is shown by the Android system in the confirmation dialog.
        // Keep it short — it appears inline in a system UI popup.
        return "Disabling this will allow Breqk to be uninstalled, which removes "
                + "all your blocking protection. Are you sure?";
    }
}

package com.doomscrollstopper;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

/**
 * AccessibilityPermissionActivity — Native gate that intercepts every cold launch.
 *
 * Blocks entry to the main React Native app until the user has enabled
 * ReelsInterventionService in system Accessibility Settings.
 *
 * Flow:
 *   App opens → onCreate() → onResume() → isAccessibilityServiceEnabled()?
 *     YES → forward to MainActivity (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK), finish()
 *     NO  → stay on this screen; show CTA button
 *   User taps CTA → Settings.ACTION_ACCESSIBILITY_SETTINGS opens
 *   User returns → onResume() fires again → re-check → proceed if granted
 *
 * Why onResume()? Android provides no broadcast or callback when an accessibility
 * service is toggled. onResume() fires automatically whenever this activity
 * returns to the foreground (e.g. after user presses back from system settings).
 *
 * Logging: filter with `adb logcat -s ACC_PERM_GATE`
 */
public class AccessibilityPermissionActivity extends AppCompatActivity {

    // ── Log tag ────────────────────────────────────────────────────────────────
    private static final String TAG = "ACC_PERM_GATE";

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: gate activity started");
        setContentView(R.layout.activity_accessibility_permission);

        // CTA button — opens system Accessibility Settings page directly
        findViewById(R.id.btn_grant_permission).setOnClickListener(v -> {
            Log.d(TAG, "CTA tapped — opening system Accessibility Settings");
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: checking if accessibility service is enabled");

        if (isAccessibilityServiceEnabled()) {
            // Permission granted — clear the gate and proceed to the main app
            Log.d(TAG, "onResume: service IS enabled → forwarding to MainActivity");
            Intent intent = new Intent(this, MainActivity.class);
            // CLEAR_TASK ensures the gate is fully removed from the back stack.
            // Pressing back from MainActivity will exit the app, not return here.
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } else {
            // Still not enabled — stay on the permission screen
            Log.d(TAG, "onResume: service NOT enabled → staying on gate screen");
        }
    }

    // ── Permission check ───────────────────────────────────────────────────────

    /**
     * Checks whether ReelsInterventionService is listed in the system's enabled
     * accessibility services. No special Android permission is required to call this.
     *
     * The setting is a colon-delimited string of "package/class" pairs, e.g.:
     *   "com.example.app/.MyService:com.other.app/.OtherService"
     *
     * @return true if ReelsInterventionService is enabled, false otherwise.
     */
    private boolean isAccessibilityServiceEnabled() {
        ComponentName expectedComponent = new ComponentName(this, ReelsInterventionService.class);
        String expectedFlat = expectedComponent.flattenToString();

        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        Log.d(TAG, "isAccessibilityServiceEnabled: raw value = " + enabledServices);
        Log.d(TAG, "isAccessibilityServiceEnabled: looking for = " + expectedFlat);

        if (enabledServices == null) {
            Log.d(TAG, "isAccessibilityServiceEnabled: ENABLED_ACCESSIBILITY_SERVICES is null → false");
            return false;
        }

        // Split the colon-delimited string and do a case-insensitive match on each entry
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);

        while (splitter.hasNext()) {
            String service = splitter.next();
            if (service.equalsIgnoreCase(expectedFlat)) {
                Log.d(TAG, "isAccessibilityServiceEnabled: MATCH found → true");
                return true;
            }
        }

        Log.d(TAG, "isAccessibilityServiceEnabled: no match → false");
        return false;
    }
}

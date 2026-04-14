package com.breqk;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.TextView;

/**
 * LaunchInterceptor
 * ------------------
 * Shows a 15-second mindfulness overlay whenever a monitored app is opened
 * freshly (not returning from the background within 30 seconds).
 *
 * The overlay blocks access to the app until:
 *   (a) The user taps "I'm Being Intentional" — immediate dismiss
 *   (b) The 15-second countdown finishes    — auto-dismiss
 * Both paths record the current timestamp to prevent re-triggering within 30s.
 *
 * Fresh-launch detection:
 *   A launch is "fresh" if the last-dismissed timestamp for this package is
 *   older than FRESH_LAUNCH_THRESHOLD_MS. Stored in SharedPreferences under
 *   KEY_LAUNCH_LAST_FOREGROUND + packageName (survives service restarts).
 *
 * Layout: reuses delay_overlay.xml
 *   title         → "Take a breath before opening {AppName}"
 *   backButton    → "I'm Being Intentional" (immediate dismiss, records timestamp)
 *   continueButton → hidden (auto-dismiss via CountDownTimer handles this path)
 *   countdownText → hidden (logging-only countdown; no UI ticker needed here)
 *
 * Thread safety:
 *   onWindowStateChanged() is called from the accessibility thread.
 *   All WindowManager operations are posted to mainHandler to ensure they
 *   run on the UI thread.
 *
 * Logging tag: LAUNCH_INTERCEPT
 * Filter:       adb logcat -s LAUNCH_INTERCEPT
 * Fresh check:  adb logcat -s LAUNCH_INTERCEPT | findstr "FRESH_CHECK"
 * Popup marker: adb logcat -s LAUNCH_INTERCEPT | findstr "POPUP_MARKER"
 */
public class LaunchInterceptor {

    private static final String TAG = "LAUNCH_INTERCEPT";

    /**
     * Duration the overlay stays visible before auto-dismiss.
     * Configurable here for testing — 15 seconds is the production value.
     */
    private static final long COUNTDOWN_DURATION_MS = 15_000L;

    /**
     * Minimum elapsed time since the last dismiss (or last foreground) before
     * the overlay re-triggers. 30 seconds prevents annoyance on quick task-switches.
     */
    private static final long FRESH_LAUNCH_THRESHOLD_MS = 30_000L;

    /** CountDownTimer tick interval (used for logcat countdown visibility only). */
    private static final long COUNTDOWN_TICK_MS = 1_000L;

    private final Context context;
    private final AccessibilityService service;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;

    /** Currently displayed overlay view, or null when none is showing. */
    private View overlayView = null;

    /** The package name of the app whose overlay is currently showing. */
    private String currentPackageName = null;

    /** Active countdown timer, or null when not counting. */
    private CountDownTimer countDownTimer = null;

    /**
     * Creates the LaunchInterceptor.
     *
     * @param context Android context (the AccessibilityService)
     * @param service AccessibilityService reference (needed for WINDOW_SERVICE)
     */
    public LaunchInterceptor(Context context, AccessibilityService service) {
        this.context = context;
        this.service = service;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Log.d(TAG, "[INIT] LaunchInterceptor created"
                + " freshThresholdMs=" + FRESH_LAUNCH_THRESHOLD_MS
                + " countdownMs=" + COUNTDOWN_DURATION_MS);
    }

    /**
     * Called by AppEventRouter on TYPE_WINDOW_STATE_CHANGED.
     *
     * Checks whether this is a fresh launch for an app with launchPopup=true.
     * If so, shows the overlay. No-ops if:
     *  - launchPopup is false for this app
     *  - An overlay is already visible (no stacking)
     *  - The app was last foregrounded within FRESH_LAUNCH_THRESHOLD_MS
     *
     * @param event  The accessibility event (used for context if needed in future)
     * @param config Feature flags for the app being opened
     */
    public void onWindowStateChanged(AccessibilityEvent event, AppEventRouter.AppConfig config) {
        if (!config.launchPopup) {
            Log.d(TAG, "launchPopup=false pkg=" + config.packageName + " — skipping");
            return;
        }

        // Don't stack overlays (one at a time)
        if (overlayView != null) {
            Log.d(TAG, "Overlay already showing for " + currentPackageName
                    + " — ignoring event for " + config.packageName);
            return;
        }

        if (!isFreshLaunch(config.packageName)) {
            return; // Detailed log emitted inside isFreshLaunch()
        }

        Log.i(TAG, "[FRESH_LAUNCH] Triggering overlay for pkg=" + config.packageName);
        showOverlay(config.packageName);
    }

    /**
     * Cleans up any active overlay and timer.
     * Call from the service's onInterrupt() and onDestroy().
     */
    public void onDestroy() {
        dismissOverlay("service_destroy");
        Log.d(TAG, "[DESTROY] LaunchInterceptor destroyed");
    }

    // =========================================================================
    // Fresh launch detection
    // =========================================================================

    /**
     * Returns true if enough time has passed since this package was last foregrounded
     * (or its overlay was last dismissed).
     *
     * Reads from SharedPreferences using KEY_LAUNCH_LAST_FOREGROUND + packageName.
     * A value of 0L (key not set) means the app has never been intercepted → always fresh.
     *
     * Log filter: adb logcat -s LAUNCH_INTERCEPT | findstr "FRESH_CHECK"
     *
     * @param packageName Package to check
     * @return true if the elapsed time exceeds FRESH_LAUNCH_THRESHOLD_MS
     */
    private boolean isFreshLaunch(String packageName) {
        String key = BreqkPrefs.KEY_LAUNCH_LAST_FOREGROUND + packageName;
        long lastFg = BreqkPrefs.get(context).getLong(key, 0L);
        long elapsed = System.currentTimeMillis() - lastFg;
        boolean fresh = elapsed > FRESH_LAUNCH_THRESHOLD_MS;
        Log.d(TAG, "[FRESH_CHECK] pkg=" + packageName
                + " lastFgMs=" + lastFg
                + " elapsedMs=" + elapsed
                + " fresh=" + fresh
                + " (thresholdMs=" + FRESH_LAUNCH_THRESHOLD_MS + ")");
        return fresh;
    }

    /**
     * Records the current time as the last-foreground timestamp for this package.
     * Must be called on both user-dismiss and auto-dismiss paths so that re-opens
     * within FRESH_LAUNCH_THRESHOLD_MS don't re-trigger the overlay.
     *
     * @param packageName Package to stamp
     */
    private void recordForeground(String packageName) {
        String key = BreqkPrefs.KEY_LAUNCH_LAST_FOREGROUND + packageName;
        long now = System.currentTimeMillis();
        BreqkPrefs.get(context).edit().putLong(key, now).apply();
        Log.d(TAG, "[RECORD_FG] pkg=" + packageName + " timestampMs=" + now);
    }

    // =========================================================================
    // Overlay management
    // =========================================================================

    /**
     * Inflates delay_overlay.xml and attaches it to the WindowManager.
     * Starts the 15-second auto-dismiss countdown.
     *
     * Window type: TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW permission needed;
     * AccessibilityServices receive this automatically).
     *
     * Flags: FLAG_NOT_TOUCH_MODAL removed (we want the overlay to intercept all touches).
     * FLAG_LAYOUT_IN_SCREEN ensures the overlay covers status bar + nav bar area.
     *
     * Must post to mainHandler — WindowManager calls must be on the UI thread.
     *
     * Log filter: adb logcat -s LAUNCH_INTERCEPT | findstr "POPUP_MARKER"
     *
     * @param packageName The app being opened (used for name resolution + logging)
     */
    private void showOverlay(final String packageName) {
        mainHandler.post(() -> {
            if (windowManager == null) {
                Log.e(TAG, "[POPUP_MARKER] WindowManager null — cannot show overlay for " + packageName);
                return;
            }

            // Resolve human-readable app name for the overlay title
            String appName = AppNameResolver.resolve(context.getPackageManager(), packageName);

            // TYPE_ACCESSIBILITY_OVERLAY: no SYSTEM_ALERT_WINDOW needed for AccessibilityServices.
            // No FLAG_NOT_TOUCH_MODAL: we want the overlay to capture all touches (blocking the app).
            // FLAG_LAYOUT_IN_SCREEN: extend layout into status-bar / nav-bar area for full coverage.
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.CENTER;

            overlayView = LayoutInflater.from(context).inflate(R.layout.delay_overlay, null);
            currentPackageName = packageName;

            // ── Title ───────────────────────────────────────────────────────────
            TextView titleView = overlayView.findViewById(R.id.title);
            titleView.setText("Take a breath before opening\n" + appName);

            // ── Continue button: hidden — auto-dismiss handles that path ─────────
            Button continueButton = overlayView.findViewById(R.id.continueButton);
            continueButton.setVisibility(View.GONE);

            // ── Back button: "I'm Being Intentional" — immediate dismiss ─────────
            Button backButton = overlayView.findViewById(R.id.backButton);
            backButton.setText("I'm Being Intentional");
            backButton.setOnClickListener(v -> {
                Log.i(TAG, "[DISMISS] User tapped 'I'm Being Intentional' for " + packageName);
                recordForeground(packageName);
                dismissOverlay("user_tap");
            });

            // ── CountdownText: hidden (pure-log countdown; no UI ticker needed) ──
            View countdownTextView = overlayView.findViewById(R.id.countdownText);
            if (countdownTextView != null) countdownTextView.setVisibility(View.GONE);

            // Attach to WindowManager before starting countdown so the view is valid
            windowManager.addView(overlayView, params);
            Log.i(TAG, "[POPUP_MARKER] LaunchInterceptor overlay shown"
                    + " pkg=" + packageName
                    + " appName='" + appName + "'"
                    + " countdownMs=" + COUNTDOWN_DURATION_MS);

            // Start the auto-dismiss countdown
            startCountdown(packageName);
        });
    }

    /**
     * Starts (or restarts) the 15-second auto-dismiss countdown.
     * Cancels any previously running timer first.
     *
     * On finish:
     *   1. Records the foreground timestamp (prevents immediate re-trigger)
     *   2. Dismisses the overlay
     *
     * @param packageName Package being timed (for logging and timestamp recording)
     */
    private void startCountdown(final String packageName) {
        cancelCountdown();
        countDownTimer = new CountDownTimer(COUNTDOWN_DURATION_MS, COUNTDOWN_TICK_MS) {
            @Override
            public void onTick(long millisUntilFinished) {
                Log.d(TAG, "[COUNTDOWN] pkg=" + packageName
                        + " secondsLeft=" + (millisUntilFinished / 1000));
            }

            @Override
            public void onFinish() {
                Log.i(TAG, "[COUNTDOWN] Timer finished for " + packageName + " — auto-dismiss");
                recordForeground(packageName);
                dismissOverlay("timer_complete");
            }
        }.start();
        Log.d(TAG, "[COUNTDOWN] Started " + (COUNTDOWN_DURATION_MS / 1000) + "s countdown"
                + " pkg=" + packageName);
    }

    /**
     * Cancels the active countdown timer. Safe to call when no timer is running.
     */
    private void cancelCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
            Log.d(TAG, "[COUNTDOWN] Cancelled");
        }
    }

    /**
     * Removes the overlay from the WindowManager and cancels the countdown timer.
     * Must be called on the main thread (or posted to mainHandler).
     * Safe to call when no overlay is showing.
     *
     * @param reason Short description of why the overlay is being dismissed (for logging)
     */
    private void dismissOverlay(String reason) {
        cancelCountdown();
        if (overlayView == null) return;

        // dismissOverlay may be called from onDestroy() (accessibility thread) or
        // from mainHandler.post() — only the latter is UI thread.
        // To be safe, post removal to mainHandler if we're not already on main thread.
        final View viewToRemove = overlayView;
        final String pkg = currentPackageName != null ? currentPackageName : "(unknown)";
        overlayView = null;
        currentPackageName = null;

        mainHandler.post(() -> {
            try {
                windowManager.removeView(viewToRemove);
                Log.d(TAG, "[DISMISS] Overlay removed reason=" + reason + " pkg=" + pkg);
            } catch (Exception e) {
                Log.w(TAG, "[DISMISS] removeView failed (already removed?) reason=" + reason
                        + " pkg=" + pkg + " err=" + e.getMessage());
            }
        });
    }
}

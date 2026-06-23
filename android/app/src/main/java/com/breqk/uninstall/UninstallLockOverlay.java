package com.Break.uninstall;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.TextView;

import com.Break.R;
import com.Break.prefs.BreakPrefs;

/**
 * Full-screen "don't delete Break" lock screen.
 *
 * Shown by {@link com.Break.ReelsInterventionService} when the user reaches the
 * Break uninstall screen in Settings AND the deletion-prevention setting is on
 * (the service gates on {@link BreakPrefs#isUninstallLockEnabled}).
 *
 * Behaviour:
 * - Inflates {@code overlay_uninstall_lock} — a FULLY OPAQUE light surface
 * (#FAFAFA) matching the React Native app theme. No blur: the opaque
 * surface simply replaces the Settings uninstall screen visually.
 * - A motivational, anti-deletion message rotates every
 * {@link #MESSAGE_ROTATE_MS}.
 * - Both action buttons are hidden for
 * {@link BreakPrefs#UNINSTALL_LOCK_DURATION_MS}
 * (30s) then revealed together. NO countdown or timer is ever displayed.
 * - "Keep Break" fires the caller's callback (GLOBAL_ACTION_HOME).
 * - "Give up and delete Break anyway" is the deliberately discouraged escape:
 * it dismisses the lock and suppresses re-show for {@link #PROCEED_SUPPRESS_MS}
 * so the OS uninstall flow can actually proceed without the overlay snapping
 * back on the next Settings content-change event.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY — no SYSTEM_ALERT_WINDOW needed for
 * AccessibilityServices. Intentionally separate from InterventionOverlay so the
 * two overlays cannot collide.
 *
 * Log filter: adb logcat -s REELS_WATCH | findstr "UNINSTALL_WATCH"
 */
public class UninstallLockOverlay {

    private static final String TAG = "REELS_WATCH";

    // How often the motivational message swaps. No timer is shown to the user.
    private static final long MESSAGE_ROTATE_MS = 4000;

    // Scroll-up animation durations for message transitions.
    private static final long MESSAGE_ANIM_OUT_MS = 180;
    private static final long MESSAGE_ANIM_IN_MS = 220;
    // How many dp the view translates during the scroll-up animation.
    private static final float MESSAGE_ANIM_TRANSLATION_DP = 28f;

    // Prevents re-showing the overlay too quickly after a normal dismiss
    // (e.g. user taps back / navigates away briefly).
    private static final long RESHOW_COOLDOWN_MS = 500;
    // After the user explicitly chooses "delete anyway", suppress re-show for
    // this long so they can complete the system uninstall confirmation without
    // the overlay reappearing on the next Settings CONTENT_CHANGED event.
    private static final long PROCEED_SUPPRESS_MS = 10_000;

    /**
     * Rotating copy. Tone: discourage deleting, encourage keeping the app, and
     * reframe the urge to uninstall as the moment the tool is actually working.
     */
    private static final String[] MESSAGES = {
            "The urge to delete Break is the exact moment it's working. Sit with it.",
            "You installed Break for a reason. That reason hasn't gone anywhere.",
            "Future you is begging you not to do this. Listen to them.",
            "Deleting this won't fix the urge — it just removes the thing protecting you from it.",
            "Thirty seconds of discomfort now beats hours lost scrolling later.",
            "This feeling passes. The habit you're fighting doesn't — unless you let Break help.",
            "You're not trapped. You're protected. There's a difference.",
            "\"Bro cmon. LOCK IN TWIN. you got this.\" \n- past you",
            "Keep Break. Tomorrow you'll be glad you stayed.",
    };

    private final Context context;
    private final Handler mainHandler;

    private View overlayView;
    private boolean isShowing = false;
    private long lastDismissMs = 0;

    // While now < suppressUntilMs, show() is a no-op. Set when the user picks
    // the discouraged "delete anyway" escape so the uninstall can proceed.
    private long suppressUntilMs = 0;

    // Rotation/reveal callbacks — tracked so dismiss() can cancel them and avoid
    // touching a removed view.
    private Runnable rotateRunnable;
    private Runnable revealRunnable;
    private int messageIndex = 0;

    public UninstallLockOverlay(Context context, Handler mainHandler) {
        this.context = context;
        this.mainHandler = mainHandler != null ? mainHandler : new Handler(Looper.getMainLooper());
    }

    public boolean isShowing() {
        return isShowing;
    }

    /**
     * Shows the lock screen. No-op if already showing, within the reshow
     * cooldown, or within the post-"delete anyway" suppression window.
     *
     * @param onKeepCallback fired when the user taps "Keep Break" after the wait
     *                       (typically GLOBAL_ACTION_HOME)
     */
    public void show(Runnable onKeepCallback) {
        if (isShowing)
            return;

        long now = System.currentTimeMillis();

        if (now < suppressUntilMs) {
            Log.d(TAG, "[UNINSTALL_WATCH] show() suppressed — user chose to proceed ("
                    + (suppressUntilMs - now) + "ms left)");
            return;
        }
        if (now - lastDismissMs < RESHOW_COOLDOWN_MS) {
            Log.d(TAG, "[UNINSTALL_WATCH] show() blocked by reshow cooldown ("
                    + (now - lastDismissMs) + "ms < " + RESHOW_COOLDOWN_MS + "ms)");
            return;
        }

        isShowing = true;

        mainHandler.post(() -> {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(TAG, "[UNINSTALL_WATCH] WindowManager null — cannot show overlay");
                isShowing = false;
                return;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.OPAQUE);
            params.gravity = Gravity.CENTER;

            overlayView = LayoutInflater.from(context)
                    .inflate(R.layout.overlay_uninstall_lock, null);

            final TextView messageView = overlayView.findViewById(R.id.uninstall_lock_message);
            final Button homeBtn = overlayView.findViewById(R.id.uninstall_lock_home_btn);
            final Button keepBtn = overlayView.findViewById(R.id.uninstall_lock_keep_btn);
            final Button deleteBtn = overlayView.findViewById(R.id.uninstall_lock_delete_btn);

            // Seed the first message immediately.
            messageIndex = 0;
            messageView.setText(MESSAGES[0]);

            // Always-available escape to the launcher (not gated by the 30s wait).
            // Mirrors the app-intercept overlay: leaving to home tears the overlay
            // down. Pressing the physical Home button reaches the same dismiss
            // path via ReelsInterventionService's non-Settings window-change check.
            homeBtn.setOnClickListener(v -> {
                Log.i(TAG, "[UNINSTALL_WATCH] 'Return to home' tapped — dismissing and going home");
                dismiss();
                if (onKeepCallback != null)
                    onKeepCallback.run();
            });

            keepBtn.setOnClickListener(v -> {
                Log.i(TAG, "[UNINSTALL_WATCH] 'Keep Break' tapped — dismissing and going home");
                dismiss();
                if (onKeepCallback != null)
                    onKeepCallback.run();
            });

            deleteBtn.setOnClickListener(v -> {
                Log.i(TAG, "[UNINSTALL_WATCH] 'delete anyway' tapped — suppressing reshow for "
                        + PROCEED_SUPPRESS_MS + "ms so the uninstall can proceed");
                suppressUntilMs = System.currentTimeMillis() + PROCEED_SUPPRESS_MS;
                // Intentionally NOT going home — leave the user on the Settings
                // uninstall screen so they can complete the deletion.
                dismiss();
            });

            try {
                windowManager.addView(overlayView, params);
                Log.i(TAG, "[UNINSTALL_WATCH] Lock screen shown (opaque light theme)");
            } catch (Exception e) {
                Log.e(TAG, "[UNINSTALL_WATCH] Failed to add overlay view", e);
                isShowing = false;
                overlayView = null;
                return;
            }

            // Rotate the message every MESSAGE_ROTATE_MS until dismissed.
            rotateRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isShowing)
                        return;
                    messageIndex = (messageIndex + 1) % MESSAGES.length;
                    animateMessageSwap(messageView, MESSAGES[messageIndex]);
                    mainHandler.postDelayed(this, MESSAGE_ROTATE_MS);
                }
            };
            mainHandler.postDelayed(rotateRunnable, MESSAGE_ROTATE_MS);

            // Reveal BOTH buttons only after the mandatory wait. No countdown shown.
            revealRunnable = () -> {
                if (!isShowing)
                    return;
                keepBtn.setVisibility(View.VISIBLE);
                deleteBtn.setVisibility(View.VISIBLE);
                Log.d(TAG, "[UNINSTALL_WATCH] Wait elapsed — buttons revealed");
            };
            mainHandler.postDelayed(revealRunnable, BreakPrefs.UNINSTALL_LOCK_DURATION_MS);
        });
    }

    /**
     * Animates the message view scrolling up: current text slides up and fades out,
     * then the new text slides up into place and fades in.
     */
    private void animateMessageSwap(TextView view, String nextText) {
        float tx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                MESSAGE_ANIM_TRANSLATION_DP,
                view.getContext().getResources().getDisplayMetrics());

        view.animate()
                .translationY(-tx)
                .alpha(0f)
                .setDuration(MESSAGE_ANIM_OUT_MS)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (!isShowing) return;
                    view.setText(nextText);
                    view.setTranslationY(tx);
                    view.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(MESSAGE_ANIM_IN_MS)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                })
                .start();
    }

    /**
     * Removes the overlay and cancels pending rotation/reveal callbacks. Safe to
     * call when nothing is showing.
     */
    public void dismiss() {
        if (!isShowing && overlayView == null)
            return;

        isShowing = false;
        lastDismissMs = System.currentTimeMillis();

        if (rotateRunnable != null) {
            mainHandler.removeCallbacks(rotateRunnable);
            rotateRunnable = null;
        }
        if (revealRunnable != null) {
            mainHandler.removeCallbacks(revealRunnable);
            revealRunnable = null;
        }

        // Cancel any in-flight message animation so it doesn't touch a detached view.
        if (overlayView != null) {
            TextView msgView = overlayView.findViewById(R.id.uninstall_lock_message);
            if (msgView != null) {
                msgView.animate().cancel();
                msgView.setAlpha(1f);
                msgView.setTranslationY(0f);
            }
        }

        final View viewToRemove = overlayView;
        overlayView = null;

        mainHandler.post(() -> {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null && viewToRemove != null) {
                try {
                    windowManager.removeView(viewToRemove);
                    Log.d(TAG, "[UNINSTALL_WATCH] Lock screen dismissed");
                } catch (Exception e) {
                    Log.w(TAG, "[UNINSTALL_WATCH] removeView failed (already removed?)", e);
                }
            }
        });
    }
}

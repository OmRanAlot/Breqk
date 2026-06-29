package com.Break.shortform.intervention;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.Break.R;

public class InterventionOverlay {
    private static final String TAG = "REELS_WATCH";

    private final Context context;
    private final Handler mainHandler;
    private View interventionView;
    // The view actually added to the WindowManager. In portrait this is the same
    // object as interventionView; in landscape it is a full-screen wrapper that
    // holds the counter-rotated interventionView (see lockToPortrait()).
    private View overlayRoot;
    private boolean isShowing = false;

    public InterventionOverlay(Context context, Handler mainHandler) {
        this.context = context;
        this.mainHandler = mainHandler != null ? mainHandler : new Handler(Looper.getMainLooper());
    }

    public boolean isShowing() {
        return isShowing;
    }

    public void show(String pkg, Runnable onLockInCallback) {
        if (isShowing) return;
        isShowing = true; // prevent re-entry

        mainHandler.post(() -> {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(TAG, "InterventionOverlay: WindowManager null, cannot show overlay");
                isShowing = false;
                return;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.BOTTOM;
            // NOTE: params.screenOrientation has NO effect on a
            // TYPE_ACCESSIBILITY_OVERLAY window — accessibility overlays cannot
            // force the display orientation; they inherit whatever orientation the
            // foreground app (e.g. YouTube fullscreen) is in. To guarantee the
            // intervention always renders portrait we counter-rotate the content
            // ourselves in lockToPortrait() below.

            interventionView = LayoutInflater.from(context)
                    .inflate(R.layout.overlay_reels_intervention, null);

            TextView titleView = interventionView.findViewById(R.id.intervention_title);
            titleView.setText("Time is up!");
            Log.d(TAG, "triggerIntervention: title set to 'Time is up!'");

            Button btnLockIn = interventionView.findViewById(R.id.btn_take_break);
            btnLockIn.setText("Lock In");
            btnLockIn.setVisibility(View.VISIBLE);
            btnLockIn.setOnClickListener(v -> {
                Log.i(TAG, "lock_in tapped for " + pkg + " â€” going to Android home screen");
                btnLockIn.setEnabled(false);
                slideOut(interventionView, () -> {
                    dismiss();
                    if (onLockInCallback != null) {
                        onLockInCallback.run();
                    }
                });
            });

            // Counter-rotate to portrait if the host app put the display in
            // landscape. Returns interventionView unchanged when already portrait.
            overlayRoot = lockToPortrait(interventionView, windowManager);

            try {
                windowManager.addView(overlayRoot, params);
                Log.i(TAG, "[BUDGET] Overlay shown (Time is up!) for " + pkg);
                slideIn(interventionView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to add intervention view", e);
                isShowing = false;
                interventionView = null;
                overlayRoot = null;
            }
        });
    }

    /**
     * Forces the intervention to render in portrait regardless of the foreground
     * app's orientation.
     *
     * A TYPE_ACCESSIBILITY_OVERLAY window cannot dictate display orientation, so
     * when the display is landscape (YouTube fullscreen) we wrap the content in a
     * full-screen container and rotate the content ±90° back to portrait. The
     * portrait-sized content, centered and rotated, exactly fills the landscape
     * window so the dark backdrop still covers the whole screen.
     *
     * @return the view to hand to WindowManager.addView — either {@code content}
     *         itself (portrait) or a wrapper holding the rotated content (landscape).
     */
    private View lockToPortrait(View content, WindowManager windowManager) {
        Display display = windowManager.getDefaultDisplay();
        int rotation = display.getRotation();

        // Already portrait (or upside-down portrait) — nothing to rotate.
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            return content;
        }

        Point size = new Point();
        display.getRealSize(size);
        int screenW = size.x; // landscape: width > height
        int screenH = size.y;

        // Counter-rotate against the display rotation so content reads upright.
        // If this lands upside-down on a given device, flip the sign here.
        float degrees = (rotation == Surface.ROTATION_90) ? -90f : 90f;
        Log.d(TAG, "lockToPortrait: display landscape (rotation=" + rotation
                + "), rotating overlay content by " + degrees + "°");

        FrameLayout wrapper = new FrameLayout(context);

        // Size the content to PORTRAIT dimensions (swap w/h). Once rotated ±90°
        // around its center, its bounding box becomes screenW × screenH and fills
        // the full landscape window.
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(screenH, screenW);
        lp.gravity = Gravity.CENTER;
        content.setLayoutParams(lp);
        content.setRotation(degrees);

        wrapper.addView(content);
        return wrapper;
    }

    private void slideIn(View root) {
        View sheet = root.findViewById(R.id.bottom_sheet_container);
        if (sheet == null) return;
        root.setAlpha(0f);
        sheet.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                sheet.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                sheet.setTranslationY(sheet.getHeight());
                root.animate().alpha(1f).setDuration(300).setInterpolator(new DecelerateInterpolator()).start();
                sheet.animate()
                        .translationY(0f)
                        .setDuration(400)
                        .setInterpolator(new DecelerateInterpolator(2.5f))
                        .start();
            }
        });
    }

    private void slideOut(View root, Runnable onDone) {
        View sheet = root.findViewById(R.id.bottom_sheet_container);
        if (sheet == null) {
            onDone.run();
            return;
        }
        root.animate().alpha(0f).setDuration(280).setInterpolator(new AccelerateInterpolator()).start();
        sheet.animate()
                .translationY(sheet.getHeight())
                .setDuration(300)
                .setInterpolator(new AccelerateInterpolator(2f))
                .withEndAction(onDone)
                .start();
    }

    public void dismiss() {
        if (!isShowing && interventionView == null) {
            Log.d(TAG, "[DISMISS_CALL] dismissIntervention: no overlay active (interventionView=null), skipping");
            return;
        }

        StackTraceElement caller = Thread.currentThread().getStackTrace().length > 3
                ? Thread.currentThread().getStackTrace()[3]
                : null;
        Log.i(TAG, "[DISMISS_CALL] dismissIntervention: removing overlay"
                + (caller != null ? " ” caller=" + caller.getMethodName()
                        + ":" + caller.getLineNumber() : ""));
        
        isShowing = false;
        // Remove the window root (a landscape wrapper, or interventionView itself
        // in portrait) — never the rotated child, which is not a window root.
        final View viewToRemove = overlayRoot != null ? overlayRoot : interventionView;
        interventionView = null;
        overlayRoot = null;

        mainHandler.post(() -> {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null && viewToRemove != null) {
                try {
                    windowManager.removeView(viewToRemove);
                    Log.d(TAG, "[DISMISS_CALL] dismissIntervention: overlay removed successfully");
                } catch (Exception e) {
                    Log.w(TAG, "[DISMISS_CALL] dismissIntervention: removeView failed (already removed?)", e);
                }
            }
        });
    }
}

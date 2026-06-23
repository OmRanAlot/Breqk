package com.Break.shortform.intervention;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.TextView;

import com.Break.R;

public class InterventionOverlay {
    private static final String TAG = "REELS_WATCH";

    private final Context context;
    private final Handler mainHandler;
    private View interventionView;
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

            try {
                windowManager.addView(interventionView, params);
                Log.i(TAG, "[BUDGET] Overlay shown (Time is up!) for " + pkg);
                slideIn(interventionView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to add intervention view", e);
                isShowing = false;
                interventionView = null;
            }
        });
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
        final View viewToRemove = interventionView;
        interventionView = null;

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

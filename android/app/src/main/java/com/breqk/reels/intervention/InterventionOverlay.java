package com.breqk.reels.intervention;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.breqk.R;

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
                Log.i(TAG, "lock_in tapped for " + pkg + " — going to Android home screen");
                dismiss();
                if (onLockInCallback != null) {
                    onLockInCallback.run();
                }
            });

            try {
                windowManager.addView(interventionView, params);
                Log.i(TAG, "[BUDGET] Overlay shown (Time is up!) for " + pkg);
            } catch (Exception e) {
                Log.e(TAG, "Failed to add intervention view", e);
                isShowing = false;
                interventionView = null;
            }
        });
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
                + (caller != null ? " — caller=" + caller.getMethodName()
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

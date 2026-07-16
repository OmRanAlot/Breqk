package com.Break.coach;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.Break.R;
import com.Break.prefs.BreakPrefs;

/**
 * IntentCoachOverlay
 * ------------------
 * Full-screen system overlay for the YouTube mindful-viewing coach. Shown at
 * YouTube launch (wired in Phase 5 from {@code ReelsInterventionService}).
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li><b>WAIT</b> — a ring fills over the user's intercept delay
 *       ({@code delay_time_seconds}). The user simply waits; the primary button
 *       is disabled and counts down.</li>
 *   <li><b>INTENT</b> — when the wait ends, an input field appears and the user
 *       types <i>why</i> they're opening YouTube.</li>
 *   <li><b>VERDICT</b> — on submit, {@link VerdictEngine} decides
 *       APPROVE / PROBE / CHALLENGE (the LLM, Phase 3, will only phrase the copy;
 *       until then {@link CoachCopy} provides it). APPROVE lets them through;
 *       PROBE/CHALLENGE require a followup answer, and proceeding records an
 *       override.</li>
 * </ol>
 *
 * "Back to Reality" exits to the home screen at any time (no override recorded).
 *
 * The window is focusable so the soft keyboard works inside the overlay (unlike
 * the budget {@code InterventionOverlay}, which takes no text input).
 *
 * Logging: TAG "COACH" — prefixes [OVERLAY], [VERDICT].
 */
public final class IntentCoachOverlay {

    private static final String TAG = "COACH";
    private static final String YT_PACKAGE = "com.google.android.youtube";

    /** Ignore taps for this long after the overlay appears (accidental taps). */
    private static final long TOUCH_GUARD_MS = 400;
    /** CHALLENGE followups demand a more considered answer than a single word. */
    private static final int CHALLENGE_MIN_ANSWER_CHARS = 12;

    private enum Phase { WAIT, INTENT, VERDICT, DONE }

    private final Context context;
    private final Handler mainHandler;
    private final CoachSessionTracker tracker;

    private View root;
    private boolean isShowing = false;
    private long shownAt = 0L;
    private Phase phase = Phase.WAIT;
    private Verdict verdict;

    private ObjectAnimator ringAnimator;
    private Runnable countdownRunnable;

    private Runnable onAllow;
    private Runnable onExit;

    public IntentCoachOverlay(Context context, Handler mainHandler) {
        this.context = context;
        this.mainHandler = mainHandler != null ? mainHandler : new Handler(Looper.getMainLooper());
        this.tracker = new CoachSessionTracker(context);
    }

    public boolean isShowing() {
        return isShowing;
    }

    /**
     * Shows the coach overlay.
     *
     * @param onAllow invoked when the user satisfies the gate and proceeds into
     *                YouTube (caller should add YouTube to its session allowlist)
     * @param onExit  invoked when the user backs out to the home screen
     */
    public void show(Runnable onAllow, Runnable onExit) {
        if (isShowing) {
            return;
        }
        this.onAllow = onAllow;
        this.onExit = onExit;
        isShowing = true;

        mainHandler.post(() -> {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                Log.e(TAG, "[OVERLAY] WindowManager null — cannot show coach");
                isShowing = false;
                return;
            }
            try {
                root = LayoutInflater.from(context).inflate(R.layout.overlay_intent_coach, null);

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        // No FLAG_NOT_FOCUSABLE: the window must take focus so the
                        // soft keyboard can open for the intent / followup fields.
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.CENTER;
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;

                root.setFocusableInTouchMode(true);
                wm.addView(root, params);
                shownAt = System.currentTimeMillis();
                tracker.markCoachShown();
                // Cross-process visibility flag: AppUsageMonitor runs in a different
                // process and can't call isShowing() directly — it reads this pref
                // for the coach-miss fallback (see AppUsageMonitor [COACH_FALLBACK]).
                BreakPrefs.get(context).edit()
                        .putBoolean(BreakPrefs.KEY_COACH_OVERLAY_VISIBLE, true)
                        .apply();

                wireButtons();
                startWaitPhase();
                Log.i(TAG, "[OVERLAY] Coach shown for YouTube launch");
            } catch (Exception e) {
                Log.e(TAG, "[OVERLAY] Failed to show coach overlay", e);
                isShowing = false;
                root = null;
            }
        });
    }

    // ── Phase 1: WAIT ─────────────────────────────────────────────────────────

    private void startWaitPhase() {
        phase = Phase.WAIT;
        int delaySecs = BreakPrefs.getEffectiveDelaySecs(context, YT_PACKAGE);

        TextView title = root.findViewById(R.id.title);
        title.setText("Take a breath");

        Button primary = root.findViewById(R.id.primaryButton);
        primary.setEnabled(false);
        primary.setText("Wait (" + delaySecs + "s)");

        ProgressBar ring = root.findViewById(R.id.countdownRing);
        startRing(ring, delaySecs);
        startCountdown(primary, delaySecs);
    }

    /** Fills the ring 0→max over the wait seconds (clockwise from 12 o'clock). */
    private void startRing(ProgressBar ring, int seconds) {
        if (ring == null) {
            return;
        }
        if (ringAnimator != null) {
            ringAnimator.cancel();
        }
        int durationMs = Math.max(0, seconds) * 1000;
        if (durationMs == 0) {
            ring.setProgress(ring.getMax());
            return;
        }
        ringAnimator = ObjectAnimator.ofInt(ring, "progress", 0, ring.getMax());
        ringAnimator.setDuration(durationMs);
        ringAnimator.setInterpolator(new LinearInterpolator());
        ringAnimator.start();
    }

    /** Ticks the wait label down; auto-advances to the intent phase at zero. */
    private void startCountdown(Button primary, int seconds) {
        countdownRunnable = new Runnable() {
            int remaining = seconds;

            @Override
            public void run() {
                remaining--;
                if (remaining > 0) {
                    primary.setText("Wait (" + remaining + "s)");
                    mainHandler.postDelayed(this, 1000);
                } else {
                    enterIntentPhase();
                }
            }
        };
        mainHandler.postDelayed(countdownRunnable, 1000);
    }

    // ── Phase 2: INTENT ───────────────────────────────────────────────────────

    private void enterIntentPhase() {
        phase = Phase.INTENT;

        TextView title = root.findViewById(R.id.title);
        title.setText("Why YouTube, right now?");

        root.findViewById(R.id.wait_group).setVisibility(View.GONE);
        root.findViewById(R.id.intent_group).setVisibility(View.VISIBLE);

        EditText intentInput = root.findViewById(R.id.intent_input);
        focusAndShowKeyboard(intentInput);

        Button primary = root.findViewById(R.id.primaryButton);
        primary.setEnabled(true);
        primary.setText("Continue");
        primary.setOnClickListener(v -> {
            if (guarded()) {
                return;
            }
            onIntentSubmitted();
        });
        Log.i(TAG, "[OVERLAY] Wait complete — intent phase");
    }

    private void onIntentSubmitted() {
        EditText intentInput = root.findViewById(R.id.intent_input);
        String intent = intentInput.getText().toString().trim();
        if (TextUtils.isEmpty(intent)) {
            intentInput.setError("Tell me what you're here for");
            return;
        }
        long now = System.currentTimeMillis();
        SessionStats stats = tracker.currentStats(now);
        verdict = VerdictEngine.decide(intent, tracker.mode(context), stats);
        Log.i(TAG, "[VERDICT] verdict=" + verdict + " stats(min=" + stats.sessionMinutes()
                + " vids=" + stats.videosWatched() + " ovr=" + stats.overridesToday() + ")");
        showVerdict(intent, stats);
    }

    // ── Phase 3: VERDICT ──────────────────────────────────────────────────────

    private void showVerdict(String intent, SessionStats stats) {
        phase = Phase.VERDICT;
        CoachCopy.CoachMessage copy = CoachCopy.forVerdict(verdict, intent, stats);

        EditText intentInput = root.findViewById(R.id.intent_input);
        intentInput.setEnabled(false);

        TextView message = root.findViewById(R.id.verdict_message);
        message.setText(copy.message);
        message.setVisibility(View.VISIBLE);

        Button primary = root.findViewById(R.id.primaryButton);

        if (verdict == Verdict.APPROVE || copy.followup == null) {
            // Approved — affirm and let them through on the next tap.
            primary.setText("Let's go");
            primary.setOnClickListener(v -> {
                if (guarded()) {
                    return;
                }
                allowAndDismiss(false);
            });
            return;
        }

        // PROBE / CHALLENGE — require a followup answer before proceeding.
        TextView followupQuestion = root.findViewById(R.id.followup_question);
        followupQuestion.setText(copy.followup);
        followupQuestion.setVisibility(View.VISIBLE);

        EditText followupInput = root.findViewById(R.id.followup_input);
        followupInput.setVisibility(View.VISIBLE);
        focusAndShowKeyboard(followupInput);

        primary.setText("Continue");
        primary.setOnClickListener(v -> {
            if (guarded()) {
                return;
            }
            String answer = followupInput.getText().toString().trim();
            int minChars = (verdict == Verdict.CHALLENGE) ? CHALLENGE_MIN_ANSWER_CHARS : 1;
            if (answer.length() < minChars) {
                followupInput.setError(verdict == Verdict.CHALLENGE
                        ? "A real answer — at least a sentence"
                        : "Give it an honest answer");
                return;
            }
            // Proceeding past a probe/challenge counts as an override.
            allowAndDismiss(true);
        });
    }

    // ── Exit paths ────────────────────────────────────────────────────────────

    private void wireButtons() {
        Button back = root.findViewById(R.id.backButton);
        back.setOnClickListener(v -> {
            if (guarded()) {
                return;
            }
            Log.i(TAG, "[OVERLAY] Back to Reality — exiting to home");
            dismiss();
            goHome();
            if (onExit != null) {
                onExit.run();
            }
        });
        // primaryButton's listener is (re)assigned per phase.
    }

    private void allowAndDismiss(boolean countsAsOverride) {
        if (countsAsOverride) {
            tracker.recordOverride(System.currentTimeMillis());
        }
        Log.i(TAG, "[OVERLAY] Proceeding into YouTube (override=" + countsAsOverride + ")");
        dismiss();
        if (onAllow != null) {
            onAllow.run();
        }
    }

    private void goHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(home);
        } catch (Exception e) {
            Log.w(TAG, "[OVERLAY] goHome failed: " + e.getMessage());
        }
    }

    public void dismiss() {
        phase = Phase.DONE;
        if (countdownRunnable != null) {
            mainHandler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
        if (ringAnimator != null) {
            ringAnimator.cancel();
            ringAnimator = null;
        }
        final View toRemove = root;
        root = null;
        isShowing = false;
        BreakPrefs.get(context).edit()
                .putBoolean(BreakPrefs.KEY_COACH_OVERLAY_VISIBLE, false)
                .apply();
        mainHandler.post(() -> {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && toRemove != null) {
                try {
                    wm.removeView(toRemove);
                } catch (Exception e) {
                    Log.w(TAG, "[OVERLAY] removeView failed (already removed?)", e);
                }
            }
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** True while taps should be ignored (just-appeared guard). */
    private boolean guarded() {
        return System.currentTimeMillis() - shownAt < TOUCH_GUARD_MS;
    }

    private void focusAndShowKeyboard(EditText field) {
        if (field == null) {
            return;
        }
        field.requestFocus();
        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }
    }
}

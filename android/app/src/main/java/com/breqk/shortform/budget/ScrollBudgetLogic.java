package com.Break.shortform.budget;

import android.content.SharedPreferences;

import com.Break.prefs.BreakPrefs;

/**
 * Pure-function helpers for scroll budget status derivation and reconciliation.
 *
 * Centralizes the "is budget exhausted?" logic so VPNModule.getScrollBudgetStatus,
 * AppUsageMonitor.getScrollBudgetStatus, and BudgetState.checkExhaustion all agree.
 *
 * The critical fix: a budget is exhausted when usedMs >= allowanceMs, NOT only
 * when exhaustedAt > 0. Previously, reducing the allowance below the amount
 * already used left canScroll=true and remainingMs=0 — a stuck 0:00 state.
 */
public final class ScrollBudgetLogic {

    private ScrollBudgetLogic() {}

    /**
     * Derives current scroll budget status from raw state values.
     *
     * @param allowanceMinutes  Minutes allowed per window
     * @param windowMinutes     Window duration in minutes
     * @param scrollTimeUsedMs  Milliseconds of scroll time accumulated in this window
     * @param windowStartTime   Epoch ms when the current window started (0 = no window yet)
     * @param budgetExhaustedAt Epoch ms when exhaustion was recorded (0 = not recorded)
     * @param now               Current epoch ms
     * @return Populated Status with canScroll, remainingMs, nextScrollAtMs
     */
    public static Status derive(
            int allowanceMinutes,
            int windowMinutes,
            long scrollTimeUsedMs,
            long windowStartTime,
            long budgetExhaustedAt,
            long now) {

        long allowanceMs = allowanceMinutes * 60_000L;
        long windowMs = windowMinutes * 60_000L;

        // If a window has started and it has since rolled over, treat as fresh.
        boolean windowExpired = windowStartTime > 0 && (now - windowStartTime) >= windowMs;
        if (windowExpired) {
            return new Status(true, allowanceMs, 0, 0);
        }

        // Exhausted if the stored flag is set OR if used >= allowance.
        boolean exhausted = (budgetExhaustedAt > 0)
                || (allowanceMs == 0)
                || (scrollTimeUsedMs >= allowanceMs);

        if (exhausted) {
            long nextScrollAtMs = (windowStartTime > 0) ? windowStartTime + windowMs : 0;
            return new Status(false, 0, nextScrollAtMs, scrollTimeUsedMs);
        }

        long remaining = Math.max(0, allowanceMs - scrollTimeUsedMs);
        return new Status(true, remaining, 0, scrollTimeUsedMs);
    }

    /**
     * Reconciles SharedPreferences state after an allowance change.
     *
     * If the new allowance is already exceeded by usedMs, writes exhaustedAt=now
     * so native enforcement fires immediately. If the new allowance is greater
     * than usedMs (user increased the cap or still has budget left), clears any
     * stale exhaustedAt so scrolling is immediately restored.
     *
     * Should be called from VPNModule.setScrollBudget immediately after
     * persisting new allowance/window values.
     */
    public static void reconcileAfterConfigChange(
            SharedPreferences prefs,
            int newAllowanceMinutes,
            long now) {

        long usedMs = prefs.getLong(BreakPrefs.KEY_SCROLL_TIME_USED_MS, 0);
        long exhaustedAt = prefs.getLong(BreakPrefs.KEY_SCROLL_BUDGET_EXHAUSTED_AT, 0);
        long allowanceMs = newAllowanceMinutes * 60_000L;

        if ((allowanceMs == 0 || usedMs >= allowanceMs) && exhaustedAt == 0) {
            // New cap is already exceeded — record exhaustion now.
            prefs.edit()
                    .putLong(BreakPrefs.KEY_SCROLL_BUDGET_EXHAUSTED_AT, now)
                    .apply();
        } else if (usedMs < allowanceMs && exhaustedAt > 0) {
            // Allowance raised above used time — restore scroll access.
            prefs.edit()
                    .putLong(BreakPrefs.KEY_SCROLL_BUDGET_EXHAUSTED_AT, 0)
                    .apply();
        }
    }

    public static final class Status {
        public final boolean canScroll;
        public final long remainingMs;
        public final long nextScrollAtMs;
        public final long usedMs;

        Status(boolean canScroll, long remainingMs, long nextScrollAtMs, long usedMs) {
            this.canScroll = canScroll;
            this.remainingMs = remainingMs;
            this.nextScrollAtMs = nextScrollAtMs;
            this.usedMs = usedMs;
        }
    }
}

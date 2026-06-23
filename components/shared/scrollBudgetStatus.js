/**
 * Pure-function JS mirror of ScrollBudgetLogic.java.
 *
 * Derives the scroll budget UI state from raw values so that components can
 * compute an optimistic status synchronously (e.g. when the user changes the
 * allowance stepper) without waiting for the next native poll.
 *
 * @param {object} params
 * @param {number} params.allowanceMinutes   - Minutes of scroll allowed per window
 * @param {number} params.windowMinutes      - Window duration in minutes
 * @param {number} params.usedMs             - Milliseconds of scroll used in this window
 * @param {number} params.windowStartMs      - Epoch ms when the window started (0 = none)
 * @param {number} params.exhaustedAtMs      - Epoch ms when exhaustion was recorded (0 = none)
 * @param {number} [params.now]              - Current epoch ms (defaults to Date.now())
 * @returns {{ canScroll: boolean, remainingMs: number, nextScrollAtMs: number, usedMs: number }}
 */
export function deriveBudgetStatus({
  allowanceMinutes,
  windowMinutes,
  usedMs,
  windowStartMs,
  exhaustedAtMs,
  now = Date.now(),
}) {
  const allowanceMs = allowanceMinutes * 60_000;
  const windowMs = windowMinutes * 60_000;

  // Window rolled over — treat as a fresh start.
  if (windowStartMs > 0 && now - windowStartMs >= windowMs) {
    return {canScroll: true, remainingMs: allowanceMs, nextScrollAtMs: 0, usedMs};
  }

  const exhausted =
    exhaustedAtMs > 0 || allowanceMs === 0 || usedMs >= allowanceMs;

  if (exhausted) {
    const nextScrollAtMs = windowStartMs > 0 ? windowStartMs + windowMs : 0;
    return {canScroll: false, remainingMs: 0, nextScrollAtMs, usedMs};
  }

  return {
    canScroll: true,
    remainingMs: Math.max(0, allowanceMs - usedMs),
    nextScrollAtMs: 0,
    usedMs,
  };
}

/**
 * Derives a windowStartMs estimate from a status object returned by
 * getScrollBudgetStatus(). When the budget is exhausted the native layer
 * provides nextScrollAtMs = windowStartMs + windowMinutes*60000.
 * When the budget is available we approximate windowStart from usedMs.
 */
export function inferWindowStartMs(status) {
  if (!status) return 0;
  if (!status.canScroll && status.nextScrollAtMs > 0) {
    return status.nextScrollAtMs - status.windowMinutes * 60_000;
  }
  // Rough approximation: window started ~ (usedMs) ago
  return status.usedMs > 0 ? Date.now() - status.usedMs : 0;
}

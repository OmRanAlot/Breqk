/**
 * lockCycle.js
 * ------------
 * Pure derivation helpers for the Settings Change Lock re-arm cycle and the
 * Content Filter double-safe guard. These MIRROR the authoritative native math
 * (SettingsLockManager.computeCycle / ContentFilterGuard.getState) so the UI can
 * tick countdowns live between native refreshes — and so the math is unit-
 * testable in Jest without an emulator.
 *
 * Keep in sync with:
 *   android/.../lock/SettingsLockManager.java (computeCycle)
 *   android/.../lock/ContentFilterGuard.java  (getState)
 *
 * All functions are pure: (inputs) → new object, no mutation, no I/O.
 */

/** Guard state names — must match ContentFilterGuard.java. */
export const GUARD_STATES = {
  GUARD_OFF: 'GUARD_OFF',
  PROTECTED: 'PROTECTED',
  PENDING_WAIT: 'PENDING_WAIT',
  CONFIRM_WINDOW: 'CONFIRM_WINDOW',
  DISABLED: 'DISABLED',
};

/** Confirm window when the grace setting is "None": 4 hours (mirrors native). */
export const CF_INTERNAL_CONFIRM_WINDOW_MS = 4 * 60 * 60 * 1000;

/**
 * Where a scope sits in its lock/grace cycle at one instant.
 *
 * The cycle anchors on `baseLockUntilMs` (the stamped end of the FIRST lock)
 * and repeats [grace][duration] forever, so any instant reduces to a modulo.
 *
 * @param {{
 *   nowMs: number,
 *   baseLockUntilMs: number,  // 0 = never locked
 *   durationMs: number,       // lock segment length (> 0)
 *   graceMs: number,          // grace segment length (0 = no re-arm)
 * }} args
 * @returns {{ locked: boolean, lockUntilMs: number, inGrace: boolean, graceEndsAtMs: number }}
 */
export function deriveLockCycle({
  nowMs,
  baseLockUntilMs,
  durationMs,
  graceMs,
}) {
  if (!baseLockUntilMs || baseLockUntilMs <= 0) {
    return { locked: false, lockUntilMs: 0, inGrace: false, graceEndsAtMs: 0 };
  }
  if (nowMs < baseLockUntilMs) {
    return {
      locked: true,
      lockUntilMs: baseLockUntilMs,
      inGrace: false,
      graceEndsAtMs: 0,
    };
  }
  if (!graceMs || graceMs <= 0) {
    // Re-arm disabled: expired means unlocked until the next edit.
    return { locked: false, lockUntilMs: 0, inGrace: false, graceEndsAtMs: 0 };
  }
  const cycle = graceMs + durationMs;
  const elapsed = nowMs - baseLockUntilMs;
  const k = Math.floor(elapsed / cycle);
  const pos = elapsed % cycle;
  const cycleStart = baseLockUntilMs + k * cycle;
  if (pos < graceMs) {
    return {
      locked: false,
      lockUntilMs: 0,
      inGrace: true,
      graceEndsAtMs: cycleStart + graceMs,
    };
  }
  return {
    locked: true,
    lockUntilMs: cycleStart + cycle,
    inGrace: false,
    graceEndsAtMs: 0,
  };
}

/**
 * Live guard state from the raw timestamps the bridge returns.
 *
 * @param {{
 *   nowMs: number,
 *   doubleSafeEnabled: boolean,
 *   filterEnabled: boolean,
 *   pendingDisableAtMs: number, // 0 = no pending disable
 *   readyAtMs: number,          // end of the full-duration wait
 *   confirmWindowMs: number,    // grace, or 4h internal fallback
 * }} args
 * @returns {{
 *   state: string,              // one of GUARD_STATES
 *   readyAtMs: number,          // when the confirm window opens (0 if n/a)
 *   confirmEndsAtMs: number,    // when the pending disable auto-reverts (0 if n/a)
 * }}
 */
export function deriveGuardState({
  nowMs,
  doubleSafeEnabled,
  filterEnabled,
  pendingDisableAtMs,
  readyAtMs,
  confirmWindowMs,
}) {
  if (!filterEnabled) {
    return { state: GUARD_STATES.DISABLED, readyAtMs: 0, confirmEndsAtMs: 0 };
  }
  if (!doubleSafeEnabled) {
    return { state: GUARD_STATES.GUARD_OFF, readyAtMs: 0, confirmEndsAtMs: 0 };
  }
  if (!pendingDisableAtMs || pendingDisableAtMs <= 0) {
    return { state: GUARD_STATES.PROTECTED, readyAtMs: 0, confirmEndsAtMs: 0 };
  }
  const confirmEndsAtMs = readyAtMs + confirmWindowMs;
  if (nowMs < readyAtMs) {
    return { state: GUARD_STATES.PENDING_WAIT, readyAtMs, confirmEndsAtMs };
  }
  if (nowMs < confirmEndsAtMs) {
    return { state: GUARD_STATES.CONFIRM_WINDOW, readyAtMs, confirmEndsAtMs };
  }
  // Confirm window expired untouched — barrier re-instates (native clears the
  // stored pending on its next read; we mirror the outcome immediately).
  return { state: GUARD_STATES.PROTECTED, readyAtMs: 0, confirmEndsAtMs: 0 };
}

/**
 * Human-readable countdown, e.g. "23h 12m", "45m 8s", "58s".
 * Returns "0s" for zero/negative remainders.
 *
 * @param {number} remainingMs
 * @returns {string}
 */
export function formatRemaining(remainingMs) {
  if (!remainingMs || remainingMs <= 0) return '0s';
  const totalSec = Math.ceil(remainingMs / 1000);
  const days = Math.floor(totalSec / 86400);
  const hours = Math.floor((totalSec % 86400) / 3600);
  const mins = Math.floor((totalSec % 3600) / 60);
  const secs = totalSec % 60;
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${mins}m`;
  if (mins > 0) return `${mins}m ${secs}s`;
  return `${secs}s`;
}

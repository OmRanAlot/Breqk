/**
 * Tests for components/shared/lockCycle.js — the pure JS mirror of the native
 * SettingsLockManager.computeCycle and ContentFilterGuard.getState math.
 */

import {
  deriveLockCycle,
  deriveGuardState,
  formatRemaining,
  GUARD_STATES,
  CF_INTERNAL_CONFIRM_WINDOW_MS,
} from '../../components/shared/lockCycle';

const HOUR = 60 * 60 * 1000;
const DURATION = 24 * HOUR; // lock segment
const GRACE = 8 * HOUR; // grace segment
const BASE = 1_780_000_000_000; // stamped lockUntil (end of first lock)

describe('deriveLockCycle', () => {
  test('returns idle state when never locked (base = 0)', () => {
    // Arrange
    const args = {
      nowMs: BASE,
      baseLockUntilMs: 0,
      durationMs: DURATION,
      graceMs: GRACE,
    };

    // Act
    const result = deriveLockCycle(args);

    // Assert
    expect(result).toEqual({
      locked: false,
      lockUntilMs: 0,
      inGrace: false,
      graceEndsAtMs: 0,
    });
  });

  test('is locked until the stamped base during the first lock segment', () => {
    const result = deriveLockCycle({
      nowMs: BASE - 1,
      baseLockUntilMs: BASE,
      durationMs: DURATION,
      graceMs: GRACE,
    });

    expect(result.locked).toBe(true);
    expect(result.lockUntilMs).toBe(BASE);
    expect(result.inGrace).toBe(false);
  });

  test('stays unlocked forever after expiry when grace is None (0)', () => {
    const result = deriveLockCycle({
      nowMs: BASE + 500 * HOUR,
      baseLockUntilMs: BASE,
      durationMs: DURATION,
      graceMs: 0,
    });

    expect(result).toEqual({
      locked: false,
      lockUntilMs: 0,
      inGrace: false,
      graceEndsAtMs: 0,
    });
  });

  test('enters grace immediately after the first lock expires', () => {
    const result = deriveLockCycle({
      nowMs: BASE + 1,
      baseLockUntilMs: BASE,
      durationMs: DURATION,
      graceMs: GRACE,
    });

    expect(result.locked).toBe(false);
    expect(result.inGrace).toBe(true);
    expect(result.graceEndsAtMs).toBe(BASE + GRACE);
  });

  test('re-arms the lock when the grace window ends', () => {
    const result = deriveLockCycle({
      nowMs: BASE + GRACE, // first instant past grace
      baseLockUntilMs: BASE,
      durationMs: DURATION,
      graceMs: GRACE,
    });

    expect(result.locked).toBe(true);
    expect(result.lockUntilMs).toBe(BASE + GRACE + DURATION);
    expect(result.inGrace).toBe(false);
  });

  test('lands in the correct segment after multiple full cycles elapsed', () => {
    // Arrange: 3 full cycles + 2h into the 4th cycle's grace window.
    const cycle = GRACE + DURATION;
    const nowMs = BASE + 3 * cycle + 2 * HOUR;

    // Act
    const result = deriveLockCycle({
      nowMs,
      baseLockUntilMs: BASE,
      durationMs: DURATION,
      graceMs: GRACE,
    });

    // Assert: in grace, ending at the 4th cycle's grace boundary.
    expect(result.inGrace).toBe(true);
    expect(result.graceEndsAtMs).toBe(BASE + 3 * cycle + GRACE);
  });

  test('lands in a re-armed lock segment mid-cycle after many cycles', () => {
    const cycle = GRACE + DURATION;
    const nowMs = BASE + 5 * cycle + GRACE + 10 * HOUR; // inside 6th lock

    const result = deriveLockCycle({
      nowMs,
      baseLockUntilMs: BASE,
      durationMs: DURATION,
      graceMs: GRACE,
    });

    expect(result.locked).toBe(true);
    expect(result.lockUntilMs).toBe(BASE + 6 * cycle);
  });

  test('boundary: exact grace start instant counts as grace, not lock', () => {
    const result = deriveLockCycle({
      nowMs: BASE, // pos = 0 < graceMs
      baseLockUntilMs: BASE,
      durationMs: DURATION,
      graceMs: GRACE,
    });

    expect(result.inGrace).toBe(true);
    expect(result.locked).toBe(false);
  });
});

describe('deriveGuardState', () => {
  const READY = BASE + DURATION;
  const baseArgs = {
    doubleSafeEnabled: true,
    filterEnabled: true,
    pendingDisableAtMs: BASE,
    readyAtMs: READY,
    confirmWindowMs: GRACE,
  };

  test('reports DISABLED when the filter itself is off', () => {
    const result = deriveGuardState({
      ...baseArgs,
      nowMs: BASE,
      filterEnabled: false,
    });

    expect(result.state).toBe(GUARD_STATES.DISABLED);
  });

  test('reports GUARD_OFF when double-safe is not enabled', () => {
    const result = deriveGuardState({
      ...baseArgs,
      nowMs: BASE,
      doubleSafeEnabled: false,
    });

    expect(result.state).toBe(GUARD_STATES.GUARD_OFF);
  });

  test('reports PROTECTED with no pending disable', () => {
    const result = deriveGuardState({
      ...baseArgs,
      nowMs: BASE,
      pendingDisableAtMs: 0,
    });

    expect(result.state).toBe(GUARD_STATES.PROTECTED);
    expect(result.readyAtMs).toBe(0);
  });

  test('reports PENDING_WAIT during the full-duration wait', () => {
    const result = deriveGuardState({ ...baseArgs, nowMs: READY - 1 });

    expect(result.state).toBe(GUARD_STATES.PENDING_WAIT);
    expect(result.readyAtMs).toBe(READY);
    expect(result.confirmEndsAtMs).toBe(READY + GRACE);
  });

  test('opens CONFIRM_WINDOW the instant the wait ends', () => {
    const result = deriveGuardState({ ...baseArgs, nowMs: READY });

    expect(result.state).toBe(GUARD_STATES.CONFIRM_WINDOW);
  });

  test('auto re-instates PROTECTED once the confirm window lapses', () => {
    const result = deriveGuardState({ ...baseArgs, nowMs: READY + GRACE });

    expect(result.state).toBe(GUARD_STATES.PROTECTED);
    expect(result.readyAtMs).toBe(0);
    expect(result.confirmEndsAtMs).toBe(0);
  });

  test('4h internal fallback drives the confirm window when grace is None', () => {
    // Arrange: the bridge sends confirmWindowMs = 4h when grace is 0.
    const args = {
      ...baseArgs,
      confirmWindowMs: CF_INTERNAL_CONFIRM_WINDOW_MS,
    };

    // Act + Assert: open just before the 4h mark, re-instated at 4h.
    expect(
      deriveGuardState({
        ...args,
        nowMs: READY + CF_INTERNAL_CONFIRM_WINDOW_MS - 1,
      }).state,
    ).toBe(GUARD_STATES.CONFIRM_WINDOW);
    expect(
      deriveGuardState({
        ...args,
        nowMs: READY + CF_INTERNAL_CONFIRM_WINDOW_MS,
      }).state,
    ).toBe(GUARD_STATES.PROTECTED);
  });
});

describe('formatRemaining', () => {
  test('returns 0s for zero or negative input', () => {
    expect(formatRemaining(0)).toBe('0s');
    expect(formatRemaining(-5000)).toBe('0s');
  });

  test('formats seconds, minutes, hours, and days', () => {
    expect(formatRemaining(58 * 1000)).toBe('58s');
    expect(formatRemaining(45 * 60 * 1000 + 8 * 1000)).toBe('45m 8s');
    expect(formatRemaining(23 * HOUR + 12 * 60 * 1000)).toBe('23h 12m');
    expect(formatRemaining(2 * 24 * HOUR + 5 * HOUR)).toBe('2d 5h');
  });
});

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
const BASE = 1_780_000_000_000; // stamped lockUntil (end of first lock)

describe('deriveLockCycle', () => {
  test('returns idle state when never locked (base = 0)', () => {
    const result = deriveLockCycle({
      nowMs: BASE,
      baseLockUntilMs: 0,
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

  test('is locked until the stamped base during the first lock segment', () => {
    const result = deriveLockCycle({
      nowMs: BASE - 1,
      baseLockUntilMs: BASE,
      durationMs: DURATION,
      graceMs: 0,
    });

    expect(result.locked).toBe(true);
    expect(result.lockUntilMs).toBe(BASE);
    expect(result.inGrace).toBe(false);
  });

  test('becomes unlocked immediately once the lock expires', () => {
    const result = deriveLockCycle({
      nowMs: BASE,
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

  test('stays unlocked forever after expiry regardless of how much time passes', () => {
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

  test('ignores graceMs even when a non-zero value is passed (re-arm removed)', () => {
    // Even if legacy code passes a non-zero graceMs, expiry still means unlocked.
    const result = deriveLockCycle({
      nowMs: BASE + 1,
      baseLockUntilMs: BASE,
      durationMs: DURATION,
      graceMs: 8 * HOUR,
    });

    expect(result.locked).toBe(false);
    expect(result.inGrace).toBe(false);
    expect(result.graceEndsAtMs).toBe(0);
  });

  test('boundary: one ms before expiry is still locked', () => {
    const result = deriveLockCycle({
      nowMs: BASE - 1,
      baseLockUntilMs: BASE,
      durationMs: DURATION,
      graceMs: 0,
    });

    expect(result.locked).toBe(true);
    expect(result.lockUntilMs).toBe(BASE);
  });
});

describe('deriveGuardState', () => {
  const READY = BASE + DURATION;
  const CONFIRM_WINDOW = CF_INTERNAL_CONFIRM_WINDOW_MS;
  const baseArgs = {
    doubleSafeEnabled: true,
    filterEnabled: true,
    pendingDisableAtMs: BASE,
    readyAtMs: READY,
    confirmWindowMs: CONFIRM_WINDOW,
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
    expect(result.confirmEndsAtMs).toBe(READY + CONFIRM_WINDOW);
  });

  test('opens CONFIRM_WINDOW the instant the wait ends', () => {
    const result = deriveGuardState({ ...baseArgs, nowMs: READY });

    expect(result.state).toBe(GUARD_STATES.CONFIRM_WINDOW);
  });

  test('auto re-instates PROTECTED once the confirm window lapses', () => {
    const result = deriveGuardState({
      ...baseArgs,
      nowMs: READY + CONFIRM_WINDOW,
    });

    expect(result.state).toBe(GUARD_STATES.PROTECTED);
    expect(result.readyAtMs).toBe(0);
    expect(result.confirmEndsAtMs).toBe(0);
  });

  test('4h internal confirm window: open just before expiry, closed at boundary', () => {
    expect(
      deriveGuardState({
        ...baseArgs,
        nowMs: READY + CF_INTERNAL_CONFIRM_WINDOW_MS - 1,
      }).state,
    ).toBe(GUARD_STATES.CONFIRM_WINDOW);
    expect(
      deriveGuardState({
        ...baseArgs,
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

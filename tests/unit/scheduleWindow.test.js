/**
 * Tests for components/shared/scheduleWindow.js — the pure JS mirror of the
 * native ModeManager.isInScheduleWindowNow math (overnight windows, day
 * filters).
 */

import {
  parseTimeToMinutes,
  isInScheduleWindow,
  isDayAllowed,
} from '../../components/shared/scheduleWindow';

const min = (h, m = 0) => h * 60 + m;

// Bedtime-style overnight schedule: 23:00 → 07:00, every day
const BEDTIME = {
  start_time: '23:00',
  end_time: '07:00',
  days: [0, 1, 2, 3, 4, 5, 6],
};

// Same-day schedule: 09:00 → 17:00, weekdays only (1=Mon..5=Fri)
const WORKDAY = {
  start_time: '09:00',
  end_time: '17:00',
  days: [1, 2, 3, 4, 5],
};

describe('parseTimeToMinutes', () => {
  test('parses 24h HH:mm strings', () => {
    expect(parseTimeToMinutes('23:00')).toBe(1380);
    expect(parseTimeToMinutes('07:30')).toBe(450);
    expect(parseTimeToMinutes('00:00')).toBe(0);
  });
});

describe('isInScheduleWindow — overnight (23:00–07:00)', () => {
  test('inside window before midnight (23:30)', () => {
    expect(isInScheduleWindow(BEDTIME, min(23, 30), 1)).toBe(true);
  });

  test('inside window after midnight (02:00)', () => {
    expect(isInScheduleWindow(BEDTIME, min(2), 1)).toBe(true);
  });

  test('exactly at start (23:00) is inside', () => {
    expect(isInScheduleWindow(BEDTIME, min(23), 1)).toBe(true);
  });

  test('exactly at end (07:00) is outside', () => {
    expect(isInScheduleWindow(BEDTIME, min(7), 1)).toBe(false);
  });

  test('midday (12:00) is outside', () => {
    expect(isInScheduleWindow(BEDTIME, min(12), 1)).toBe(false);
  });

  test('post-midnight tail uses the START day for the day filter', () => {
    // Sunday-only bedtime; Monday 02:00 belongs to Sunday's window
    const sundayOnly = { ...BEDTIME, days: [0] };
    expect(isInScheduleWindow(sundayOnly, min(2), 1)).toBe(true); // Mon 02:00 → Sun start
    expect(isInScheduleWindow(sundayOnly, min(23, 30), 1)).toBe(false); // Mon 23:30 → Mon start
    expect(isInScheduleWindow(sundayOnly, min(23, 30), 0)).toBe(true); // Sun 23:30 → Sun start
  });
});

describe('isInScheduleWindow — same-day (09:00–17:00 weekdays)', () => {
  test('inside window on a weekday', () => {
    expect(isInScheduleWindow(WORKDAY, min(12), 3)).toBe(true);
  });

  test('outside window hours on a weekday', () => {
    expect(isInScheduleWindow(WORKDAY, min(8), 3)).toBe(false);
    expect(isInScheduleWindow(WORKDAY, min(17), 3)).toBe(false);
  });

  test('inside hours but excluded day (Saturday)', () => {
    expect(isInScheduleWindow(WORKDAY, min(12), 6)).toBe(false);
  });

  test('null or malformed schedule is never in window', () => {
    expect(isInScheduleWindow(null, min(12), 3)).toBe(false);
    expect(isInScheduleWindow({}, min(12), 3)).toBe(false);
  });
});

describe('isDayAllowed', () => {
  test('missing days array allows every day', () => {
    expect(isDayAllowed({ start_time: '09:00', end_time: '17:00' }, 0)).toBe(
      true,
    );
    expect(isDayAllowed({ start_time: '09:00', end_time: '17:00' }, 6)).toBe(
      true,
    );
  });

  test('respects explicit days array', () => {
    expect(isDayAllowed({ days: [2, 4] }, 2)).toBe(true);
    expect(isDayAllowed({ days: [2, 4] }, 3)).toBe(false);
  });
});

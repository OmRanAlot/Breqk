/**
 * Tests for components/shared/scheduleWindow.js — the pure JS mirror of the
 * native ModeManager.isInScheduleWindowNow math (overnight windows, day
 * filters).
 */

import {
  parseTimeToMinutes,
  isInScheduleWindow,
  isDayAllowed,
  formatTime12h,
  toTime24h,
  splitTime12h,
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

describe('formatTime12h', () => {
  test('formats afternoon and evening times', () => {
    expect(formatTime12h('22:00')).toBe('10:00 PM');
    expect(formatTime12h('13:05')).toBe('1:05 PM');
    expect(formatTime12h('23:59')).toBe('11:59 PM');
  });

  test('formats morning times', () => {
    expect(formatTime12h('07:00')).toBe('7:00 AM');
    expect(formatTime12h('09:30')).toBe('9:30 AM');
  });

  test('midnight is 12 AM, not 0 AM', () => {
    expect(formatTime12h('00:00')).toBe('12:00 AM');
    expect(formatTime12h('00:45')).toBe('12:45 AM');
  });

  test('noon is 12 PM, not 0 PM', () => {
    expect(formatTime12h('12:00')).toBe('12:00 PM');
    expect(formatTime12h('12:30')).toBe('12:30 PM');
  });
});

describe('toTime24h', () => {
  test('builds evening times', () => {
    expect(toTime24h(10, 0, 'PM')).toBe('22:00');
    expect(toTime24h(11, 59, 'PM')).toBe('23:59');
  });

  test('builds morning times, zero-padding the hour', () => {
    expect(toTime24h(7, 0, 'AM')).toBe('07:00');
    expect(toTime24h(9, 5, 'AM')).toBe('09:05');
  });

  test('12 AM is midnight and 12 PM is noon', () => {
    expect(toTime24h(12, 0, 'AM')).toBe('00:00');
    expect(toTime24h(12, 0, 'PM')).toBe('12:00');
  });
});

describe('splitTime12h', () => {
  test('splits a stored 24h time into picker columns', () => {
    expect(splitTime12h('22:15')).toEqual({
      hours12: 10,
      minutes: 15,
      meridiem: 'PM',
    });
    expect(splitTime12h('00:00')).toEqual({
      hours12: 12,
      minutes: 0,
      meridiem: 'AM',
    });
    expect(splitTime12h('12:00')).toEqual({
      hours12: 12,
      minutes: 0,
      meridiem: 'PM',
    });
  });
});

describe('formatTime12h / toTime24h round trip', () => {
  test('every hour:00 survives a round trip through the picker columns', () => {
    for (let hour = 0; hour < 24; hour++) {
      const stored = `${String(hour).padStart(2, '0')}:00`;
      const { hours12, minutes, meridiem } = splitTime12h(stored);
      expect(toTime24h(hours12, minutes, meridiem)).toBe(stored);
    }
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

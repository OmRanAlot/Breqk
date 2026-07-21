/**
 * scheduleWindow.js
 * ------------------
 * Pure JS mirror of the native ModeManager schedule-window math
 * (android/.../mode/ModeManager.java → isInScheduleWindowNow). Keep the two
 * in sync: same overnight semantics, same day-of-week convention (0=Sun..6=Sat),
 * and the day filter applies to the day the window STARTED.
 *
 * Used by the UI to reason about schedules without a native round-trip, and
 * unit-tested in tests/unit/scheduleWindow.test.js.
 */

/**
 * Parses "HH:mm" (24h) into total minutes from midnight.
 * @param {string} timeStr
 * @returns {number}
 */
export function parseTimeToMinutes(timeStr) {
  const [hours, minutes] = String(timeStr).split(':');
  return Number(hours) * 60 + Number(minutes);
}

/**
 * Formats a stored "HH:mm" (24h) time for display as 12-hour with a meridiem,
 * e.g. "22:00" → "10:00 PM", "00:30" → "12:30 AM", "12:00" → "12:00 PM".
 *
 * Display only — the stored format stays 24h so it keeps matching the native
 * ModeManager parser.
 *
 * @param {string} timeStr  "HH:mm"
 * @returns {string}
 */
export function formatTime12h(timeStr) {
  const { hours12, minutes, meridiem } = splitTime12h(timeStr);
  return `${hours12}:${String(minutes).padStart(2, '0')} ${meridiem}`;
}

/**
 * Inverse of formatTime12h: builds the stored "HH:mm" (24h) string from the
 * picker's three columns.
 *
 * @param {number} hours12   1..12
 * @param {number} minutes   0..59
 * @param {'AM'|'PM'} meridiem
 * @returns {string} "HH:mm"
 */
export function toTime24h(hours12, minutes, meridiem) {
  const base = hours12 % 12; // 12 → 0, so 12 AM = 00:00 and 12 PM = 12:00
  const hours24 = meridiem === 'PM' ? base + 12 : base;
  return `${String(hours24).padStart(2, '0')}:${String(minutes).padStart(
    2,
    '0',
  )}`;
}

/**
 * Splits a stored "HH:mm" into the three values the time picker's columns need.
 *
 * @param {string} timeStr "HH:mm"
 * @returns {{ hours12: number, minutes: number, meridiem: 'AM'|'PM' }}
 */
export function splitTime12h(timeStr) {
  const [rawHours, rawMinutes] = String(timeStr).split(':');
  const hours24 = Number(rawHours);
  return {
    hours12: hours24 % 12 === 0 ? 12 : hours24 % 12,
    minutes: Number(rawMinutes),
    meridiem: hours24 < 12 ? 'AM' : 'PM',
  };
}

/**
 * Returns true when `nowMinutes` on `dayIndex` falls inside the schedule
 * window. Overnight windows (start > end, e.g. 23:00–07:00) wrap midnight;
 * in the post-midnight tail the day filter checks the PREVIOUS day, because
 * that is the day the window started.
 *
 * @param {{ start_time: string, end_time: string, days?: number[] } | null} schedule
 * @param {number} nowMinutes  minutes from midnight (0..1439)
 * @param {number} dayIndex    0=Sunday .. 6=Saturday
 * @returns {boolean}
 */
export function isInScheduleWindow(schedule, nowMinutes, dayIndex) {
  if (!schedule || !schedule.start_time || !schedule.end_time) {
    return false;
  }
  const startMinutes = parseTimeToMinutes(schedule.start_time);
  const endMinutes = parseTimeToMinutes(schedule.end_time);

  const overnight = startMinutes > endMinutes;
  const inWindow = overnight
    ? nowMinutes >= startMinutes || nowMinutes < endMinutes
    : nowMinutes >= startMinutes && nowMinutes < endMinutes;
  if (!inWindow) {
    return false;
  }

  let effectiveDay = dayIndex;
  if (overnight && nowMinutes < endMinutes) {
    effectiveDay = (dayIndex + 6) % 7; // post-midnight tail — window started yesterday
  }
  return isDayAllowed(schedule, effectiveDay);
}

/**
 * Checks the optional days filter (0=Sun..6=Sat). No filter = every day.
 * @param {{ days?: number[] }} schedule
 * @param {number} dayIndex
 * @returns {boolean}
 */
export function isDayAllowed(schedule, dayIndex) {
  if (!Array.isArray(schedule.days)) {
    return true;
  }
  return schedule.days.includes(dayIndex);
}

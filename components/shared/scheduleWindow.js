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

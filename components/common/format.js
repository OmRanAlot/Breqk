/**
 * format.js — Shared display formatters.
 * ─────────────────────────────────────────────────────────────────────────────
 * Small, pure string formatters reused across screens. Consolidated here so the
 * same logic isn't copy-pasted per component (previously duplicated in
 * home.js, customize.js, and AppDetail.js).
 */

/** Format minutes as "Xh Ym", "Xh", or "Ym". Returns "—" for null/undefined. */
export const formatTime = minutes => {
  if (minutes == null) return '—';
  const m = Math.round(minutes);
  if (m >= 60) {
    const h = Math.floor(m / 60);
    const rem = m % 60;
    return rem > 0 ? `${h}h ${rem}m` : `${h}h`;
  }
  return `${m}m`;
};

/** Format a nullable integer stat; returns "—" for null/undefined. */
export const formatCount = value => (value == null ? '—' : String(value));

/** Format milliseconds as "M:SS" for countdown displays. */
export const formatBudgetTime = ms => {
  if (ms == null || ms <= 0) return '0:00';
  const totalSec = Math.floor(ms / 1000);
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${String(sec).padStart(2, '0')}`;
};

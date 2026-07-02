/**
 * theme.js — Break onboarding design tokens.
 * ─────────────────────────────────────────────────────────────────────────────
 * Lifted verbatim from the "Break Onboarding" handoff design (claude.ai/design)
 * so the onboarding flow matches the mockup exactly. The warm off-white palette
 * is intentionally distinct from the rest of the app's `globalStyles` — these
 * tokens are scoped to the first-run flow only.
 *
 * Colour reference (design source → token):
 *   #f4f3f1  screen background          → bg
 *   #1a1815  primary ink                → ink
 *   #161412  near-black (pills/icons)   → inkDeep
 *   #a4a09a  body copy                  → body
 *   #9c988f  small uppercase labels     → label
 *   #bdb9b1  back / step counter        → faint
 *   #6c685f  disabled row text          → dim
 *   #fbfbfa  card surface               → card
 *   #eae8e4  card / divider border      → border
 *   #ececea  segmented track / off icon → track
 *   #ededeb  permission icon tile/chip  → tile
 *   #f0efed  reassurance card           → reassure
 *   #d2cfca  unchecked control border   → controlBorder
 */

export const T = {
  bg: '#f4f3f1',
  ink: '#1a1815',
  inkDeep: '#161412',
  onInk: '#f6f4f1',
  iconOnInk: '#f4f3f1',
  body: '#a4a09a',
  label: '#9c988f',
  faint: '#bdb9b1',
  dim: '#6c685f',
  card: '#fbfbfa',
  border: '#eae8e4',
  track: '#ececea',
  tile: '#ededeb',
  reassure: '#f0efed',
  controlBorder: '#d2cfca',
  dotInactive: '#d6d3ce',
  accent: '#1a1815',
};

/**
 * Apps offered on the "What pulls you in?" screen. Package names map to the
 * native managed-apps registry; monograms reproduce the design's dark tiles.
 * The first three are pre-selected to remove setup friction (per the chat
 * transcript: "We picked a few for you").
 */
export const ONBOARDING_APPS = [
  { pkg: 'com.instagram.android', label: 'Instagram', monogram: 'In' },
  { pkg: 'com.zhiliaoapp.musically', label: 'TikTok', monogram: 'Tk' },
  { pkg: 'com.google.android.youtube', label: 'YouTube', monogram: 'Yt' },
  { pkg: 'com.twitter.android', label: 'X', monogram: 'X' },
  { pkg: 'com.reddit.frontpage', label: 'Reddit', monogram: 'Re' },
];

export const DEFAULT_SELECTED = [
  'com.instagram.android',
  'com.zhiliaoapp.musically',
  'com.google.android.youtube',
];

/** Preset intercept messages shown on the "What should we say?" screen. */
export const MESSAGE_PRESETS = [
  'Is this intentional?',
  'Do you have a minute to spare?',
  'Reclaim your time.',
];

/** Breathing-pause duration options, in seconds. */
export const BREATH_DURATIONS = [5, 15, 30];

/**
 * "Set your limits" screen — short-form scroll guardrails.
 *
 * Threshold: consecutive Reels/Shorts before the intervention popup fires
 * (native clamps 1–20, default 4). Allowance: minutes of short-form permitted
 * per rolling window (native clamps 1–30, default 5). The window itself is kept
 * at the native default of 60 minutes to keep onboarding to two simple choices —
 * persisted via SettingsModule.saveScrollBudget(allowance, SCROLL_WINDOW_MINUTES).
 */
export const SCROLL_THRESHOLD_OPTIONS = [3, 5, 10];
export const DEFAULT_SCROLL_THRESHOLD = 5;

export const SCROLL_ALLOWANCE_OPTIONS = [5, 10, 15];
export const DEFAULT_SCROLL_ALLOWANCE = 5;

/** Fixed budget window (minutes) — matches the native default. */
export const SCROLL_WINDOW_MINUTES = 60;

/**
 * Per-app breath defaults reproduced from the design (Instagram 15s, TikTok
 * 30s, YouTube off). Apps without an explicit default fall back to on/15s when
 * the user selects them.
 */
export const DEFAULT_BREATH = {
  'com.instagram.android': { on: true, secs: 15 },
  'com.zhiliaoapp.musically': { on: true, secs: 30 },
  'com.google.android.youtube': { on: false, secs: 15 },
};

export const BREATH_FALLBACK = { on: true, secs: 15 };

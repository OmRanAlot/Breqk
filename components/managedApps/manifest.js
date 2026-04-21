/**
 * manifest.js — Central registry for all managed apps.
 *
 * Adding a new app: one new object in MANAGED_APPS.
 * Adding a new intervention type: one new feature entry + native detection service.
 *
 * Each feature entry:
 *   key            — SharedPreferences field inside app_policies[pkg]
 *   label          — display name shown in AppDetail
 *   kind           — 'toggle' (default) | 'stepper'
 *   min/max/step   — only for kind='stepper'
 *
 * safeModePlatform — if present, AppDetail shows an "Open in Safe Mode" button
 *                    navigating to Browser with that platform param.
 */
export const MANAGED_APPS = [
  {
    pkg: 'com.instagram.android',
    label: 'Instagram',
    emoji: '📷',
    safeModePlatform: 'instagram',
    features: [
      { key: 'reels_detection', label: 'Reels Detection' },
      {
        key: 'session_post_limit',
        label: 'Home Feed Post Limit',
        kind: 'stepper',
        min: 5,
        max: 100,
        step: 5,
      },
    ],
  },
  {
    pkg: 'com.google.android.youtube',
    label: 'YouTube',
    emoji: '▶️',
    safeModePlatform: 'youtube',
    features: [{ key: 'shorts_detection', label: 'Shorts Detection' }],
  },
  {
    pkg: 'com.zhiliaoapp.musically',
    label: 'TikTok',
    emoji: '🎵',
    features: [
      { key: 'tiktok_foryou', label: 'For You Detection' },
      {
        key: 'session_post_limit',
        label: 'Session Post Limit',
        kind: 'stepper',
        min: 5,
        max: 100,
        step: 5,
      },
    ],
  },
  {
    pkg: 'com.reddit.frontpage',
    label: 'Reddit',
    emoji: '👽',
    features: [
      { key: 'reddit_feed', label: 'Feed / Video Detection' },
      {
        key: 'session_post_limit',
        label: 'Session Post Limit',
        kind: 'stepper',
        min: 5,
        max: 100,
        step: 5,
      },
    ],
  },
  {
    pkg: 'com.twitter.android',
    label: 'X / Twitter',
    emoji: '𝕏',
    features: [
      { key: 'twitter_foryou', label: 'For You Detection' },
      {
        key: 'session_post_limit',
        label: 'Session Post Limit',
        kind: 'stepper',
        min: 5,
        max: 100,
        step: 5,
      },
    ],
  },
  {
    pkg: 'com.snapchat.android',
    label: 'Snapchat',
    emoji: '👻',
    features: [{ key: 'snap_spotlight', label: 'Spotlight Detection' }],
  },
  {
    pkg: 'com.facebook.katana',
    label: 'Facebook',
    emoji: '📘',
    features: [
      { key: 'facebook_reels', label: 'Reels Detection' },
      { key: 'facebook_feed', label: 'Feed Detection' },
      {
        key: 'session_post_limit',
        label: 'Feed Post Limit',
        kind: 'stepper',
        min: 5,
        max: 100,
        step: 5,
      },
    ],
  },
];

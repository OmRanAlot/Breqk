/**
 * activeModeSettings.js — write home-screen edits into the ACTIVE mode.
 * ─────────────────────────────────────────────────────────────────────────────
 * The home screens (AppDetail per-app settings) are the ONLY place a mode is
 * edited. Whatever mode is active is what they edit: every per-app toggle lands
 * in that mode's `policy_overrides`, and the forced-pause duration lands in its
 * `setting_overrides.delay_time_seconds`. There is no separate "base" write path
 * and no editor modal any more — see docs/current_task.md.
 *
 * Persisting through `SettingsModule.saveModes` reuses the native reconcile that
 * already re-derives the effective blocked_apps set and pushes it to the live
 * monitor, so edits to the active mode take effect immediately.
 *
 * Logging prefix: [ActiveModeSettings]
 */

import { NativeModules } from 'react-native';

const { SettingsModule } = NativeModules;

/** The always-on baseline mode. Editing while it is active edits Default itself. */
export const DEFAULT_MODE_ID = 'default';

/**
 * Reads the active mode id and the full modes map. Falls back to the Default
 * mode id when nothing is active, so callers always have a concrete write target.
 *
 * @returns {Promise<{ activeModeId: string, modes: object }>}
 */
export async function getActiveModeContext() {
  const activeId = await new Promise(resolve =>
    SettingsModule.getActiveMode(resolve),
  );
  const modesJson = await new Promise(resolve => SettingsModule.getModes(resolve));
  let modes = {};
  try {
    modes = JSON.parse(modesJson || '{}');
  } catch (e) {
    console.warn('[ActiveModeSettings] parse modes failed:', e);
  }
  return { activeModeId: activeId || DEFAULT_MODE_ID, modes };
}

/**
 * Folds one app's settings into the ACTIVE mode and persists via saveModes.
 *
 * Every boolean key in `policy` is written into
 * `modes[active].policy_overrides[packageName]` (these are exactly the per-app
 * policy features — reels/feed/short-form detection, app_open_intercept,
 * enabled, free_break_enabled …). Non-boolean keys (e.g. the numeric
 * session_post_limit) are ignored here — they persist through their own global
 * setters in the caller.
 *
 * `delaySecs`, when provided, becomes the mode-wide forced-pause duration
 * (`setting_overrides.delay_time_seconds`). One value per mode, by design.
 *
 * @param {string} packageName
 * @param {Record<string, unknown>} policy Full local policy object for the app.
 * @param {number} [delaySecs] Forced-pause duration in seconds.
 * @returns {Promise<string>} The mode id that was written to.
 */
export async function saveAppSettingsToActiveMode(packageName, policy, delaySecs) {
  const { activeModeId, modes } = await getActiveModeContext();

  const mode = modes[activeModeId] ? { ...modes[activeModeId] } : {};
  const policyOverrides = { ...(mode.policy_overrides || {}) };
  const appOverride = { ...(policyOverrides[packageName] || {}) };

  Object.entries(policy || {}).forEach(([key, value]) => {
    if (typeof value === 'boolean') {
      appOverride[key] = value;
    }
  });
  policyOverrides[packageName] = appOverride;
  mode.policy_overrides = policyOverrides;

  if (typeof delaySecs === 'number' && Number.isFinite(delaySecs)) {
    mode.setting_overrides = {
      ...(mode.setting_overrides || {}),
      delay_time_seconds: Math.round(delaySecs),
    };
  }

  const nextModes = { ...modes, [activeModeId]: mode };
  console.log(
    '[ActiveModeSettings] saving',
    packageName,
    'into mode',
    activeModeId,
  );
  await SettingsModule.saveModes(JSON.stringify(nextModes));
  return activeModeId;
}

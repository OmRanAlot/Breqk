/**
 * useDefaultModeGate.js — "base settings are editable only in Default mode".
 * ─────────────────────────────────────────────────────────────────────────────
 * While a real mode (Study, Bedtime, custom…) is active, that mode's
 * policy_overrides / setting_overrides TAKE OVER the app's behaviour. Editing the
 * base settings underneath would be a lie: the write would land in prefs, be
 * masked by the mode, and appear to do nothing. So the Customize screen and the
 * per-app AppDetail screen go read-only whenever a non-default mode is on, and
 * tell the user to switch back to Default.
 *
 * "default" is the always-on baseline mode — it IS the base configuration, so it
 * does NOT gate anything. Same rule as the native side
 * (BreakPrefs.isBaseSettingsEditable), which independently rejects any write that
 * slips past this UI.
 *
 * Composes with, and does not replace, useSettingsLock: that gates on the
 * settings-lock countdown, this gates on the active mode. A control is editable
 * only when BOTH allow it.
 *
 * Usage:
 *   const { isEditable, activeModeName, activeModeColor, switchToDefault } =
 *     useDefaultModeGate(navigation);
 *
 * Logging prefix: [ModeGate]
 */

import { useState, useEffect, useCallback } from 'react';
import { NativeModules, AppState } from 'react-native';

const { SettingsModule, VPNModule } = NativeModules;

/** The always-on baseline mode. Not a mode the user deliberately enters. */
export const DEFAULT_MODE_ID = 'default';

/** How often to re-check the gate while a gated screen is open. */
const GATE_POLL_MS = 10000;

const useDefaultModeGate = (navigation = null) => {
  // Optimistic default: editable. A brief false would flash the read-only banner
  // on every mount; a brief true is harmless because the native layer rejects any
  // write that shouldn't land.
  const [isEditable, setIsEditable] = useState(true);
  const [activeModeId, setActiveModeId] = useState(null);
  const [activeModeName, setActiveModeName] = useState(null);
  const [activeModeColor, setActiveModeColor] = useState(null);
  const [activeModeIcon, setActiveModeIcon] = useState(null);

  /**
   * Reads the gate state from native, then resolves the active mode's display
   * name + color from the modes JSON so the banner renders in the mode's own
   * colour rather than a generic warning style.
   */
  const refresh = useCallback(() => {
    SettingsModule.getBaseSettingsEditable((editable, modeId) => {
      console.log(
        '[ModeGate] editable=' + editable + " activeMode='" + modeId + "'",
      );
      setIsEditable(editable);
      setActiveModeId(modeId || null);

      if (editable) {
        // Default mode (or none active) — nothing to name.
        setActiveModeName(null);
        setActiveModeColor(null);
        setActiveModeIcon(null);
        return;
      }

      SettingsModule.getModes(json => {
        try {
          const modes = json ? JSON.parse(json) : {};
          setActiveModeName(modes[modeId]?.name || 'A mode');
          setActiveModeColor(modes[modeId]?.color || null);
          setActiveModeIcon(modes[modeId]?.icon || null);
        } catch (e) {
          console.warn('[ModeGate] parse modes failed:', e);
          setActiveModeName('A mode');
          setActiveModeColor(null);
          setActiveModeIcon(null);
        }
      });
    });
  }, []);

  /**
   * Escape hatch: drop back to Default so the settings become editable again.
   * deactivateMode() falls back to "default" natively (ModeManager.deactivate).
   */
  const switchToDefault = useCallback(async () => {
    console.log('[ModeGate] switching to Default mode to unlock settings');
    try {
      await VPNModule.deactivateMode();
      refresh();
      return true;
    } catch (e) {
      console.warn('[ModeGate] switchToDefault failed:', e);
      return false;
    }
  }, [refresh]);

  // Load on mount, then re-check whenever the answer could have changed under
  // the user. A SCHEDULED mode (Bedtime at 22:00) activates from an AlarmManager
  // alarm and emits no JS event, so a screen left open would keep showing
  // editable controls whose writes native now rejects — hence the poll.
  useEffect(() => {
    refresh();

    const appStateSub = AppState.addEventListener('change', nextState => {
      if (nextState === 'active') refresh();
    });

    const focusUnsub = navigation?.addListener
      ? navigation.addListener('focus', refresh)
      : null;

    const interval = setInterval(refresh, GATE_POLL_MS);

    return () => {
      appStateSub?.remove();
      if (focusUnsub) focusUnsub();
      clearInterval(interval);
    };
  }, [refresh, navigation]);

  return {
    isEditable,
    activeModeId,
    activeModeName,
    activeModeColor,
    activeModeIcon,
    switchToDefault,
    refresh,
  };
};

export default useDefaultModeGate;

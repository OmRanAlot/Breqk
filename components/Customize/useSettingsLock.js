/**
 * useSettingsLock
 * ---------------
 * Drives the opt-in "Settings Change Lock" for ONE scope.
 *
 * A scope is either the literal string 'global' (the Customize screen) or a
 * managed app's package name (the AppDetail screen). Scopes are independent —
 * editing one never starts another's timer.
 *
 * Behaviour:
 *   - Reads native lock state on mount and whenever the screen regains focus.
 *   - Ticks once a second so a countdown stays live AND an expiring lock flips
 *     back to editable without leaving the screen.
 *   - `markDirty()` records that the user actually changed a protected setting.
 *   - When the user LEAVES the screen (navigation blur, app backgrounded, or
 *     unmount) AND the feature is enabled AND the scope was edited, the native
 *     lock (re)starts: lockUntil = now + duration. This is the "save & exit
 *     starts the timer" behaviour. While locked, the screen renders read-only.
 *   - Once the lock expires the scope stays editable until the next real edit.
 *
 * The lock is purely additive friction; it never blocks the underlying writes,
 * which still commit immediately via the normal save paths.
 *
 * Logging prefix: [SettingsLock]
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, NativeModules } from 'react-native';
import { deriveLockCycle } from '../shared/lockCycle';

const { SettingsModule } = NativeModules;

const EMPTY_STATE = {
  enabled: false,
  locked: false,
  anyLocked: false,
  lockUntilMs: 0,
  baseLockUntilMs: 0,
  durationMs: 24 * 60 * 60 * 1000,
};

/**
 * @param {string} scope            'global' or a package name
 * @param {object} [navigation]     React Navigation prop (optional)
 * @param {object} [options]
 * @param {boolean} [options.autoLockOnLeave=true]
 *        When true (default), the lock (re)starts automatically on the three
 *        "leaving the screen" signals (blur / background / unmount) — the
 *        original behaviour used by the global Customize screen. When false,
 *        leaving never starts the lock; the caller must arm it explicitly via
 *        the returned `startLock()`. AppDetail passes false so the lock arms on
 *        an explicit Save instead of on plain exit.
 * @returns {{
 *   enabled: boolean, locked: boolean, anyLocked: boolean, lockUntilMs: number,
 *   durationMs: number, remainingMs: number, markDirty: () => void,
 *   startLock: () => void, refresh: () => void,
 *   setEnabled: (v: boolean) => Promise<void>, setDurationHours: (h: number) => Promise<void>,
 * }}
 */
export default function useSettingsLock(scope, navigation, options = {}) {
  const { autoLockOnLeave = true } = options;
  const [state, setState] = useState(EMPTY_STATE);
  const [now, setNow] = useState(Date.now());

  // Whether the user changed a protected setting on this screen this visit.
  const dirtyRef = useRef(false);
  // Mirror enabled into a ref so the exit handler reads the latest value.
  const enabledRef = useRef(false);
  enabledRef.current = state.enabled;

  const refresh = useCallback(() => {
    try {
      SettingsModule.getSettingsLockState(scope, json => {
        try {
          const parsed = JSON.parse(json || '{}');
          setState({
            enabled: !!parsed.enabled,
            locked: !!parsed.locked,
            anyLocked: !!parsed.anyLocked,
            lockUntilMs: Number(parsed.lockUntil) || 0,
            baseLockUntilMs: Number(parsed.baseLockUntil) || 0,
            durationMs: Number(parsed.durationMs) || EMPTY_STATE.durationMs,
          });
        } catch (e) {
          console.warn('[SettingsLock] parse failed:', e?.message || e);
        }
      });
    } catch (e) {
      console.warn('[SettingsLock] refresh failed:', e?.message || e);
    }
  }, [scope]);

  const markDirty = useCallback(() => {
    dirtyRef.current = true;
  }, []);

  // Fires the native lock start for this scope and clears the dirty flag.
  const doStartLock = useCallback(
    reason => {
      console.log(
        '[SettingsLock] ' + reason + ' → starting lock for scope=',
        scope,
      );
      try {
        SettingsModule.startSettingsLock(scope);
      } catch (e) {
        console.warn(
          '[SettingsLock] startSettingsLock failed:',
          e?.message || e,
        );
      }
      dirtyRef.current = false;
    },
    [scope],
  );

  // Auto-start on exit: only when enabled, the scope was edited, AND the caller
  // opted into auto-lock-on-leave. AppDetail opts out and arms via startLock().
  const maybeStartLock = useCallback(() => {
    if (!autoLockOnLeave) return;
    if (!enabledRef.current || !dirtyRef.current) return;
    doStartLock('exit');
  }, [autoLockOnLeave, doStartLock]);

  // Explicit arm, e.g. from an AppDetail "Save" action. Arms only when the
  // feature is enabled; no-op otherwise. Refreshes so the UI flips to locked.
  const startLock = useCallback(() => {
    if (!enabledRef.current) return;
    doStartLock('save');
    refresh();
  }, [doStartLock, refresh]);

  // Load on mount + on focus return.
  useEffect(() => {
    refresh();
    const focusUnsub = navigation?.addListener
      ? navigation.addListener('focus', refresh)
      : null;
    return () => {
      if (focusUnsub) focusUnsub();
    };
  }, [navigation, refresh]);

  // Start the lock on the three "leaving the screen" signals.
  useEffect(() => {
    const blurUnsub = navigation?.addListener
      ? navigation.addListener('blur', maybeStartLock)
      : null;
    const appStateSub = AppState.addEventListener('change', next => {
      if (next !== 'active') maybeStartLock();
    });
    return () => {
      if (blurUnsub) blurUnsub();
      appStateSub.remove();
      // Unmount also counts as leaving the screen.
      maybeStartLock();
    };
  }, [navigation, maybeStartLock]);

  // Derive the live lock state from the stamped base timestamp using the same
  // math as the native layer. This flips the UI at lock expiry without waiting
  // for a native refresh.
  const cycle = deriveLockCycle({
    nowMs: now,
    baseLockUntilMs: state.baseLockUntilMs,
    durationMs: state.durationMs,
    graceMs: 0,
  });
  const liveLocked = state.enabled && cycle.locked;
  const remainingMs = liveLocked ? cycle.lockUntilMs - now : 0;

  // Tick while locked so the countdown stays live and expiry renders on time.
  useEffect(() => {
    if (!liveLocked) return undefined;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [liveLocked]);

  // Re-sync with native whenever our derived "locked" disagrees with the last
  // native read (i.e. a segment boundary passed) so anyLocked etc. stay fresh.
  useEffect(() => {
    if (liveLocked !== state.locked) {
      console.log('[SettingsLock] segment boundary crossed → refreshing');
      refresh();
    }
  }, [liveLocked, state.locked, refresh]);

  const setEnabled = useCallback(
    async value => {
      try {
        await SettingsModule.setSettingsLockEnabled(value);
        console.log('[SettingsLock] setEnabled →', value);
      } catch (e) {
        console.warn('[SettingsLock] setEnabled failed:', e?.message || e);
      }
      refresh();
    },
    [refresh],
  );

  const setDurationHours = useCallback(
    async hours => {
      try {
        await SettingsModule.setSettingsLockDuration(hours);
        console.log('[SettingsLock] setDurationHours →', hours);
      } catch (e) {
        console.warn('[SettingsLock] setDuration failed:', e?.message || e);
      }
      refresh();
    },
    [refresh],
  );

  return {
    enabled: state.enabled,
    locked: liveLocked,
    anyLocked: state.anyLocked,
    lockUntilMs: cycle.lockUntilMs || state.lockUntilMs,
    durationMs: state.durationMs,
    remainingMs,
    markDirty,
    startLock,
    refresh,
    setEnabled,
    setDurationHours,
  };
}

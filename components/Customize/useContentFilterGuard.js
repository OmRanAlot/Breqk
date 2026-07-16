/**
 * useContentFilterGuard
 * ---------------------
 * Drives the opt-in "double-safe" disable for the browser content filter.
 *
 * State machine (authoritative in native ContentFilterGuard.java; mirrored live
 * here via shared/lockCycle.deriveGuardState):
 *
 *   PROTECTED ─requestDisable→ PENDING_WAIT ─(wait ends)→ CONFIRM_WINDOW
 *     └────────────── cancelDisable / auto-expiry re-instates ──────────────┘
 *   CONFIRM_WINDOW ─confirmDisable→ DISABLED
 *
 * The hook refreshes from native on mount/focus, ticks once a second while a
 * pending disable is in flight, and re-syncs when a boundary passes (wait →
 * confirm window, or confirm window → auto re-instate).
 *
 * Logging prefix: [CFGuard]
 */

import { useCallback, useEffect, useState } from 'react';
import { NativeModules } from 'react-native';
import { deriveGuardState, GUARD_STATES } from '../shared/lockCycle';

const { SettingsModule } = NativeModules;

const EMPTY_STATE = {
  doubleSafeEnabled: false,
  filterEnabled: true,
  pendingDisableAtMs: 0,
  readyAtMs: 0,
  confirmWindowMs: 0,
};

/**
 * @param {object} [navigation] React Navigation prop (optional)
 * @returns {{
 *   state: string, doubleSafeEnabled: boolean, filterEnabled: boolean,
 *   readyAtMs: number, confirmEndsAtMs: number,
 *   waitRemainingMs: number, confirmRemainingMs: number,
 *   refresh: () => void,
 *   setDoubleSafe: (v: boolean) => Promise<boolean>,
 *   requestDisable: () => Promise<boolean>,
 *   confirmDisable: () => Promise<boolean>,
 *   cancelDisable: () => Promise<boolean>,
 * }}
 */
export default function useContentFilterGuard(navigation) {
  const [raw, setRaw] = useState(EMPTY_STATE);
  const [now, setNow] = useState(Date.now());

  const refresh = useCallback(() => {
    try {
      SettingsModule.getContentFilterGuardState(json => {
        try {
          const parsed = JSON.parse(json || '{}');
          setRaw({
            doubleSafeEnabled: !!parsed.doubleSafeEnabled,
            filterEnabled: !!parsed.filterEnabled,
            pendingDisableAtMs: Number(parsed.pendingDisableAt) || 0,
            readyAtMs: Number(parsed.readyAt) || 0,
            confirmWindowMs: Number(parsed.confirmWindowMs) || 0,
          });
        } catch (e) {
          console.warn('[CFGuard] parse failed:', e?.message || e);
        }
      });
    } catch (e) {
      console.warn('[CFGuard] refresh failed:', e?.message || e);
    }
  }, []);

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

  // Live derivation — same math as native, so boundaries flip without a read.
  const derived = deriveGuardState({
    nowMs: now,
    doubleSafeEnabled: raw.doubleSafeEnabled,
    filterEnabled: raw.filterEnabled,
    pendingDisableAtMs: raw.pendingDisableAtMs,
    readyAtMs: raw.readyAtMs,
    confirmWindowMs: raw.confirmWindowMs,
  });

  const isPendingFlight =
    derived.state === GUARD_STATES.PENDING_WAIT ||
    derived.state === GUARD_STATES.CONFIRM_WINDOW;

  // Tick while a pending disable is in flight so countdowns stay live.
  useEffect(() => {
    if (!isPendingFlight) return undefined;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [isPendingFlight]);

  // When the confirm window lapses, the derived state flips to PROTECTED while
  // native still stores the stale pending — refresh so native lazily clears it.
  useEffect(() => {
    const nativeHasPending = raw.pendingDisableAtMs > 0;
    if (nativeHasPending && derived.state === GUARD_STATES.PROTECTED) {
      console.log(
        '[CFGuard] confirm window lapsed → refreshing (auto re-instate)',
      );
      refresh();
    }
  }, [derived.state, raw.pendingDisableAtMs, refresh]);

  const setDoubleSafe = useCallback(
    async value => {
      try {
        await SettingsModule.setContentFilterDoubleSafe(value);
        console.log('[CFGuard] setDoubleSafe →', value);
        return true;
      } catch (e) {
        console.warn('[CFGuard] setDoubleSafe refused:', e?.message || e);
        return false;
      } finally {
        refresh();
      }
    },
    [refresh],
  );

  const requestDisable = useCallback(async () => {
    try {
      await SettingsModule.requestContentFilterDisable();
      console.log('[CFGuard] disable requested (barrier 1 down, wait started)');
      return true;
    } catch (e) {
      console.warn('[CFGuard] requestDisable refused:', e?.message || e);
      return false;
    } finally {
      refresh();
    }
  }, [refresh]);

  const confirmDisable = useCallback(async () => {
    try {
      await SettingsModule.confirmContentFilterDisable();
      console.log('[CFGuard] disable CONFIRMED — filter off');
      return true;
    } catch (e) {
      console.warn('[CFGuard] confirmDisable refused:', e?.message || e);
      return false;
    } finally {
      refresh();
    }
  }, [refresh]);

  const cancelDisable = useCallback(async () => {
    try {
      await SettingsModule.cancelContentFilterDisable();
      console.log('[CFGuard] pending disable cancelled');
      return true;
    } catch (e) {
      console.warn('[CFGuard] cancelDisable failed:', e?.message || e);
      return false;
    } finally {
      refresh();
    }
  }, [refresh]);

  return {
    state: derived.state,
    doubleSafeEnabled: raw.doubleSafeEnabled,
    filterEnabled: raw.filterEnabled,
    readyAtMs: derived.readyAtMs,
    confirmEndsAtMs: derived.confirmEndsAtMs,
    waitRemainingMs:
      derived.state === GUARD_STATES.PENDING_WAIT ? derived.readyAtMs - now : 0,
    confirmRemainingMs:
      derived.state === GUARD_STATES.CONFIRM_WINDOW
        ? derived.confirmEndsAtMs - now
        : 0,
    refresh,
    setDoubleSafe,
    requestDisable,
    confirmDisable,
    cancelDisable,
  };
}

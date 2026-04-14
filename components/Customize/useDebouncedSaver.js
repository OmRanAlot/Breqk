/*
 * useDebouncedSaver
 * -----------------
 * Coalesces rapid writes into a single commit after a quiet period.
 *
 * Usage:
 *   const saver = useDebouncedSaver(7000, { onCommit: () => showToast('Saved') });
 *   saver.schedule('appFeature:com.instagram.android:app_open_intercept', () => {
 *     SettingsModule.setAppFeature(pkg, feature, value);
 *   });
 *
 * Semantics:
 *   - Pending commits are keyed. Scheduling with the same key replaces any
 *     earlier commit for that key (last-write-wins per key).
 *   - The single debounce timer is re-armed on every schedule call. After the
 *     quiet period elapses, ALL pending commits fire in one batch, onCommit
 *     is invoked once, and the pending map is cleared.
 *   - flush() runs pending commits immediately (use on blur / background /
 *     unmount so writes never get silently dropped).
 *
 * Logging prefix: [Saver]
 */

import { useCallback, useEffect, useMemo, useRef } from 'react';

export default function useDebouncedSaver(delayMs, options = {}) {
  const { onCommit } = options;

  // Map<string, () => void> — committed functions keyed by a stable identifier
  const pendingRef = useRef(new Map());
  // Handle for the current debounce timer, or null when idle
  const timerRef = useRef(null);
  // Ref-hold onCommit so the timer callback always uses the latest value
  const onCommitRef = useRef(onCommit);
  useEffect(() => {
    onCommitRef.current = onCommit;
  }, [onCommit]);

  // Runs all pending commits synchronously and clears state.
  // Used by the timer fire path and by flush().
  const runPending = useCallback(() => {
    const pending = pendingRef.current;
    if (pending.size === 0) return;
    console.log(`[Saver] committing ${pending.size} pending write(s)`);
    pending.forEach((fn, key) => {
      try {
        fn();
      } catch (e) {
        console.warn(`[Saver] commit failed for key=${key}:`, e?.message || e);
      }
    });
    pending.clear();
    if (onCommitRef.current) onCommitRef.current();
  }, []);

  const schedule = useCallback(
    (key, commitFn) => {
      pendingRef.current.set(key, commitFn);
      console.log(`[Saver] scheduled key=${key} (pending=${pendingRef.current.size})`);
      if (timerRef.current) {
        clearTimeout(timerRef.current);
      }
      timerRef.current = setTimeout(() => {
        timerRef.current = null;
        runPending();
      }, delayMs);
    },
    [delayMs, runPending],
  );

  const flush = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    runPending();
  }, [runPending]);

  // Auto-flush on unmount so nothing silently drops when the user leaves the screen.
  useEffect(() => {
    return () => {
      flush();
    };
  }, [flush]);

  // Return a stable object so consumers can put this in useEffect dep arrays
  // without triggering resubscribe loops. schedule/flush are themselves stable
  // (useCallback with empty/stable deps), so the object identity never changes.
  return useMemo(() => ({ schedule, flush }), [schedule, flush]);
}

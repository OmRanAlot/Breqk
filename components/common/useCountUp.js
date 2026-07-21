/**
 * useCountUp.js — Animates a number counting up to a target value.
 * ─────────────────────────────────────────────────────────────────────────────
 * JS-driven (useNativeDriver: false) since the current value is read on every
 * frame via a listener rather than animating a native-driver-only property.
 */

import { useEffect, useRef, useState } from 'react';
import { Animated, Easing } from 'react-native';

export const COUNT_UP_DURATION_MS = 900;
export const BAR_FILL_DURATION_MS = 700;
export const FILL_EASING = Easing.out(Easing.cubic);

/**
 * @param {number|null|undefined} targetValue
 * @param {{ enabled?: boolean, duration?: number }} [options]
 * @returns {number} rounded value, animating toward targetValue while enabled
 */
export default function useCountUp(
  targetValue,
  { enabled = true, duration = COUNT_UP_DURATION_MS } = {},
) {
  const [displayValue, setDisplayValue] = useState(0);
  const animatedValue = useRef(new Animated.Value(0)).current;
  const lastAnimatedTarget = useRef(null);

  useEffect(() => {
    if (!enabled || targetValue == null) return;
    if (lastAnimatedTarget.current === targetValue) return;
    lastAnimatedTarget.current = targetValue;

    const listenerId = animatedValue.addListener(({ value }) => {
      setDisplayValue(Math.round(value));
    });

    Animated.timing(animatedValue, {
      toValue: targetValue,
      duration,
      easing: FILL_EASING,
      useNativeDriver: false,
    }).start();

    return () => animatedValue.removeListener(listenerId);
  }, [targetValue, enabled, duration, animatedValue]);

  return displayValue;
}

/**
 * BlockerInterstitial.tsx — "Is this intentional?" Overlay
 * ─────────────────────────────────────────────────────────────────────────────
 * Pure black full-screen overlay. The message text is static and centered.
 * Two rings expand outward from the text and fade (sonar/ripple effect), offset
 * in timing so they feel like continuous waves emanating from the question.
 *
 * Matches the native Android delay_overlay.xml design.
 *
 * Layout:
 *   • Pure black background
 *   • Center: two expanding ring pulses + static "Is this intentional?" text
 *   • Bottom: "Back to Reality" white pill + "Continue (Wait Xs)" ghost button
 *
 * Usage (preview in Customize screen):
 *   <BlockerInterstitial
 *     duration={5}
 *     onComplete={() => setPreviewVisible(false)}
 *     onForceClose={() => setPreviewVisible(false)}
 *   />
 *
 * Props:
 *   duration?     — Seconds before Continue enables. Default: 3
 *   onComplete?   — Called when "Back to Reality" is tapped
 *   onForceClose? — Called when "Continue" is tapped (after countdown)
 *
 * Logging prefix: [BlockerInterstitial]
 */

import React, { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Easing,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

// ─── Props ────────────────────────────────────────────────────────────────────
type Props = {
  /** Seconds before the Continue button becomes tappable. Default: 3 */
  duration?: number;
  /** Called when "Back to Reality" is tapped */
  onComplete?: () => void;
  /** Called when "Continue" is tapped (after countdown expires) */
  onForceClose?: () => void;
  /**
   * When true, shows the budget-exhausted variant:
   *   - Title changes to "Scroll time is up!"
   *   - Continue button is hidden
   *   - A live countdown to nextScrollAtMs is shown
   * Used only for Customize screen preview — actual lockout is the native overlay.
   */
  budgetExhausted?: boolean;
  /** Epoch ms when the next scroll window opens (used when budgetExhausted=true) */
  nextScrollAtMs?: number;
};

// ─── Ring animation helper ────────────────────────────────────────────────────
/**
 * Creates an infinite expanding-ring animation.
 * The ring scales from 0.6→2.0 while fading from opacity 0.4→0, then restarts.
 * Since opacity ends at 0, the restart jump is invisible.
 *
 * @param value   Animated.Value driven from 0 to 1
 * @param delay   Ms to wait before the first cycle starts (stagger between rings)
 * @param duration Ms for one full expand+fade cycle
 */
function createRingLoop(
  value: Animated.Value,
  delay: number,
  duration: number,
): Animated.CompositeAnimation {
  return Animated.loop(
    Animated.sequence([
      Animated.delay(delay),
      Animated.timing(value, {
        toValue: 1,
        duration,
        easing: Easing.out(Easing.ease),
        useNativeDriver: true,
      }),
      // Instant reset to 0 — invisible because opacity is 0 at toValue=1
      Animated.timing(value, {
        toValue: 0,
        duration: 0,
        useNativeDriver: true,
      }),
    ])
  );
}

// ─── Component ────────────────────────────────────────────────────────────────
const BlockerInterstitial: React.FC<Props> = ({
  duration = 3,
  onComplete,
  onForceClose,
  budgetExhausted = false,
  nextScrollAtMs,
}) => {
  const [countdown, setCountdown] = useState(duration);
  const [continueEnabled, setContinueEnabled] = useState(false);
  // Live countdown for budget-exhausted variant (ms until next scroll window)
  const [budgetCountdownMs, setBudgetCountdownMs] = useState(
    nextScrollAtMs ? Math.max(0, nextScrollAtMs - Date.now()) : 0,
  );

  // Two ring animation values, staggered so they feel like continuous waves
  const ring1 = useRef(new Animated.Value(0)).current;
  const ring2 = useRef(new Animated.Value(0)).current;

  // ── Mount: start ring animations and countdown ──────────────────────────────
  useEffect(() => {
    console.log('[BlockerInterstitial] mounted — countdown:', duration, 's');

    // Ring 1 starts immediately; Ring 2 starts 1000ms later for a stagger
    const CYCLE_MS = 2400;
    const loop1 = createRingLoop(ring1, 0, CYCLE_MS);
    const loop2 = createRingLoop(ring2, CYCLE_MS / 2, CYCLE_MS);
    loop1.start();
    loop2.start();
    console.log('[BlockerInterstitial] ring animations started');

    // Continue button countdown
    const interval = setInterval(() => {
      setCountdown((prev) => {
        const next = prev - 1;
        console.log('[BlockerInterstitial] continue countdown:', next);
        if (next <= 0) {
          clearInterval(interval);
          setContinueEnabled(true);
          console.log('[BlockerInterstitial] Continue button enabled');
          return 0;
        }
        return next;
      });
    }, 1000);

    // Budget countdown tick (only when budgetExhausted=true)
    let budgetInterval: ReturnType<typeof setInterval> | undefined;
    if (budgetExhausted && nextScrollAtMs) {
      budgetInterval = setInterval(() => {
        setBudgetCountdownMs(Math.max(0, nextScrollAtMs - Date.now()));
      }, 1000);
      console.log('[BlockerInterstitial] budget countdown started');
    }

    return () => {
      loop1.stop();
      loop2.stop();
      clearInterval(interval);
      if (budgetInterval) clearInterval(budgetInterval);
      console.log('[BlockerInterstitial] unmounted — animations and countdown stopped');
    };
  }, []); // mount-only

  // ── Interpolations ────────────────────────────────────────────────────────
  // Each ring: starts at scale 0.6 (slightly smaller than text), expands to 2.2
  const ring1Scale = ring1.interpolate({ inputRange: [0, 1], outputRange: [0.6, 2.2] });
  const ring1Opacity = ring1.interpolate({ inputRange: [0, 0.6, 1], outputRange: [0.4, 0.2, 0] });

  const ring2Scale = ring2.interpolate({ inputRange: [0, 1], outputRange: [0.6, 2.2] });
  const ring2Opacity = ring2.interpolate({ inputRange: [0, 0.6, 1], outputRange: [0.4, 0.2, 0] });

  // ── Button handlers ───────────────────────────────────────────────────────
  const handleBackToReality = () => {
    console.log('[BlockerInterstitial] "Back to Reality" tapped');
    onComplete?.();
  };

  const handleContinue = () => {
    if (!continueEnabled) return;
    console.log('[BlockerInterstitial] "Continue" tapped');
    onForceClose?.();
  };

  const continueLabel = continueEnabled ? 'Continue' : `Continue (Wait ${countdown}s)`;

  // Budget countdown label for exhausted variant
  const budgetCountdownLabel = (() => {
    if (!budgetExhausted) return '';
    if (budgetCountdownMs <= 0) return 'Ready to scroll again!';
    const totalSec = Math.floor(budgetCountdownMs / 1000);
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    return `You can scroll again in ${min}:${String(sec).padStart(2, '0')}`;
  })();

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <View style={styles.backdrop} accessibilityViewIsModal accessibilityLabel="Blocker overlay">

      {/* ── Center: expanding rings behind static text ───────────────────────── */}
      <View style={styles.centerSection}>
        {/* Ring 1 */}
        <Animated.View
          style={[
            styles.ring,
            { transform: [{ scale: ring1Scale }], opacity: ring1Opacity },
          ]}
        />
        {/* Ring 2 — staggered by half a cycle */}
        <Animated.View
          style={[
            styles.ring,
            { transform: [{ scale: ring2Scale }], opacity: ring2Opacity },
          ]}
        />

        {/* Title: changes based on variant */}
        <Text style={styles.questionText} accessibilityRole="text">
          {budgetExhausted ? 'Scroll time is up!' : 'Is this intentional?'}
        </Text>

        {/* Budget countdown (only in budget-exhausted variant) */}
        {budgetExhausted && (
          <Text style={styles.budgetCountdownText}>{budgetCountdownLabel}</Text>
        )}
      </View>

      {/* ── Bottom: action buttons ─────────────────────────────────────────── */}
      <View style={styles.bottomSection}>
        {/* Primary: Back to Reality */}
        <TouchableOpacity
          style={styles.primaryButton}
          activeOpacity={0.85}
          onPress={handleBackToReality}
          accessibilityRole="button"
          accessibilityLabel="Back to Reality"
        >
          <Text style={styles.primaryButtonText}>Back to Reality</Text>
        </TouchableOpacity>

        {/* Ghost: Continue with countdown — hidden when budget is exhausted */}
        {!budgetExhausted && (
          <TouchableOpacity
            style={[styles.ghostButton, continueEnabled && styles.ghostButtonEnabled]}
            activeOpacity={continueEnabled ? 0.75 : 1}
            onPress={handleContinue}
            disabled={!continueEnabled}
            accessibilityRole="button"
            accessibilityLabel={continueLabel}
            accessibilityState={{ disabled: !continueEnabled }}
          >
            <Text style={[styles.ghostButtonText, continueEnabled && styles.ghostButtonTextEnabled]}>
              {continueLabel}
            </Text>
          </TouchableOpacity>
        )}
      </View>
    </View>
  );
};

export default BlockerInterstitial;

// ─── Styles ───────────────────────────────────────────────────────────────────

// Ring base size — the expand animation scales this up to 2.2×
const RING_SIZE = 180;

const styles = StyleSheet.create({
  // Pure black, full-screen
  backdrop: {
    flex: 1,
    backgroundColor: '#000000',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 72,
    paddingHorizontal: 32,
  },

  // ── Center: fills remaining space, stacks rings + text via absolute positioning ──
  centerSection: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },

  // Expanding ring: absolutely centered so it overlaps the text area
  ring: {
    position: 'absolute',
    width: RING_SIZE,
    height: RING_SIZE,
    borderRadius: RING_SIZE / 2,
    borderWidth: 1,
    borderColor: '#FFFFFF',
  },

  // Static question text — sits on top of the rings, does not move
  questionText: {
    fontSize: 28,
    color: '#FFFFFF',
    fontWeight: '300',
    textAlign: 'center',
    lineHeight: 38,
    letterSpacing: -0.3,
    // Constrain width so text wraps within the ring's diameter
    maxWidth: RING_SIZE - 16,
  },

  // Budget countdown shown below the title when budgetExhausted=true
  budgetCountdownText: {
    marginTop: 16,
    fontSize: 15,
    color: 'rgba(255, 255, 255, 0.65)',
    textAlign: 'center',
    fontVariant: ['tabular-nums'],
  },

  // ── Bottom buttons ──────────────────────────────────────────────────────────
  bottomSection: {
    width: '100%',
    gap: 14,
  },

  // "Back to Reality" — white pill, black text
  primaryButton: {
    width: '100%',
    height: 56,
    backgroundColor: '#FFFFFF',
    borderRadius: 9999,
    justifyContent: 'center',
    alignItems: 'center',
  },
  primaryButtonText: {
    color: '#000000',
    fontSize: 18,
    fontWeight: '700',
  },

  // "Continue (Wait Xs)" — ghost pill, disabled
  ghostButton: {
    width: '100%',
    height: 48,
    borderRadius: 9999,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.2)',
    backgroundColor: 'rgba(255, 255, 255, 0.04)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  ghostButtonEnabled: {
    backgroundColor: 'rgba(255, 255, 255, 0.12)',
    borderColor: 'rgba(255, 255, 255, 0.35)',
  },
  ghostButtonText: {
    color: 'rgba(255, 255, 255, 0.45)',
    fontSize: 14,
    fontWeight: '500',
  },
  ghostButtonTextEnabled: {
    color: '#FFFFFF',
  },
});

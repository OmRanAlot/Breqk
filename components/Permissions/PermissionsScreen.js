/**
 * PermissionsScreen.js
 * ─────────────────────────────────────────────────────────────────────────────
 * 6-screen onboarding flow (Tether light design system):
 *   0 — Welcome          ("Stay Intentional")
 *   1 — Usage Access     (requestPermissions → PACKAGE_USAGE_STATS)
 *   2 — Overlay          (requestOverlayPermission → SYSTEM_ALERT_WINDOW)
 *   3 — VPN Background   (requestVpnPermission → BIND_VPN_SERVICE)
 *   4 — Uninstall Guard  (activateDeviceAdmin → DevicePolicyManager)
 *   5 — Success          ("You're All Set")
 *
 * When the user returns from Android Settings (AppState change), the screen
 * auto-advances if the relevant permission has been granted.
 * The VPN screen auto-advances on any app-return (no programmatic check).
 * The Device Admin screen checks checkPermissions().deviceAdmin on return.
 *
 * DEV bypass for Device Admin (ADB):
 *   adb shell dpm remove-active-admin com.breqk/.BreqkDeviceAdminReceiver
 *   adb uninstall com.breqk
 *
 * Logging prefix: [PermissionsScreen]
 */

import React, { useState, useEffect, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  AppState,
  NativeModules,
  SafeAreaView,
  Animated,
} from 'react-native';
import Svg, { Path, Circle, Rect, Polyline } from 'react-native-svg';

const { VPNModule, SettingsModule } = NativeModules;

// ─── Tether Light Palette ─────────────────────────────────────────────────────
// Matches design/tokens.ts `light` block for easy cross-reference.
const L = {
  bg: '#F8F8F6',
  charcoal: '#1A1A1A',
  muted: '#6B6B6B',
  placeholder: '#D1D5DB',
  ctaBg: '#1A1A1A',
  ctaText: '#FFFFFF',
  dotActive: '#1A1A1A',
  dotInactive: '#D1D5DB',
};

const TOTAL_SCREENS = 6; // indices 0-5

// ─── Inline SVG Icons ─────────────────────────────────────────────────────────
// Each icon is a React component sized 64×64, using charcoal stroke,
// with strokeWidth=1.2 to match the light aesthetic.

const ShieldIcon = () => (
  <Svg
    width={64}
    height={64}
    fill="none"
    stroke={L.charcoal}
    strokeWidth={1.2}
    strokeLinecap="round"
    strokeLinejoin="round"
    viewBox="0 0 24 24"
  >
    <Path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
    <Circle cx={12} cy={11} r={2} />
  </Svg>
);

const EyeIcon = () => (
  <Svg
    width={64}
    height={64}
    fill="none"
    stroke={L.charcoal}
    strokeWidth={1.2}
    strokeLinecap="round"
    strokeLinejoin="round"
    viewBox="0 0 24 24"
  >
    <Path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
    <Circle cx={12} cy={12} r={3} />
  </Svg>
);

const LayersIcon = () => (
  <Svg
    width={64}
    height={64}
    fill="none"
    stroke={L.charcoal}
    strokeWidth={1.2}
    strokeLinecap="round"
    strokeLinejoin="round"
    viewBox="0 0 24 24"
  >
    <Polyline points="12,2 2,7 12,12 22,7 12,2" />
    <Polyline points="2,17 12,22 22,17" />
    <Polyline points="2,12 12,17 22,12" />
  </Svg>
);

const LockIcon = () => (
  <Svg
    width={64}
    height={64}
    fill="none"
    stroke={L.charcoal}
    strokeWidth={1.2}
    strokeLinecap="round"
    strokeLinejoin="round"
    viewBox="0 0 24 24"
  >
    <Rect x={3} y={11} width={18} height={11} rx={2} ry={2} />
    <Path d="M7 11V7a5 5 0 0 1 10 0v4" />
  </Svg>
);

// Shield with a lock inside — used for the Device Admin (uninstall guard) screen
const ShieldLockIcon = () => (
  <Svg
    width={64}
    height={64}
    fill="none"
    stroke={L.charcoal}
    strokeWidth={1.2}
    strokeLinecap="round"
    strokeLinejoin="round"
    viewBox="0 0 24 24"
  >
    <Path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
    <Rect x={9} y={11} width={6} height={5} rx={1} />
    <Path d="M10 11V9a2 2 0 0 1 4 0v2" />
  </Svg>
);

const CheckCircleIcon = () => (
  <Svg
    width={96}
    height={96}
    fill="none"
    stroke={L.charcoal}
    strokeWidth={1.2}
    strokeLinecap="round"
    strokeLinejoin="round"
    viewBox="0 0 24 24"
  >
    <Path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
    <Polyline points="22,4 12,14.01 9,11.01" />
  </Svg>
);

// ─── Screen Definitions ───────────────────────────────────────────────────────
// `permKey` — key in VPNModule.checkPermissions() result (null = no check available)
// `callPermission` — function to open system permission dialog (null = no dialog)

const SCREENS = [
  {
    type: 'welcome', // no illustration box; charcoal shield icon
    Icon: ShieldIcon,
    headline: 'Stay Intentional',
    subtitle:
      'Breqk helps you reclaim your time by pausing mindless scrolling.',
    primaryLabel: 'Next',
    permKey: null,
    callPermission: null,
  },
  {
    type: 'permission', // gray illustration box
    Icon: EyeIcon,
    headline: 'Allow Usage Access',
    subtitle:
      'To detect when distracting apps are opened, we need permission to read which app is in the foreground.',
    primaryLabel: 'Grant Permission',
    permKey: 'usage',
    callPermission: () => VPNModule.requestPermissions(),
  },
  {
    type: 'permission',
    Icon: LayersIcon,
    headline: 'Show Pause Screen',
    subtitle:
      "We'll display a brief pause before opening a blocked app. This requires draw-over-apps permission.",
    primaryLabel: 'Grant Permission',
    permKey: 'overlay',
    callPermission: () => VPNModule.requestOverlayPermission(),
  },
  {
    type: 'permission',
    Icon: LockIcon,
    headline: 'Enable Background Guard',
    subtitle:
      'A VPN binding keeps the app alive in the background so monitoring works even when the screen is off.',
    primaryLabel: 'Grant Permission',
    permKey: null, // checkPermissions() doesn't expose VPN — auto-advance on return
    callPermission: () => VPNModule.requestVpnPermission(),
  },
  {
    // Screen 4 — Device Admin (uninstall protection)
    // permKey 'deviceAdmin' is checked by checkPermissions() on app return.
    // Skippable: committed users activate it; others can proceed without it.
    type: 'permission',
    Icon: ShieldLockIcon,
    headline: 'Protect Your Commitment',
    subtitle:
      'Prevent impulsive deletion. Activating Device Admin means you\u2019ll need to go through an extra step before uninstalling Breqk.',
    primaryLabel: 'Activate Protection',
    permKey: 'deviceAdmin',
    callPermission: () => VPNModule.activateDeviceAdmin(),
    // Rendered as an "Optional" pill above the headline so users understand
    // this step is different from the three hard requirements above it.
    optional: true,
  },
  {
    type: 'success', // no box; large check icon
    Icon: CheckCircleIcon,
    headline: "You're All Set",
    subtitle:
      'Breqk is now active. Customize your experience anytime in settings.',
    primaryLabel: 'Go to Home',
    permKey: null,
    callPermission: null,
  },
];

// ─── Component ────────────────────────────────────────────────────────────────
export default function PermissionsScreen({ onComplete }) {
  const [screenIndex, setScreenIndex] = useState(0);
  const appStateRef = useRef(AppState.currentState);
  // Track whether user visited the VPN screen (no callback from system; auto-advance)
  const vpnVisited = useRef(false);

  // Entrance animation for each screen transition
  const fadeAnim = useRef(new Animated.Value(0)).current;
  const slideAnim = useRef(new Animated.Value(20)).current;

  const animateIn = () => {
    fadeAnim.setValue(0);
    slideAnim.setValue(20);
    Animated.parallel([
      Animated.timing(fadeAnim, {
        toValue: 1,
        duration: 500,
        useNativeDriver: true,
      }),
      Animated.timing(slideAnim, {
        toValue: 0,
        duration: 500,
        useNativeDriver: true,
      }),
    ]).start();
  };

  // ── Navigation helpers ────────────────────────────────────────────────────

  const advanceTo = index => {
    console.log('[PermissionsScreen] advancing to screen', index);
    if (index >= TOTAL_SCREENS) {
      handleAllGranted();
      return;
    }
    setScreenIndex(index);
    // animateIn fires via useEffect below
  };

  /** Called when returning to foreground — re-check permissions and advance if granted. */
  const checkAndAdvance = async currentIndex => {
    console.log('[PermissionsScreen] checkAndAdvance on screen', currentIndex);
    try {
      const screen = SCREENS[currentIndex];

      // VPN screen has no programmatic check — just advance on any return
      if (
        screen.type === 'permission' &&
        screen.permKey === null &&
        vpnVisited.current
      ) {
        console.log('[PermissionsScreen] VPN screen — app returned, advancing');
        vpnVisited.current = false;
        advanceTo(currentIndex + 1);
        return;
      }

      // For other permission screens, check if it was actually granted
      if (screen.permKey) {
        const perms = await VPNModule.checkPermissions();
        console.log(
          '[PermissionsScreen] permissions check result:',
          JSON.stringify(perms),
        );
        if (perms[screen.permKey]) {
          console.log(
            '[PermissionsScreen] permission granted:',
            screen.permKey,
          );
          advanceTo(currentIndex + 1);
        }
      }
    } catch (e) {
      console.warn('[PermissionsScreen] checkAndAdvance error:', e);
    }
  };

  // ── Primary button handler ────────────────────────────────────────────────

  const handlePrimary = async () => {
    const screen = SCREENS[screenIndex];
    console.log(
      '[PermissionsScreen] primary tapped — screen',
      screenIndex,
      screen.type,
    );

    if (screen.type === 'welcome') {
      advanceTo(1);
      return;
    }
    if (screen.type === 'success') {
      handleAllGranted();
      return;
    }

    // Permission screen: open system dialog
    if (screen.permKey === null) {
      // VPN — mark visited before leaving so AppState handler can advance
      vpnVisited.current = true;
    }
    try {
      const result = await screen.callPermission();
      console.log(
        '[PermissionsScreen] callPermission resolved for screen',
        screenIndex,
        'result:',
        result,
      );
      // If the native method returns true it means the permission was already granted
      // (e.g. Device Admin was already active). Advance immediately rather than waiting
      // for an AppState change that will never come.
      if (result === true && screen.permKey) {
        console.log(
          '[PermissionsScreen] already granted — advancing immediately',
        );
        advanceTo(screenIndex + 1);
      }
    } catch (e) {
      console.warn('[PermissionsScreen] callPermission rejected:', e);
    }
  };

  // ── Start monitoring when all permissions are done ────────────────────────

  const handleAllGranted = async () => {
    console.log(
      '[PermissionsScreen] handleAllGranted — loading saved blocked apps',
    );
    try {
      const savedApps = await new Promise(resolve => {
        SettingsModule.getBlockedApps(apps => resolve(apps));
      });
      const blockedList =
        savedApps && savedApps.length > 0
          ? savedApps
          : ['com.instagram.android', 'com.google.android.youtube'];

      await VPNModule.setBlockedApps(blockedList);
      await VPNModule.startMonitoring();
      SettingsModule.saveMonitoringEnabled(true);
      console.log('[PermissionsScreen] monitoring started successfully');
    } catch (e) {
      console.warn('[PermissionsScreen] auto-start failed (non-fatal):', e);
    }
    onComplete();
  };

  // ── Effects ───────────────────────────────────────────────────────────────

  // Animate in on mount
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    animateIn();
  }, []);

  // Animate in whenever screenIndex changes
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    animateIn();
  }, [screenIndex]);

  // Listen for app foreground return → check permissions
  // Re-subscribe when screenIndex changes so the closure captures the latest value.
  useEffect(() => {
    const sub = AppState.addEventListener('change', nextState => {
      const wasBackground = appStateRef.current.match(/inactive|background/);
      if (wasBackground && nextState === 'active') {
        console.log(
          '[PermissionsScreen] app foregrounded — checking permissions',
        );
        checkAndAdvance(screenIndex);
      }
      appStateRef.current = nextState;
    });
    return () => sub.remove();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [screenIndex]);

  // ── Render ────────────────────────────────────────────────────────────────

  const screen = SCREENS[screenIndex];
  const { Icon } = screen;

  return (
    <SafeAreaView style={styles.safe}>
      <View style={styles.container}>
        {/* ── Animated top section (illustration + text) ─────────────── */}
        <Animated.View
          style={[
            styles.topSection,
            { opacity: fadeAnim, transform: [{ translateY: slideAnim }] },
          ]}
        >
          {/* Illustration area — varies by screen type */}
          {screen.type === 'permission' ? (
            // Gray rounded illustration placeholder (stitch screen 5 style)
            <View style={styles.illustrationBox}>
              <Icon />
            </View>
          ) : (
            // Welcome + Success: no box, just the icon
            <View style={styles.iconWrapper}>
              <Icon />
            </View>
          )}

          {/* Optional pill — only on skippable, non-critical screens */}
          {screen.optional && (
            <View style={styles.optionalPill}>
              <Text style={styles.optionalPillText}>OPTIONAL</Text>
            </View>
          )}

          {/* Headline */}
          <Text
            style={[
              styles.headline,
              screen.type === 'welcome' && styles.headlineLight,
            ]}
          >
            {screen.headline}
          </Text>

          {/* Subtitle */}
          <Text style={styles.subtitle}>{screen.subtitle}</Text>
        </Animated.View>

        {/* ── Flexible spacer ────────────────────────────────────────── */}
        <View style={styles.spacer} />

        {/* ── Bottom section (dots + buttons) ────────────────────────── */}
        <View style={styles.bottomSection}>
          {/* Pagination dots (5 total) */}
          <View style={styles.dotsRow}>
            {Array.from({ length: TOTAL_SCREENS }).map((_, i) => (
              <View
                key={i}
                style={[
                  styles.dot,
                  i === screenIndex ? styles.dotActive : styles.dotInactive,
                ]}
              />
            ))}
          </View>

          {/* Primary CTA */}
          <TouchableOpacity
            style={styles.primaryButton}
            onPress={handlePrimary}
            activeOpacity={0.85}
            accessibilityRole="button"
            accessibilityLabel={screen.primaryLabel}
          >
            <Text style={styles.primaryButtonText}>{screen.primaryLabel}</Text>
          </TouchableOpacity>

          {/* Skip (only for permission screens that have a checkable permKey) */}
          {screen.type === 'permission' && screen.permKey !== null && (
            <TouchableOpacity
              style={styles.skipButton}
              onPress={() => {
                console.log('[PermissionsScreen] skipped screen', screenIndex);
                advanceTo(screenIndex + 1);
              }}
              activeOpacity={0.6}
              accessibilityRole="button"
              accessibilityLabel="Skip for now"
            >
              <Text style={styles.skipButtonText}>Skip for now</Text>
            </TouchableOpacity>
          )}
        </View>
      </View>
    </SafeAreaView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  safe: {
    flex: 1,
    backgroundColor: L.bg,
  },
  container: {
    flex: 1,
    paddingHorizontal: 32,
    paddingTop: 48,
    paddingBottom: 40,
    alignItems: 'center',
  },

  // Top animated section
  topSection: {
    alignItems: 'center',
    width: '100%',
  },

  // Gray rounded illustration box used on permission screens
  illustrationBox: {
    width: 220,
    height: 220,
    borderRadius: 24,
    backgroundColor: L.placeholder,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 40,
    // Subtle shadow to lift the box
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 2,
  },

  // Transparent wrapper for welcome / success icons
  iconWrapper: {
    width: 120,
    height: 120,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 40,
  },

  optionalPill: {
    backgroundColor: 'rgba(26,26,26,0.06)',
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 9999,
    marginBottom: 12,
  },
  optionalPillText: {
    fontSize: 10,
    fontWeight: '700',
    color: L.muted,
    letterSpacing: 1.4,
  },

  headline: {
    fontSize: 30,
    fontWeight: '600',
    color: L.charcoal,
    textAlign: 'center',
    letterSpacing: -0.5,
    marginBottom: 14,
  },
  // Welcome screen uses a lighter, larger headline (stitch screen 4 style)
  headlineLight: {
    fontSize: 32,
    fontWeight: '300',
  },

  subtitle: {
    fontSize: 17,
    color: L.muted,
    textAlign: 'center',
    lineHeight: 26,
    paddingHorizontal: 8,
  },

  spacer: {
    flex: 1,
  },

  // Bottom section
  bottomSection: {
    width: '100%',
    alignItems: 'center',
    gap: 14,
  },

  // Pagination dot row
  dotsRow: {
    flexDirection: 'row',
    gap: 8,
    marginBottom: 8,
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: 3,
  },
  dotActive: {
    backgroundColor: L.dotActive,
  },
  dotInactive: {
    backgroundColor: L.dotInactive,
  },

  // Primary button (pill-shaped, charcoal fill)
  primaryButton: {
    backgroundColor: L.ctaBg,
    borderRadius: 9999,
    paddingVertical: 18,
    paddingHorizontal: 32,
    width: '100%',
    alignItems: 'center',
    // Subtle shadow
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.12,
    shadowRadius: 4,
    elevation: 2,
  },
  primaryButtonText: {
    color: L.ctaText,
    fontSize: 17,
    fontWeight: '600',
  },

  // Skip link (text-only, secondary)
  skipButton: {
    paddingVertical: 10,
  },
  skipButtonText: {
    color: L.muted,
    fontSize: 15,
    fontWeight: '500',
  },
});

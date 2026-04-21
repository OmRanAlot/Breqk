/**
 * AppDetail.js — Per-app settings screen.
 *
 * Receives { packageName } from route.params and renders all controls
 * for that app from the manifest:
 *   • Enabled master toggle (short-circuits everything below when off)
 *   • App Open Intercept toggle (universal — delay overlay on launch)
 *   • Per-app feature toggles (Switch or stepper per manifest kind)
 *   • 20-Min Free Break toggle (once per day, scoped to this app)
 *   • "Open in Safe Mode" button (apps with safeModePlatform only)
 *
 * Writes immediately on each toggle via SettingsModule.setAppFeature.
 * For Instagram session_post_limit, also calls saveHomeFeedPostLimit
 * for backwards compatibility with the native ReelsInterventionService.
 *
 * Logging prefix: [AppDetail]
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  Switch,
  TouchableOpacity,
  ScrollView,
  StyleSheet,
  NativeModules,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path } from 'react-native-svg';
import { MANAGED_APPS } from '../managedApps/manifest';

const { VPNModule, SettingsModule } = NativeModules;

const L = {
  bg: '#FAFAFA',
  charcoal: '#1A1A1A',
  muted: '#737373',
  border: '#E5E5E5',
  cardBg: '#FFFFFF',
  cardBorder: 'rgba(0,0,0,0.07)',
};

const BackIcon = ({ color, size }) => (
  <Svg
    width={size}
    height={size}
    fill="none"
    stroke={color}
    strokeWidth={1.5}
    strokeLinecap="round"
    strokeLinejoin="round"
    viewBox="0 0 24 24"
  >
    <Path d="M15 19l-7-7 7-7" />
  </Svg>
);

const DEFAULT_POST_LIMIT = 20;

const AppDetail = ({ navigation, route }) => {
  const { packageName } = route.params;
  const insets = useSafeAreaInsets();

  const appInfo = MANAGED_APPS.find(a => a.pkg === packageName);

  const [policy, setPolicy] = useState({});
  const [postLimit, setPostLimit] = useState(DEFAULT_POST_LIMIT);

  useEffect(() => {
    console.log('[AppDetail] loading policy for', packageName);
    SettingsModule.getAppPolicies(json => {
      try {
        const policies = JSON.parse(json || '{}');
        const p = policies[packageName] || {};
        setPolicy(p);

        if (typeof p.session_post_limit === 'number') {
          setPostLimit(p.session_post_limit);
        } else if (packageName === 'com.instagram.android') {
          SettingsModule.getHomeFeedPostLimit(v => {
            setPostLimit(typeof v === 'number' ? v : DEFAULT_POST_LIMIT);
          });
        }

        console.log('[AppDetail] policy loaded:', JSON.stringify(p));
      } catch (e) {
        console.warn('[AppDetail] parse policy failed:', e);
      }
    });
  }, [packageName]);

  const setFeature = useCallback(
    async (key, value) => {
      console.log('[AppDetail] setFeature', packageName, key, '→', value);
      setPolicy(prev => ({ ...prev, [key]: value }));
      try {
        await SettingsModule.setAppFeature(packageName, key, value);
        if (key === 'app_open_intercept') {
          VPNModule.startMonitoring().catch(() => {});
        }
      } catch (e) {
        console.error('[AppDetail] setAppFeature failed:', e);
      }
    },
    [packageName],
  );

  const stepperFeature = appInfo?.features.find(
    f => f.key === 'session_post_limit',
  );

  const adjustPostLimit = useCallback(
    delta => {
      if (!stepperFeature) {
        return;
      }
      const next = Math.max(
        stepperFeature.min,
        Math.min(stepperFeature.max, postLimit + delta * stepperFeature.step),
      );
      setPostLimit(next);
      console.log('[AppDetail] session_post_limit →', next);
      SettingsModule.setAppFeature(packageName, 'session_post_limit', next);
      if (packageName === 'com.instagram.android') {
        SettingsModule.saveHomeFeedPostLimit(next);
      }
    },
    [packageName, postLimit, stepperFeature],
  );

  if (!appInfo) {
    return null;
  }

  const isEnabled = policy.enabled !== false;
  const toggleFeatures = appInfo.features.filter(f => f.kind !== 'stepper');
  const hasSafeMode = Boolean(appInfo.safeModePlatform);

  return (
    <View style={[styles.container, { paddingTop: Math.max(insets.top, 0) }]}>
      {/* ── Header ── */}
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.backButton}
          onPress={() => navigation.goBack()}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel="Back"
        >
          <BackIcon color={L.charcoal} size={22} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>
          {appInfo.emoji} {appInfo.label}
        </Text>
        <View style={styles.headerSpacer} />
      </View>

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {/* ── Master toggle ── */}
        <View style={styles.section}>
          <View style={styles.toggleRow}>
            <View style={styles.toggleLabelGroup}>
              <Text style={styles.toggleLabel}>Enable</Text>
              <Text style={styles.toggleCaption}>
                Turn off to disable all interventions for {appInfo.label}.
              </Text>
            </View>
            <Switch
              value={isEnabled}
              onValueChange={val => setFeature('enabled', val)}
              trackColor={{ false: '#D6D6D6', true: L.charcoal }}
              thumbColor="#FFFFFF"
              accessibilityLabel={`Enable ${appInfo.label} interventions`}
            />
          </View>
        </View>

        {/* ── Per-app controls (gated on master toggle) ── */}
        {isEnabled && (
          <>
            {/* App Open Intercept */}
            <View style={styles.section}>
              <Text style={styles.sectionLabel}>App Open</Text>
              <View style={styles.toggleRow}>
                <View style={styles.toggleLabelGroup}>
                  <Text style={styles.toggleLabel}>App Open Intercept</Text>
                  <Text style={styles.toggleCaption}>
                    Show a delay overlay every time you open {appInfo.label}.
                  </Text>
                </View>
                <Switch
                  value={policy.app_open_intercept === true}
                  onValueChange={val => setFeature('app_open_intercept', val)}
                  trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                  thumbColor="#FFFFFF"
                  accessibilityLabel="App Open Intercept"
                />
              </View>
            </View>

            {/* Feature toggles */}
            {toggleFeatures.length > 0 && (
              <View style={styles.section}>
                <Text style={styles.sectionLabel}>Interventions</Text>
                {toggleFeatures.map((feature, i) => (
                  <View
                    key={feature.key}
                    style={[styles.toggleRow, i > 0 && styles.toggleRowDivided]}
                  >
                    <Text style={styles.toggleLabel}>{feature.label}</Text>
                    <Switch
                      value={policy[feature.key] === true}
                      onValueChange={val => setFeature(feature.key, val)}
                      trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                      thumbColor="#FFFFFF"
                      accessibilityLabel={feature.label}
                    />
                  </View>
                ))}
              </View>
            )}

            {/* Session post limit stepper */}
            {stepperFeature && (
              <View style={styles.section}>
                <Text style={styles.sectionLabel}>{stepperFeature.label}</Text>
                <Text style={styles.sectionCaption}>
                  After this many posts, you'll be prompted to stop scrolling.
                </Text>
                <View style={styles.stepperRow}>
                  <TouchableOpacity
                    style={styles.stepperBtn}
                    onPress={() => adjustPostLimit(-1)}
                    accessibilityRole="button"
                    accessibilityLabel="Decrease limit"
                  >
                    <Text style={styles.stepperBtnText}>−</Text>
                  </TouchableOpacity>
                  <Text style={styles.stepperValue}>{postLimit}</Text>
                  <TouchableOpacity
                    style={styles.stepperBtn}
                    onPress={() => adjustPostLimit(1)}
                    accessibilityRole="button"
                    accessibilityLabel="Increase limit"
                  >
                    <Text style={styles.stepperBtnText}>+</Text>
                  </TouchableOpacity>
                  <Text style={styles.stepperUnit}>posts</Text>
                </View>
              </View>
            )}

            {/* 20-Min Free Break */}
            <View style={styles.section}>
              <Text style={styles.sectionLabel}>Free Break</Text>
              <View style={styles.toggleRow}>
                <View style={styles.toggleLabelGroup}>
                  <Text style={styles.toggleLabel}>20-Min Free Break</Text>
                  <Text style={styles.toggleCaption}>
                    Once per day — scroll freely for 20 min with no
                    interruptions.
                  </Text>
                </View>
                <Switch
                  value={policy.free_break_enabled === true}
                  onValueChange={val => setFeature('free_break_enabled', val)}
                  trackColor={{ false: '#D6D6D6', true: L.charcoal }}
                  thumbColor="#FFFFFF"
                  accessibilityLabel="20-Minute Free Break"
                />
              </View>
            </View>

            {/* Safe Mode */}
            {hasSafeMode && (
              <View style={styles.section}>
                <Text style={styles.sectionLabel}>Safe Mode</Text>
                <Text style={styles.sectionCaption}>
                  Opens {appInfo.label} through a restricted browser — no Reels
                  or Shorts.
                </Text>
                <TouchableOpacity
                  style={styles.safeModeButton}
                  activeOpacity={0.85}
                  onPress={() => {
                    console.log(
                      '[AppDetail] Open Safe Mode:',
                      appInfo.safeModePlatform,
                    );
                    navigation.navigate('Browser', {
                      platform: appInfo.safeModePlatform,
                    });
                  }}
                  accessibilityRole="button"
                  accessibilityLabel={`Open ${appInfo.label} in Safe Mode`}
                >
                  <Text style={styles.safeModeButtonText}>
                    Open in Safe Mode
                  </Text>
                </TouchableOpacity>
              </View>
            )}
          </>
        )}
      </ScrollView>
    </View>
  );
};

export default AppDetail;

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: L.bg,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    backgroundColor: L.bg,
    borderBottomWidth: 1,
    borderBottomColor: L.border,
  },
  backButton: {
    width: 36,
    height: 36,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerTitle: {
    flex: 1,
    textAlign: 'center',
    fontSize: 18,
    fontWeight: '500',
    color: L.charcoal,
    letterSpacing: -0.2,
  },
  headerSpacer: { width: 36 },

  scroll: { flex: 1 },
  scrollContent: {
    paddingHorizontal: 24,
    paddingTop: 28,
    paddingBottom: 48,
  },

  section: { marginBottom: 32 },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: L.charcoal,
    textTransform: 'uppercase',
    letterSpacing: 1.5,
    marginBottom: 12,
  },
  sectionCaption: {
    fontSize: 12,
    color: L.muted,
    lineHeight: 17,
    marginBottom: 14,
    marginTop: -4,
  },

  toggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 4,
  },
  toggleRowDivided: {
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: L.border,
  },
  toggleLabelGroup: {
    flex: 1,
    marginRight: 12,
  },
  toggleLabel: {
    fontSize: 16,
    color: L.charcoal,
    fontWeight: '400',
  },
  toggleCaption: {
    fontSize: 12,
    color: L.muted,
    marginTop: 3,
    lineHeight: 17,
  },

  stepperRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  stepperBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    borderWidth: 1,
    borderColor: L.border,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: L.cardBg,
  },
  stepperBtnText: {
    fontSize: 20,
    color: L.charcoal,
    lineHeight: 24,
  },
  stepperValue: {
    fontSize: 22,
    fontWeight: '300',
    color: L.charcoal,
    minWidth: 40,
    textAlign: 'center',
  },
  stepperUnit: {
    fontSize: 14,
    color: L.muted,
  },

  safeModeButton: {
    backgroundColor: L.charcoal,
    borderRadius: 9999,
    paddingVertical: 14,
    paddingHorizontal: 28,
    alignItems: 'center',
    alignSelf: 'flex-start',
  },
  safeModeButtonText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '500',
  },
});

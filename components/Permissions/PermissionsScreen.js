/**
 * PermissionsScreen.js
 * ─────────────────────────────────────────────────────────────────────────────
 * Break first-run onboarding — 9 screens, implemented from the
 * "Break Onboarding" handoff design (claude.ai/design):
 *
 *   0 — Welcome              ("Stay intentional")
 *   1 — Apps to manage       (multi-select, smart defaults)
 *   2 — Intercept message    (presets + write-your-own, live preview)
 *   3 — Per-app breath        (on/off + 5/15/30s per selected app)
 *   4 — Permission · Accessibility       (required, no skip)
 *   5 — Permission · Usage Access        (required, no skip)
 *   6 — Permission · Display Over Apps   (required, no skip)
 *   7 — Prevent deletion     (optional opt-in, skippable)
 *   8 — Done                 ("You're all set")
 *
 * Friction is removed via pre-selected apps, reversible choices, and a single
 * step-counter. The three permission screens are mandatory: there is no skip,
 * and each only advances once the permission is actually granted (verified on
 * foreground return). Privacy is reassured on every permission screen and the
 * final summary — Break collects no data at all.
 *
 * Selections are persisted and monitoring is started in `handleComplete()`.
 * The `onComplete` contract is unchanged from the previous implementation, so
 * App.tsx integration needs no edits.
 *
 * Logging prefix: [PermissionsScreen]
 */

import React, { useState, useEffect, useRef } from 'react';
import {
  View,
  Text,
  ScrollView,
  TextInput,
  AppState,
  NativeModules,
  SafeAreaView,
  Animated,
  TouchableOpacity,
} from 'react-native';
import { ShieldIcon, CheckIcon } from './onboarding/icons';
import {
  PillButton,
  Eyebrow,
  StepHeader,
  ProgressDots,
  ReassuranceCard,
  AppSelectRow,
  Monogram,
  Toggle,
  Segmented,
} from './onboarding/components';
import {
  T,
  ONBOARDING_APPS,
  DEFAULT_SELECTED,
  MESSAGE_PRESETS,
  BREATH_DURATIONS,
  DEFAULT_BREATH,
  BREATH_FALLBACK,
} from './onboarding/theme';
import { PERMISSION_STEPS } from './permissionSteps';
import { styles, strongStyle } from './PermissionsScreen.styles';

const { VPNModule, SettingsModule } = NativeModules;

const TOTAL_STEPS = 9;
const FIRST_PERMISSION_STEP = 4;
// Optional deletion-prevention opt-in, shown after the required permissions
// (it relies on the accessibility service granted at step 4) and before Done.
const PROTECT_STEP = 7;

/**
 * Merge native permission checks into a single map. checkPermissions() returns
 * { usage, overlay } and, on builds with the native accessibility addition,
 * { accessibility }. We fall back to SettingsModule.isContentFilterServiceEnabled
 * for the accessibility flag so the flow works against older native builds too.
 *
 * @returns {Promise<{usage:boolean, overlay:boolean, accessibility:boolean}>}
 */
async function checkAllPermissions() {
  const perms = await VPNModule.checkPermissions();
  let accessibility = perms.accessibility;
  if (typeof accessibility !== 'boolean') {
    accessibility = await new Promise(resolve => {
      try {
        SettingsModule.isContentFilterServiceEnabled(enabled =>
          resolve(!!enabled),
        );
      } catch (e) {
        resolve(false);
      }
    });
  }
  return { usage: !!perms.usage, overlay: !!perms.overlay, accessibility };
}

export default function PermissionsScreen({ onComplete }) {
  const [step, setStep] = useState(0);
  const [selectedApps, setSelectedApps] = useState(DEFAULT_SELECTED);
  const [message, setMessage] = useState(MESSAGE_PRESETS[0]);
  const [customMode, setCustomMode] = useState(false);
  const [customText, setCustomText] = useState('');
  const [breath, setBreath] = useState(() => ({ ...DEFAULT_BREATH }));
  const [protectEnabled, setProtectEnabled] = useState(false);

  const appStateRef = useRef(AppState.currentState);
  const fadeAnim = useRef(new Animated.Value(0)).current;
  const slideAnim = useRef(new Animated.Value(16)).current;

  // ── Entrance animation on each step change ─────────────────────────────────
  useEffect(() => {
    fadeAnim.setValue(0);
    slideAnim.setValue(16);
    Animated.parallel([
      Animated.timing(fadeAnim, {
        toValue: 1,
        duration: 360,
        useNativeDriver: true,
      }),
      Animated.timing(slideAnim, {
        toValue: 0,
        duration: 360,
        useNativeDriver: true,
      }),
    ]).start();
  }, [step, fadeAnim, slideAnim]);

  // ── Auto-advance permission steps once the permission is actually granted ──
  useEffect(() => {
    const sub = AppState.addEventListener('change', async nextState => {
      const wasBackground = appStateRef.current.match(/inactive|background/);
      appStateRef.current = nextState;
      if (!(wasBackground && nextState === 'active')) {
        return;
      }
      if (
        step < FIRST_PERMISSION_STEP ||
        step >= FIRST_PERMISSION_STEP + PERMISSION_STEPS.length
      ) {
        return;
      }
      const config = PERMISSION_STEPS[step - FIRST_PERMISSION_STEP];
      try {
        const perms = await checkAllPermissions();
        console.log(
          '[PermissionsScreen] foreground re-check:',
          JSON.stringify(perms),
        );
        if (perms[config.permKey]) {
          console.log(
            '[PermissionsScreen] granted:',
            config.permKey,
            '→ advancing',
          );
          setStep(s => s + 1);
        }
      } catch (e) {
        console.warn('[PermissionsScreen] permission re-check failed:', e);
      }
    });
    return () => sub.remove();
  }, [step]);

  // ── State helpers ──────────────────────────────────────────────────────────
  const toggleApp = pkg => {
    setSelectedApps(prev => {
      if (prev.includes(pkg)) {
        return prev.filter(p => p !== pkg);
      }
      // Seed a breath default for newly selected apps that lack one.
      setBreath(b => (b[pkg] ? b : { ...b, [pkg]: { ...BREATH_FALLBACK } }));
      return [...prev, pkg];
    });
  };

  const setBreathOn = (pkg, on) =>
    setBreath(b => ({ ...b, [pkg]: { ...(b[pkg] || BREATH_FALLBACK), on } }));
  const setBreathSecs = (pkg, secs) =>
    setBreath(b => ({
      ...b,
      [pkg]: { ...(b[pkg] || BREATH_FALLBACK), secs, on: true },
    }));

  const chooseMessage = preset => {
    setCustomMode(false);
    setMessage(preset);
  };
  const enableCustom = () => {
    setCustomMode(true);
    setMessage(customText);
  };
  const onCustomChange = text => {
    setCustomText(text);
    setMessage(text);
  };

  const next = () => setStep(s => Math.min(s + 1, TOTAL_STEPS - 1));
  const back = () => setStep(s => Math.max(s - 1, 0));

  // ── Permission CTA — open the relevant system settings screen ──────────────
  const requestPermission = async config => {
    try {
      console.log('[PermissionsScreen] requesting permission:', config.permKey);
      await config.request();
    } catch (e) {
      console.warn('[PermissionsScreen] permission request failed:', e);
    }
  };

  // ── Persist everything and start monitoring ────────────────────────────────
  const handleComplete = async () => {
    console.log('[PermissionsScreen] handleComplete — persisting selections');
    const finalMessage = (message || '').trim() || MESSAGE_PRESETS[0];
    try {
      await VPNModule.setBlockedApps(selectedApps);
      await VPNModule.setDelayMessage(finalMessage);
      for (const pkg of selectedApps) {
        const cfg = breath[pkg] || BREATH_FALLBACK;
        const delaySecs = cfg.on ? cfg.secs : 0;
        // popupDelayMin left at 0 — breath pause only, no follow-up popup.
        await SettingsModule.setAppInterceptSettings(
          pkg,
          finalMessage,
          delaySecs,
          0,
        );
      }
      await VPNModule.startMonitoring();
      SettingsModule.saveMonitoringEnabled(true);
      SettingsModule.saveUninstallLockEnabled(protectEnabled);
      console.log(
        '[PermissionsScreen] setup persisted, monitoring started, deletion-prevention=',
        protectEnabled,
      );
    } catch (e) {
      console.warn('[PermissionsScreen] persist/start failed (non-fatal):', e);
    }
    onComplete();
  };

  // ── Per-step renderers ─────────────────────────────────────────────────────
  const renderWelcome = () => (
    <View style={styles.centered}>
      <ShieldIcon size={56} color={T.ink} strokeWidth={1.4} />
      <Text style={styles.welcomeTitle}>Stay intentional</Text>
      <Text style={styles.welcomeBody}>
        Break helps you reclaim your time by pausing mindless scrolling.
      </Text>
      <View style={styles.welcomeFooter}>
        <ProgressDots total={4} active={0} />
        <PillButton label="Get started" onPress={next} />
      </View>
    </View>
  );

  const renderApps = () => (
    <View style={styles.flex}>
      <StepHeader onBack={back} stepLabel="Step 1 of 3" />
      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.scrollBody}
      >
        <Text style={styles.h2}>What pulls you in?</Text>
        <Text style={styles.p}>
          We picked a few for you. Tap to change — nothing is permanent.
        </Text>
        <View style={styles.appList}>
          {ONBOARDING_APPS.map(app => (
            <AppSelectRow
              key={app.pkg}
              app={app}
              selected={selectedApps.includes(app.pkg)}
              onToggle={() => toggleApp(app.pkg)}
            />
          ))}
        </View>
        <Text style={styles.addAnother}>+ Add another app</Text>
      </ScrollView>
      <View style={styles.footerBordered}>
        <PillButton
          label={`Continue · ${selectedApps.length} selected`}
          onPress={next}
          disabled={selectedApps.length === 0}
        />
      </View>
    </View>
  );

  const renderMessage = () => (
    <View style={styles.flex}>
      <StepHeader onBack={back} stepLabel="Step 2 of 3" />
      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.scrollBody}
      >
        <Text style={styles.h2}>What should we say?</Text>
        <Text style={styles.p}>Shown the moment you open a managed app.</Text>

        <View style={styles.previewCard}>
          <Text style={styles.previewLabel}>Preview</Text>
          <Text style={styles.previewMessage}>
            {message || 'Is this intentional?'}
          </Text>
        </View>

        <View style={styles.messageList}>
          {MESSAGE_PRESETS.map(preset => {
            const active = !customMode && message === preset;
            return (
              <TouchableOpacity
                key={preset}
                style={[
                  styles.messageOption,
                  active && styles.messageOptionActive,
                ]}
                onPress={() => chooseMessage(preset)}
                activeOpacity={0.7}
                accessibilityRole="radio"
                accessibilityState={{ selected: active }}
              >
                <Text
                  style={[
                    styles.messageText,
                    !active && styles.messageTextMuted,
                  ]}
                >
                  {preset}
                </Text>
                {active ? (
                  <View style={styles.messageCheck}>
                    <CheckIcon
                      size={10}
                      color={T.iconOnInk}
                      strokeWidth={3.5}
                    />
                  </View>
                ) : null}
              </TouchableOpacity>
            );
          })}

          {customMode ? (
            <TextInput
              style={styles.customInput}
              value={customText}
              onChangeText={onCustomChange}
              placeholder="Write your own"
              placeholderTextColor={T.label}
              autoFocus
              maxLength={80}
            />
          ) : (
            <TouchableOpacity
              style={styles.customOption}
              onPress={enableCustom}
              activeOpacity={0.7}
            >
              <Text style={styles.customOptionText}>+ Write your own</Text>
            </TouchableOpacity>
          )}
        </View>
      </ScrollView>
      <View style={styles.footer}>
        <PillButton label="Continue" onPress={next} />
      </View>
    </View>
  );

  const renderBreath = () => (
    <View style={styles.flex}>
      <StepHeader onBack={back} stepLabel="Step 3 of 3" />
      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.scrollBody}
      >
        <Text style={styles.h2}>Add a breath</Text>
        <Text style={styles.p}>
          Turn a pause on per app, and choose how long.
        </Text>
        <View style={styles.breathList}>
          {selectedApps.map(pkg => {
            const app = ONBOARDING_APPS.find(a => a.pkg === pkg) || {
              label: pkg,
              monogram: '·',
            };
            const cfg = breath[pkg] || BREATH_FALLBACK;
            return (
              <View key={pkg} style={styles.breathCard}>
                <View style={styles.breathHeader}>
                  <Monogram
                    text={app.monogram}
                    active={cfg.on}
                    size={34}
                    radius={9}
                    fontSize={14}
                  />
                  <Text
                    style={[styles.breathName, !cfg.on && styles.breathNameOff]}
                  >
                    {app.label}
                  </Text>
                  <Toggle
                    value={cfg.on}
                    onChange={on => setBreathOn(pkg, on)}
                    label={`${app.label} breath`}
                  />
                </View>
                {cfg.on ? (
                  <View style={styles.breathSegment}>
                    <Segmented
                      options={BREATH_DURATIONS}
                      value={cfg.secs}
                      onChange={secs => setBreathSecs(pkg, secs)}
                    />
                  </View>
                ) : (
                  <Text style={styles.breathOff}>
                    Pause off — opens straight away.
                  </Text>
                )}
              </View>
            );
          })}
        </View>
      </ScrollView>
      <View style={styles.footer}>
        <PillButton label="Continue" onPress={next} />
      </View>
    </View>
  );

  const renderPermission = () => {
    const idx = step - FIRST_PERMISSION_STEP;
    const config = PERMISSION_STEPS[idx];
    const { Icon } = config;
    return (
      <View style={styles.flex}>
        <View style={styles.permTopRow}>
          <Eyebrow style={styles.permEyebrow}>Permissions</Eyebrow>
          <ProgressDots total={PERMISSION_STEPS.length} active={idx} />
        </View>
        <ScrollView
          showsVerticalScrollIndicator={false}
          contentContainerStyle={styles.scrollBody}
        >
          <View style={styles.permIconTile}>
            <Icon size={30} color={T.ink} />
          </View>
          <Eyebrow style={styles.requiredLabel}>Required to continue</Eyebrow>
          <Text style={[styles.h2, styles.permHeadline]}>
            {config.headline}
          </Text>
          <Text style={styles.permBody}>{config.body}</Text>
          <View style={styles.permReassure}>
            <ReassuranceCard>{config.reassurance}</ReassuranceCard>
          </View>
        </ScrollView>
        <View style={styles.footer}>
          <PillButton
            label={config.cta}
            onPress={() => requestPermission(config)}
          />
        </View>
      </View>
    );
  };

  // Optional opt-in — reuses the accessibility service to show a 30s pause when
  // the user opens Break's uninstall screen. Skippable; persisted in
  // handleComplete() via SettingsModule.saveUninstallLockEnabled().
  const renderProtect = () => {
    const enableAndContinue = () => {
      console.log('[PermissionsScreen] deletion-prevention enabled');
      setProtectEnabled(true);
      next();
    };
    const skip = () => {
      console.log('[PermissionsScreen] deletion-prevention skipped');
      setProtectEnabled(false);
      next();
    };
    return (
      <View style={styles.flex}>
        <View style={styles.permTopRow}>
          <Eyebrow style={styles.permEyebrow}>One last thing</Eyebrow>
        </View>
        <ScrollView
          showsVerticalScrollIndicator={false}
          contentContainerStyle={styles.scrollBody}
        >
          <View style={styles.permIconTile}>
            <ShieldIcon size={30} color={T.ink} strokeWidth={1.7} />
          </View>
          <Eyebrow style={styles.requiredLabel}>Optional</Eyebrow>
          <Text style={[styles.h2, styles.permHeadline]}>
            Prevent impulse deletion
          </Text>
          <Text style={styles.permBody}>
            If you head to Break's uninstall screen, a full-screen pause appears
            for 30 seconds with reasons to keep going. It's gentle friction —
            never a lock, and you can always continue.
          </Text>
          <View style={styles.permReassure}>
            <ReassuranceCard>
              Uses the accessibility service you just granted — it only watches
              for that one Settings screen and{' '}
              <Text style={strongStyle}>collects no data at all</Text>.
            </ReassuranceCard>
          </View>
        </ScrollView>
        <View style={styles.footer}>
          <PillButton label="Turn on protection" onPress={enableAndContinue} />
          <TouchableOpacity
            style={styles.skipLink}
            onPress={skip}
            activeOpacity={0.6}
            accessibilityRole="button"
            accessibilityLabel="Not now"
          >
            <Text style={styles.skipLinkText}>Not now</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  };

  const renderDone = () => {
    const breathOnCount = selectedApps.filter(
      pkg => (breath[pkg] || BREATH_FALLBACK).on,
    ).length;
    return (
      <View style={styles.centered}>
        <View style={styles.doneCircle}>
          <CheckIcon size={32} color={T.iconOnInk} strokeWidth={2.4} />
        </View>
        <Text style={styles.doneTitle}>You're all set</Text>
        <Text style={styles.welcomeBody}>
          All three permissions are on. Break is watching gently in the
          background — and remembers nothing.
        </Text>
        <View style={styles.chipsRow}>
          <View style={styles.chip}>
            <View style={styles.chipDot} />
            <Text style={styles.chipText}>
              {selectedApps.length} apps managed
            </Text>
          </View>
          {breathOnCount > 0 ? (
            <View style={styles.chip}>
              <Text style={styles.chipText}>
                Breath on · {breathOnCount} apps
              </Text>
            </View>
          ) : null}
          {protectEnabled ? (
            <View style={styles.chip}>
              <Text style={styles.chipText}>Deletion guard on</Text>
            </View>
          ) : null}
          <View style={styles.chip}>
            <Text style={styles.chipText}>No data collected</Text>
          </View>
        </View>
        <View style={styles.doneFooter}>
          <PillButton label="Open Break" onPress={handleComplete} />
        </View>
      </View>
    );
  };

  const renderStep = () => {
    if (step === 0) return renderWelcome();
    if (step === 1) return renderApps();
    if (step === 2) return renderMessage();
    if (step === 3) return renderBreath();
    if (
      step >= FIRST_PERMISSION_STEP &&
      step < FIRST_PERMISSION_STEP + PERMISSION_STEPS.length
    ) {
      return renderPermission();
    }
    if (step === PROTECT_STEP) return renderProtect();
    return renderDone();
  };

  return (
    <SafeAreaView style={styles.safe}>
      <Animated.View
        style={[
          styles.screen,
          { opacity: fadeAnim, transform: [{ translateY: slideAnim }] },
        ]}
      >
        {renderStep()}
      </Animated.View>
    </SafeAreaView>
  );
}

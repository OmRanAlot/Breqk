/**
 * home.js — Home Screen (Break light design system)
 * ─────────────────────────────────────────────────────────────────────────────
 * Dashboard layout:
 *   • App name header (centred) + settings gear icon
 *   • Summary stat cards: total screen time, unlocks, notifications
 *   • Top 5 apps by usage with proportional progress bars
 *   • Scroll budget card (always shown when data available)
 *   • Free break card/button (when free break toggle is ON in Customize)
 *   • "Open Instagram (Safe Mode)" primary action button (always shown)
 *
 * Data strategy:
 *   Real usage data is loaded via useDigitalWellbeing hook (5-min TTL cache).
 *   Data refreshes automatically when the app returns to the foreground.
 *   Unavailable metrics (API version or OEM restriction) are hidden, not shown as 0.
 *
 * Monitoring lifecycle is separate from data display; startMonitoring / event
 * listeners are preserved verbatim to avoid regression.
 *
 * Logging prefix: [Home]
 */

import React, { useRef, useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  Platform,
  AppState,
  NativeModules,
  NativeEventEmitter,
  TouchableOpacity,
  ScrollView,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path, Circle } from 'react-native-svg';
import useDigitalWellbeing from './useDigitalWellbeing';
import ManagedAppsList from './ManagedAppsList';
import HomeScrollBudgetCard from './HomeScrollBudgetCard';
import FreeBreakCard from './FreeBreakCard';
import { styles, L } from './home.styles';
import { formatTime, formatCount } from '../common/format';

const { VPNModule, SettingsModule } = NativeModules;
const appBlockerEmitter = new NativeEventEmitter(VPNModule);

// ─── Settings icon ────────────────────────────────────────────────────────────
const SettingsIcon = ({ color, size }) => (
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
    <Circle cx={12} cy={12} r={3} />
    <Path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
  </Svg>
);

// ─── Layers/Modes icon ───────────────────────────────────────────────────────
const ModesIcon = ({ color, size }) => (
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
    <Path d="M12 2L2 7l10 5 10-5-10-5z" />
    <Path d="M2 17l10 5 10-5" />
    <Path d="M2 12l10 5 10-5" />
  </Svg>
);

// ─── Sub-components ───────────────────────────────────────────────────────────

/** Single stat card shown in the summary row */
const StatCard = ({ label, value, loading }) => (
  <View style={styles.statCard}>
    {loading ? (
      <View style={styles.skeletonValue} />
    ) : (
      <Text style={styles.statValue}>{value}</Text>
    )}
    <Text style={styles.statLabel}>{label}</Text>
  </View>
);

/** One row in the top-apps list */
const AppUsageRow = ({ appName, usageTimeMin, totalMin }) => {
  // Progress bar fill ratio relative to TOTAL screen time — conveys absolute
  // share rather than "tallest bar = 100%" which hides how big the top app is.
  const ratio = totalMin > 0 ? Math.min(1, usageTimeMin / totalMin) : 0;

  return (
    <View style={styles.appUsageRow}>
      <View style={styles.appUsageInfo}>
        <Text style={styles.appUsageName} numberOfLines={1}>
          {appName}
        </Text>
        <Text style={styles.appUsageTime}>{formatTime(usageTimeMin)}</Text>
      </View>
      <View style={styles.usageBar}>
        <View
          style={[
            styles.usageBarFill,
            { width: `${Math.round(ratio * 100)}%` },
          ]}
        />
      </View>
    </View>
  );
};

// ─── Main Component ───────────────────────────────────────────────────────────

const Home = ({ navigation }) => {
  const insets = useSafeAreaInsets();
  const [isMonitoring, setIsMonitoring] = useState(false);

  // ── Digital Wellbeing data ────────────────────────────────────────────────
  const { stats, topApps, loading, error, refresh } = useDigitalWellbeing();

  // ── Free break status ─────────────────────────────────────────────────────
  // { enabled, active, startTimeMs, durationMs, remainingMs, usedToday }
  const [freeBreakStatus, setFreeBreakStatus] = useState(null);

  // ── App policies + active mode (loaded from SharedPreferences) ───────────
  // appPolicies shape: { [pkg]: { app_open_intercept, reels_detection } }
  const [appPolicies, setAppPolicies] = useState({});
  const [activeModeName, setActiveModeName] = useState(null);

  // ── Centralized settings loader ────────────────────────────────────────────
  // Loads app policies, active mode name, and triggers free break poll.
  // Called on mount, AppState→foreground, AND navigation focus (returning
  // from AppDetail / Customize / Modes).
  const loadPolicies = useCallback(() => {
    // Load the base per-app policies, then layer the active mode's
    // policy_overrides on top so the Managed Apps list shows the EFFECTIVE
    // state — the same resolution AppDetail and the native services use
    // (BreakPrefs.isFeatureEnabled). Without this the list shows stale base
    // flags (e.g. YouTube still reading reels_detection from the migration
    // default) even though the active mode turned them off.
    SettingsModule.getActiveMode(activeId => {
      SettingsModule.getModes(modesJson => {
        let modes = {};
        try {
          modes = modesJson ? JSON.parse(modesJson) : {};
        } catch (e) {
          console.warn('[Home] parse modes failed:', e);
        }
        const overrides =
          activeId && modes[activeId]?.policy_overrides
            ? modes[activeId].policy_overrides
            : {};

        SettingsModule.getAppPolicies(json => {
          try {
            const base = json ? JSON.parse(json) : {};
            const effective = {};
            // Base apps with their mode overrides merged on top.
            Object.keys(base).forEach(pkg => {
              effective[pkg] = { ...base[pkg], ...(overrides[pkg] || {}) };
            });
            // Apps that only exist as mode overrides still need a row.
            Object.keys(overrides).forEach(pkg => {
              if (!effective[pkg]) {
                effective[pkg] = { ...overrides[pkg] };
              }
            });
            setAppPolicies(effective);
          } catch (e) {
            console.warn('[Home] parse appPolicies failed:', e);
            setAppPolicies({});
          }
        });
      });
    });
  }, []);

  const loadActiveMode = useCallback(() => {
    SettingsModule.getActiveMode(modeId => {
      if (!modeId) {
        setActiveModeName(null);
        return;
      }
      SettingsModule.getModes(json => {
        try {
          const modes = json ? JSON.parse(json) : {};
          setActiveModeName(modes[modeId]?.name || null);
        } catch (e) {
          console.warn('[Home] parse modes failed:', e);
          setActiveModeName(null);
        }
      });
    });
  }, []);

  const reloadAll = useCallback(() => {
    console.log('[Home] reloadAll triggered');
    loadPolicies();
    loadActiveMode();
  }, [loadPolicies, loadActiveMode]);

  // Load on mount + refresh on foreground resume
  useEffect(() => {
    reloadAll();
    const sub = AppState.addEventListener('change', nextState => {
      if (nextState === 'active') {
        reloadAll();
      }
    });
    return () => sub?.remove();
  }, [reloadAll]);

  // Reload when screen regains focus (e.g. returning from AppDetail where
  // the user may have toggled free_break_enabled or changed per-app policies)
  useEffect(() => {
    const focusUnsub = navigation?.addListener
      ? navigation.addListener('focus', () => {
          console.log('[Home] focus → reloading policies & mode');
          reloadAll();
        })
      : null;
    return () => {
      if (focusUnsub) focusUnsub();
    };
  }, [navigation, reloadAll]);

  // Derived: any app has reels_detection enabled → scroll budget matters.
  const anyReelsOn = Object.values(appPolicies).some(
    p => p?.reels_detection === true,
  );

  // ── Scroll budget status (polled every 5s) ───────────────────────────────
  // Reads from SharedPreferences via VPNModule so the displayed status reflects
  // what MyVpnService's monitor has accumulated. Gated on anyReelsOn — when no
  // app has reels_detection enabled the budget is meaningless, so skip polling
  // and hide the card entirely rather than showing stale data.
  const [budgetStatus, setBudgetStatus] = useState(null);
  const appStateRefBudget = useRef(AppState.currentState);

  useEffect(() => {
    if (!anyReelsOn) {
      setBudgetStatus(null);
      return;
    }
    const pollBudget = async () => {
      try {
        const status = await VPNModule.getScrollBudgetStatus();
        setBudgetStatus(status);
        console.log(
          '[Home] scroll budget polled: canScroll=' +
            status.canScroll +
            ' remainingMs=' +
            status.remainingMs +
            ' usedMs=' +
            status.usedMs,
        );
      } catch (e) {
        console.warn('[Home] getScrollBudgetStatus failed:', e);
      }
    };
    pollBudget(); // initial fetch
    const interval = setInterval(pollBudget, 2000);

    // Also refresh on foreground resume for immediate accuracy
    const sub = AppState.addEventListener('change', nextState => {
      if (
        appStateRefBudget.current.match(/inactive|background/) &&
        nextState === 'active'
      ) {
        pollBudget();
      }
      appStateRefBudget.current = nextState;
    });

    return () => {
      clearInterval(interval);
      sub?.remove();
    };
  }, [anyReelsOn]);

  // ── Free break polling (every 5s; also refreshes on foreground resume) ──────
  useEffect(() => {
    const appStateRefBreak = { current: AppState.currentState };

    const pollFreeBreak = async () => {
      try {
        const status = await VPNModule.getFreeBreakStatus();
        setFreeBreakStatus(status);
        if (status.active) {
          console.log(
            '[Home] free break active — remainingMs=' + status.remainingMs,
          );
        }
      } catch (e) {
        console.warn('[Home] getFreeBreakStatus failed:', e);
      }
    };

    pollFreeBreak(); // initial fetch on mount
    const interval = setInterval(pollFreeBreak, 5000);

    const sub = AppState.addEventListener('change', nextState => {
      if (
        appStateRefBreak.current.match(/inactive|background/) &&
        nextState === 'active'
      ) {
        pollFreeBreak();
      }
      appStateRefBreak.current = nextState;
    });

    return () => {
      clearInterval(interval);
      sub?.remove();
    };
  }, []);

  // ── Monitoring lifecycle refs ─────────────────────────────────────────────
  const appStateRef = useRef(AppState.currentState);
  const restartDebounceRef = useRef(null);

  // ── Monitoring helpers ────────────────────────────────────────────────────
  // NOTE: These are preserved verbatim from the original implementation.

  const startMonitoring = async apps => {
    try {
      await VPNModule.setBlockedApps(Array.from(apps));
      await VPNModule.startMonitoring();
      setIsMonitoring(true);
      console.log('[Home] monitoring started with', apps.size, 'blocked apps');
    } catch (e) {
      console.error('[Home] startMonitoring failed:', e);
    }
  };

  const restartMonitoring = useCallback(async () => {
    try {
      // Check monitoring_enabled before restarting — respects "App Open Intercept" toggle.
      // Without this guard, returning to foreground would re-enable monitoring even if off.
      const monitoringEnabled = await new Promise(resolve => {
        SettingsModule.getMonitoringEnabled(v => resolve(v));
      });
      if (monitoringEnabled === false) {
        console.log(
          '[Home] restartMonitoring skipped — monitoring disabled by user',
        );
        return;
      }
      await VPNModule.stopMonitoring();
      setTimeout(async () => {
        await VPNModule.startMonitoring();
        console.log('[Home] monitoring restarted');
      }, 800);
    } catch (e) {
      console.warn('[Home] restartMonitoring failed:', e);
    }
  }, []);

  const debouncedRestart = useCallback(() => {
    if (restartDebounceRef.current) clearTimeout(restartDebounceRef.current);
    restartDebounceRef.current = setTimeout(restartMonitoring, 1000);
  }, [restartMonitoring]);

  // ── Free break handlers ───────────────────────────────────────────────────

  const handleStartFreeBreak = async () => {
    console.log('[Home] starting 20-min free break');
    try {
      await VPNModule.startFreeBreak();
      const status = await VPNModule.getFreeBreakStatus();
      setFreeBreakStatus(status);
      console.log(
        '[Home] free break started — remainingMs=' + status.remainingMs,
      );
    } catch (e) {
      console.error('[Home] startFreeBreak failed:', e);
    }
  };

  const handleEndFreeBreak = async () => {
    console.log('[Home] ending free break early (user-initiated)');
    try {
      await VPNModule.endFreeBreak();
      const status = await VPNModule.getFreeBreakStatus();
      setFreeBreakStatus(status);
      console.log('[Home] free break ended early');
    } catch (e) {
      console.error('[Home] endFreeBreak failed:', e);
    }
  };

  // ── Initialise (monitoring + defaults) ────────────────────────────────────

  useEffect(() => {
    const init = async () => {
      console.log('[Home] initialising');
      try {
        // Load saved blocked apps (seed defaults if empty)
        const savedApps = await new Promise(resolve => {
          SettingsModule.getBlockedApps(apps => resolve(apps));
        });
        let appsSet = new Set(savedApps || []);
        let updated = false;
        ['com.instagram.android', 'com.google.android.youtube'].forEach(pkg => {
          if (!appsSet.has(pkg)) {
            appsSet.add(pkg);
            updated = true;
          }
        });
        if (updated) SettingsModule.saveBlockedApps(Array.from(appsSet));

        // Check if monitoring is enabled before starting — respects the
        // "App Open Intercept" toggle in Customize. Without this check,
        // monitoring would restart on every Home mount even if toggled off.
        const monitoringEnabled = await new Promise(resolve => {
          SettingsModule.getMonitoringEnabled(v => resolve(v));
        });
        console.log('[Home] monitoring_enabled preference:', monitoringEnabled);

        if (monitoringEnabled !== false) {
          await startMonitoring(appsSet);
        } else {
          console.log('[Home] monitoring disabled by user — skipping start');
          setIsMonitoring(false);
        }

        // Sync widget if available
        if (Platform.OS === 'android' && SettingsModule.updateWidgetStats) {
          SettingsModule.updateWidgetStats(85, 45, 14, true);
        }
      } catch (e) {
        console.error('[Home] init failed:', e);
      }
    };
    init();

    // Event listeners
    const detectionSub = appBlockerEmitter.addListener(
      'onAppDetected',
      event => {
        console.log('[Home] app detected:', event?.packageName);
      },
    );

    const blockedSub = appBlockerEmitter.addListener(
      'onBlockedAppOpened',
      event => {
        console.log('[Home] blocked app opened:', event?.packageName);
        // App.tsx handles navigation to Browser; this is a secondary log hook.
      },
    );

    const stateSub = AppState.addEventListener('change', nextState => {
      if (
        appStateRef.current.match(/inactive|background/) &&
        nextState === 'active' &&
        isMonitoring
      ) {
        debouncedRestart();
      }
      appStateRef.current = nextState;
    });

    return () => {
      detectionSub.remove();
      blockedSub.remove();
      stateSub?.remove();
      if (restartDebounceRef.current) clearTimeout(restartDebounceRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isMonitoring, debouncedRestart]);

  // ── Derived display values ────────────────────────────────────────────────

  const maxTopAppTime = topApps.length > 0 ? topApps[0].usageTimeMin : 0;

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <View style={[styles.container, { paddingTop: Math.max(insets.top, 16) }]}>
      {/* ── Header: app name (centred) + modes icon + settings icon ─── */}
      <View style={styles.header}>
        <View style={styles.headerSpacer} />

        <Text style={styles.appName}>Break</Text>

        <View style={styles.headerActions}>
          <TouchableOpacity
            style={styles.headerButton}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel="Modes"
            onPress={() => {
              console.log('[Home] modes tapped — navigating to Modes');
              navigation.navigate('Modes');
            }}
          >
            <ModesIcon color={L.muted} size={20} />
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.headerButton}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel="Settings"
            onPress={() => {
              console.log('[Home] settings tapped — navigating to Customize');
              navigation.navigate('Customize');
            }}
          >
            <SettingsIcon color={L.muted} size={22} />
          </TouchableOpacity>
        </View>
      </View>

      {/* ── Status strip: monitoring + active mode ──────────────────── */}
      {/* Gives the user an at-a-glance answer to "is the app actually doing
                    anything right now?" without opening Customize or Modes. */}
      <View style={styles.statusStrip}>
        <View style={styles.statusItem}>
          <View
            style={[
              styles.statusDot,
              {
                backgroundColor: isMonitoring ? L.accentGreen : L.muted,
              },
            ]}
          />
          <Text style={styles.statusText}>
            {isMonitoring ? 'Monitoring on' : 'Monitoring off'}
          </Text>
        </View>
        {activeModeName && (
          <>
            <Text style={styles.statusDivider}>·</Text>
            <Text style={styles.statusText} numberOfLines={1}>
              {activeModeName} mode
            </Text>
          </>
        )}
      </View>

      {/* ── Main scrollable content ─────────────────────────────────── */}
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {/* ── Summary stat cards ────────────────────────────────── */}
        <View style={styles.statsRow}>
          <StatCard
            label="Screen Time"
            value={formatTime(stats.totalScreenTimeMin)}
            loading={loading}
          />
          {/* Only show unlock card if value is available (API 28+) */}
          {(loading || stats.unlockCount !== null) && (
            <StatCard
              label="Unlocks"
              value={formatCount(stats.unlockCount)}
              loading={loading}
            />
          )}
          {/* Only show notification card if value is available */}
          {(loading || stats.notificationCount !== null) && (
            <StatCard
              label="Notifications"
              value={formatCount(stats.notificationCount)}
              loading={loading}
            />
          )}
        </View>

        {/* ── Scroll Budget card (tap → Customize to edit budget) ─── */}
        <HomeScrollBudgetCard
          budgetStatus={budgetStatus}
          onPress={() => {
            console.log(
              '[Home] scroll budget tapped — navigating to Customize',
            );
            navigation.navigate('Customize');
          }}
        />

        {/* ── Free Break card / button ──────────────────────────── */}
        <FreeBreakCard
          freeBreakStatus={freeBreakStatus}
          onStart={handleStartFreeBreak}
          onEnd={handleEndFreeBreak}
        />

        {/* ── Error state ───────────────────────────────────────── */}
        {error && error !== 'usage_permission_missing' && (
          <TouchableOpacity style={styles.errorRow} onPress={refresh}>
            <Text style={styles.errorText}>
              Could not load stats. Tap to retry.
            </Text>
          </TouchableOpacity>
        )}

        {/* ── Top Apps section ──────────────────────────────────── */}
        <View style={styles.topAppsSection}>
          {/* Section header: title left, total time right */}
          <View style={styles.topAppsSectionHeader}>
            <Text style={styles.sectionTitle}>Today's Top Apps</Text>
            {stats.totalScreenTimeMin != null && (
              <Text style={styles.topAppsTotalTime}>
                Total: {formatTime(stats.totalScreenTimeMin)}
              </Text>
            )}
          </View>

          {loading ? (
            // Loading skeleton rows
            [0, 1, 2].map(i => (
              <View key={i} style={styles.appUsageRow}>
                <View style={styles.appUsageInfo}>
                  <View style={[styles.skeletonText, { width: '55%' }]} />
                  <View style={[styles.skeletonText, { width: '20%' }]} />
                </View>
                <View style={styles.usageBar}>
                  <View
                    style={[styles.usageBarFill, { width: `${60 - i * 15}%` }]}
                  />
                </View>
              </View>
            ))
          ) : topApps.length === 0 ? (
            <Text style={styles.emptyText}>No usage data available yet</Text>
          ) : (
            topApps.map(app => (
              <AppUsageRow
                key={app.packageName}
                appName={app.appName}
                usageTimeMin={app.usageTimeMin}
                totalMin={stats.totalScreenTimeMin || maxTopAppTime}
              />
            ))
          )}
        </View>

        {/* ── Managed Apps list ─────────────────────────────────── */}
        <ManagedAppsList
          appPolicies={appPolicies}
          onSelect={pkg =>
            navigation.navigate('AppDetail', { packageName: pkg })
          }
        />
      </ScrollView>
    </View>
  );
};

export default Home;

/**
 * useDigitalWellbeing.js — Custom hook for Android Digital Wellbeing stats
 * ─────────────────────────────────────────────────────────────────────────────
 * Encapsulates fetching, caching, and refreshing today's usage stats from the
 * native VPNModule bridge.
 *
 * Returned object:
 *   stats       — { totalScreenTimeMin, unlockCount, notificationCount }
 *                 unlockCount / notificationCount are null when the native layer
 *                 returns -1 (API too low or OEM restriction).
 *   topApps     — Array of { packageName, appName, usageTimeMin }
 *   loading     — true while first fetch is in progress
 *   error       — error message string or null
 *   refresh()   — force re-fetch ignoring cache
 *
 * Caching:
 *   Data is cached for CACHE_TTL_MS (default 5 minutes).
 *   Cache is bypassed when the app returns to the foreground AND the TTL has expired.
 *   refresh() always bypasses the cache.
 *
 * Logging prefix: [useDigitalWellbeing]
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, NativeModules } from 'react-native';

const { VPNModule } = NativeModules;

// ── Configuration ──────────────────────────────────────────────────────────────
// How many top apps to fetch for the dashboard
const TOP_APPS_LIMIT = 5;

// Cache TTL in ms — matches the existing home.js pattern (5 minutes)
const CACHE_TTL_MS = 5 * 60 * 1000;

// ── Empty/default state ────────────────────────────────────────────────────────
const DEFAULT_STATS = {
    totalScreenTimeMin: null,
    unlockCount: null,
    notificationCount: null,
};

// ── Hook ───────────────────────────────────────────────────────────────────────
export default function useDigitalWellbeing() {
    const [stats, setStats] = useState(DEFAULT_STATS);
    const [topApps, setTopApps] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);

    // Cache stored in a ref so it persists across renders without causing re-renders
    const cacheRef = useRef({
        stats: { data: null, timestamp: 0 },
        topApps: { data: null, timestamp: 0 },
    });

    const appStateRef = useRef(AppState.currentState);

    // ── Fetch logic ─────────────────────────────────────────────────────────────

    /**
     * fetchData — Fetches stats and top apps from native layer.
     * @param {boolean} forceRefresh — When true, ignores cache.
     */
    const fetchData = useCallback(async (forceRefresh = false) => {
        if (!VPNModule) {
            console.warn('[useDigitalWellbeing] VPNModule not available');
            setError('VPNModule unavailable');
            setLoading(false);
            return;
        }

        const now = Date.now();
        const statsCache = cacheRef.current.stats;
        const topAppsCache = cacheRef.current.topApps;

        const statsStale = !statsCache.data || (now - statsCache.timestamp) >= CACHE_TTL_MS;
        const topAppsStale = !topAppsCache.data || (now - topAppsCache.timestamp) >= CACHE_TTL_MS;

        // Skip fetch entirely if both caches are warm and caller did not force refresh
        if (!forceRefresh && !statsStale && !topAppsStale) {
            console.log('[useDigitalWellbeing] Cache hit — skipping fetch');
            return;
        }

        console.log('[useDigitalWellbeing] Fetching data, forceRefresh=' + forceRefresh);
        const fetchStart = Date.now();

        try {
            // Check permission before querying (avoids confusing error logs)
            const hasPermission = await VPNModule.isUsageAccessGranted();
            if (!hasPermission) {
                console.log('[useDigitalWellbeing] No usage permission — cannot fetch stats');
                setError('usage_permission_missing');
                setLoading(false);
                return;
            }

            // Fetch both in parallel for speed
            const [rawStats, rawTopApps] = await Promise.all([
                forceRefresh || statsStale
                    ? VPNModule.getDigitalWellbeingStats()
                    : Promise.resolve(statsCache.data),
                forceRefresh || topAppsStale
                    ? VPNModule.getTopAppsToday(TOP_APPS_LIMIT)
                    : Promise.resolve(topAppsCache.data),
            ]);

            const fetchDurationMs = Date.now() - fetchStart;
            console.log('[useDigitalWellbeing] Fetch complete in ' + fetchDurationMs + 'ms');
            console.log('[useDigitalWellbeing] rawStats:', JSON.stringify(rawStats));
            console.log('[useDigitalWellbeing] topApps count:', rawTopApps ? rawTopApps.length : 0);

            // ── Update stats cache ───────────────────────────────────────────
            if (forceRefresh || statsStale) {
                cacheRef.current.stats = { data: rawStats, timestamp: now };
            }

            // ── Update topApps cache ─────────────────────────────────────────
            if (forceRefresh || topAppsStale) {
                cacheRef.current.topApps = { data: rawTopApps, timestamp: now };
            }

            // ── Map native values to state ────────────────────────────────────
            // Native returns -1 as sentinel for "unavailable" — convert to null for JS consumers
            setStats({
                totalScreenTimeMin: typeof rawStats.totalScreenTimeMin === 'number'
                    ? rawStats.totalScreenTimeMin
                    : null,
                unlockCount: rawStats.unlockCount >= 0 ? rawStats.unlockCount : null,
                notificationCount: rawStats.notificationCount >= 0 ? rawStats.notificationCount : null,
            });

            setTopApps(Array.isArray(rawTopApps) ? rawTopApps : []);
            setError(null);
        } catch (e) {
            const msg = e?.message || String(e);
            console.warn('[useDigitalWellbeing] Fetch error:', msg);
            setError(msg);
        } finally {
            setLoading(false);
        }
    }, []);

    // ── Mount: initial fetch ─────────────────────────────────────────────────────

    useEffect(() => {
        console.log('[useDigitalWellbeing] Mounted — initial fetch');
        fetchData(false);
    }, [fetchData]);

    // ── AppState listener: refresh on foreground ────────────────────────────────

    useEffect(() => {
        const subscription = AppState.addEventListener('change', (nextState) => {
            if (
                appStateRef.current.match(/inactive|background/) &&
                nextState === 'active'
            ) {
                console.log('[useDigitalWellbeing] App foregrounded — checking cache');
                fetchData(false); // will only fetch if cache is stale
            }
            appStateRef.current = nextState;
        });

        return () => subscription?.remove();
    }, [fetchData]);

    // ── Public refresh function ──────────────────────────────────────────────────

    const refresh = useCallback(() => {
        console.log('[useDigitalWellbeing] refresh() called — force fetching');
        setLoading(true);
        fetchData(true);
    }, [fetchData]);

    return { stats, topApps, loading, error, refresh };
}

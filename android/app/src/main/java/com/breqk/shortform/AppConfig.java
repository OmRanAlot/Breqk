package com.breqk.shortform;

/**
 * Immutable configuration for a monitored app.
 * Resolved from BreqkPrefs feature flags through AppEventRouter's 5-second config cache.
 * Active mode overrides are applied transparently via BreqkPrefs.isFeatureEnabled().
 */
public final class AppConfig {
    /** Package name of the monitored app (e.g. "com.instagram.android"). */
    public final String packageName;
    /**
     * If true, ContentFilter ejects the user from short-form content
     * (Reels / Shorts / FYP) via GLOBAL_ACTION_BACK.
     */
    public final boolean blockShortForm;
    /**
     * If true, LaunchInterceptor shows a 15-second mindfulness overlay
     * on fresh app launches (not re-opens within 30 seconds).
     */
    public final boolean launchPopup;

    public AppConfig(String packageName, boolean blockShortForm, boolean launchPopup) {
        this.packageName = packageName;
        this.blockShortForm = blockShortForm;
        this.launchPopup = launchPopup;
    }
}

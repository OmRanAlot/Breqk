package com.breqk.shortform.detection;

import android.view.accessibility.AccessibilityNodeInfo;

/**
 * ShortFormDetector
 * ------------------
 * Interface for all short-form content layout detectors.
 *
 * Implementations:
 *   - InstagramDetector â€” detects Instagram Reels via clips_viewer_* view IDs
 *   - YouTubeDetector   â€” detects YouTube Shorts via Tier 0-4 ladder
 *
 * Usage in ReelsInterventionService:
 *   DetectResult result = detector.detect(root);
 *   if (result.inShortForm) { // user is in Reels/Shorts }
 *
 * Design note: DetectResult carries a 'tier' string so the service can emit
 * one-line [SHORTS_ACTIVE] or [TIER] log lines without knowing the internal
 * detection strategy.
 *
 * Log filter (for calling code):
 *   adb logcat -s REELS_WATCH | findstr "TIER"
 */
public interface ShortFormDetector {

    /**
     * Immutable result returned by {@link #detect(AccessibilityNodeInfo)}.
     */
    class DetectResult {
        /** True if the user is currently in a full-screen short-form content viewer. */
        public final boolean inShortForm;
        /**
         * Which detection tier or strategy produced this result.
         * Examples: "FAST_PATH", "TIER1", "TIER2", "TIER3", "TIER0_CLASS", "false"
         * Always non-null. Empty string = caller should not log a tier transition.
         */
        public final String tier;

        /**
         * @param inShortForm true if short-form content was detected
         * @param tier        which detection strategy confirmed the result (for logging)
         */
        public DetectResult(boolean inShortForm, String tier) {
            this.inShortForm = inShortForm;
            this.tier = tier != null ? tier : "";
        }

        /** Convenience factory for a negative (not in short form) result. */
        public static DetectResult notDetected() {
            return new DetectResult(false, "false");
        }
    }

    /**
     * Inspects the given accessibility tree and returns a {@link DetectResult}
     * indicating whether the user is currently viewing full-screen short-form content.
     *
     * Called from the accessibility thread â€” must be fast (no blocking I/O, no heavy
     * allocations). Tree traversal is acceptable; network access is not.
     *
     * Callers must NOT recycle {@code root} â€” the detector does not own the root node.
     * The detector is responsible for recycling any intermediate nodes it acquires via
     * {@code root.findAccessibilityNodeInfosByViewId()} or {@code root.getChild()}.
     *
     * @param root The root AccessibilityNodeInfo of the active window (may be null
     *             â€” detector must handle null gracefully and return a not-detected result).
     * @return A non-null DetectResult. Never returns null.
     */
    DetectResult detect(AccessibilityNodeInfo root);
}

package com.breqk.shortform.platform.tiktok;

import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.shortform.detection.ShortFormDetector;
import com.breqk.shortform.detection.ShortFormDetector.DetectResult;

/**
 * TikTokDetector
 * ---------------
 * Stub — detection logic not yet implemented.
 *
 * Always returns DetectResult.notDetected(). Populate TikTokViewIds and implement
 * detection tiers (matching InstagramDetector / YouTubeDetector patterns) once
 * TikTok support is prioritized.
 *
 * Log filter: adb logcat -s REELS_WATCH | findstr "TIKTOK"
 */
public class TikTokDetector implements ShortFormDetector {

    private final String tag;

    public TikTokDetector(Context context, String tag) {
        this.tag = tag;
    }

    @Override
    public DetectResult detect(AccessibilityNodeInfo root) {
        Log.d(tag, "[TIKTOK] TikTokDetector: stub — not yet implemented");
        return DetectResult.notDetected();
    }
}

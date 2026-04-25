package com.breqk.shortform.platform.facebook;

import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.shortform.detection.ShortFormDetector;
import com.breqk.shortform.detection.ShortFormDetector.DetectResult;

/**
 * FacebookDetector
 * -----------------
 * Stub — detection logic not yet implemented.
 *
 * Always returns DetectResult.notDetected(). Populate FacebookViewIds and implement
 * detection tiers (matching InstagramDetector patterns) once Facebook support is prioritized.
 *
 * Log filter: adb logcat -s REELS_WATCH | findstr "FACEBOOK"
 */
public class FacebookDetector implements ShortFormDetector {

    private final String tag;

    public FacebookDetector(Context context, String tag) {
        this.tag = tag;
    }

    @Override
    public DetectResult detect(AccessibilityNodeInfo root) {
        Log.d(tag, "[FACEBOOK] FacebookDetector: stub — not yet implemented");
        return DetectResult.notDetected();
    }
}

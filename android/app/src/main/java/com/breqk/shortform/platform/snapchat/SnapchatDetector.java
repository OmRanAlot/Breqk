package com.breqk.shortform.platform.snapchat;

import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.shortform.detection.ShortFormDetector;
import com.breqk.shortform.detection.ShortFormDetector.DetectResult;

/**
 * SnapchatDetector
 * -----------------
 * Stub — detection logic not yet implemented.
 *
 * Always returns DetectResult.notDetected(). Populate SnapchatViewIds and implement
 * detection tiers (matching InstagramDetector patterns) once Snapchat support is prioritized.
 *
 * Log filter: adb logcat -s REELS_WATCH | findstr "SNAPCHAT"
 */
public class SnapchatDetector implements ShortFormDetector {

    private final String tag;

    public SnapchatDetector(Context context, String tag) {
        this.tag = tag;
    }

    @Override
    public DetectResult detect(AccessibilityNodeInfo root) {
        Log.d(tag, "[SNAPCHAT] SnapchatDetector: stub — not yet implemented");
        return DetectResult.notDetected();
    }
}

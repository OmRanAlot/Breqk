package com.breqk.shortform.platform.tiktok;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.shortform.detection.ShortFormDetector.DetectResult;
import com.breqk.shortform.platform.FilterHandler;
import com.breqk.shortform.platform.Platform;

/**
 * TikTokFilterHandler
 * --------------------
 * Stub — not yet active. Registered in PlatformRegistry but detection always returns false.
 *
 * Implement detect() by wiring TikTokDetector once TikTok support is prioritized.
 */
public class TikTokFilterHandler implements FilterHandler {

    private static final String TAG = "REELS_WATCH";

    private final TikTokDetector detector;

    public TikTokFilterHandler(Context context, AccessibilityService service) {
        this.detector = new TikTokDetector(context, TAG);
    }

    @Override
    public boolean handle(AccessibilityEvent event, AccessibilityNodeInfo root) {
        DetectResult result = detector.detect(root);
        return result.inShortForm;
    }

    @Override
    public Platform platform() {
        return Platform.TIKTOK;
    }
}

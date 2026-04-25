package com.breqk.shortform.platform.facebook;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.shortform.detection.ShortFormDetector.DetectResult;
import com.breqk.shortform.platform.FilterHandler;
import com.breqk.shortform.platform.Platform;

/**
 * FacebookFilterHandler
 * ----------------------
 * Stub — not yet active. Registered in PlatformRegistry but detection always returns false.
 *
 * Implement detect() by wiring FacebookDetector once Facebook support is prioritized.
 */
public class FacebookFilterHandler implements FilterHandler {

    private static final String TAG = "REELS_WATCH";

    private final FacebookDetector detector;

    public FacebookFilterHandler(Context context, AccessibilityService service) {
        this.detector = new FacebookDetector(context, TAG);
    }

    @Override
    public boolean handle(AccessibilityEvent event, AccessibilityNodeInfo root) {
        DetectResult result = detector.detect(root);
        return result.inShortForm;
    }

    @Override
    public Platform platform() {
        return Platform.FACEBOOK;
    }
}

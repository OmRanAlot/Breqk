package com.breqk.shortform.platform.snapchat;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.shortform.detection.ShortFormDetector.DetectResult;
import com.breqk.shortform.platform.FilterHandler;
import com.breqk.shortform.platform.Platform;

/**
 * SnapchatFilterHandler
 * ----------------------
 * Stub — not yet active. Registered in PlatformRegistry but detection always returns false.
 *
 * Implement detect() by wiring SnapchatDetector once Snapchat support is prioritized.
 */
public class SnapchatFilterHandler implements FilterHandler {

    private static final String TAG = "REELS_WATCH";

    private final SnapchatDetector detector;

    public SnapchatFilterHandler(Context context, AccessibilityService service) {
        this.detector = new SnapchatDetector(context, TAG);
    }

    @Override
    public boolean handle(AccessibilityEvent event, AccessibilityNodeInfo root) {
        DetectResult result = detector.detect(root);
        return result.inShortForm;
    }

    @Override
    public Platform platform() {
        return Platform.SNAPCHAT;
    }
}

package com.breqk.shortform;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.breqk.shortform.platform.FilterHandler;
import com.breqk.shortform.platform.Platform;
import com.breqk.shortform.platform.PlatformRegistry;

import java.util.concurrent.ConcurrentHashMap;

/**
 * ContentFilter
 * --------------
 * Thin dispatcher: given an AccessibilityEvent and AppConfig, looks up the per-platform
 * FilterHandler from PlatformRegistry and delegates detection. If detection confirms
 * short-form content, fires GLOBAL_ACTION_BACK (ejection) with debouncing.
 *
 * Per-platform detection logic lives in:
 *   shortform/platform/instagram/InstagramFilterHandler.java
 *   shortform/platform/youtube/YouTubeFilterHandler.java
 *   (TikTok / Facebook / Snapchat — boilerplate stubs, not yet active)
 *
 * Logging tag: CONTENT_FILTER
 * Ejection events: adb logcat -s CONTENT_FILTER | findstr "EJECT"
 * Debounce:        adb logcat -s CONTENT_FILTER | findstr "DEBOUNCE"
 */
public class ContentFilter {

    private static final String TAG = "CONTENT_FILTER";

    /**
     * Minimum ms between GLOBAL_ACTION_BACK calls for the same package.
     * A single physical swipe fires 3–4 TYPE_VIEW_SCROLLED events within ~200ms;
     * 600ms ensures only one ejection per physical gesture.
     */
    private static final long EJECTION_DEBOUNCE_MS = 600L;

    private final AccessibilityService service;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Per-package last-ejection timestamp. ConcurrentHashMap for teardown safety. */
    private final ConcurrentHashMap<String, Long> lastEjectionTime = new ConcurrentHashMap<>();

    public ContentFilter(Context context, AccessibilityService service) {
        this.service = service;
        PlatformRegistry.init(context, service);
        Log.d(TAG, "[INIT] ContentFilter created debounceMs=" + EJECTION_DEBOUNCE_MS);
    }

    /**
     * Called by AppEventRouter on TYPE_WINDOW_CONTENT_CHANGED and TYPE_VIEW_SCROLLED.
     * Fast-exits if blockShortForm is false or no handler is registered for the package.
     */
    public void onContentChanged(AccessibilityEvent event, AppConfig config) {
        if (!config.blockShortForm) return;

        String pkg = config.packageName;
        Platform p = Platform.fromPackage(pkg);
        FilterHandler handler = PlatformRegistry.handlerFor(p);

        if (handler == null) {
            Log.w(TAG, "[SKIP] No active handler for pkg=" + pkg);
            return;
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        boolean detected = handler.handle(event, root);
        if (root != null) root.recycle();

        if (detected) {
            eject(pkg, handler.platform() + " short-form confirmed");
        }
    }

    /** Cleans up internal state. Call from the service's onInterrupt() / onDestroy(). */
    public void onDestroy() {
        lastEjectionTime.clear();
        Log.d(TAG, "[DESTROY] ContentFilter destroyed");
    }

    /**
     * Performs GLOBAL_ACTION_BACK to eject the user from short-form content.
     * Debounced per package to prevent rapid-fire back-presses from a single swipe.
     */
    private void eject(String pkg, String reason) {
        long now = System.currentTimeMillis();
        Long last = lastEjectionTime.get(pkg);

        if (last != null && (now - last) < EJECTION_DEBOUNCE_MS) {
            Log.d(TAG, "[DEBOUNCE] pkg=" + pkg
                    + " elapsedMs=" + (now - last)
                    + " < debounceMs=" + EJECTION_DEBOUNCE_MS + " — skipping");
            return;
        }

        lastEjectionTime.put(pkg, now);
        Log.i(TAG, "[EJECT] pkg=" + pkg + " reason=" + reason
                + " → performGlobalAction(GLOBAL_ACTION_BACK)");

        mainHandler.post(() -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK));
    }
}

package com.breqk.shortform.platform;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;

import com.breqk.shortform.platform.instagram.InstagramFilterHandler;
import com.breqk.shortform.platform.youtube.YouTubeFilterHandler;
// TikTok / Facebook / Snapchat handlers intentionally NOT imported — boilerplate only.

import java.util.EnumMap;
import java.util.Map;

/**
 * Opt-in table mapping Platform → FilterHandler.
 *
 * To enable a new platform:
 *   1. Create its *Detector, *ViewIds, and *FilterHandler files under platform/<name>/.
 *   2. Import the *FilterHandler here.
 *   3. Uncomment the HANDLERS.put(...) line below.
 *   4. The Platform enum entry and package string are already present.
 */
public final class PlatformRegistry {

    private static Map<Platform, FilterHandler> handlers;

    /** Must be called once from ContentFilter before any lookup. */
    public static synchronized void init(Context context, AccessibilityService service) {
        if (handlers != null) return;
        Map<Platform, FilterHandler> map = new EnumMap<>(Platform.class);
        map.put(Platform.INSTAGRAM, new InstagramFilterHandler(context, service));
        map.put(Platform.YOUTUBE,   new YouTubeFilterHandler(context, service));
        // map.put(Platform.TIKTOK,   new TikTokFilterHandler(context, service));   // TODO: enable when implemented
        // map.put(Platform.FACEBOOK, new FacebookFilterHandler(context, service));  // TODO: enable when implemented
        // map.put(Platform.SNAPCHAT, new SnapchatFilterHandler(context, service));  // TODO: enable when implemented
        handlers = map;
    }

    /** Returns the FilterHandler for the given platform, or null if not yet enabled. */
    public static FilterHandler handlerFor(Platform p) {
        return handlers != null ? handlers.get(p) : null;
    }

    /** Returns true if the package has an active registered handler. */
    public static boolean isMonitored(String pkg) {
        Platform p = Platform.fromPackage(pkg);
        return p != null && handlers != null && handlers.containsKey(p);
    }

    /** Returns a comma-separated list of monitored package names (for logging). */
    public static String monitoredPackageList() {
        if (handlers == null) return "(uninitialized)";
        StringBuilder sb = new StringBuilder();
        for (Platform p : handlers.keySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.pkg);
        }
        return sb.toString();
    }

    private PlatformRegistry() {}
}

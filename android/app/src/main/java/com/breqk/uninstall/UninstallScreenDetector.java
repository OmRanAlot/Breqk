package com.Break.uninstall;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Detects when the user is on the Android Settings App Info / uninstall screen for Break.
 *
 * Detection requires ALL three conditions:
 *   1. This app's identity appears in the node tree (case-insensitive) — either its
 *      launcher label (@string/app_name) or its package id. Both are resolved at
 *      runtime from Context, never hardcoded, so renaming the app or its
 *      applicationId cannot silently disable deletion prevention.
 *   2. "uninstall" appears in the node tree (case-insensitive)
 *   3. At least one App Info marker is present ("force stop", "storage", "notifications",
 *      "app info", "open by default") — rules out Settings search results where "Break"
 *      appears as a list item but the Uninstall button isn't actually on screen.
 *
 * BFS is bounded to MAX_NODES to avoid OOM on dense OEM Settings trees.
 *
 * Log filter: adb logcat -s REELS_WATCH | findstr "UNINSTALL_WATCH"
 */
public class UninstallScreenDetector {

    private static final String TAG = "REELS_WATCH";

    // BFS cap — prevents OOM on dense OEM Settings accessibility trees
    private static final int MAX_NODES = 500;

    // App Info markers: at least one must be present to confirm we're on the App Info page.
    // The uninstall-confirm dialog itself may not contain all of these, but the App Info
    // page that launches it always does. OEM translations may vary — expand this list if
    // false negatives appear on Samsung/Xiaomi. All lowercased for case-insensitive match.
    private static final String[] APP_INFO_MARKERS = {
            "force stop",
            "storage",
            "notifications",
            "app info",
            "open by default",
            "permissions",
    };

    // Lowercased identity tokens for this app (launcher label + package id), resolved
    // once from Context on first use. Both are stable for the process lifetime, and
    // this method runs on every debounced Settings event, so the PackageManager
    // lookup is cached rather than repeated. Volatile: written on the accessibility
    // event thread, and a benign duplicate computation on a race is harmless.
    private static volatile String[] identityTokens;

    /**
     * Returns true if the accessibility tree rooted at {@code root} looks like the
     * Break App Info / uninstall screen in Android Settings.
     *
     * @param ctx  used to resolve this app's label and package id at runtime
     * @param root safe to pass null or a recycled root (returns false)
     */
    public static boolean isOnBreakUninstallScreen(Context ctx, AccessibilityNodeInfo root) {
        if (root == null || ctx == null) return false;

        String[] identity = resolveIdentityTokens(ctx);

        boolean hasBreak = false;
        boolean hasUninstall = false;
        boolean hasAppInfoMarker = false;

        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;

            String text = collectText(node);
            if (!text.isEmpty()) {
                if (!hasBreak) {
                    for (String token : identity) {
                        if (text.contains(token)) {
                            hasBreak = true;
                            Log.d(TAG, "[UNINSTALL_WATCH] Found app identity '" + token + "' in node text='" + text + "'");
                            break;
                        }
                    }
                }
                if (!hasUninstall && text.contains("uninstall")) {
                    hasUninstall = true;
                    Log.d(TAG, "[UNINSTALL_WATCH] Found 'uninstall' in node text='" + text + "'");
                }
                if (!hasAppInfoMarker) {
                    for (String marker : APP_INFO_MARKERS) {
                        if (text.contains(marker)) {
                            hasAppInfoMarker = true;
                            Log.d(TAG, "[UNINSTALL_WATCH] Found App Info marker '" + marker + "' in text='" + text + "'");
                            break;
                        }
                    }
                }
            }

            // Short-circuit once all three signals are found
            if (hasBreak && hasUninstall && hasAppInfoMarker) break;

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }

        boolean detected = hasBreak && hasUninstall && hasAppInfoMarker;
        Log.d(TAG, "[UNINSTALL_WATCH] scan complete visited=" + visited
                + " hasBreak=" + hasBreak
                + " hasUninstall=" + hasUninstall
                + " hasAppInfoMarker=" + hasAppInfoMarker
                + " -> detected=" + detected);
        return detected;
    }

    /**
     * Resolves this app's lowercased identity tokens: the launcher label
     * (@string/app_name) and the package id. Result is cached in {@link #identityTokens}.
     *
     * Matching on EITHER is deliberate. Most OEM App Info screens show the label in the
     * header and the package id in the footer, but some show only one, and a localized
     * build could change the label. The package id is the stable fallback.
     *
     * Both are lowercased to match {@link #collectText}, which lowercases node text —
     * a mixed-case literal here can never match and would silently disable the feature.
     */
    private static String[] resolveIdentityTokens(Context ctx) {
        String[] cached = identityTokens;
        if (cached != null) return cached;

        String pkg = ctx.getPackageName().toLowerCase();
        String label = null;
        try {
            PackageManager pm = ctx.getPackageManager();
            CharSequence raw = ctx.getApplicationInfo().loadLabel(pm);
            if (raw != null && raw.length() > 0) label = raw.toString().trim().toLowerCase();
        } catch (Exception e) {
            // Label lookup should never fail for our own package, but a null/odd
            // PackageManager must not take the whole detector down — fall back to
            // the package id, which is always available.
            Log.w(TAG, "[UNINSTALL_WATCH] loadLabel failed, falling back to package id", e);
        }

        String[] tokens = (label == null || label.isEmpty() || label.equals(pkg))
                ? new String[] { pkg }
                : new String[] { label, pkg };

        Log.d(TAG, "[UNINSTALL_WATCH] app identity tokens=" + java.util.Arrays.toString(tokens));
        identityTokens = tokens;
        return tokens;
    }

    /** Collects text and content description from a node into a single lowercased string. */
    private static String collectText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        CharSequence text = node.getText();
        if (text != null) sb.append(text);
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(desc);
        }
        return sb.toString().toLowerCase();
    }
}

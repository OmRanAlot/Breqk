package com.Break;

/**
 * Browser bar monitoring and redirects away from blocked domains.
 *
 * <p><b>Flow:</b> {@link ReelsInterventionService} → {@link #onAccessibilityEvent} → omnibar probes
 * (view IDs → multi-root DFS) → deferred rescan → {@link #findBlockedDomain} →
 * {@link #redirect} (random benign URL, 2&nbsp;s cooldown).
 *
 * <p>Log tag {@code BROWSER_WATCH}. Runs inside the unified {@link ReelsInterventionService}.
 */
import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.Break.prefs.BreakPrefs;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class BrowserBarContentFilter {

    private static final String TAG = "BROWSER_WATCH";

    private static final long DIAG_THROTTLE_PREFS_MS = 25_000L;
    private static final long DIAG_THROTTLE_PKG_MS = 20_000L;
    private static final int DIAG_THROTTLE_MAP_CAP = 64;

    /** Throttle map for diagnostic log lines. */
    private static final Map<String, Long> DIAG_ELIGIBLE_AFTER_MS = new HashMap<>();

    private static boolean diagAllow(String throttleKey, long intervalMs) {
        long now = System.currentTimeMillis();
        synchronized (DIAG_ELIGIBLE_AFTER_MS) {
            Long eligible = DIAG_ELIGIBLE_AFTER_MS.get(throttleKey);
            if (eligible != null && now < eligible)
                return false;
            if (DIAG_ELIGIBLE_AFTER_MS.size() >= DIAG_THROTTLE_MAP_CAP)
                DIAG_ELIGIBLE_AFTER_MS.clear();
            DIAG_ELIGIBLE_AFTER_MS.put(throttleKey, now + intervalMs);
            return true;
        }
    }

    private static String clipForLog(String s, int maxChars) {
        if (s == null)
            return "null";
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        if (flat.length() <= maxChars)
            return flat;
        return flat.substring(0, maxChars) + "…";
    }

    private static final String[] REDIRECT_URL = {
            "https://yt3.ggpht.com/m1oST1H1GY1ZCFmxjmbl7EM6tNtAsa8YA1wx5Z0c4JM7hOSS9_BKlQBa_6eyeQvjq4MxnX0YM7wvK9A=s736-c-fcrop64=1,00001960ffffe69f-rw-nd-v1",
            "https://yt3.ggpht.com/Ik813-X8arGKfGD18QjODweB2YEJ7lUaVmRNVg2qNrzTiaDSmsu7TqmfyamDKnS-6jljjhaYXmxFm_8=s651-c-fcrop64=1,0ec80000f137ffff-rw-nd-v1",
            "https://yt3.ggpht.com/3t7vqyepr0V-utfbkkstmgtWBrL0lyWdJcoHjX86B2I1y8E6mH8Us9ZHCERUQt8BJ5PVvRZmwXL-0A=s736-c-fcrop64=1,0000197dffffe682-rw-nd-v1",
            "https://yt3.ggpht.com/QI1sYETg0XGjzbkgQ8HEwtejI7KX4t44IxpKjRrFeEi8hLHjMiiCDLB7G2PUciwSDGetWzNgJ7HdAA=s800-c-fcrop64=1,00000000ffffffff-rw-nd-v1",
            "https://yt3.ggpht.com/wYTTW50sfgCQLhjc5pjlKsmZKsm1hYuowdoAE4Z7TMZT7nJTgtFaXFmIaPzqq7l56tdNOrlhn-48rQ=s736-c-fcrop64=1,00000000ffffffff-rw-nd-v1",
    };

    private static final long REDIRECT_COOLDOWN_MS = 2000;
    private static long lastRedirectTime = 0;
    private static final Random RANDOM = new Random();

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static String deferredPollPackage;
    private static WeakReference<AccessibilityService> deferredHostRef;
    private static final Runnable DEFER_POLL_EARLY = BrowserBarContentFilter::runDeferredBrowserUrlPeek;
    private static final Runnable DEFER_POLL_LATE = BrowserBarContentFilter::runDeferredBrowserUrlPeek;

    private static final int TREE_SCAN_MAX_DEPTH = 32;
    private static final int TREE_SCAN_MAX_NODES = 900;

    private static final Set<String> BLOCKED_DOMAINS = new HashSet<>(Arrays.asList(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
            "redtube.com", "youporn.com", "tube8.com", "spankbang.com",
            "beeg.com", "porn.com", "sex.com", "chaturbate.com",
            "onlyfans.com", "stripchat.com", "bongacams.com", "myfreecams.com",
            "cam4.com", "brazzers.com", "naughtyamerica.com", "bangbros.com",
            "realitykings.com", "mofos.com", "digitalplayground.com", "evilangel.com",
            "kink.com", "adulttime.com", "score.com", "hustler.com",
            "playboy.com", "playboyplus.com", "livejasmin.com", "slutload.com",
            "drtuber.com", "tnaflix.com", "hentaihaven.xxx", "nhentai.net",
            "rule34.xxx", "gelbooru.com", "danbooru.donmai.us", "motherless.com",
            "xtube.com", "gaytube.com", "pornmd.com", "txxx.com",
            "porntube.com", "cliphunter.com", "empflix.com", "youjizz.com",
            "jizzhut.com", "nuvid.com"));

    private static final Map<String, String[]> BROWSER_URL_IDS = new HashMap<>();

    static {
        String[] chromiumUrlIds = new String[] { "url_bar", "location_bar_edit_text" };
        BROWSER_URL_IDS.put("com.android.chrome", chromiumUrlIds);
        BROWSER_URL_IDS.put("com.chrome.beta", chromiumUrlIds);
        BROWSER_URL_IDS.put("com.chrome.dev", chromiumUrlIds);
        BROWSER_URL_IDS.put("org.mozilla.firefox", new String[] { "mozac_browser_toolbar_url_view" });
        BROWSER_URL_IDS.put("org.mozilla.firefox_beta", new String[] { "mozac_browser_toolbar_url_view" });
        BROWSER_URL_IDS.put("org.mozilla.fenix", new String[] { "mozac_browser_toolbar_url_view" });
        BROWSER_URL_IDS.put("com.sec.android.app.sbrowser", new String[] { "location_bar_edit_text", "url_bar" });
        BROWSER_URL_IDS.put("com.sec.android.app.sbrowser.beta", new String[] { "location_bar_edit_text", "url_bar" });
        BROWSER_URL_IDS.put("com.brave.browser", chromiumUrlIds);
        BROWSER_URL_IDS.put("com.opera.browser", new String[] { "url_field", "address_bar_url_view" });
        BROWSER_URL_IDS.put("com.opera.mini.native", new String[] { "url_field" });
        BROWSER_URL_IDS.put("com.microsoft.emmx", new String[] { "url_bar", "address_bar" });
        BROWSER_URL_IDS.put("com.kiwibrowser.browser", chromiumUrlIds);
        BROWSER_URL_IDS.put("com.vivaldi.browser", chromiumUrlIds);
        BROWSER_URL_IDS.put("com.duckduckgo.mobile.android", new String[] { "omnibarTextInput", "url_bar" });
        BROWSER_URL_IDS.put("com.UCMobile.intl", new String[] { "url_bar", "addressbar_text" });
        BROWSER_URL_IDS.put("com.yandex.browser", new String[] { "url_bar", "bro_omnibar_address_title_text" });
        BROWSER_URL_IDS.put("mark.via.gp", new String[] { "url", "input" });
    }

    private BrowserBarContentFilter() {
    }

    /** Returns {@code true} if the package is a monitored browser (in {@link #BROWSER_URL_IDS}). */
    public static boolean isBrowserPackage(String packageName) {
        return BROWSER_URL_IDS.containsKey(packageName);
    }

    /** Logs browser-filter readiness when {@link ReelsInterventionService} connects. */
    static void logBrowserFilterReady(AccessibilityService service) {
        Log.i(TAG,
                "[BROWSER_FILTER_READY] unified ReelsInterventionService — browser domain blocking active");
        Log.d(TAG, "  content_filter_enabled=" + BreakPrefs.isContentFilterEnabled(service));
        Log.d(TAG, "  monitoring " + BROWSER_URL_IDS.size() + " browser packages:");
        for (String pkg : BROWSER_URL_IDS.keySet()) {
            String[] ids = BROWSER_URL_IDS.get(pkg);
            Log.d(TAG,
                    "    - " + pkg + " ids=" + (ids != null ? Arrays.toString(ids) : "null"));
        }
    }

    /** Called from {@link ReelsInterventionService#onAccessibilityEvent} for browser packages. */
    public static void onAccessibilityEvent(AccessibilityService host, AccessibilityEvent event) {
        if (event == null)
            return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null)
            return;

        String packageName = pkg.toString();

        boolean filterOn = BreakPrefs.isContentFilterEnabled(host);
        if (!filterOn) {
            if (diagAllow("prefs_off", DIAG_THROTTLE_PREFS_MS))
                Log.w(TAG,
                        "[SKIP_PREFS_OFF] content_filter_enabled=false — turn ON in Customize → "
                                + "Browser Safety (Break accessibility service must be enabled)");
            return;
        }
        if (!BROWSER_URL_IDS.containsKey(packageName)) {
            if (diagAllow("unk_pkg_" + packageName, DIAG_THROTTLE_PKG_MS))
                Log.w(TAG, "[UNSUPPORTED_BROWSER] pkg=" + packageName
                        + " — not in BrowserBarContentFilter.BROWSER_URL_IDS; add mapping or report");
            return;
        }

        String eventType = eventTypeName(event.getEventType());
        String className = event.getClassName() != null ? event.getClassName().toString() : "null";

        Log.d(TAG, "--- [EVENT] via " + host.getClass().getSimpleName() + " pkg=" + packageName
                + " type=" + eventType + " class=" + className + " windowId=" + event.getWindowId());

        if (event.getText() != null && !event.getText().isEmpty()) {
            Log.d(TAG, "  event.getText() has " + event.getText().size() + " item(s):");
            for (int i = 0; i < event.getText().size(); i++) {
                CharSequence cs = event.getText().get(i);
                Log.d(TAG, "    [" + i + "] = \"" + (cs != null ? cs.toString() : "null") + "\"");
            }
        } else {
            Log.d(TAG, "  event.getText() = empty/null");
        }

        CharSequence cd = event.getContentDescription();
        Log.d(TAG, "  contentDescription = " + (cd != null ? "\"" + cd + "\"" : "null"));

        String url = extractUrl(host, packageName, event);
        Log.d(TAG, "  >>> [extractUrl] result: "
                + (url != null ? "\"" + clipForLog(url, 160) + "\"" : "null — no URL found"));
        evaluateAddressBarCandidate(host, packageName, url);

        maybeScheduleDeferredUrlPeek(host, packageName, event.getEventType());
    }

    public static void cancelDeferredCallbacks() {
        MAIN_HANDLER.removeCallbacks(DEFER_POLL_EARLY);
        MAIN_HANDLER.removeCallbacks(DEFER_POLL_LATE);
        deferredPollPackage = null;
        deferredHostRef = null;
    }

    private static void maybeScheduleDeferredUrlPeek(AccessibilityService host, String packageName,
            int eventType) {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
            scheduleDeferredBrowserUrlPeek(host, packageName);
        }
    }

    private static void scheduleDeferredBrowserUrlPeek(AccessibilityService host, String packageName) {
        deferredPollPackage = packageName;
        deferredHostRef = new WeakReference<>(host);
        MAIN_HANDLER.removeCallbacks(DEFER_POLL_EARLY);
        MAIN_HANDLER.removeCallbacks(DEFER_POLL_LATE);
        Log.d(TAG, "  [DEFER_SCHED] pkg=" + packageName + " at +220ms +700ms");
        MAIN_HANDLER.postDelayed(DEFER_POLL_EARLY, 220);
        MAIN_HANDLER.postDelayed(DEFER_POLL_LATE, 700);
    }

    private static void runDeferredBrowserUrlPeek() {
        String pkg = deferredPollPackage;
        if (pkg == null) {
            Log.d(TAG, "[DEFERRED] skipped — deferredPollPackage was null");
            return;
        }
        AccessibilityService host = deferredHostRef != null ? deferredHostRef.get() : null;
        if (host == null) {
            Log.w(TAG, "[DEFERRED] skipped — host service was garbage-collected");
            return;
        }
        if (!BreakPrefs.isContentFilterEnabled(host)) {
            Log.d(TAG, "[DEFERRED] skipped prefs off pkg=" + pkg);
            return;
        }
        if (!BROWSER_URL_IDS.containsKey(pkg)) {
            Log.d(TAG, "[DEFERRED] skipped unknown pkg=" + pkg);
            return;
        }

        Log.d(TAG, "--- [DEFERRED] PEEK pkg=" + pkg + " ---");
        String url = sweepBrowserTreesForAddressBar(host, pkg, null);
        evaluateAddressBarCandidate(host, pkg, url);
    }

    private static void evaluateAddressBarCandidate(AccessibilityService host, String browserPackageName,
            String url) {
        if (url == null || url.isEmpty())
            return;
        String hostStr = hostFromBarText(url);
        Log.d(TAG, "  [EVAL] parsedHost="
                + (hostStr != null ? hostStr : "<unparsed>")
                + " raw="
                + clipForLog(url, 120));

        String matchedDomain = findBlockedDomain(url);
        if (matchedDomain != null) {
            Log.i(TAG, "  [MATCH] BLOCKED host=" + hostStr + " rule=" + matchedDomain);
            long now = System.currentTimeMillis();
            long cooldownRemaining = REDIRECT_COOLDOWN_MS - (now - lastRedirectTime);
            if (cooldownRemaining > 0) {
                Log.d(TAG, "  [REDIRECT_SKIP] cooldown active " + cooldownRemaining + "ms");
            } else {
                lastRedirectTime = now;
                String target = REDIRECT_URL[RANDOM.nextInt(REDIRECT_URL.length)];
                Log.i(TAG, "  [REDIRECT] blocked domain detected — replacing omnibar + redirecting target=" + target);
                tryClearBlockedOmnibar(host, browserPackageName, target);
                redirectTo(host, target);
            }
        } else {
            Log.d(TAG, "  [MATCH] ALLOW (not on blocklist) parsedHost="
                    + (hostStr != null ? hostStr : "?"));
        }
    }

    /**
     * Attempts {@link AccessibilityNodeInfo#ACTION_FOCUS} + {@link AccessibilityNodeInfo#ACTION_SET_TEXT}
     * on toolbar URL candidates to replace the blocked URL with {@code replacementUrl}. Works on API 26+
     * when the omnibar exposes an editable node; unreliable across OEMs.
     */
    private static boolean tryClearBlockedOmnibar(AccessibilityService host, String packageName,
            String replacementUrl) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "  [OMNIBAR_CLEAR_SKIP] SDK<26 ACTION_SET_TEXT bundle not used");
            return false;
        }

        Bundle emptyText = new Bundle();
        emptyText.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                replacementUrl);

        List<AccessibilityNodeInfo> roots = gatherBrowserRootsForPackage(host, packageName);
        boolean anyOk = false;
        Log.d(TAG, "  [OMNIBAR_CLEAR] pkg=" + packageName + " roots=" + roots.size());
        try {
            for (AccessibilityNodeInfo root : roots) {
                try {
                    if (performClearOnUrlNodesInRoot(packageName, root, emptyText))
                        anyOk = true;
                } finally {
                    root.recycle();
                }
            }
        } finally {
            roots.clear();
        }

        if (anyOk)
            Log.i(TAG, "  [OMNIBAR_REPLACED] pkg=" + packageName);
        else
            Log.d(TAG, "  [OMNIBAR_REPLACE_MISS] pkg=" + packageName + " (SET_TEXT not supported — redirect will still fire)");

        return anyOk;
    }

    /** @return {@code true} if at least one node accepted SET_TEXT. */
    private static boolean performClearOnUrlNodesInRoot(String packageName, AccessibilityNodeInfo root,
            Bundle setTextArgs) {
        String[] ids = BROWSER_URL_IDS.get(packageName);
        if (ids == null || root == null)
            return false;

        boolean anyOk = false;
        for (String idSuffix : ids) {
            String fullId = packageName + ":id/" + idSuffix;
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(fullId);
            if (nodes == null || nodes.isEmpty())
                continue;
            try {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node == null)
                        continue;
                    try {
                        if (!node.isEditable()) {
                            Log.d(TAG, "  [OMNIBAR_CLEAR] weak id=\"" + fullId + "\" not editable → try SET_TEXT");
                        }
                        if (!node.isFocused())
                            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        boolean ok =
                                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs);
                        Log.d(TAG, "  [OMNIBAR_CLEAR] id=\"" + fullId + "\" SET_TEXT=" + ok);
                        if (ok)
                            anyOk = true;
                    } catch (Exception e) {
                        Log.w(TAG, "  [OMNIBAR_CLEAR] id=\"" + fullId + "\" exception", e);
                    }
                }
            } finally {
                for (AccessibilityNodeInfo leftover : nodes) {
                    if (leftover != null)
                        try {
                            leftover.recycle();
                        } catch (Exception ignored) {
                        }
                }
            }
        }
        return anyOk;
    }

    private static List<AccessibilityNodeInfo> gatherBrowserRootsForPackage(AccessibilityService svc,
            String packageName) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();

        HashSet<Integer> seenRootRefs = new HashSet<>();

        boolean activeIncluded = false;
        AccessibilityNodeInfo activeRoot = svc.getRootInActiveWindow();
        if (activeRoot != null) {
            if (packageMatchesRoot(activeRoot, packageName)) {
                seenRootRefs.add(System.identityHashCode(activeRoot));
                out.add(activeRoot);
                activeIncluded = true;
            } else {
                activeRoot.recycle();
            }
        }

        List<AccessibilityWindowInfo> wins = Build.VERSION.SDK_INT >= 21 ? svc.getWindows() : null;
        int winListSize = wins != null ? wins.size() : 0;

        if (wins != null) {
            try {
                for (AccessibilityWindowInfo w : wins) {
                    if (w == null)
                        continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null)
                        continue;
                    if (!packageMatchesRoot(r, packageName)) {
                        r.recycle();
                        continue;
                    }
                    int oid = System.identityHashCode(r);
                    if (!seenRootRefs.add(oid)) {
                        r.recycle();
                        continue;
                    }
                    out.add(r);
                }
            } finally {
                for (AccessibilityWindowInfo w : wins)
                    try {
                        w.recycle();
                    } catch (Exception ignored) {
                    }
            }
        }

        Log.d(TAG, "  [ROOTS] pkg=" + packageName + " total=" + out.size() + " activeIncluded="
                + activeIncluded + " accessibilityWindows=" + winListSize);

        return out;
    }

    private static boolean packageMatchesRoot(AccessibilityNodeInfo root, String packageName) {
        CharSequence p = root.getPackageName();
        return p != null && packageName.contentEquals(p);
    }

    private static String extractUrl(AccessibilityService host, String packageName,
            AccessibilityEvent event) {
        if (event != null) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                try {
                    CharSequence srcTxt = source.getText();
                    Log.d(TAG, "  [SOURCE] class=" + source.getClassName() + " viewId="
                            + source.getViewIdResourceName() + " editable=" + source.isEditable()
                            + " textSnippet="
                            + clipForLog(srcTxt != null ? srcTxt.toString() : "null", 100));

                    String fromSource = readableUrlCandidateFromNode(source);
                    Log.d(TAG, "  [SOURCE] urlLikeCandidate="
                            + (fromSource != null ? clipForLog(fromSource, 120) : "null"));
                    if (fromSource != null && hostFromBarText(fromSource) != null)
                        return fromSource.trim();
                } finally {
                    source.recycle();
                }
            }
            Log.d(TAG, "  [extractUrl] sweeping tree roots for ids + DFS fallback");
            return sweepBrowserTreesForAddressBar(host, packageName, event);
        }
        Log.d(TAG, "  [extractUrl] sweeping tree roots (event=null path)");
        return sweepBrowserTreesForAddressBar(host, packageName, null);
    }

    private static String sweepBrowserTreesForAddressBar(AccessibilityService host, String packageName,
            AccessibilityEvent event) {
        List<AccessibilityNodeInfo> roots = gatherBrowserRootsForPackage(host, packageName);
        Log.d(TAG, "  [SWEEP] begin roots=" + roots.size() + " pkg=" + packageName);
        try {
            for (AccessibilityNodeInfo root : roots) {
                try {
                    String hit = probeRootForUrlCandidate(packageName, root);
                    if (hit != null && hostFromBarText(hit) != null)
                        return hit.trim();

                    Log.d(TAG, "  [SWEEP] toolbar id MISS — DFS scan (budget maxNodes=" + TREE_SCAN_MAX_NODES
                            + ")");
                    hit = scanTreeForAddressLikeText(root);
                    if (hit != null && hostFromBarText(hit) != null)
                        return hit.trim();
                } finally {
                    root.recycle();
                }
            }
        } finally {
            roots.clear();
        }

        if (event != null) {
            String fallback = tryEventTextAddressCandidates(event);
            if (fallback != null)
                return fallback;
        }

        Log.d(TAG, "  [SWEEP_END] MISS pkg=" + packageName);
        return null;
    }

    private static String probeRootForUrlCandidate(String packageName, AccessibilityNodeInfo root) {
        String[] ids = BROWSER_URL_IDS.get(packageName);
        if (ids == null || root == null)
            return null;

        Log.d(TAG, "  [PROBE] scanning view IDs for pkg=" + packageName);
        for (String idSuffix : ids) {
            String fullId = packageName + ":id/" + idSuffix;
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(fullId);
            if (nodes == null || nodes.isEmpty())
                continue;
            try {
                for (AccessibilityNodeInfo node : nodes) {
                    CharSequence text = node.getText();
                    CharSequence desc = node.getContentDescription();
                    Log.d(TAG, "  [PROBE]   candidate id=\"" + fullId + "\" text="
                            + (text != null ? "\"" + text + "\"" : "null")
                            + " desc=" + (desc != null ? "\"" + desc + "\"" : "null"));

                    String fromText = text != null ? text.toString().trim() : "";
                    String fromDesc = desc != null ? desc.toString().trim() : "";
                    if (!fromText.isEmpty() && looksLikeAddressBarText(fromText))
                        return fromText;
                    if (!fromDesc.isEmpty() && looksLikeAddressBarText(fromDesc))
                        return fromDesc;
                }
            } finally {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null)
                        try {
                            node.recycle();
                        } catch (Exception ignored) {
                        }
                }
            }
        }
        return null;
    }

    private static String readableUrlCandidateFromNode(AccessibilityNodeInfo node) {
        if (node == null)
            return null;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null) {
            String t = text.toString().trim();
            if (looksLikeAddressBarText(t))
                return t;
        }
        if (desc != null) {
            String d = desc.toString().trim();
            if (looksLikeAddressBarText(d))
                return d;
        }
        return null;
    }

    private static String scanTreeForAddressLikeText(AccessibilityNodeInfo root) {
        int[] budget = new int[] { TREE_SCAN_MAX_NODES };
        return scanTreeForAddressLikeText(root, 0, budget);
    }

    private static String scanTreeForAddressLikeText(AccessibilityNodeInfo root, int depth, int[] budget) {
        if (root == null || depth > TREE_SCAN_MAX_DEPTH || budget[0] <= 0)
            return null;
        budget[0]--;

        String hit = readableUrlCandidateFromNode(root);
        if (hit != null) {
            Log.d(TAG, "  [DFS_HIT] depth=" + depth + " value=" + clipForLog(hit, 140));
            return hit;
        }

        int cc = root.getChildCount();
        for (int i = 0; i < cc; i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            try {
                String found = scanTreeForAddressLikeText(child, depth + 1, budget);
                if (found != null)
                    return found;
            } finally {
                if (child != null)
                    child.recycle();
            }
        }
        return null;
    }

    private static String tryEventTextAddressCandidates(AccessibilityEvent event) {
        Log.d(TAG, "  [FALLBACK] event.getText()");
        if (event == null || event.getText() == null)
            return null;
        for (CharSequence cs : event.getText()) {
            if (cs != null) {
                String t = cs.toString().trim();
                boolean usable = looksLikeAddressBarText(t)
                        || (hostFromBarText(t) != null && t.contains("."));
                Log.d(TAG, "  [FALLBACK]   candidate=\"" + t + "\" usable=" + usable);
                if (usable && hostFromBarText(t) != null)
                    return t;
            }
        }
        return null;
    }

    private static boolean looksLikeAddressBarText(String t) {
        if (t == null || t.isEmpty())
            return false;
        if (t.startsWith("http://") || t.startsWith("https://"))
            return true;
        if (t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0)
            return false;
        if (t.indexOf(' ') >= 0)
            return false;
        int slash = t.indexOf('/');
        String hostPart = slash >= 0 ? t.substring(0, slash) : t;
        if (hostPart.isEmpty())
            return false;
        return hostPart.indexOf('.') > 0;
    }

    private static String hostFromBarText(String raw) {
        if (raw == null)
            return null;
        String s = raw.trim();
        if (s.isEmpty())
            return null;
        try {
            if (!s.contains("://")) {
                s = "http://" + s;
            }
            Uri uri = Uri.parse(s);
            String host = uri.getHost();
            if (host == null || host.isEmpty())
                return null;
            return host.toLowerCase(Locale.US);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hostMatchesBlockedDomain(String asciiHostLower, String blockedLower) {
        if (blockedLower.isEmpty())
            return false;
        if (asciiHostLower.equals(blockedLower))
            return true;
        return asciiHostLower.endsWith("." + blockedLower);
    }

    /**
     * Prefer parsed-host suffix matching; fall back to substring on the omnibar/raw string — same idea
     * as legacy {@code contains(domain)} matching on the URL string before the reorg refactor.
     */
    private static String findBlockedDomain(String urlOrHost) {
        if (TextUtils.isEmpty(urlOrHost))
            return null;

        String hostParsed = hostFromBarText(urlOrHost);
        if (hostParsed != null) {
            for (String domain : BLOCKED_DOMAINS) {
                String dl = domain.toLowerCase(Locale.US);
                if (hostMatchesBlockedDomain(hostParsed, dl))
                    return domain;
            }
        }

        String lower = urlOrHost.trim().toLowerCase(Locale.US);
        for (String domain : BLOCKED_DOMAINS) {
            String dl = domain.toLowerCase(Locale.US);
            if (lower.contains(dl))
                return domain;
        }
        return null;
    }

    private static void redirectTo(AccessibilityService host, String target) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(intent);
            Log.i(TAG, "[REDIRECT_OK] ACTION_VIEW dispatched target=" + target);
        } catch (Exception e) {
            Log.w(TAG, "redirect failed for " + target, e);
        }
    }

    private static String eventTypeName(int type) {
        switch (type) {
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                return "CONTENT_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                return "STATE_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                return "TEXT_CHANGED";
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
                return "WINDOWS_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                return "VIEW_CLICKED";
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                return "VIEW_FOCUSED";
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                return "VIEW_SCROLLED";
            default:
                return "TYPE_" + type;
        }
    }
}

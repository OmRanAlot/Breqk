package com.doomscrollstopper;

/*
 * PornBlockerService
 * -------------------
 * AccessibilityService that monitors browser URL bars and redirects away
 * from blocked domains (porn sites).
 *
 * Filter logcat with: adb logcat -s BROWSER_WATCH
 *
 * Architecture:
 *   AccessibilityService (this) → onAccessibilityEvent
 *     → extractUrl (view ID lookup → event text fallback)
 *     → findBlockedDomain
 *     → redirect (random Pinterest image, 2s cooldown)
 *
 * Config: res/xml/porn_blocker_accessibility_config.xml
 * Registered in: AndroidManifest.xml
 *
 * See also: ReelsInterventionService — handles Instagram/YouTube scroll detection separately.
 */

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;

public class PornBlockerService extends AccessibilityService {

    // Filter logcat with: adb logcat -s BROWSER_WATCH
    private static final String TAG = "BROWSER_WATCH";

    private static final String[] REDIRECT_URL = {
            "https://yt3.ggpht.com/m1oST1H1GY1ZCFmxjmbl7EM6tNtAsa8YA1wx5Z0c4JM7hOSS9_BKlQBa_6eyeQvjq4MxnX0YM7wvK9A=s736-c-fcrop64=1,00001960ffffe69f-rw-nd-v1",
            "https://yt3.ggpht.com/iprA-W4c-61kWmWDcR80PcP3sWafbKO9P4FOZDMaBoamOtHAu7nGDo8Eb-sHHQsgWCndqz3dfqivxQ=s718-c-fcrop64=1,2e610000d19effff-rw-nd-v1",
            "https://yt3.ggpht.com/Ik813-X8arGKfGD18QjODweB2YEJ7lUaVmRNVg2qNrzTiaDSmsu7TqmfyamDKnS-6jljjhaYXmxFm_8=s651-c-fcrop64=1,0ec80000f137ffff-rw-nd-v1"
    };

    private static final long REDIRECT_COOLDOWN_MS = 2000;
    private long lastRedirectTime = 0;

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
        BROWSER_URL_IDS.put("com.android.chrome", new String[] { "url_bar" });
        BROWSER_URL_IDS.put("com.chrome.beta", new String[] { "url_bar" });
        BROWSER_URL_IDS.put("com.chrome.dev", new String[] { "url_bar" });
        BROWSER_URL_IDS.put("org.mozilla.firefox", new String[] { "mozac_browser_toolbar_url_view" });
        BROWSER_URL_IDS.put("org.mozilla.firefox_beta", new String[] { "mozac_browser_toolbar_url_view" });
        BROWSER_URL_IDS.put("org.mozilla.fenix", new String[] { "mozac_browser_toolbar_url_view" });
        BROWSER_URL_IDS.put("com.sec.android.app.sbrowser", new String[] { "location_bar_edit_text", "url_bar" });
        BROWSER_URL_IDS.put("com.brave.browser", new String[] { "url_bar" });
        BROWSER_URL_IDS.put("com.opera.browser", new String[] { "url_field", "address_bar_url_view" });
        BROWSER_URL_IDS.put("com.opera.mini.native", new String[] { "url_field" });
        BROWSER_URL_IDS.put("com.microsoft.emmx", new String[] { "url_bar", "address_bar" });
        BROWSER_URL_IDS.put("com.kiwibrowser.browser", new String[] { "url_bar" });
        BROWSER_URL_IDS.put("com.vivaldi.browser", new String[] { "url_bar" });
        BROWSER_URL_IDS.put("com.duckduckgo.mobile.android", new String[] { "omnibarTextInput", "url_bar" });
        BROWSER_URL_IDS.put("com.UCMobile.intl", new String[] { "url_bar", "addressbar_text" });
        BROWSER_URL_IDS.put("com.yandex.browser", new String[] { "url_bar", "bro_omnibar_address_title_text" });
        BROWSER_URL_IDS.put("mark.via.gp", new String[] { "url", "input" });
    }

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null)
            info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 200;
        setServiceInfo(info);

        Log.d(TAG, "=== PornBlockerService CONNECTED ===");
        Log.d(TAG, "  eventTypes: WINDOW_CONTENT_CHANGED | WINDOW_STATE_CHANGED");
        Log.d(TAG, "  flags: FLAG_REPORT_VIEW_IDS | FLAG_RETRIEVE_INTERACTIVE_WINDOWS");
        Log.d(TAG, "  notificationTimeout: 200ms");
        Log.d(TAG, "  monitoring " + BROWSER_URL_IDS.size() + " browser packages:");
        for (String pkg : BROWSER_URL_IDS.keySet()) {
            Log.d(TAG, "    - " + pkg + " → ids=" + Arrays.toString(BROWSER_URL_IDS.get(pkg)));
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null)
            return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null)
            return;

        String packageName = pkg.toString();

        // Only process events from known browsers
        if (!BROWSER_URL_IDS.containsKey(packageName))
            return;

        String eventType = eventTypeName(event.getEventType());
        String className = event.getClassName() != null ? event.getClassName().toString() : "null";

        Log.d(TAG, "--- EVENT from " + packageName + " ---");
        Log.d(TAG, "  type=" + eventType + "  class=" + className);

        // Log raw event text list
        if (event.getText() != null && !event.getText().isEmpty()) {
            Log.d(TAG, "  event.getText() has " + event.getText().size() + " item(s):");
            for (int i = 0; i < event.getText().size(); i++) {
                CharSequence cs = event.getText().get(i);
                Log.d(TAG, "    [" + i + "] = \"" + (cs != null ? cs.toString() : "null") + "\"");
            }
        } else {
            Log.d(TAG, "  event.getText() = empty/null");
        }

        // Log content description
        CharSequence cd = event.getContentDescription();
        Log.d(TAG, "  contentDescription = " + (cd != null ? "\"" + cd + "\"" : "null"));

        // Extract URL and log each step
        String url = extractUrl(packageName, event);
        Log.d(TAG, "  >>> extractUrl result: " + (url != null ? "\"" + url + "\"" : "null — no URL found"));

        if (url == null || url.isEmpty())
            return;

        // Block check
        String matchedDomain = findBlockedDomain(url);
        if (matchedDomain != null) {
            Log.d(TAG, "  BLOCKED! url=\"" + url + "\" matched domain=\"" + matchedDomain + "\"");
            long now = System.currentTimeMillis();
            long cooldownRemaining = REDIRECT_COOLDOWN_MS - (now - lastRedirectTime);
            if (cooldownRemaining > 0) {
                Log.d(TAG, "  cooldown active — " + cooldownRemaining + "ms remaining, skipping redirect");
            } else {
                lastRedirectTime = now;
                Log.d(TAG, "  REDIRECTING → Pinterest (ts=" + now + ")");
                redirect();
            }
        } else {
            Log.d(TAG, "  not blocked: \"" + url + "\"");
        }
    }

    private String extractUrl(String packageName, AccessibilityEvent event) {
        // Path 1: targeted view ID lookup
        String[] ids = BROWSER_URL_IDS.get(packageName);
        if (ids != null) {
            Log.d(TAG, "  [extractUrl] trying " + ids.length + " view ID(s) for " + packageName);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                Log.d(TAG, "  [extractUrl] getRootInActiveWindow() returned NULL");
            } else {
                try {
                    for (String idSuffix : ids) {
                        String fullId = packageName + ":id/" + idSuffix;
                        Log.d(TAG, "  [extractUrl] searching for viewId=\"" + fullId + "\"");
                        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(fullId);
                        if (nodes == null || nodes.isEmpty()) {
                            Log.d(TAG, "  [extractUrl]   → 0 nodes found");
                        } else {
                            Log.d(TAG, "  [extractUrl]   → " + nodes.size() + " node(s) found");
                            for (int i = 0; i < nodes.size(); i++) {
                                AccessibilityNodeInfo node = nodes.get(i);
                                CharSequence text = node.getText();
                                CharSequence desc = node.getContentDescription();
                                Log.d(TAG, "  [extractUrl]   node[" + i + "] text=" +
                                        (text != null ? "\"" + text + "\"" : "null") +
                                        "  desc=" + (desc != null ? "\"" + desc + "\"" : "null"));
                                node.recycle();
                                if (text != null && !text.toString().isEmpty()) {
                                    return text.toString().trim();
                                }
                            }
                        }
                    }
                } finally {
                    root.recycle();
                }
            }
        }

        // Path 2: fallback from event text
        Log.d(TAG, "  [extractUrl] falling back to event.getText()");
        if (event.getText() != null) {
            for (CharSequence cs : event.getText()) {
                if (cs != null) {
                    String t = cs.toString().trim();
                    boolean isUrl = t.startsWith("http://") || t.startsWith("https://");
                    Log.d(TAG, "  [extractUrl]   candidate=\"" + t + "\"  isUrl=" + isUrl);
                    if (isUrl)
                        return t;
                }
            }
        }

        return null;
    }

    private String findBlockedDomain(String url) {
        String lower = url.toLowerCase();
        for (String domain : BLOCKED_DOMAINS) {
            if (lower.contains(domain))
                return domain;
        }
        return null;
    }

    private void redirect() {
        Random rand = new Random();
        int index = rand.nextInt(REDIRECT_URL.length);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(REDIRECT_URL[index]));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    public void onInterrupt() {
    }

    private static String eventTypeName(int type) {
        switch (type) {
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                return "CONTENT_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                return "STATE_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                return "TEXT_CHANGED";
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

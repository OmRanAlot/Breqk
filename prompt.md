# WebView Reels/Shorts Blocker — Claude Code Instructions

## Context

This is an addition to an existing bare React Native (no Expo) Android doomscrolling app.
The app already has:
- Friction/delay screen when opening apps
- Netflix-style 10-minute check-in overlay
- Accessibility Service + UsageStatsManager for app detection

We are adding a **WebView-based Instagram and YouTube browser** that strips Reels, Shorts,
and algorithmic feeds, while preserving DMs, search, subscriptions feed, and video playback.

---

## Testing Environment Warning

**Developer is on Windows, testing on iPad only.**

This means:
- The WebView feature (Instagram/YouTube browser) is the ONLY feature testable on iPad
- The friction screen and Accessibility Service features are Android-only and cannot be tested on iPad
- To run on iPad from Windows, use:
  - **React Native CLI over USB** with iTunes installed on Windows
- Metro bundler runs on Windows fine, iOS device connects to it over local network
- **CSS selectors for instagram.com and youtube.com will behave the same on iPad as iPhone** — safe to test there
- iPad screen is larger so the WebView will look different proportionally, but functionality is identical

---

## Step 1 — Install Dependencies

```bash
npm install react-native-webview
cd ios && pod install && cd ..
```

No other native dependencies are needed. Do NOT install Expo packages — this is bare RN.

---

## Step 2 — Enable Cookie Persistence on Android

In `android/app/src/main/java/com/yourapp/MainApplication.java` (or `.kt`), inside `onCreate()`:

```java
import android.webkit.CookieManager;

@Override
public void onCreate() {
  super.onCreate();
  CookieManager.getInstance().setAcceptCookie(true);
  // ... rest of existing onCreate
}
```

For iOS, cookie persistence is handled automatically via `sharedCookiesEnabled` prop on the WebView.

---

## Step 3 — Create the Injection Scripts

Create a new file: `src/webview/injections.js`

```javascript
// CSS and JS injected into Instagram and YouTube WebViews
// to remove Reels, Shorts, and algorithmic feeds.
//
// IMPORTANT: Instagram and YouTube periodically change their CSS class names.
// Prefer targeting stable attributes (href, aria-label, element type) over
// obfuscated class names. If selectors stop working, inspect instagram.com
// in a desktop browser and update the selectors here.
//
// FUTURE: Consider fetching these from a remote config endpoint so selectors
// can be updated without pushing an app update.

export const INSTAGRAM_CSS = `
  /* Remove Reels tab from bottom navigation */
  a[href="/reels/"],
  a[href*="/reels"] { display: none !important; }

  /* Remove Reels from home feed (video posts) */
  article[role="presentation"] video { display: none !important; }

  /* Remove Explore / Search page link */
  a[href="/explore/"] { display: none !important; }

  /* Remove suggested posts section */
  ._aa4w,
  [data-visualcompletion="ignore-dynamic"] { display: none !important; }

  /* Remove Stories bar on home feed */
  ._acuy { display: none !important; }
`;

export const YOUTUBE_CSS = `
  /* Remove Shorts shelf on homepage */
  ytd-reel-shelf-renderer { display: none !important; }

  /* Remove Shorts in sidebar navigation */
  a[href^="/shorts"],
  ytd-guide-entry-renderer a[href="/shorts"] { display: none !important; }

  /* Remove homepage recommendations entirely — subscriptions feed remains accessible */
  ytd-browse[page-subtype="home"] ytd-rich-grid-renderer { display: none !important; }

  /* Remove autoplay next video overlay */
  .ytp-autonav-endscreen-upnext-button { display: none !important; }

  /* Remove end screen suggested videos */
  .ytp-endscreen-content { display: none !important; }
`;

// Wraps CSS in a MutationObserver so rules re-apply as the DOM updates
// (both Instagram and YouTube are heavy SPAs that load content dynamically)
export const buildInjectionScript = (css) => `
  (function() {
    const styleId = 'doom-blocker-styles';

    function injectStyles() {
      let existing = document.getElementById(styleId);
      if (!existing) {
        const style = document.createElement('style');
        style.id = styleId;
        style.textContent = \`${css}\`;
        (document.head || document.documentElement).appendChild(style);
      }
    }

    // Inject immediately
    injectStyles();

    // Re-inject on DOM mutations (SPA navigation replaces head/body)
    const observer = new MutationObserver(() => injectStyles());
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  })();
  true; // required by react-native-webview — script must return true
`;
```

---

## Step 4 — Create the WebView Screen

Create a new file: `src/screens/BrowserScreen.js`

```javascript
import React, { useRef, useState } from 'react';
import {
  View,
  StyleSheet,
  ActivityIndicator,
  TouchableOpacity,
  Text,
  SafeAreaView,
  Platform,
} from 'react-native';
import { WebView } from 'react-native-webview';
import { INSTAGRAM_CSS, YOUTUBE_CSS, buildInjectionScript } from '../webview/injections';

const PLATFORMS = {
  instagram: {
    url: 'https://www.instagram.com',
    css: INSTAGRAM_CSS,
    label: 'Instagram',
  },
  youtube: {
    url: 'https://www.youtube.com',
    css: YOUTUBE_CSS,
    label: 'YouTube',
  },
};

export function BrowserScreen({ route, navigation }) {
  // Pass platform='instagram' or platform='youtube' via route params
  const platform = route?.params?.platform ?? 'instagram';
  const config = PLATFORMS[platform];

  const webviewRef = useRef(null);
  const [loading, setLoading] = useState(true);
  const [canGoBack, setCanGoBack] = useState(false);

  const injectionScript = buildInjectionScript(config.css);

  return (
    <SafeAreaView style={styles.container}>

      {/* Minimal top bar — back button + platform label */}
      <View style={styles.topBar}>
        {canGoBack && (
          <TouchableOpacity
            onPress={() => webviewRef.current?.goBack()}
            style={styles.backButton}
          >
            <Text style={styles.backText}>← Back</Text>
          </TouchableOpacity>
        )}
        <Text style={styles.platformLabel}>{config.label} (Distraction-Free)</Text>
      </View>

      {loading && (
        <ActivityIndicator
          style={styles.loader}
          size="large"
          color="#888"
        />
      )}

      <WebView
        ref={webviewRef}
        source={{ uri: config.url }}

        // Inject CSS before page content loads to prevent flash of blocked content
        injectedJavaScriptBeforeContentLoaded={injectionScript}

        // Also inject after load as a fallback
        injectedJavaScript={injectionScript}

        // Cookie persistence — keeps user logged in across sessions
        sharedCookiesEnabled={true}
        thirdPartyCookiesEnabled={true}

        // Required for YouTube inline video playback
        allowsInlineMediaPlayback={true}
        mediaPlaybackRequiresUserAction={false}

        // Required for YouTube audio to continue when screen is off (iOS)
        allowsAirPlayForMediaPlayback={true}

        // User agent — use desktop UA so instagram.com serves full site, not mobile redirect
        // Remove this if mobile layout is preferred
        // applicationNameForUserAgent="Mozilla/5.0"

        onLoadStart={() => setLoading(true)}
        onLoadEnd={() => setLoading(false)}
        onNavigationStateChange={(state) => setCanGoBack(state.canGoBack)}

        // Log JS errors from the injected scripts during development
        onMessage={(event) => {
          if (__DEV__) console.log('[WebView]', event.nativeEvent.data);
        }}

        style={styles.webview}
      />

    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
  },
  topBar: {
    height: 44,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#ccc',
  },
  backButton: {
    marginRight: 12,
  },
  backText: {
    fontSize: 16,
    color: '#007AFF',
  },
  platformLabel: {
    fontSize: 14,
    color: '#666',
    fontWeight: '500',
  },
  loader: {
    position: 'absolute',
    top: '50%',
    left: '50%',
    zIndex: 10,
  },
  webview: {
    flex: 1,
  },
});
```

---

## Step 5 — Add Routes to Navigator

In your existing navigator file (likely `src/navigation/index.js` or `App.js`), add the BrowserScreen:

```javascript
import { BrowserScreen } from './src/screens/BrowserScreen';

// Inside your Stack.Navigator:
<Stack.Screen
  name="Browser"
  component={BrowserScreen}
  options={{ headerShown: false }}
/>
```

Navigate to it from anywhere with:

```javascript
navigation.navigate('Browser', { platform: 'instagram' });
navigation.navigate('Browser', { platform: 'youtube' });
```

---

## Step 6 — Hook Into Existing Friction Screen (Android only)

In your existing Accessibility Service or overlay logic, when Instagram or YouTube is detected
as the foreground app, instead of (or in addition to) showing the friction screen, you can
redirect the user to open your BrowserScreen instead.

This requires a deep link or a notification tap that opens the Browser route.
Set up deep linking in `AndroidManifest.xml`:

```xml
<intent-filter>
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <data android:scheme="yourappscheme" android:host="browser" />
</intent-filter>
```

Then from your native service:
```java
Intent intent = new Intent(Intent.ACTION_VIEW,
  Uri.parse("yourappscheme://browser?platform=instagram"));
intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
startActivity(intent);
```

---

## Known Maintenance Issue — Selector Rot

Instagram and YouTube regularly update their frontend. CSS class names like `._aa4w` will
break without warning. When this happens:

1. Open instagram.com or youtube.com in a desktop Chrome/Firefox
2. Right-click the element you want to hide → Inspect
3. Find a stable selector (prefer `href`, `aria-label`, or element type over class names)
4. Update `src/webview/injections.js`

**Long-term fix:** Host your CSS strings on a simple API endpoint and fetch them at app launch.
This lets you fix broken selectors without submitting an app update.

```javascript
// Example: fetch remote selectors at app startup
const res = await fetch('https://your-api.com/selectors.json');
const { instagramCSS, youtubeCSS } = await res.json();
```

---

## What This Does NOT Support

| Feature | Status |
|---|---|
| Native Instagram app notifications | ❌ Not possible — user must use your WebView |
| Native YouTube app | ❌ Same — WebView only |
| Friction screen on iOS | ❌ iOS has no Accessibility Service equivalent |
| 10-min check-in on iOS | ❌ Same — Android only |
| Blocking Reels in native apps | ❌ Impossible on both platforms without root |

---

## Summary of Files to Create/Modify

| File | Action |
|---|---|
| `src/webview/injections.js` | Create new |
| `src/screens/BrowserScreen.js` | Create new |
| `src/navigation/index.js` | Add Browser route |
| `android/.../MainApplication.java` | Add CookieManager line |
| `package.json` | `npm install react-native-webview` |
| `ios/Podfile.lock` | `pod install` after npm install |
import React, { useRef, useState } from 'react';
import {
  View,
  StyleSheet,
  ActivityIndicator,
  TouchableOpacity,
  Text,
  SafeAreaView,
} from 'react-native';
import { WebView } from 'react-native-webview';
import { INSTAGRAM_CSS, YOUTUBE_CSS, buildInjectionScript } from './injections';
import { colors } from '../../design/tokens';

const s = colors.stitch;

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
  const platform = route?.params?.platform ?? 'instagram';
  const config = PLATFORMS[platform];

  const webviewRef = useRef(null);
  const [loading, setLoading] = useState(true);
  const [canGoBack, setCanGoBack] = useState(false);

  const injectionScript = buildInjectionScript(config.css);

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.topBar}>
        <TouchableOpacity
          onPress={() => navigation.goBack()}
          style={styles.closeButton}
        >
          <Text style={styles.closeText}>✕</Text>
        </TouchableOpacity>

        {canGoBack && (
          <TouchableOpacity
            onPress={() => webviewRef.current?.goBack()}
            style={styles.backButton}
          >
            <Text style={styles.backText}>← Back</Text>
          </TouchableOpacity>
        )}

        <Text style={styles.platformLabel}>{config.label} — Distraction-Free</Text>
      </View>

      {loading && (
        <ActivityIndicator
          style={styles.loader}
          size="large"
          color={s.seafoam}
        />
      )}

      <WebView
        ref={webviewRef}
        source={{ uri: config.url }}
        injectedJavaScriptBeforeContentLoaded={injectionScript}
        injectedJavaScript={injectionScript}
        sharedCookiesEnabled={true}
        thirdPartyCookiesEnabled={true}
        allowsInlineMediaPlayback={true}
        mediaPlaybackRequiresUserAction={false}
        allowsAirPlayForMediaPlayback={true}
        onLoadStart={() => setLoading(true)}
        onLoadEnd={() => setLoading(false)}
        onNavigationStateChange={(state) => setCanGoBack(state.canGoBack)}
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
    backgroundColor: s.navy,
  },
  topBar: {
    height: 48,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    backgroundColor: s.appSurface,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: s.appBorder,
  },
  closeButton: {
    marginRight: 12,
    padding: 4,
  },
  closeText: {
    fontSize: 16,
    color: s.seafoam,
  },
  backButton: {
    marginRight: 12,
  },
  backText: {
    fontSize: 15,
    color: s.seafoam,
  },
  platformLabel: {
    fontSize: 14,
    color: s.mint,
    fontWeight: '500',
    flex: 1,
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

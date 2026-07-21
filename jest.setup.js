/* eslint-env jest */
/**
 * jest.setup.js
 * -------------
 * Global Jest setup for the Breqk app.
 *
 * The app's components grab custom native modules (VPNModule, SettingsModule)
 * at module scope — e.g. components/Home/home.js constructs a
 * NativeEventEmitter(VPNModule) on import. Those modules only exist on a real
 * Android device, so Jest must provide mocks before any component is imported.
 */
import { NativeModules } from 'react-native';
import 'react-native-gesture-handler/jestSetup';

// react-native-webview registers a TurboModule (RNCWebViewModule) that only
// exists in the native binary — replace the whole library with a plain View.
jest.mock('react-native-webview', () => {
  const React = require('react');
  const { View } = require('react-native');
  const MockWebView = props => React.createElement(View, props);
  return { WebView: MockWebView, default: MockWebView };
});

/**
 * Build a self-populating native-module mock: any method accessed returns a
 * jest.fn resolving to the given default value. Keeps the mock in sync with
 * the bridge automatically as new @ReactMethods are added.
 *
 * @param {Record<string, unknown>} overrides - explicit per-method return values
 */
function autoNativeModuleMock(overrides = {}) {
  const target = {
    // Required by NativeEventEmitter's invariant checks
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  };
  for (const [name, value] of Object.entries(overrides)) {
    target[name] = jest.fn(() => Promise.resolve(value));
  }
  return new Proxy(target, {
    get(obj, prop) {
      if (!(prop in obj) && typeof prop === 'string') {
        obj[prop] = jest.fn(() => Promise.resolve(null));
      }
      return obj[prop];
    },
  });
}

NativeModules.VPNModule = autoNativeModuleMock({
  // Permission / state checks default to safe booleans
  checkPermissions: { usage: true, overlay: true, accessibility: true },
  checkOverlayPermission: true,
  checkUsageStatsPermission: true,
  isAccessibilityServiceEnabled: true,
  isMonitoringEnabled: false,
  // JSON-string returning methods must be parseable
  getBlockedApps: '[]',
  getInstalledApps: '[]',
});

NativeModules.SettingsModule = autoNativeModuleMock({
  getAppPolicies: '{}',
  getModes: '[]',
});

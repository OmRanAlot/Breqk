// App.tsx
// Root of Breqk. Checks permissions on mount, then renders a Stack
// navigator containing Home → Customize / Modes → Browser.
//
// Navigation architecture:
//   Stack: Home (initial) → Customize (via gear icon) | Modes (via layers icon) → Browser (via deep link or button)
//
// Native overlays (AppUsageMonitor + ReelsInterventionService) handle all app-blocking
// and Reels intervention UI directly via WindowManager. No JS-side modal is needed here.
//
// Deep linking: breqk://browser/:platform → Browser screen (used by widget buttons)

import React, { useState, useEffect } from 'react';
import { NativeModules } from 'react-native';
import Home from './components/Home/home';
import Customize from './components/Customize/customize';
import ModesScreen from './components/Modes/ModesScreen';
import PermissionsScreen from './components/Permissions/PermissionsScreen';
import { BrowserScreen } from './components/Browser/BrowserScreen';
import {
  NavigationContainer,
  createNavigationContainerRef,
} from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';

const { VPNModule } = NativeModules;
const Stack = createNativeStackNavigator();

/**
 * navigationRef — allows navigation from outside React tree (e.g. native event callbacks).
 * Exported so other modules can call navigationRef.navigate() if needed.
 */
export const navigationRef = createNavigationContainerRef();

/**
 * Deep linking configuration for widget quick-launch buttons.
 * Maps breqk://browser/:platform → Browser screen.
 * Works for both cold start and warm start (singleTask launchMode).
 */
const linking = {
  prefixes: ['breqk://'],
  config: {
    screens: {
      // Browser is directly on the stack — deep link resolves correctly without tabs
      Browser: 'browser/:platform',
    },
  },
};

const App = () => {
  // null = checking, false = missing, true = granted
  const [permissionsGranted, setPermissionsGranted] = useState<boolean | null>(
    null,
  );

  // Check required permissions (usage stats + overlay) on mount
  useEffect(() => {
    console.log('[App] checking permissions');
    VPNModule.checkPermissions()
      .then((perms: { usage: boolean; overlay: boolean }) => {
        const granted = perms.usage && perms.overlay;
        console.log(
          '[App] permissions result — usage:',
          perms.usage,
          'overlay:',
          perms.overlay,
          '→ granted:',
          granted,
        );
        setPermissionsGranted(granted);
      })
      .catch((e: Error) => {
        console.error('[App] checkPermissions failed:', e);
        setPermissionsGranted(false);
      });
  }, []);

  // Render nothing while permission check is in flight
  if (permissionsGranted === null) {
    console.log('[App] permission check in progress — rendering null');
    return null;
  }

  // Permissions missing — show the onboarding/permission request flow
  if (!permissionsGranted) {
    console.log('[App] permissions not granted — showing PermissionsScreen');
    return (
      <SafeAreaProvider>
        <PermissionsScreen
          onComplete={() => {
            console.log(
              '[App] PermissionsScreen complete — permissions granted',
            );
            setPermissionsGranted(true);
          }}
        />
      </SafeAreaProvider>
    );
  }

  // All permissions granted — show the main app
  console.log('[App] permissions granted — rendering main navigator');
  return (
    <SafeAreaProvider>
      <NavigationContainer ref={navigationRef} linking={linking}>
        {/*
          Stack navigator — no bottom tabs.
          Home is the initial route.
          Customize is pushed by the gear icon on Home.
          Browser is pushed by safe-mode buttons or widget deep links.
          All headers are hidden; each screen manages its own header UI.
        */}
        <Stack.Navigator
          screenOptions={{ headerShown: false }}
          initialRouteName="Home"
        >
          <Stack.Screen name="Home" component={Home} />
          <Stack.Screen name="Customize" component={Customize} />
          <Stack.Screen name="Modes" component={ModesScreen} />
          <Stack.Screen name="Browser" component={BrowserScreen} />
        </Stack.Navigator>
      </NavigationContainer>
    </SafeAreaProvider>
  );
};

export default App;

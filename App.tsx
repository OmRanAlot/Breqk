// App.tsx
import React, { useState, useEffect, useRef } from 'react';
import { StyleSheet, Text, NativeModules, NativeEventEmitter, Linking } from 'react-native';
import Home from './components/Home/home';
import Customize from './components/Customize/customize';
import Progress from './components/Progress/progress';
import PermissionsScreen from './components/Permissions/PermissionsScreen';
import { BrowserScreen } from './components/Browser/BrowserScreen';
import { NavigationContainer, createNavigationContainerRef } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';

const { VPNModule, SettingsModule } = NativeModules;
const Tab = createBottomTabNavigator();
const Stack = createNativeStackNavigator();
const navigationRef = createNavigationContainerRef();

/**
 * Deep linking configuration for widget quick-launch buttons.
 * Maps doomscroll://browser/:platform to the Browser screen.
 * Handles both cold start and warm start (singleTask launchMode).
 */
const linking = {
  prefixes: ['doomscroll://'],
  config: {
    screens: {
      Browser: 'browser/:platform',
    },
  },
};

function MainTabs() {
  return (
    <Tab.Navigator
      initialRouteName="Home"
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarStyle: {
          backgroundColor: '#1D201F',
          height: 80,
          paddingBottom: 20,
          paddingTop: 10,
          shadowColor: '#000',
          shadowOffset: { width: 0, height: -2 },
          shadowOpacity: 0.3,
          shadowRadius: 8,
          elevation: 8,
          borderTopWidth: 0,
        },
        tabBarLabelStyle: {
          fontSize: 12,
          fontWeight: '600',
          marginTop: 4,
        },
        tabBarActiveTintColor: '#5B9A8B',
        tabBarInactiveTintColor: '#6B7280',
        tabBarIcon: ({ focused, color }) => {
          let iconText;

          if (route.name === 'Customize') {
            iconText = focused ? '⚙️' : '⚙️';
          } else if (route.name === 'Home') {
            iconText = focused ? '🏠' : '🏠';
          } else if (route.name === 'Progress') {
            iconText = focused ? '📊' : '📊';
          }

          return <Text style={{ fontSize: 20, color }}>{iconText}</Text>;
        },
      })}
    >
      <Tab.Screen name="Home" component={Home} />
      <Tab.Screen name="Customize" component={Customize} />
      <Tab.Screen name="Progress" component={Progress} />
    </Tab.Navigator>
  );
}

const App = () => {
  const [permissionsGranted, setPermissionsGranted] = useState<boolean | null>(null);

  useEffect(() => {
    VPNModule.checkPermissions().then((perms: { usage: boolean; overlay: boolean }) => {
      setPermissionsGranted(perms.usage && perms.overlay);
    });
  }, []);

  // Redirect to distraction-free browser when a blocked app is opened
  useEffect(() => {
    const emitter = new NativeEventEmitter(VPNModule);
    const sub = emitter.addListener('onBlockedAppOpened', (event: { packageName: string }) => {
      if (!navigationRef.isReady()) return;
      const pkg = event.packageName;
      if (pkg === 'com.instagram.android') {
        SettingsModule.getRedirectInstagramToBrowser((value: boolean) => {
          if (value) {
            navigationRef.navigate('Browser' as never, { platform: 'instagram' } as never);
          }
        });
      } else if (pkg === 'com.google.android.youtube') {
        navigationRef.navigate('Browser' as never, { platform: 'youtube' } as never);
      }
    });
    return () => sub.remove();
  }, []);

  if (permissionsGranted === null) return null;

  if (!permissionsGranted) {
    return (
      <SafeAreaProvider>
        <PermissionsScreen onComplete={() => setPermissionsGranted(true)} />
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider>
      <NavigationContainer ref={navigationRef} linking={linking}>
        <Stack.Navigator screenOptions={{ headerShown: false }}>
          <Stack.Screen name="MainTabs" component={MainTabs} />
          <Stack.Screen name="Browser" component={BrowserScreen} />
        </Stack.Navigator>
      </NavigationContainer>
    </SafeAreaProvider>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1D201F',
  },
});

export default App;

// App.tsx
import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, NativeModules } from 'react-native';
import Home from './components/Home/home';
import Customize from './components/Customize/customize';
import Progress from './components/Progress/progress';
import PermissionsScreen from './components/Permissions/PermissionsScreen';
import { NavigationContainer } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { SafeAreaProvider } from 'react-native-safe-area-context';

const { VPNModule } = NativeModules;
const Tab = createBottomTabNavigator();

const App = () => {
  const [permissionsGranted, setPermissionsGranted] = useState<boolean | null>(null);

  useEffect(() => {
    VPNModule.checkPermissions().then((perms: { usage: boolean; overlay: boolean }) => {
      setPermissionsGranted(perms.usage && perms.overlay);
    });
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
      <NavigationContainer>
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
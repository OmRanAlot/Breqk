/**
 * permissionSteps.js — Config for the three required permission steps (4–6).
 * ─────────────────────────────────────────────────────────────────────────────
 * Each entry drives one permission screen in the onboarding flow: its icon,
 * copy, CTA label, reassurance JSX, and the native request it triggers.
 * Extracted from PermissionsScreen.js to keep that screen focused on flow logic.
 */

import React from 'react';
import { Text, NativeModules } from 'react-native';
import { TargetIcon, BarsIcon, LayersIcon } from './onboarding/icons';
import { strongStyle } from './PermissionsScreen.styles';

const { VPNModule } = NativeModules;

// Steps 4–6 are the three required permissions, in display order.
export const PERMISSION_STEPS = [
  {
    permKey: 'accessibility',
    Icon: TargetIcon,
    headline: 'Allow Accessibility',
    body: 'This lets Break notice when you open one of your chosen apps so it can step in with a pause. It only ever watches for those apps.',
    cta: 'Enable Accessibility',
    reassurance: (
      <>
        Nothing you do is read, stored, or sent. Break has no servers and
        collects <Text style={strongStyle}>no data at all</Text>.
      </>
    ),
    request: () => VPNModule.requestAccessibilityPermission(),
  },
  {
    permKey: 'usage',
    Icon: BarsIcon,
    headline: 'Allow Usage Access',
    body: 'This powers your screen-time totals and save events on the home screen. Every number is counted on your phone alone.',
    cta: 'Enable Usage Access',
    reassurance: (
      <>
        Usage stays on-device and is never uploaded. There is no account and{' '}
        <Text style={strongStyle}>no data is collected</Text>.
      </>
    ),
    request: () => VPNModule.requestPermissions(),
  },
  {
    permKey: 'overlay',
    Icon: LayersIcon,
    headline: 'Allow Display Over Apps',
    body: "This lets the breathing pause appear on top of the app you opened. It's the only thing Break ever draws on screen.",
    cta: 'Enable Display Over Apps',
    reassurance: (
      <>
        Break only shows the pause — it sees nothing underneath and{' '}
        <Text style={strongStyle}>collects no data whatsoever</Text>.
      </>
    ),
    request: () => VPNModule.requestOverlayPermission(),
  },
];

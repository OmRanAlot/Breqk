/**
 * icons.js — Inline SVG icons for the Break onboarding flow.
 * ─────────────────────────────────────────────────────────────────────────────
 * Each icon mirrors the exact path data from the "Break Onboarding" design so
 * the rendered screens match the mockup. Colours come from `theme.js`.
 */

import React from 'react';
import Svg, { Path, Circle, Line, Rect, Polyline } from 'react-native-svg';
import { T } from './theme';

/** Shield + keyhole — Break's brand mark. Used on Welcome and reassurance cards. */
export const ShieldIcon = ({ size = 56, color = T.ink, strokeWidth = 1.4 }) => (
  <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <Path
      d="M12 3 L20 6 V11 C20 16.2 16.4 19.6 12 21 C7.6 19.6 4 16.2 4 11 V6 Z"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinejoin="round"
    />
    <Circle
      cx={12}
      cy={10.6}
      r={1.7}
      stroke={color}
      strokeWidth={strokeWidth}
      fill="none"
    />
    <Line
      x1={12}
      y1={12.3}
      x2={12}
      y2={14.6}
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
    />
  </Svg>
);

/** Concentric target — Accessibility permission. */
export const TargetIcon = ({ size = 30, color = T.ink }) => (
  <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <Circle cx={12} cy={12} r={9} stroke={color} strokeWidth={1.7} />
    <Circle cx={12} cy={12} r={2.4} fill={color} />
  </Svg>
);

/** Ascending bars — Usage Access permission. */
export const BarsIcon = ({ size = 30, color = T.ink }) => (
  <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <Rect x={4} y={13} width={4} height={7} rx={1.2} fill={color} />
    <Rect x={10} y={8} width={4} height={12} rx={1.2} fill={color} />
    <Rect x={16} y={4} width={4} height={16} rx={1.2} fill={color} />
  </Svg>
);

/** Overlapping windows — Display Over Apps permission. */
export const LayersIcon = ({ size = 30, color = T.ink, fill = T.tile }) => (
  <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <Rect
      x={4}
      y={4}
      width={13}
      height={13}
      rx={3}
      fill="none"
      stroke={color}
      strokeWidth={1.7}
    />
    <Rect
      x={9}
      y={9}
      width={13}
      height={13}
      rx={3}
      fill={fill}
      stroke={color}
      strokeWidth={1.7}
    />
  </Svg>
);

/** Checkmark — used in selected rows, toggles, and the Done screen. */
export const CheckIcon = ({
  size = 13,
  color = T.iconOnInk,
  strokeWidth = 3,
}) => (
  <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <Polyline
      points="20 6 9 17 4 12"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </Svg>
);

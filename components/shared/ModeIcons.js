/**
 * ModeIcons.js — Stroke-based SVG icons for mode cards and the mode editor.
 * ─────────────────────────────────────────────────────────────────────────────
 * Replaces the old emoji icon set. Paths are Feather-style (MIT) 24×24 strokes
 * so they match the app's other inline SVG icons (BackIcon, CloseIcon, etc.).
 *
 * Usage:
 *   <ModeIcon name="book" size={24} color="#1A1A1A" />
 *
 * Adding an icon: add a render entry in ICON_SHAPES and the key automatically
 * appears in MODE_ICON_KEYS (used by the editor's icon picker).
 */

import React from 'react';
import Svg, { Path, Circle, Rect } from 'react-native-svg';

// Each entry holds the inner SVG shapes for a 24×24 viewBox. Stroke props are
// inherited from the parent <Svg> in ModeIcon.
const ICON_SHAPES = {
  // Open book — study
  book: (
    <>
      <Path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z" />
      <Path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z" />
    </>
  ),
  // Crescent moon — bedtime
  moon: <Path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />,
  // Dumbbell — workout
  dumbbell: (
    <>
      <Path d="M4 9v6" />
      <Path d="M7 6v12" />
      <Path d="M7 12h10" />
      <Path d="M17 6v12" />
      <Path d="M20 9v6" />
    </>
  ),
  // Target — focus
  focus: (
    <>
      <Circle cx={12} cy={12} r={10} />
      <Circle cx={12} cy={12} r={6} />
      <Circle cx={12} cy={12} r={2} />
    </>
  ),
  // Coffee cup — break
  coffee: (
    <>
      <Path d="M18 8h1a4 4 0 0 1 0 8h-1" />
      <Path d="M2 8h16v9a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4V8z" />
      <Path d="M6 1v3" />
      <Path d="M10 1v3" />
      <Path d="M14 1v3" />
    </>
  ),
  // Briefcase — work
  work: (
    <>
      <Rect x={2} y={7} width={20} height={14} rx={2} />
      <Path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16" />
    </>
  ),
  // Lightning bolt — default
  default: <Path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" />,
};

/** Icon keys available in the mode editor's icon picker. */
export const MODE_ICON_KEYS = Object.keys(ICON_SHAPES);

/**
 * Renders a mode icon by key. Unknown keys fall back to the bolt so modes
 * saved with older icon names never render blank.
 *
 * @param {{ name: string, size?: number, color?: string, strokeWidth?: number }} props
 */
const ModeIcon = ({
  name,
  size = 24,
  color = '#1A1A1A',
  strokeWidth = 1.8,
}) => (
  <Svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke={color}
    strokeWidth={strokeWidth}
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    {ICON_SHAPES[name] || ICON_SHAPES.default}
  </Svg>
);

export default ModeIcon;

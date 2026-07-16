/**
 * TimePickerSheet.styles.js — Styles for the shared time-picker bottom sheet.
 * ─────────────────────────────────────────────────────────────────────────────
 * Uses the same Break light palette (`L`) as the mode editor so the sheet reads
 * as part of the editor rather than an OS dialog.
 *
 * ROW_HEIGHT is exported because TimePickerSheet needs the exact pixel value to
 * drive snapToInterval and the initial scroll offset — the wheel maths and the
 * layout must not drift apart.
 */

import { StyleSheet } from 'react-native';
import { L } from '../Modes/ModeEditorModal.styles';

export const ROW_HEIGHT = 44;
export const VISIBLE_ROWS = 5; // odd, so one row sits centered under the band

const WHEEL_HEIGHT = ROW_HEIGHT * VISIBLE_ROWS;
// Pads the wheel so the first and last values can still scroll to the center.
const EDGE_PADDING = (WHEEL_HEIGHT - ROW_HEIGHT) / 2;

export const styles = StyleSheet.create({
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.35)',
  },
  sheet: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: L.cardBg,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    paddingBottom: 32,
  },
  sheetHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 8,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: L.border,
  },
  headerBtn: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    minWidth: 72,
  },
  sheetTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: L.charcoal,
  },
  cancelText: {
    fontSize: 15,
    color: L.muted,
  },
  doneText: {
    fontSize: 15,
    fontWeight: '600',
    color: L.charcoal,
    textAlign: 'right',
  },
  wheelArea: {
    height: WHEEL_HEIGHT,
    justifyContent: 'center',
    marginTop: 8,
  },
  selectionBand: {
    position: 'absolute',
    left: 24,
    right: 24,
    height: ROW_HEIGHT,
    top: EDGE_PADDING,
    borderRadius: 10,
    backgroundColor: L.bg,
    borderWidth: 1,
    borderColor: L.border,
  },
  columns: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
  },
  column: {
    height: WHEEL_HEIGHT,
    width: 76,
  },
  columnContent: {
    paddingVertical: EDGE_PADDING,
  },
  row: {
    height: ROW_HEIGHT,
    alignItems: 'center',
    justifyContent: 'center',
  },
  rowText: {
    fontSize: 20,
    color: L.muted,
    fontVariant: ['tabular-nums'],
  },
  rowTextSelected: {
    fontSize: 22,
    fontWeight: '600',
    color: L.charcoal,
  },
  colon: {
    fontSize: 22,
    fontWeight: '600',
    color: L.charcoal,
    marginHorizontal: -6,
    marginBottom: 2,
  },
});

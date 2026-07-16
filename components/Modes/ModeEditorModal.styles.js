/**
 * ModeEditorModal.styles.js — Styles + palette for the mode editor modal.
 * ─────────────────────────────────────────────────────────────────────────────
 * Extracted verbatim from ModeEditorModal.js. Exports the Break light palette
 * (`L`) so the modal shares a single source of truth for colors.
 */

import { StyleSheet } from 'react-native';

// ─── Break Light Palette ─────────────────────────────────────────────────────
export const L = {
  bg: '#FAFAFA',
  charcoal: '#1A1A1A',
  muted: '#737373',
  border: '#E5E5E5',
  cardBg: '#FFFFFF',
  cardBorder: 'rgba(0,0,0,0.07)',
};

export const styles = StyleSheet.create({
  modalContainer: {
    flex: 1,
    backgroundColor: L.bg,
  },
  modalHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
    backgroundColor: L.bg,
    borderBottomWidth: 1,
    borderBottomColor: L.border,
  },
  headerCloseBtn: {
    width: 36,
    height: 36,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '500',
    color: L.charcoal,
  },
  headerSaveBtn: {
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  headerSaveText: {
    fontSize: 16,
    fontWeight: '600',
    color: L.charcoal,
  },
  modalScroll: {
    flex: 1,
  },
  modalScrollContent: {
    paddingHorizontal: 24,
    paddingTop: 24,
    paddingBottom: 48,
  },
  previewCard: {
    backgroundColor: L.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 20,
    alignItems: 'center',
    marginBottom: 24,
    flexDirection: 'row',
    gap: 12,
  },
  previewIconTile: {
    width: 48,
    height: 48,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  previewName: {
    fontSize: 20,
    fontWeight: '600',
    color: L.charcoal,
    flex: 1,
  },
  previewDot: {
    width: 12,
    height: 12,
    borderRadius: 6,
  },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: L.muted,
    textTransform: 'uppercase',
    letterSpacing: 1.2,
    marginBottom: 12,
    marginTop: 20,
  },
  sectionCaption: {
    fontSize: 13,
    color: L.muted,
    lineHeight: 18,
    marginTop: -6,
    marginBottom: 12,
  },
  // Same as sectionCaption but sits UNDER its control rather than above it.
  sectionCaptionBelow: {
    fontSize: 12,
    color: L.muted,
    textAlign: 'center',
    marginTop: 8,
  },
  nameInput: {
    fontSize: 16,
    color: L.charcoal,
    borderBottomWidth: 1.5,
    borderBottomColor: L.charcoal,
    paddingVertical: 8,
  },
  iconGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
  },
  iconOption: {
    width: 52,
    height: 52,
    borderRadius: 12,
    backgroundColor: L.cardBg,
    borderWidth: 2,
    borderColor: L.cardBorder,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconOptionSelected: {
    borderWidth: 2,
  },
  colorGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
  },
  colorOption: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: 'center',
    justifyContent: 'center',
  },
  colorOptionSelected: {
    borderWidth: 3,
    borderColor: '#FFFFFF',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.2,
    shadowRadius: 4,
    elevation: 4,
  },
  // ── App Open Intercept box ────────────────────────────────────────────────
  interceptBox: {
    backgroundColor: L.cardBg,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 14,
  },
  interceptAppRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 6,
  },
  interceptAppLabel: {
    flex: 1,
    fontSize: 15,
    fontWeight: '500',
    color: L.charcoal,
  },
  interceptRemoveBtn: {
    width: 28,
    height: 28,
    alignItems: 'center',
    justifyContent: 'center',
  },
  interceptEmptyText: {
    fontSize: 13,
    color: L.muted,
    textAlign: 'center',
    lineHeight: 18,
    paddingVertical: 8,
  },
  addAppBtn: {
    alignSelf: 'center',
    width: 48,
    height: 48,
    borderRadius: 24,
    borderWidth: 1.5,
    borderColor: L.border,
    borderStyle: 'dashed',
    alignItems: 'center',
    justifyContent: 'center',
    marginVertical: 8,
  },
  appPickerList: {
    borderTopWidth: 1,
    borderTopColor: L.border,
    marginTop: 8,
    paddingTop: 4,
  },
  appPickerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 8,
  },
  appPickerLabel: {
    flex: 1,
    fontSize: 15,
    color: L.charcoal,
  },
  appPickerCancel: {
    fontSize: 13,
    color: L.muted,
    textAlign: 'center',
    paddingVertical: 8,
  },
  // ── Reels Detection box ───────────────────────────────────────────────────
  reelsBox: {
    backgroundColor: L.cardBg,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: L.cardBorder,
    paddingHorizontal: 14,
    paddingVertical: 4,
  },
  reelsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 10,
  },
  reelsRowDivider: {
    borderBottomWidth: 1,
    borderBottomColor: L.border,
  },
  reelsRowInfo: {
    flex: 1,
  },
  reelsRowLabel: {
    fontSize: 15,
    fontWeight: '500',
    color: L.charcoal,
  },
  reelsRowFeature: {
    fontSize: 12,
    color: L.muted,
    marginTop: 1,
  },
  delayRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 20,
    backgroundColor: L.cardBg,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: L.cardBorder,
    paddingVertical: 12,
  },
  stepperBtn: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: L.bg,
    borderWidth: 1,
    borderColor: L.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepperBtnDisabled: {
    opacity: 0.35,
  },
  stepperBtnText: {
    fontSize: 24,
    fontWeight: '300',
    color: L.charcoal,
  },
  delayValue: {
    fontSize: 28,
    fontWeight: '500',
    color: L.charcoal,
    minWidth: 60,
    textAlign: 'center',
    fontVariant: ['tabular-nums'],
  },
  addScheduleBtn: {
    borderWidth: 1,
    borderColor: L.border,
    borderStyle: 'dashed',
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
  },
  addScheduleBtnText: {
    fontSize: 14,
    color: L.muted,
    fontWeight: '500',
  },
  scheduleBlock: {
    backgroundColor: L.cardBg,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: L.cardBorder,
    padding: 14,
    gap: 12,
  },
  scheduleTimeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  scheduleTimeLabel: {
    fontSize: 14,
    color: L.charcoal,
  },
  // Tappable time value: clock icon + "10:00 PM" + chevron. Replaced the old
  // free-text HH:mm input, so it reads as a control rather than a field.
  scheduleTimeValueGroup: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    backgroundColor: L.bg,
    borderWidth: 1,
    borderColor: L.border,
    borderRadius: 10,
    paddingVertical: 8,
    paddingHorizontal: 12,
  },
  scheduleTimeValue: {
    fontSize: 15,
    fontWeight: '500',
    color: L.charcoal,
    fontVariant: ['tabular-nums'],
    minWidth: 72,
    textAlign: 'right',
  },
  dayPickerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  dayBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: L.bg,
    borderWidth: 1,
    borderColor: L.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dayBtnActive: {
    backgroundColor: L.charcoal,
    borderColor: L.charcoal,
  },
  dayBtnText: {
    fontSize: 13,
    fontWeight: '500',
    color: L.muted,
  },
  dayBtnTextActive: {
    color: '#FFFFFF',
  },
  removeScheduleText: {
    fontSize: 13,
    color: '#E53935',
    textAlign: 'center',
    marginTop: 4,
  },
  deleteBtn: {
    marginTop: 32,
    paddingVertical: 14,
    alignItems: 'center',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#E53935',
  },
  deleteBtnText: {
    fontSize: 15,
    fontWeight: '500',
    color: '#E53935',
  },
});

/**
 * AppDetail.styles.js — Styles + palette for the per-app detail screen.
 * ─────────────────────────────────────────────────────────────────────────────
 * Extracted verbatim from AppDetail.js. Exports the Break light palette (`L`)
 * so the screen and its intercept/modal sub-components share one color source.
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
  container: {
    flex: 1,
    backgroundColor: L.bg,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    backgroundColor: L.bg,
    borderBottomWidth: 1,
    borderBottomColor: L.border,
  },
  backButton: {
    width: 36,
    height: 36,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerTitle: {
    flex: 1,
    textAlign: 'center',
    fontSize: 18,
    fontWeight: '500',
    color: L.charcoal,
    letterSpacing: -0.2,
  },
  headerSpacer: { width: 36 },

  scroll: { flex: 1 },
  scrollContent: {
    paddingHorizontal: 24,
    paddingTop: 28,
    paddingBottom: 48,
  },

  section: { marginBottom: 32 },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: L.charcoal,
    textTransform: 'uppercase',
    letterSpacing: 1.5,
    marginBottom: 12,
  },
  sectionCaption: {
    fontSize: 12,
    color: L.muted,
    lineHeight: 17,
    marginBottom: 14,
    marginTop: -4,
  },

  toggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 4,
  },
  toggleRowDivided: {
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: L.border,
  },
  toggleLabelGroup: {
    flex: 1,
    marginRight: 12,
  },
  toggleLabel: {
    fontSize: 16,
    color: L.charcoal,
    fontWeight: '400',
  },
  toggleCaption: {
    fontSize: 12,
    color: L.muted,
    marginTop: 3,
    lineHeight: 17,
  },

  stepperRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  stepperBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    borderWidth: 1,
    borderColor: L.border,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: L.cardBg,
  },
  stepperBtnText: {
    fontSize: 20,
    color: L.charcoal,
    lineHeight: 24,
  },
  stepperValue: {
    fontSize: 22,
    fontWeight: '300',
    color: L.charcoal,
    minWidth: 40,
    textAlign: 'center',
  },
  stepperUnit: {
    fontSize: 14,
    color: L.muted,
  },

  safeModeButton: {
    backgroundColor: L.charcoal,
    borderRadius: 9999,
    paddingVertical: 14,
    paddingHorizontal: 28,
    alignItems: 'center',
    alignSelf: 'flex-start',
  },
  safeModeButtonText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '500',
  },

  // ── Per-app intercept customization ─────────────────────────────────────
  interceptBox: {
    marginTop: 16,
    padding: 16,
    backgroundColor: L.cardBg,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: L.cardBorder,
  },
  interceptFieldLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: L.muted,
    textTransform: 'uppercase',
    letterSpacing: 1,
    marginBottom: 6,
  },
  interceptInput: {
    borderWidth: 1,
    borderColor: L.border,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    fontSize: 14,
    color: L.charcoal,
    marginBottom: 16,
    minHeight: 60,
    textAlignVertical: 'top',
  },
  interceptRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 4,
  },
  interceptValue: {
    fontSize: 14,
    fontWeight: '500',
    color: L.charcoal,
  },
  interceptSlider: {
    width: '100%',
    height: 36,
    marginBottom: 12,
  },
  interceptSegment: {
    flexDirection: 'row',
    borderWidth: 1,
    borderColor: L.border,
    borderRadius: 8,
    overflow: 'hidden',
    marginBottom: 12,
  },
  interceptSegBtn: {
    flex: 1,
    paddingVertical: 8,
    alignItems: 'center',
    backgroundColor: L.cardBg,
  },
  interceptSegBtnActive: {
    backgroundColor: L.charcoal,
  },
  interceptSegText: {
    fontSize: 13,
    color: L.muted,
    fontWeight: '500',
  },
  interceptSegTextActive: {
    color: '#FFFFFF',
  },
  applyAllButton: {
    marginTop: 8,
    borderWidth: 1,
    borderColor: L.border,
    borderRadius: 8,
    paddingVertical: 10,
    alignItems: 'center',
  },
  applyAllButtonText: {
    fontSize: 14,
    color: L.charcoal,
    fontWeight: '500',
  },

  // ── Apply-to-all modal ───────────────────────────────────────────────────
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.45)',
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 32,
  },
  modalCard: {
    width: '100%',
    backgroundColor: L.cardBg,
    borderRadius: 16,
    padding: 24,
  },
  modalTitle: {
    fontSize: 17,
    fontWeight: '600',
    color: L.charcoal,
    marginBottom: 10,
  },
  modalBody: {
    fontSize: 14,
    color: L.muted,
    lineHeight: 20,
    marginBottom: 20,
  },
  modalActions: {
    flexDirection: 'row',
    gap: 12,
  },
  modalCancel: {
    flex: 1,
    borderWidth: 1,
    borderColor: L.border,
    borderRadius: 9999,
    paddingVertical: 12,
    alignItems: 'center',
  },
  modalCancelText: {
    fontSize: 15,
    color: L.charcoal,
    fontWeight: '500',
  },
  modalConfirm: {
    flex: 1,
    backgroundColor: L.charcoal,
    borderRadius: 9999,
    paddingVertical: 12,
    alignItems: 'center',
  },
  modalConfirmText: {
    fontSize: 15,
    color: '#FFFFFF',
    fontWeight: '500',
  },

  // ── Sticky Save bar ─────────────────────────────────────────────────────────
  saveBar: {
    paddingHorizontal: 24,
    paddingTop: 12,
    backgroundColor: L.bg,
    borderTopWidth: 1,
    borderTopColor: L.border,
  },
  saveButton: {
    backgroundColor: L.charcoal,
    borderRadius: 9999,
    paddingVertical: 15,
    alignItems: 'center',
  },
  saveButtonDisabled: {
    backgroundColor: '#E8E8E8',
  },
  saveButtonText: {
    fontSize: 16,
    color: '#FFFFFF',
    fontWeight: '600',
  },
  saveButtonTextDisabled: {
    color: L.muted,
  },
});

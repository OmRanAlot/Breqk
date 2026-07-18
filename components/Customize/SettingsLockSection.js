/**
 * SettingsLockSection
 * -------------------
 * The opt-in control for the Settings Change Lock, shown in the global Customize
 * screen (and governing per-app scopes too — there is a single feature toggle).
 *
 * Mirrors the "Prevent deletion" pattern: flipping the toggle ON first opens a
 * confirmation modal explaining the commitment; turning it OFF is direct (so the
 * feature keeps the same easy-exit discretion as deletion prevention). When on,
 * a duration picker lets the user choose how long each scope stays locked after
 * an edit.
 *
 * This control is intentionally NOT gated by the lock and changing it does NOT
 * start a lock — so the user can always disable the feature.
 *
 * Logging prefix: [SettingsLock]
 */

import React, { useState } from 'react';
import {
  View,
  Text,
  Switch,
  StyleSheet,
  Modal,
  TouchableOpacity,
} from 'react-native';
import InfoCircle from '../shared/InfoCircle';

const DURATION_OPTIONS = [24, 48, 72, 168]; // hours
const formatDuration = h => {
  if (h < 48) return `${h}h`;
  if (h < 168) return `${h / 24}d`;
  return '1wk';
};

/**
 * @param {{
 *   enabled: boolean,
 *   durationMs: number,
 *   onToggle: (value: boolean) => void,
 *   onPickDuration: (hours: number) => void,
 *   locked?: boolean,
 * }} props
 */
export default function SettingsLockSection({
  enabled,
  durationMs,
  onToggle,
  onPickDuration,
  locked = false,
}) {
  const [confirmVisible, setConfirmVisible] = useState(false);
  const currentHours = Math.round((durationMs || 0) / (60 * 60 * 1000));

  const handleToggle = value => {
    if (locked) return; // toggle is read-only while a lock is active
    if (value) {
      // Enabling: explain the commitment first.
      setConfirmVisible(true);
    } else {
      // Disabling is direct and instantly unlocks every scope.
      onToggle(false);
    }
  };

  const confirmEnable = () => {
    setConfirmVisible(false);
    onToggle(true);
  };

  return (
    <View style={styles.section}>
      <View style={styles.sectionLabelRow}>
        <Text style={styles.sectionLabel}>Settings Change Lock</Text>
        <InfoCircle title="How the Settings Change Lock works">
          <Text style={infoStyles.para}>
            When this is on, changing a settings screen and leaving it makes
            that screen read-only for your chosen duration (the lock timer).
          </Text>
          <Text style={infoStyles.para}>
            Once the timer expires the screen is freely editable again — it only
            re-locks after you actually make another change and leave.
          </Text>
          <Text style={infoStyles.para}>
            Each scope is independent — the global settings and each managed app
            lock on their own timers.
          </Text>
        </InfoCircle>
      </View>
      <View style={styles.row}>
        <View style={styles.rowText}>
          <Text style={styles.toggleLabel}>Lock settings after changes</Text>
          <Text style={styles.toggleSub}>
            After you change a screen and leave it, that screen stays read-only
            for a while. Each app — and the global settings — locks on its own.
          </Text>
        </View>
        <Switch
          value={enabled}
          onValueChange={handleToggle}
          disabled={locked}
          trackColor={{ false: '#D4D4D4', true: '#1A1A1A' }}
          thumbColor="#FFFFFF"
          accessibilityLabel="Lock settings after changes"
        />
      </View>

      {locked && (
        <Text style={styles.lockedNote}>
          A lock is currently active, so this switch is read-only — you can
          change it once the active timer ends.
        </Text>
      )}

      {enabled && (
        <View style={styles.durationRow}>
          <Text style={styles.durationLabel}>Lock for</Text>
          <View style={[styles.segmented, locked && styles.segmentedDisabled]}>
            {DURATION_OPTIONS.map(h => {
              const active = h === currentHours;
              return (
                <TouchableOpacity
                  key={h}
                  style={[styles.segment, active && styles.segmentActive]}
                  onPress={() => !locked && onPickDuration(h)}
                  disabled={locked}
                  activeOpacity={0.7}
                  accessibilityRole="button"
                  accessibilityState={{ selected: active, disabled: locked }}
                >
                  <Text
                    style={[
                      styles.segmentText,
                      active && styles.segmentTextActive,
                    ]}
                  >
                    {formatDuration(h)}
                  </Text>
                </TouchableOpacity>
              );
            })}
          </View>
        </View>
      )}

      <Modal
        visible={confirmVisible}
        transparent
        animationType="fade"
        onRequestClose={() => setConfirmVisible(false)}
      >
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>Lock settings after changes?</Text>
            <Text style={styles.modalBody}>
              Whenever you change a screen and leave it, that screen becomes
              read-only for {formatDuration(currentHours || 24)} before you can
              change it again. This adds a deliberate pause so you can't
              instantly loosen your own limits on impulse.
            </Text>
            <Text style={styles.modalBody}>
              Each scope is separate: locking the global settings won't lock an
              app's settings, and vice-versa.
            </Text>
            <Text style={styles.modalBody}>
              Once the timer expires the screen is freely editable again — it
              only re-locks after you make another change and leave.
            </Text>
            <Text style={styles.modalWarn}>
              Important: once a screen is locked, this switch locks too — you
              can't turn the feature off until that screen's timer ends. That's
              what makes it a real commitment.
            </Text>
            <View style={styles.modalButtons}>
              <TouchableOpacity
                style={styles.modalCancel}
                onPress={() => setConfirmVisible(false)}
                activeOpacity={0.7}
              >
                <Text style={styles.modalCancelText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={styles.modalConfirm}
                onPress={confirmEnable}
                activeOpacity={0.8}
              >
                <Text style={styles.modalConfirmText}>Turn on</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
}

const INK = '#1A1A1A';

// Shared paragraph style for the InfoCircle modal body.
const infoStyles = StyleSheet.create({
  para: {
    fontSize: 13.5,
    lineHeight: 20,
    color: '#525252',
    marginBottom: 12,
  },
});

const styles = StyleSheet.create({
  section: { marginTop: 8, marginBottom: 28 },
  sectionLabelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  sectionLabel: {
    fontSize: 13,
    fontWeight: '700',
    color: INK,
    letterSpacing: 0.3,
    textTransform: 'uppercase',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  rowText: { flex: 1, paddingRight: 14 },
  toggleLabel: { fontSize: 15, fontWeight: '600', color: INK },
  toggleSub: { fontSize: 12.5, lineHeight: 18, color: '#737373', marginTop: 3 },
  durationRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 16,
  },
  durationLabel: { fontSize: 13, fontWeight: '600', color: '#737373' },
  lockedNote: {
    fontSize: 12.5,
    lineHeight: 18,
    color: '#9a3412',
    marginTop: 10,
    fontWeight: '500',
  },
  segmented: {
    flexDirection: 'row',
    backgroundColor: '#EFEAE0',
    borderRadius: 10,
    padding: 3,
  },
  segmentedDisabled: { opacity: 0.5 },
  segment: { paddingVertical: 6, paddingHorizontal: 11, borderRadius: 8 },
  segmentActive: { backgroundColor: INK },
  segmentText: { fontSize: 12.5, fontWeight: '700', color: '#737373' },
  segmentTextActive: { color: '#FFFFFF' },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.45)',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 28,
  },
  modalCard: {
    backgroundColor: '#FFFFFF',
    borderRadius: 18,
    padding: 22,
    width: '100%',
  },
  modalTitle: { fontSize: 17, fontWeight: '700', color: INK, marginBottom: 10 },
  modalBody: {
    fontSize: 13.5,
    lineHeight: 20,
    color: '#525252',
    marginBottom: 12,
  },
  modalWarn: {
    fontSize: 13,
    lineHeight: 19,
    color: '#9a3412',
    backgroundColor: '#fff4ed',
    borderRadius: 10,
    padding: 12,
    marginBottom: 12,
    fontWeight: '500',
  },
  modalButtons: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    marginTop: 4,
  },
  modalCancel: { paddingVertical: 10, paddingHorizontal: 18, marginRight: 8 },
  modalCancelText: { fontSize: 14, fontWeight: '600', color: '#737373' },
  modalConfirm: {
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 22,
    backgroundColor: INK,
  },
  modalConfirmText: { fontSize: 14, fontWeight: '700', color: '#FFFFFF' },
});

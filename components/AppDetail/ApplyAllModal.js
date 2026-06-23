/**
 * ApplyAllModal.js — "Apply to all apps?" confirmation for AppDetail.
 * ─────────────────────────────────────────────────────────────────────────────
 * Self-contained: the parent owns visibility and supplies cancel/confirm
 * callbacks. Warns that the current app's intercept settings (message,
 * countdown, re-show) will overwrite every managed app. Extracted from AppDetail.js.
 */

import React from 'react';
import { View, Text, Modal, TouchableOpacity } from 'react-native';
import { styles } from './AppDetail.styles';

const ApplyAllModal = ({ visible, appLabel, onCancel, onConfirm }) => (
  <Modal
    visible={visible}
    transparent
    animationType="fade"
    onRequestClose={onCancel}
  >
    <View style={styles.modalBackdrop}>
      <View style={styles.modalCard}>
        <Text style={styles.modalTitle}>Apply to all apps?</Text>
        <Text style={styles.modalBody}>
          This will overwrite the intercept message, countdown, and re-show
          settings for every managed app with {appLabel}'s current values.
        </Text>
        <View style={styles.modalActions}>
          <TouchableOpacity
            style={styles.modalCancel}
            onPress={onCancel}
            activeOpacity={0.75}
          >
            <Text style={styles.modalCancelText}>Cancel</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={styles.modalConfirm}
            onPress={onConfirm}
            activeOpacity={0.75}
          >
            <Text style={styles.modalConfirmText}>Apply</Text>
          </TouchableOpacity>
        </View>
      </View>
    </View>
  </Modal>
);

export default ApplyAllModal;

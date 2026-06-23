/**
 * DeletionInfoModal.js — confirmation modal shown before enabling
 * "Deletion Prevention" on the Customize screen.
 * ─────────────────────────────────────────────────────────────────────────────
 * Self-contained: the parent controls visibility and supplies the
 * cancel/confirm callbacks. Explains what the feature does, its limitations,
 * and the privacy guarantee before the user opts in. Extracted from customize.js.
 */

import React from 'react';
import { View, Text, Modal, ScrollView, TouchableOpacity } from 'react-native';
import { styles } from './customize.styles';

const WHAT_IT_DOES = [
  'Uses the accessibility service you already granted to notice when you open Break’s App Info / uninstall screen in Android Settings.',
  'Shows a full-screen pause for 30 seconds with reasons to keep going.',
  'After the 30 seconds you can continue — it never permanently stops you from uninstalling.',
];

const RISKS = [
  'This is friction, not a lock. You can wait out the timer, turn off the accessibility service, or use safe mode to remove Break anytime.',
  'Detection reads only the on-screen text of the Settings uninstall page to know when to show the pause — nothing else.',
  'Some phone brands label that screen differently, so on rare devices the pause may not appear.',
  'Like any accessibility feature, it depends on a permission that can read screen content; Break uses it solely to detect blocked apps and this screen.',
];

const DeletionInfoModal = ({ visible, onCancel, onConfirm }) => (
  <Modal
    visible={visible}
    transparent
    animationType="fade"
    onRequestClose={onCancel}
  >
    <View style={styles.infoModalOverlay}>
      <View style={styles.infoModalCard}>
        <ScrollView
          style={styles.infoModalScroll}
          contentContainerStyle={styles.infoModalContent}
          showsVerticalScrollIndicator={false}
        >
          <Text style={styles.infoModalTitle}>Before you turn this on</Text>

          <Text style={styles.infoModalSectionHeading}>What it does</Text>
          {WHAT_IT_DOES.map((line, i) => (
            <View key={`does-${i}`} style={styles.infoModalBulletRow}>
              <Text style={styles.infoModalBullet}>{'•'}</Text>
              <Text style={styles.infoModalBulletText}>{line}</Text>
            </View>
          ))}

          <Text style={styles.infoModalSectionHeading}>
            Risks &amp; limitations
          </Text>
          {RISKS.map((line, i) => (
            <View key={`risk-${i}`} style={styles.infoModalBulletRow}>
              <Text style={styles.infoModalBullet}>{'•'}</Text>
              <Text style={styles.infoModalBulletText}>{line}</Text>
            </View>
          ))}

          <Text style={styles.infoModalSectionHeading}>Your privacy</Text>
          <Text style={styles.infoModalPrivacy}>
            Break collects no data at all. It cannot — the app has no server and
            makes no network connection whatsoever, so there is no way for any
            of this to ever leave your phone. Everything stays in local settings
            on your device. Nothing is collected, nothing is sent.
          </Text>
        </ScrollView>

        <View style={styles.infoModalButtonRow}>
          <TouchableOpacity
            style={styles.infoModalCancelButton}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel="Cancel"
            onPress={onCancel}
          >
            <Text style={styles.infoModalCancelText}>Cancel</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={styles.infoModalEnableButton}
            activeOpacity={0.7}
            accessibilityRole="button"
            accessibilityLabel="Enable deletion prevention"
            onPress={onConfirm}
          >
            <Text style={styles.infoModalEnableText}>Enable</Text>
          </TouchableOpacity>
        </View>
      </View>
    </View>
  </Modal>
);

export default DeletionInfoModal;

/**
 * InfoCircle
 * ----------
 * Small "ⓘ" tap target that opens a modal explaining a feature. Used next to
 * section labels (Settings Change Lock, Browser Safety double-safe) so the
 * mechanics of timers/barriers are one tap away without cluttering the screen.
 *
 * @param {{
 *   title: string,              // modal heading
 *   children: React.ReactNode,  // modal body (Text elements / rich content)
 *   accessibilityLabel?: string,
 * }} props
 */

import React, { useState } from 'react';
import {
  Modal,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  ScrollView,
} from 'react-native';

export default function InfoCircle({ title, children, accessibilityLabel }) {
  const [visible, setVisible] = useState(false);

  return (
    <>
      <TouchableOpacity
        style={styles.circle}
        onPress={() => setVisible(true)}
        activeOpacity={0.6}
        accessibilityRole="button"
        accessibilityLabel={accessibilityLabel || `About ${title}`}
        hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
      >
        <Text style={styles.circleText}>i</Text>
      </TouchableOpacity>

      <Modal
        visible={visible}
        transparent
        animationType="fade"
        onRequestClose={() => setVisible(false)}
      >
        <View style={styles.backdrop}>
          <View style={styles.card}>
            <Text style={styles.title}>{title}</Text>
            <ScrollView
              style={styles.bodyScroll}
              showsVerticalScrollIndicator={false}
            >
              {children}
            </ScrollView>
            <TouchableOpacity
              style={styles.closeButton}
              onPress={() => setVisible(false)}
              activeOpacity={0.8}
              accessibilityRole="button"
              accessibilityLabel="Close info"
            >
              <Text style={styles.closeText}>Got it</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    </>
  );
}

const INK = '#1A1A1A';
const styles = StyleSheet.create({
  circle: {
    width: 18,
    height: 18,
    borderRadius: 9,
    borderWidth: 1.5,
    borderColor: '#A3A3A3',
    alignItems: 'center',
    justifyContent: 'center',
    marginLeft: 6,
  },
  circleText: {
    fontSize: 11,
    fontWeight: '700',
    fontStyle: 'italic',
    color: '#A3A3A3',
    lineHeight: 13,
  },
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.45)',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 28,
  },
  card: {
    backgroundColor: '#FFFFFF',
    borderRadius: 18,
    padding: 22,
    width: '100%',
    maxHeight: '75%',
  },
  title: { fontSize: 17, fontWeight: '700', color: INK, marginBottom: 12 },
  bodyScroll: { flexGrow: 0 },
  closeButton: {
    marginTop: 16,
    alignSelf: 'flex-end',
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 22,
    backgroundColor: INK,
  },
  closeText: { fontSize: 14, fontWeight: '700', color: '#FFFFFF' },
});

/**
 * InterceptMessageSection.js — "Intercept Message" section of Customize.
 * ─────────────────────────────────────────────────────────────────────────────
 * Presentational: intercept-message text input, default pause-duration slider,
 * and the "Preview Intercept" button. State + handlers are owned by the
 * Customize screen and passed in as props. Extracted from customize.js.
 */

import React from 'react';
import { View, Text, TextInput, TouchableOpacity } from 'react-native';
import Slider from '@react-native-community/slider';
import { styles, L } from './customize.styles';

const InterceptMessageSection = ({
  interceptMessage,
  setInterceptMessage,
  handleMessageSubmit,
  sliderValue,
  pauseDuration,
  handleSliderChange,
  handleSliderComplete,
  onPreview,
}) => (
  <View style={styles.section}>
    <Text style={styles.sectionLabel}>Intercept Message</Text>

    <TextInput
      style={styles.messageInput}
      value={interceptMessage}
      onChangeText={setInterceptMessage}
      onSubmitEditing={handleMessageSubmit}
      placeholder="Enter message..."
      placeholderTextColor={L.muted}
      returnKeyType="done"
      accessibilityLabel="Intercept message"
    />

    <View style={styles.durationHeader}>
      <Text style={styles.durationLabel}>Default Pause Duration</Text>
      <Text style={styles.durationValue}>{sliderValue} seconds</Text>
    </View>
    <Text style={styles.toggleCaption}>
      Applies to all apps unless you set a custom countdown on an app&apos;s
      detail screen.
    </Text>

    <Slider
      style={styles.slider}
      minimumValue={1}
      maximumValue={30}
      step={1}
      value={pauseDuration}
      minimumTrackTintColor={L.charcoal}
      maximumTrackTintColor={L.sliderTrack}
      thumbTintColor={L.sliderThumb}
      onValueChange={handleSliderChange}
      onSlidingComplete={handleSliderComplete}
      accessibilityLabel="Pause duration in seconds"
    />

    <View style={styles.sliderLabels}>
      <Text style={styles.sliderRangeLabel}>1s</Text>
      <Text style={styles.sliderRangeLabel}>30s</Text>
    </View>

    <TouchableOpacity
      style={styles.previewButton}
      activeOpacity={0.85}
      onPress={onPreview}
      accessibilityRole="button"
      accessibilityLabel="Preview intercept"
    >
      <Text style={styles.previewButtonText}>Preview Intercept</Text>
    </TouchableOpacity>
  </View>
);

export default InterceptMessageSection;

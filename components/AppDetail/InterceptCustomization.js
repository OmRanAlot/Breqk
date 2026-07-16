/**
 * InterceptCustomization.js — per-app App Open Intercept customization box.
 * ─────────────────────────────────────────────────────────────────────────────
 * The card shown under "App Open Intercept" when it's enabled: overlay message,
 * countdown slider, re-show frequency (once / every X min), and "Apply to all".
 * For YouTube it also offers the Typing Coach toggle (showCoachToggle), which
 * switches the intercept STYLE: coach ON → typing gate fires at launch and at
 * the re-show frequency below; coach OFF → the normal delay overlay, exactly
 * like other apps. Purely presentational — it updates local state via the
 * setters and calls `onEdit()` to mark the parent dirty. Nothing persists here;
 * AppDetail commits everything on Save.
 */

import React from 'react';
import { View, Text, TextInput, TouchableOpacity, Switch } from 'react-native';
import Slider from '@react-native-community/slider';
import { styles, L } from './AppDetail.styles';

const InterceptCustomization = ({
  interceptMessage,
  setInterceptMessage,
  interceptDelaySecs,
  setInterceptDelaySecs,
  interceptFreqMode,
  setInterceptFreqMode,
  interceptRepeatMin,
  setInterceptRepeatMin,
  showCoachToggle = false,
  coachEnabled = false,
  setCoachEnabled = () => {},
  onEdit,
  onApplyAll,
}) => (
  <View style={styles.interceptBox}>
    {/* Typing Coach (YouTube only) — picks the intercept style */}
    {showCoachToggle && (
      <View style={[styles.toggleRow, { marginBottom: 10 }]}>
        <View style={styles.toggleLabelGroup}>
          <Text style={styles.toggleLabel}>Typing Coach</Text>
          <Text style={styles.toggleCaption}>
            Replaces the delay overlay: type why you're opening YouTube — at
            launch, and again at the re-show frequency below.
          </Text>
        </View>
        <Switch
          value={coachEnabled}
          onValueChange={val => {
            setCoachEnabled(val);
            onEdit();
          }}
          trackColor={{ false: '#D6D6D6', true: L.charcoal }}
          thumbColor="#FFFFFF"
          accessibilityLabel="Typing Coach"
        />
      </View>
    )}

    {/* Message */}
    <Text style={styles.interceptFieldLabel}>Overlay message</Text>
    <TextInput
      style={styles.interceptInput}
      value={interceptMessage}
      onChangeText={text => {
        setInterceptMessage(text);
        onEdit();
      }}
      placeholder="Take a moment before opening this app…"
      placeholderTextColor={L.muted}
      multiline
      maxLength={120}
    />

    {/* Duration */}
    <View style={styles.interceptRow}>
      <Text style={styles.interceptFieldLabel}>Countdown</Text>
      <Text style={styles.interceptValue}>{interceptDelaySecs}s</Text>
    </View>
    <Slider
      style={styles.interceptSlider}
      minimumValue={5}
      maximumValue={30}
      step={1}
      value={interceptDelaySecs}
      onValueChange={v => setInterceptDelaySecs(Math.round(v))}
      onSlidingComplete={v => {
        setInterceptDelaySecs(Math.round(v));
        onEdit();
      }}
      minimumTrackTintColor={L.charcoal}
      maximumTrackTintColor={L.border}
      thumbTintColor={L.charcoal}
    />

    {/* Frequency */}
    <Text style={[styles.interceptFieldLabel, { marginTop: 6 }]}>
      Re-show overlay
    </Text>
    <View style={styles.interceptSegment}>
      <TouchableOpacity
        style={[
          styles.interceptSegBtn,
          interceptFreqMode === 'once' && styles.interceptSegBtnActive,
        ]}
        onPress={() => {
          setInterceptFreqMode('once');
          onEdit();
        }}
        activeOpacity={0.75}
      >
        <Text
          style={[
            styles.interceptSegText,
            interceptFreqMode === 'once' && styles.interceptSegTextActive,
          ]}
        >
          Once per open
        </Text>
      </TouchableOpacity>
      <TouchableOpacity
        style={[
          styles.interceptSegBtn,
          interceptFreqMode === 'repeat' && styles.interceptSegBtnActive,
        ]}
        onPress={() => {
          setInterceptFreqMode('repeat');
          onEdit();
        }}
        activeOpacity={0.75}
      >
        <Text
          style={[
            styles.interceptSegText,
            interceptFreqMode === 'repeat' && styles.interceptSegTextActive,
          ]}
        >
          Every X min
        </Text>
      </TouchableOpacity>
    </View>

    {interceptFreqMode === 'repeat' && (
      <>
        <View style={styles.interceptRow}>
          <Text style={styles.interceptFieldLabel}>Repeat interval</Text>
          <Text style={styles.interceptValue}>{interceptRepeatMin}m</Text>
        </View>
        <Slider
          style={styles.interceptSlider}
          minimumValue={1}
          maximumValue={60}
          step={1}
          value={interceptRepeatMin}
          onValueChange={v => setInterceptRepeatMin(Math.round(v))}
          onSlidingComplete={() => onEdit()}
          minimumTrackTintColor={L.charcoal}
          maximumTrackTintColor={L.border}
          thumbTintColor={L.charcoal}
        />
      </>
    )}

    {/* Apply to all */}
    <TouchableOpacity
      style={styles.applyAllButton}
      onPress={onApplyAll}
      activeOpacity={0.75}
    >
      <Text style={styles.applyAllButtonText}>Apply to all apps</Text>
    </TouchableOpacity>
  </View>
);

export default InterceptCustomization;

/**
 * InterceptCustomization.js — per-app App Open Intercept customization box.
 * ─────────────────────────────────────────────────────────────────────────────
 * The card shown under "App Open Intercept" when it's enabled: overlay message,
 * countdown slider, re-show frequency (once / every X min), and "Apply to all".
 * State + persistence are owned by AppDetail; this is presentational. Extracted
 * from AppDetail.js.
 */

import React from 'react';
import { View, Text, TextInput, TouchableOpacity } from 'react-native';
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
  interceptSaveTimer,
  scheduleInterceptSave,
  saveInterceptSettings,
  onApplyAll,
}) => (
  <View style={styles.interceptBox}>
    {/* Message */}
    <Text style={styles.interceptFieldLabel}>Overlay message</Text>
    <TextInput
      style={styles.interceptInput}
      value={interceptMessage}
      onChangeText={text => {
        setInterceptMessage(text);
        scheduleInterceptSave(
          text,
          interceptDelaySecs,
          interceptFreqMode,
          interceptRepeatMin,
        );
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
        const rounded = Math.round(v);
        setInterceptDelaySecs(rounded);
        if (interceptSaveTimer.current) {
          clearTimeout(interceptSaveTimer.current);
          interceptSaveTimer.current = null;
        }
        saveInterceptSettings(
          interceptMessage,
          rounded,
          interceptFreqMode,
          interceptRepeatMin,
        );
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
          scheduleInterceptSave(
            interceptMessage,
            interceptDelaySecs,
            'once',
            interceptRepeatMin,
          );
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
          scheduleInterceptSave(
            interceptMessage,
            interceptDelaySecs,
            'repeat',
            interceptRepeatMin,
          );
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
          onValueChange={v => {
            setInterceptRepeatMin(v);
            scheduleInterceptSave(
              interceptMessage,
              interceptDelaySecs,
              interceptFreqMode,
              v,
            );
          }}
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

/**
 * TimePickerSheet.js — Bottom-sheet time picker with hour / minute / AM-PM
 * scroll columns.
 * ─────────────────────────────────────────────────────────────────────────────
 * Replaces the free-text "HH:mm" TextInput that used to sit in the mode editor's
 * schedule block. Pure JS (snap-scrolling ScrollViews) so it needs no native
 * date-picker dependency and matches the Break light palette.
 *
 * Values cross the boundary as stored 24h "HH:mm" strings — the 12h/AM-PM split
 * lives entirely inside this component (see shared/scheduleWindow.js helpers),
 * so callers and the native ModeManager keep speaking the same format.
 *
 * Minutes step by MINUTE_STEP; a time loaded off that grid snaps to the nearest
 * step so the wheel always has a row to land on.
 *
 * Logging prefix: [TimePicker]
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  View,
  Text,
  Modal,
  ScrollView,
  TouchableOpacity,
  Pressable,
} from 'react-native';
import { splitTime12h, toTime24h } from './scheduleWindow';
import { styles, ROW_HEIGHT } from './TimePickerSheet.styles';

const MINUTE_STEP = 5;

const HOURS = Array.from({ length: 12 }, (_, i) => i + 1); // 1..12
const MINUTES = Array.from(
  { length: 60 / MINUTE_STEP },
  (_, i) => i * MINUTE_STEP,
); // 0, 5, ..., 55
const MERIDIEMS = ['AM', 'PM'];

/** Snaps an arbitrary minute value onto the MINUTE_STEP grid (55 is the cap). */
const snapMinutes = minutes =>
  Math.min(55, Math.round(minutes / MINUTE_STEP) * MINUTE_STEP);

/**
 * One snap-scrolling wheel column.
 *
 * @param {{ values: Array<number|string>, selected: number|string,
 *           onSelect: (value: number|string) => void,
 *           format?: (value: number|string) => string,
 *           label: string }} props
 */
const WheelColumn = ({ values, selected, onSelect, format, label }) => {
  const scrollRef = useRef(null);
  const selectedIndex = values.indexOf(selected);

  // Scroll the current value under the highlight band when the sheet opens.
  // Runs on mount only: re-running on every selection change would fight the
  // user's own scrolling.
  useEffect(() => {
    if (selectedIndex < 0) {
      return undefined;
    }
    // Without the frame delay the ScrollView has no layout yet and ignores this.
    const timer = setTimeout(() => {
      scrollRef.current?.scrollTo({
        y: selectedIndex * ROW_HEIGHT,
        animated: false,
      });
    }, 0);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleMomentumEnd = useCallback(
    event => {
      const offsetY = event.nativeEvent.contentOffset.y;
      const index = Math.round(offsetY / ROW_HEIGHT);
      const clamped = Math.max(0, Math.min(values.length - 1, index));
      onSelect(values[clamped]);
    },
    [values, onSelect],
  );

  return (
    <ScrollView
      ref={scrollRef}
      style={styles.column}
      contentContainerStyle={styles.columnContent}
      showsVerticalScrollIndicator={false}
      snapToInterval={ROW_HEIGHT}
      decelerationRate="fast"
      onMomentumScrollEnd={handleMomentumEnd}
      accessibilityLabel={label}
    >
      {values.map(value => {
        const isSelected = value === selected;
        const text = format ? format(value) : String(value);
        return (
          <Pressable
            key={String(value)}
            style={styles.row}
            onPress={() => onSelect(value)}
            accessibilityRole="button"
            accessibilityLabel={`${label} ${text}`}
            accessibilityState={{ selected: isSelected }}
          >
            <Text
              style={[styles.rowText, isSelected && styles.rowTextSelected]}
            >
              {text}
            </Text>
          </Pressable>
        );
      })}
    </ScrollView>
  );
};

/**
 * @param {{ visible: boolean, title: string, value: string,
 *           onConfirm: (time24h: string) => void, onCancel: () => void }} props
 *   `value` and the onConfirm argument are both stored 24h "HH:mm" strings.
 */
const TimePickerSheet = ({ visible, title, value, onConfirm, onCancel }) => {
  const [hours12, setHours12] = useState(12);
  const [minutes, setMinutes] = useState(0);
  const [meridiem, setMeridiem] = useState('AM');

  // Re-seed the columns from `value` each time the sheet opens, so reopening
  // after a cancel shows the saved time rather than the abandoned edit.
  useEffect(() => {
    if (!visible) {
      return;
    }
    const split = splitTime12h(value);
    setHours12(split.hours12);
    setMinutes(snapMinutes(split.minutes));
    setMeridiem(split.meridiem);
  }, [visible, value]);

  const handleDone = () => {
    const time24h = toTime24h(hours12, minutes, meridiem);
    console.log('[TimePicker] confirmed:', title, '→', time24h);
    onConfirm(time24h);
  };

  return (
    <Modal
      visible={visible}
      transparent
      animationType="slide"
      onRequestClose={onCancel}
    >
      <Pressable
        style={styles.backdrop}
        onPress={onCancel}
        accessibilityRole="button"
        accessibilityLabel="Dismiss time picker"
      />
      <View style={styles.sheet}>
        <View style={styles.sheetHeader}>
          <TouchableOpacity onPress={onCancel} style={styles.headerBtn}>
            <Text style={styles.cancelText}>Cancel</Text>
          </TouchableOpacity>
          <Text style={styles.sheetTitle}>{title}</Text>
          <TouchableOpacity onPress={handleDone} style={styles.headerBtn}>
            <Text style={styles.doneText}>Done</Text>
          </TouchableOpacity>
        </View>

        <View style={styles.wheelArea}>
          {/* Highlight band marking the row the wheels snap to. */}
          <View style={styles.selectionBand} pointerEvents="none" />

          <View style={styles.columns}>
            <WheelColumn
              label="Hour"
              values={HOURS}
              selected={hours12}
              onSelect={setHours12}
            />
            <Text style={styles.colon}>:</Text>
            <WheelColumn
              label="Minute"
              values={MINUTES}
              selected={minutes}
              onSelect={setMinutes}
              format={m => String(m).padStart(2, '0')}
            />
            <WheelColumn
              label="AM or PM"
              values={MERIDIEMS}
              selected={meridiem}
              onSelect={setMeridiem}
            />
          </View>
        </View>
      </View>
    </Modal>
  );
};

export { MINUTE_STEP };
export default TimePickerSheet;

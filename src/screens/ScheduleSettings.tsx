import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AppState,
  Button,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  blocking,
  SchedulePolicy,
  ScheduleSnapshot,
} from '../native/BlockingModule';

const days = [
  'Monday',
  'Tuesday',
  'Wednesday',
  'Thursday',
  'Friday',
  'Saturday',
  'Sunday',
];
const initialPolicy = (): SchedulePolicy => ({
  startTime: '06:00',
  endTime: '09:00',
  weekdays: [1, 2, 3, 4, 5, 6, 7],
  packages: [],
});
const message = (reason: unknown) =>
  reason instanceof Error ? reason.message : 'Native schedule operation failed';
const validTime = (value: string) =>
  /^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(value);

export default function ScheduleSettings(): React.JSX.Element {
  const [snapshot, setSnapshot] = useState<ScheduleSnapshot | null>(null);
  const [draft, setDraft] = useState<SchedulePolicy>(initialPolicy);
  const [reading, setReading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const alive = useRef(false);
  const active = useRef(true);
  const dirty = useRef(false);
  const savingNow = useRef(false);
  const request = useRef(0);

  const refresh = useCallback(async () => {
    if (!alive.current || savingNow.current) return;
    const id = ++request.current;
    setReading(true);
    try {
      const view = await blocking.getScheduleConfiguration();
      if (!alive.current || id !== request.current) return;
      setSnapshot(view);
      setLoadError(null);
      if (!dirty.current) setDraft(view.policy ?? initialPolicy());
    } catch (reason) {
      if (alive.current && id === request.current)
        setLoadError(message(reason));
    } finally {
      if (alive.current && id === request.current) setReading(false);
    }
  }, []);

  useEffect(() => {
    alive.current = true;
    active.current =
      !AppState.currentState || AppState.currentState === 'active';
    refresh();
    const subscription = AppState.addEventListener('change', state => {
      active.current = state === 'active';
      if (active.current) refresh();
    });
    const timer = setInterval(() => {
      if (active.current) refresh();
    }, 5_000);
    return () => {
      alive.current = false;
      clearInterval(timer);
      subscription.remove();
    };
  }, [refresh]);

  const change = (update: (value: SchedulePolicy) => SchedulePolicy) => {
    if (savingNow.current) return;
    dirty.current = true;
    setDraft(update);
    setError(null);
    setNotice(null);
  };

  const save = async () => {
    if (savingNow.current || reading || !snapshot || loadError) return;
    if (!validTime(draft.startTime) || !validTime(draft.endTime)) {
      setError('Enter valid 24-hour times in HH:mm format.');
      return;
    }
    if (draft.startTime >= draft.endTime) {
      setError(
        'Start must be earlier than end. Overnight windows are not supported.',
      );
      return;
    }
    savingNow.current = true;
    setSaving(true);
    setError(null);
    setNotice(null);
    const id = ++request.current;
    try {
      const view = await blocking.saveScheduleConfiguration({
        ...draft,
        weekdays: [...draft.weekdays].sort((a, b) => a - b),
        packages: [...draft.packages].sort(),
      });
      if (!alive.current || id !== request.current) return;
      dirty.current = false;
      setSnapshot(view);
      setDraft(view.policy ?? initialPolicy());
      setNotice('Schedule saved.');
    } catch (reason) {
      if (alive.current && id === request.current) setError(message(reason));
    } finally {
      savingNow.current = false;
      if (alive.current && id === request.current) setSaving(false);
    }
  };

  const unavailable = snapshot === null || saving;
  const apps = snapshot?.supportedApps ?? [];
  const allApps = [
    ...apps,
    ...draft.packages
      .filter(packageName => !apps.some(app => app.packageName === packageName))
      .map(packageName => ({ packageName, label: packageName })),
  ];
  const names = (packages: string[]) =>
    packages
      .map(
        packageName =>
          apps.find(app => app.packageName === packageName)?.label ??
          packageName,
      )
      .join(', ');
  const current = snapshot?.activeSession;

  return (
    <View style={styles.panel}>
      <Text style={styles.heading}>Morning schedule</Text>
      <Text style={styles.body}>
        Use local time and a same-day window. Once a session starts, edits apply
        to a later session.
      </Text>
      {reading && !snapshot ? <Text>Loading saved schedule…</Text> : null}
      {snapshot?.policy === null ? (
        <Text>No schedule saved. Choose apps and times, then save.</Text>
      ) : null}
      {current ? (
        <View style={styles.summary}>
          <Text
            style={styles.heading}
          >{`Active session: ${current.startTime}–${current.endTime}`}</Text>
          <Text>{`Apps: ${names(current.packages)}`}</Text>
        </View>
      ) : snapshot?.policy ? (
        <Text>Outside a blocking session.</Text>
      ) : null}
      {snapshot?.changesDeferred ? (
        <Text
          style={styles.body}
        >{`Changes apply from ${snapshot.effectiveFrom}. Today's schedule is unchanged.`}</Text>
      ) : null}
      {snapshot?.policy &&
      (!snapshot.policy.packages.length || !snapshot.policy.weekdays.length) ? (
        <Text>
          No future blocking is selected. An active session keeps its original
          rules.
        </Text>
      ) : null}

      <Text style={styles.heading}>Apps</Text>
      <View style={styles.choices}>
        {allApps.map(app => {
          const checked = draft.packages.includes(app.packageName);
          return (
            <Pressable
              key={app.packageName}
              accessibilityRole="checkbox"
              accessibilityLabel={app.label}
              accessibilityState={{ checked, disabled: unavailable }}
              disabled={unavailable}
              style={[styles.choice, checked && styles.selected]}
              onPress={() =>
                change(value => ({
                  ...value,
                  packages: value.packages.includes(app.packageName)
                    ? value.packages.filter(item => item !== app.packageName)
                    : [...value.packages, app.packageName],
                }))
              }
            >
              <Text
                style={checked ? styles.selectedText : styles.choiceText}
              >{`${checked ? '✓ ' : ''}${app.label}`}</Text>
            </Pressable>
          );
        })}
      </View>
      <Text style={styles.heading}>Weekdays</Text>
      <View style={styles.choices}>
        {days.map((label, index) => {
          const day = index + 1;
          const checked = draft.weekdays.includes(day);
          return (
            <Pressable
              key={label}
              accessibilityRole="checkbox"
              accessibilityLabel={label}
              accessibilityState={{ checked, disabled: unavailable }}
              disabled={unavailable}
              style={[styles.choice, checked && styles.selected]}
              onPress={() =>
                change(value => ({
                  ...value,
                  weekdays: value.weekdays.includes(day)
                    ? value.weekdays.filter(item => item !== day)
                    : [...value.weekdays, day],
                }))
              }
            >
              <Text style={checked ? styles.selectedText : styles.choiceText}>
                {label.slice(0, 3)}
              </Text>
            </Pressable>
          );
        })}
      </View>
      <View style={styles.timeRow}>
        <View style={styles.timeField}>
          <Text>Start (24-hour HH:mm)</Text>
          <TextInput
            accessibilityLabel="Start time"
            value={draft.startTime}
            editable={!unavailable}
            maxLength={5}
            selectTextOnFocus
            autoCorrect={false}
            autoCapitalize="none"
            style={styles.input}
            onChangeText={value =>
              change(previous => ({ ...previous, startTime: value }))
            }
          />
        </View>
        <View style={styles.timeField}>
          <Text>End (24-hour HH:mm)</Text>
          <TextInput
            accessibilityLabel="End time"
            value={draft.endTime}
            editable={!unavailable}
            maxLength={5}
            selectTextOnFocus
            autoCorrect={false}
            autoCapitalize="none"
            style={styles.input}
            onChangeText={value =>
              change(previous => ({ ...previous, endTime: value }))
            }
          />
        </View>
      </View>
      {dirty.current ? <Text>Unsaved changes.</Text> : null}
      {loadError ? (
        <Text
          accessibilityRole="alert"
          style={styles.error}
        >{`Cannot load schedule: ${loadError}`}</Text>
      ) : null}
      {error ? (
        <Text accessibilityRole="alert" style={styles.error}>
          {error}
        </Text>
      ) : null}
      {notice ? <Text accessibilityLiveRegion="polite">{notice}</Text> : null}
      {saving ? (
        <Text accessibilityLiveRegion="polite">Saving schedule…</Text>
      ) : null}
      <Button
        title="Save schedule"
        disabled={saving || reading || !snapshot || loadError !== null}
        onPress={save}
      />
      <Button title="Reload schedule" disabled={saving} onPress={refresh} />
    </View>
  );
}

const styles = StyleSheet.create({
  panel: {
    gap: 14,
    marginTop: 20,
    paddingTop: 20,
    borderTopWidth: 1,
    borderTopColor: '#b9c6bc',
  },
  heading: { fontSize: 19, fontWeight: '600', color: '#19251d' },
  body: { fontSize: 16, lineHeight: 24, color: '#334338' },
  summary: { padding: 12, gap: 8, backgroundColor: '#e5eee7', borderRadius: 8 },
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  choice: {
    minHeight: 48,
    minWidth: 48,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderWidth: 1,
    borderColor: '#789080',
    borderRadius: 8,
  },
  selected: { backgroundColor: '#284f36', borderColor: '#284f36' },
  selectedText: { color: '#ffffff', fontSize: 16 },
  choiceText: { color: '#19251d', fontSize: 16 },
  timeRow: { flexDirection: 'row', gap: 12 },
  timeField: { flex: 1, gap: 8 },
  input: {
    minHeight: 48,
    padding: 10,
    borderWidth: 1,
    borderColor: '#789080',
    borderRadius: 8,
    backgroundColor: '#ffffff',
    color: '#19251d',
    fontSize: 18,
  },
  error: { color: '#9b2c2c', fontSize: 16 },
});

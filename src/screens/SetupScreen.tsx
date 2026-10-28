import React, { useCallback, useEffect, useState } from 'react';
import {
  AppState,
  Button,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { blocking, BlockingStatus } from '../native/BlockingModule';

const message = (error: unknown) =>
  error instanceof Error ? error.message : 'Native operation failed';

export default function SetupScreen(): React.JSX.Element {
  const [status, setStatus] = useState<BlockingStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setStatus(await blocking.getStatus());
      setError(null);
    } catch (reason) {
      setStatus(null);
      setError(message(reason));
    }
  }, []);

  useEffect(() => {
    refresh();
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') {
        refresh();
      }
    });
    return () => subscription.remove();
  }, [refresh]);

  const openSettings = async () => {
    try {
      await blocking.openAccessibilitySettings();
      setError(null);
    } catch (reason) {
      setError(message(reason));
    }
  };

  const connection = !status
    ? 'Status unavailable'
    : !status.enabled
    ? 'Accessibility access disabled'
    : status.connected
    ? 'Service connected'
    : 'Enabled, service disconnected';

  return (
    <SafeAreaView style={styles.page}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>Morning Blocker</Text>
        <Text style={styles.heading}>Instagram blocking prototype</Text>
        <Text style={styles.body}>
          Instagram stays blocked whenever this accessibility service is
          enabled, even when this screen is closed. No schedules or emergency
          access are available in this prototype.
        </Text>
        <Text style={styles.heading}>Accessibility access</Text>
        <Text style={styles.body}>
          Morning Blocker uses Android accessibility access to identify the
          foreground app from window and package metadata and cover Instagram
          with a blocking screen. It can access window information, but does not
          read message text, store screen content, or transmit data. Everything
          runs on your phone.
        </Text>
        <Text style={styles.body}>
          Enable access only if you agree to this use. You can disable the
          service in Android accessibility settings or uninstall the app at any
          time.
        </Text>
        <Button
          title="I agree — open accessibility settings"
          onPress={openSettings}
        />
        <View style={styles.status}>
          <Text style={styles.heading}>{connection}</Text>
          {status?.error ? (
            <Text accessibilityRole="alert">{status.error}</Text>
          ) : null}
          {error ? <Text accessibilityRole="alert">{error}</Text> : null}
          <Text style={styles.body}>
            A connected service still needs testing on this device. Open
            Instagram to check interruption, then try again after closing this
            app.
          </Text>
          <Button title="Refresh status" onPress={refresh} />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: '#faf8f3' },
  content: { padding: 24, gap: 16 },
  title: { fontSize: 30, fontWeight: '700', color: '#19251d' },
  heading: { fontSize: 19, fontWeight: '600', color: '#19251d' },
  body: { fontSize: 16, lineHeight: 25, color: '#334338' },
  status: { gap: 16, marginTop: 16 },
});

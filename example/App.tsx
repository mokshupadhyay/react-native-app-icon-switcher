import React, { useEffect, useState } from 'react';
import {
  Linking,
  Platform,
  Pressable,
  ScrollView,
  Settings,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  addAppIconChangeListener,
  cancelPendingAppIcon,
  clearAppIconSchedule,
  scheduleAppIcon,
  useAppIcon,
} from 'react-native-app-icon-switcher';
import type { AppIconName } from 'react-native-app-icon-switcher';

const label = (icon: AppIconName) => icon ?? 'Primary';

function Button({ title, onPress, selected }: { title: string; onPress: () => void; selected?: boolean }) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [styles.button, selected && styles.selected, pressed && styles.pressed]}>
      <Text style={[styles.buttonText, selected && styles.selectedText]}>{title}</Text>
    </Pressable>
  );
}

export default function App() {
  const { state, setIcon, refresh } = useAppIcon();
  const [log, setLog] = useState<string[]>([]);
  const note = (line: string) => setLog((lines) => [`${new Date().toLocaleTimeString()}  ${line}`, ...lines].slice(0, 12));

  useEffect(() => {
    const subscription = addAppIconChangeListener(({ icon, previous }) =>
      note(`changed: ${label(previous)} → ${label(icon)}`),
    );
    return () => subscription.remove();
  }, []);

  // flexappicon://icon/Midnight, flexappicon://icon/default, flexappicon://schedule/Sunset/30
  useEffect(() => {
    const open = (url: string | null) => {
      const match = url && /^flexappicon:\/\/(icon|schedule)\/([^/?#]+)(?:\/(\d+))?/.exec(url);
      if (!match) return;
      const icon = decodeURIComponent(match[2]);
      if (match[1] === 'icon') {
        choose(icon);
      } else {
        scheduleAppIcon(icon, Date.now() + Number(match[3] || 30) * 1000).then(() => {
          note(`scheduled ${icon} in ${match[3] || 30}s`);
          refresh();
        });
      }
    };
    Linking.getInitialURL().then(open);
    // iOS scripted runs: `xcrun simctl launch <device> <bundle> -flexDemo icon/Midnight`.
    const demo = Platform.OS === 'ios' ? Settings.get('flexDemo') : null;
    if (typeof demo === 'string') open(`flexappicon://${demo}`);
    const subscription = Linking.addEventListener('url', ({ url }) => open(url));
    return () => subscription.remove();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const choose = async (icon: AppIconName) => {
    try {
      const result = await setIcon(icon);
      note(`setAppIcon(${label(icon)}): ${result.status}`);
    } catch (error) {
      const { code, message } = error as { code?: string; message?: string };
      note(`setAppIcon(${label(icon)}) failed: ${code} ${message}`);
    }
  };

  const icons: AppIconName[] = [null, ...(state?.icons ?? [])];

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <StatusBar barStyle="dark-content" />
      <Text style={styles.title}>Flex App Icon</Text>
      <Text testID="current" style={styles.row}>
        Current: {state ? label(state.current) : '…'}
      </Text>
      <Text testID="pending" style={styles.row}>
        Pending: {state?.pending ? label(state.pending.icon) : 'none'}
      </Text>
      <Text style={styles.row}>Supported: {state ? String(state.supported) : '…'}</Text>

      <View style={styles.buttons}>
        {icons.map((icon) => (
          <Button key={label(icon)} title={label(icon)} selected={state?.current === icon} onPress={() => choose(icon)} />
        ))}
      </View>

      <View style={styles.buttons}>
        <Button
          title="Sunset in 30s"
          onPress={async () => {
            await scheduleAppIcon('Sunset', Date.now() + 30_000);
            note('scheduled Sunset in 30s');
            refresh();
          }}
        />
        <Button
          title="Cancel pending"
          onPress={async () => {
            await cancelPendingAppIcon();
            refresh();
          }}
        />
        <Button
          title="Clear schedule"
          onPress={async () => {
            await clearAppIconSchedule();
            refresh();
          }}
        />
      </View>

      {state && state.schedule.length > 0 && (
        <Text style={styles.row}>
          Schedule: {state.schedule.map((entry) => `${label(entry.icon)} @ ${new Date(entry.at).toLocaleTimeString()}`).join(', ')}
        </Text>
      )}
      {state?.issues.map((issue) => (
        <Text key={issue} style={styles.issue}>
          {issue}
        </Text>
      ))}

      <Text style={styles.subtitle}>Log</Text>
      {log.map((line, index) => (
        <Text key={`${index}-${line}`} style={styles.log}>
          {line}
        </Text>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 24, paddingTop: 72, gap: 8 },
  title: { fontSize: 28, fontWeight: '700', marginBottom: 8 },
  subtitle: { fontSize: 18, fontWeight: '600', marginTop: 16 },
  row: { fontSize: 16 },
  buttons: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  button: { paddingHorizontal: 16, paddingVertical: 10, borderRadius: 10, backgroundColor: '#E5E7EB' },
  selected: { backgroundColor: '#2563EB' },
  pressed: { opacity: 0.7 },
  buttonText: { fontSize: 15, fontWeight: '600', color: '#111827' },
  selectedText: { color: 'white' },
  issue: { color: '#B91C1C' },
  log: { fontFamily: 'Menlo', fontSize: 12, color: '#374151' },
});

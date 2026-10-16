import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { Linking, StyleSheet, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { MAX_SCALE, type Library } from './bench/BenchViewer';
import { BENCHMARK_FIXTURES, type FixtureName } from './documents';
import { BenchmarkScreen, type BenchRequest } from './screens/BenchmarkScreen';
import { DocumentsScreen } from './screens/DocumentsScreen';
import { EmbeddedScreen } from './screens/EmbeddedScreen';
import { OfflineScreen } from './screens/OfflineScreen';
import {
  SHOWCASE_FEATURES,
  ShowcaseScreen,
  type ShowcaseFeature,
} from './screens/ShowcaseScreen';
import { ViewerScreen } from './screens/ViewerScreen';
import { Button, colors } from './ui';

type Tab = 'viewer' | 'embedded' | 'offline' | 'documents' | 'benchmark';

/**
 * `betterpdfexample://bench?library=better&fixture=large&run=3[&scrollSeconds=300][&source=remote][&zoom=0.05]`
 * starts a benchmark run.
 */
function parseBenchLink(url: string | null): BenchRequest | null {
  if (url == null) return null;
  const match = /^[a-z]+:\/\/bench\?(.*)$/.exec(url);
  if (match == null) return null;
  const params = new Map(
    (match[1] ?? '')
      .split('&')
      .map(
        (pair) => pair.split('=').map(decodeURIComponent) as [string, string],
      ),
  );
  const library = params.get('library');
  const fixture = params.get('fixture');
  if (library !== 'better' && library !== 'react-native-pdf') return null;
  if (!BENCHMARK_FIXTURES.some((name) => name === fixture)) return null;
  const zoom = params.has('zoom') ? Number(params.get('zoom')) : undefined;
  // Beyond the viewers' range the zoom would be clamped and recorded wrong.
  if (zoom !== undefined && !(zoom > 0 && zoom <= MAX_SCALE)) return null;
  return {
    library: library satisfies Library,
    fixture: fixture as FixtureName,
    runId: Number(params.get('run') ?? Date.now()),
    scrollWindowMs: params.has('scrollSeconds')
      ? Number(params.get('scrollSeconds')) * 1000
      : undefined,
    remote: params.get('source') === 'remote',
    zoom,
  };
}

/** `betterpdfexample://showcase?feature=search` plays one feature full screen. */
function parseShowcaseLink(url: string | null): ShowcaseFeature | null {
  const match =
    url == null ? null : /^[a-z]+:\/\/showcase\?feature=([a-z]+)/.exec(url);
  const feature = match?.[1];
  return SHOWCASE_FEATURES.find((name) => name === feature) ?? null;
}

export default function App() {
  const [tab, setTab] = useState<Tab>('viewer');
  const [autoRun, setAutoRun] = useState<BenchRequest | null>(null);
  const [showcase, setShowcase] = useState<{
    feature: ShowcaseFeature;
    key: number;
  } | null>(null);

  useEffect(() => {
    const handle = (url: string | null) => {
      const feature = parseShowcaseLink(url);
      if (feature != null) {
        setShowcase({ feature, key: Date.now() });
        return;
      }
      const request = parseBenchLink(url);
      if (request == null) return;
      setShowcase(null);
      setTab('benchmark');
      setAutoRun(request);
    };
    Linking.getInitialURL().then(handle);
    const subscription = Linking.addEventListener('url', (event) =>
      handle(event.url),
    );
    return () => subscription.remove();
  }, []);

  if (showcase != null) {
    return (
      <SafeAreaProvider>
        <StatusBar style="dark" />
        <ShowcaseScreen key={showcase.key} feature={showcase.feature} />
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.fill} edges={['top', 'bottom']}>
        <StatusBar style="dark" />
        <View style={styles.tabs}>
          <Button
            title="Viewer"
            selected={tab === 'viewer'}
            onPress={() => setTab('viewer')}
          />
          <Button
            title="Embedded"
            selected={tab === 'embedded'}
            onPress={() => setTab('embedded')}
          />
          <Button
            title="Offline"
            selected={tab === 'offline'}
            onPress={() => setTab('offline')}
          />
          <Button
            title="Docs"
            selected={tab === 'documents'}
            onPress={() => setTab('documents')}
          />
          {/* Short labels: five tabs fit one row on a phone. */}
          <Button
            title="Bench"
            selected={tab === 'benchmark'}
            onPress={() => setTab('benchmark')}
          />
        </View>
        {tab === 'viewer' && <ViewerScreen />}
        {tab === 'embedded' && <EmbeddedScreen />}
        {tab === 'offline' && <OfflineScreen />}
        {tab === 'documents' && <DocumentsScreen />}
        {tab === 'benchmark' && <BenchmarkScreen autoRun={autoRun} />}
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: colors.card },
  tabs: { flexDirection: 'row', gap: 8, padding: 8, justifyContent: 'center' },
});

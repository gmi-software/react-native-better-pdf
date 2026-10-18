import { useCallback, useEffect, useRef, useState } from 'react';
import { Platform, ScrollView, StyleSheet, Text, View } from 'react-native';
import {
  BenchViewer,
  type BenchViewerHandle,
  type Library,
} from '../bench/BenchViewer';
import { LOG_PREFIX, runScenario, type BenchResult } from '../bench/scenario';
import {
  BENCHMARK_FIXTURES,
  ensureFixture,
  FIXTURE_SERVER,
  type FixtureName,
} from '../documents';
import { Button, colors } from '../ui';

export interface BenchRequest {
  library: Library;
  fixture: FixtureName;
  /** Distinguishes repeated runs of the same configuration. */
  runId: number;
  /** Length of the host-driven scroll window; longer for memory soak runs. */
  scrollWindowMs?: number;
  /**
   * Open the fixture from the fixture server instead of a local copy, so
   * the time to load includes each library's download.
   */
  remote?: boolean;
  /** Zoom of the scroll window; below 1 also lowers `minScale` to it. */
  zoom?: number;
}

interface ActiveRun {
  request: BenchRequest;
  uri: string;
  mountedAt: number;
}

type Waiter = { page: number; resolve: (reached: boolean) => void };

// Machine-readable lines for the host drivers. iOS release builds drop
// console.log (React Native's release log threshold is "error").
const benchLog =
  Platform.OS === 'ios' && !__DEV__ ? console.error : console.log;

export function BenchmarkScreen({ autoRun }: { autoRun: BenchRequest | null }) {
  const [library, setLibrary] = useState<Library>('better');
  const [fixture, setFixture] = useState<FixtureName>('medium');
  const [active, setActive] = useState<ActiveRun | null>(null);
  const [status, setStatus] = useState('Idle');
  const [results, setResults] = useState<BenchResult[]>([]);

  const viewer = useRef<BenchViewerHandle>(null);
  const loadResolver = useRef<((pageCount: number) => void) | null>(null);
  const loadRejecter = useRef<((error: Error) => void) | null>(null);
  const waiters = useRef<Waiter[]>([]);
  const callbacks = useRef(0);

  const start = useCallback(async (request: BenchRequest) => {
    setStatus(`Preparing ${request.fixture}…`);
    setActive(null);
    // A fresh URL per run: neither library may reuse an earlier download.
    const uri = request.remote
      ? `${FIXTURE_SERVER}/${request.fixture}.pdf?run=${request.runId}`
      : (await ensureFixture(request.fixture)).uri;
    // Let the previous viewer unmount and release memory before measuring.
    await new Promise((resolve) => setTimeout(resolve, 1000));

    const loaded = new Promise<number>((resolve, reject) => {
      loadResolver.current = resolve;
      loadRejecter.current = reject;
    });
    const mountedAt = performance.now();
    setActive({ request, uri, mountedAt });
    setStatus(`Running ${request.library} × ${request.fixture}…`);

    let result: BenchResult;
    try {
      callbacks.current = 0;
      result = await runScenario(
        request.library,
        request.fixture,
        Platform.OS,
        mountedAt,
        {
          viewer: () => viewer.current,
          loaded,
          waitForPage: (page, timeoutMs) =>
            new Promise<boolean>((resolve) => {
              const waiter: Waiter = { page, resolve };
              waiters.current.push(waiter);
              setTimeout(() => {
                waiters.current = waiters.current.filter((w) => w !== waiter);
                resolve(false);
              }, timeoutMs);
            }),
          callbackCount: () => callbacks.current,
          log: benchLog,
        },
        request.scrollWindowMs,
        request.zoom,
      );
    } catch (error) {
      result = {
        library: request.library,
        fixture: request.fixture,
        platform: Platform.OS,
        zoom: request.zoom ?? 1,
        pageCount: 0,
        timeToLoadMs: -1,
        jumps: [],
        js: {
          idle: emptyFrames,
          jumps: emptyFrames,
          zoom: emptyFrames,
          scroll: emptyFrames,
        },
        jsCallbacksDuringScroll: 0,
        nativeStats: null,
        error: String(error),
      };
    }
    benchLog(
      `${LOG_PREFIX}_RESULT ${JSON.stringify({ ...result, runId: request.runId })}`,
    );
    setResults((previous) => [result, ...previous]);
    setStatus('Done');
    setActive(null);
  }, []);

  useEffect(() => {
    // A deep link asked for a run (see App); starting it is this effect's job.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (autoRun != null) start(autoRun);
  }, [autoRun, start]);

  return (
    <View style={styles.fill}>
      <View style={styles.controls}>
        <View style={styles.row}>
          {(['better', 'react-native-pdf'] as const).map((value) => (
            <Button
              key={value}
              title={value}
              selected={library === value}
              onPress={() => setLibrary(value)}
            />
          ))}
        </View>
        <View style={styles.row}>
          {BENCHMARK_FIXTURES.map((value) => (
            <Button
              key={value}
              title={value}
              selected={fixture === value}
              onPress={() => setFixture(value)}
            />
          ))}
        </View>
        <View style={styles.row}>
          <Button
            title="Run"
            onPress={() => start({ library, fixture, runId: Date.now() })}
          />
          <Text style={styles.status}>{status}</Text>
        </View>
      </View>
      <View style={styles.fill}>
        {active != null ? (
          <BenchViewer
            key={`${active.request.library}-${active.request.runId}`}
            ref={viewer}
            library={active.request.library}
            uri={active.uri}
            minScale={Math.min(1, active.request.zoom ?? 1)}
            onLoad={(pageCount) => loadResolver.current?.(pageCount)}
            onError={(message) => loadRejecter.current?.(new Error(message))}
            onPageChange={(page) => {
              callbacks.current++;
              const reached = waiters.current.filter(
                (waiter) => waiter.page === page,
              );
              waiters.current = waiters.current.filter(
                (waiter) => waiter.page !== page,
              );
              reached.forEach((waiter) => waiter.resolve(true));
            }}
          />
        ) : (
          <ScrollView contentContainerStyle={styles.results}>
            {results.map((result, index) => (
              <Text key={index} style={styles.result}>
                {summarize(result)}
              </Text>
            ))}
          </ScrollView>
        )}
      </View>
    </View>
  );
}

const emptyFrames = {
  durationMs: 0,
  frames: 0,
  droppedFrames: 0,
  maxFrameMs: 0,
  p95FrameMs: 0,
};

function summarize(result: BenchResult): string {
  if (result.error != null)
    return `${result.library} × ${result.fixture}: ${result.error}`;
  const jumps = result.jumps.map((jump) => jump.latencyMs ?? '×').join(', ');
  return [
    `${result.library} × ${result.fixture} (${result.pageCount} pages)`,
    `  load ${result.timeToLoadMs} ms · jumps [${jumps}] ms`,
    `  JS dropped: idle ${result.js.idle.droppedFrames} · jumps ${result.js.jumps.droppedFrames} · zoom ${result.js.zoom.droppedFrames} · scroll ${result.js.scroll.droppedFrames}`,
  ].join('\n');
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  controls: { backgroundColor: colors.card, padding: 8, gap: 4 },
  row: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 4 },
  status: { color: colors.muted, fontSize: 13, marginLeft: 8 },
  results: { padding: 12, gap: 12 },
  result: { fontFamily: 'Courier', fontSize: 12, color: colors.text },
});

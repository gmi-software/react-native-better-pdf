import type { PdfViewStats } from 'react-native-better-pdf';
import type { FixtureName } from '../documents';
import type { BenchViewerHandle, Library } from './BenchViewer';
import { JsFrameMonitor, type JsFrameStats } from './JsFrameMonitor';

/** Log prefix the host scripts (`benchmarks/*.mjs`) look for. */
export const LOG_PREFIX = 'BETTERPDF_BENCH';

/** Default duration of the window in which the host script performs swipe gestures. */
export const SCROLL_WINDOW_MS = 10_000;

export interface PageJump {
  target: number;
  latencyMs: number | null;
}

export interface BenchResult {
  library: Library;
  fixture: FixtureName;
  platform: string;
  /** Zoom of the scroll window. */
  zoom: number;
  pageCount: number;
  /** Mount → load event. */
  timeToLoadMs: number;
  jumps: PageJump[];
  js: {
    idle: JsFrameStats;
    jumps: JsFrameStats;
    zoom: JsFrameStats;
    scroll: JsFrameStats;
  };
  /** Viewer → JS callbacks received during the scroll window (page changes). */
  jsCallbacksDuringScroll: number;
  nativeStats: PdfViewStats | null;
  error: string | null;
}

export interface ScenarioHooks {
  viewer: () => BenchViewerHandle | null;
  /** Resolves with the page count once the viewer reports load. */
  loaded: Promise<number>;
  /** Resolves when the viewer reports `page`. */
  waitForPage: (page: number, timeoutMs: number) => Promise<boolean>;
  /** Number of viewer callbacks received so far. */
  callbackCount: () => number;
  log: (line: string) => void;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * The scenario, identical for both libraries:
 * 1. load → time to load event
 * 2. idle 1.5 s → JS activity with a static document
 * 3. five non-animated page jumps → latency until the page-change event
 * 4. zoom to 2.5x and back → JS activity during zoom
 * 5. window for host-driven swipes (10 s, or the soak duration) → JS activity while scrolling,
 *    at `zoom` (1 unless a variant asks for another)
 */
export async function runScenario(
  library: Library,
  fixture: FixtureName,
  platform: string,
  mountedAt: number,
  hooks: ScenarioHooks,
  scrollWindowMs: number = SCROLL_WINDOW_MS,
  zoom: number = 1,
): Promise<BenchResult> {
  const monitor = new JsFrameMonitor();
  const pageCount = await hooks.loaded;
  const timeToLoadMs = Math.round(performance.now() - mountedAt);
  hooks.log(`${LOG_PREFIX}_LOADED ${timeToLoadMs}`);

  monitor.begin();
  await sleep(1500);
  const idle = monitor.end();

  const jumps: PageJump[] = [];
  monitor.begin();
  for (const fraction of [0.5, 0.9, 0.1, 0.75, 0.25]) {
    const target = Math.min(pageCount - 1, Math.floor(pageCount * fraction));
    const started = performance.now();
    hooks.viewer()?.goToPage(target);
    const reached = await hooks.waitForPage(target, 5000);
    jumps.push({
      target,
      latencyMs: reached ? Math.round(performance.now() - started) : null,
    });
    await sleep(700);
  }
  const jumpStats = monitor.end();

  hooks.viewer()?.goToPage(0);
  await sleep(700);

  monitor.begin();
  hooks.viewer()?.zoomTo(2.5);
  await sleep(1500);
  hooks.viewer()?.zoomTo(1);
  await sleep(1000);
  const zoomFrames = monitor.end();

  if (zoom !== 1) {
    hooks.viewer()?.zoomTo(zoom);
    await sleep(1000);
  }

  hooks.log(`${LOG_PREFIX}_SCROLL_START ${scrollWindowMs}`);
  const callbacksBefore = hooks.callbackCount();
  monitor.begin();
  await sleep(scrollWindowMs);
  const scroll = monitor.end();
  const jsCallbacksDuringScroll = hooks.callbackCount() - callbacksBefore;
  hooks.log(`${LOG_PREFIX}_SCROLL_END`);
  // Zoomed out, dozens of pages are still rendering when the swipes stop:
  // read the stats once they are all on screen.
  if (zoom !== 1) await sleep(3000);

  return {
    library,
    fixture,
    platform,
    zoom,
    pageCount,
    timeToLoadMs,
    jumps,
    js: { idle, jumps: jumpStats, zoom: zoomFrames, scroll },
    jsCallbacksDuringScroll,
    nativeStats: hooks.viewer()?.getStats() ?? null,
    error: null,
  };
}

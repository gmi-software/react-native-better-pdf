#!/usr/bin/env node
// Android benchmark driver. See docs/benchmarks.md for the methodology.
//
//   node benchmarks/run-android.mjs [--runs 3] [--fixtures small,medium] [--libraries better,react-native-pdf]
//   node benchmarks/run-android.mjs --scroll-seconds 300 --fixtures extreme --runs 1   # memory soak
//   node benchmarks/run-android.mjs --source remote   # open from the fixture server: load includes the download
//   node benchmarks/run-android.mjs --zoom 0.05 --fixtures extreme --libraries better   # scroll zoomed out (minScale lowered to it)
//
// Prerequisites:
// - A RELEASE build of the example app installed on a single connected device
//   (dev builds run JS unoptimized and would penalize the JS-heavy library).
// - `node benchmarks/serve-fixtures.mjs` running and `adb reverse tcp:8090 tcp:8090`.
//
// For every (library, fixture, run) it cold-starts the app with a deep link,
// samples memory and per-thread CPU from /proc, performs swipes during the
// app's scroll window, reads `dumpsys gfxinfo`, and collects the app's own
// JSON result from logcat.

import { execFile, spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const run = promisify(execFile);
const PACKAGE = 'software.gmi.betterpdf.example';
const PREFIX = 'BETTERPDF_BENCH';
const RESULTS_DIR = join(dirname(fileURLToPath(import.meta.url)), 'results');

const args = parseArgs(process.argv.slice(2));
const RUNS = Number(args.runs ?? 3);
const FIXTURES = (args.fixtures ?? 'small,medium,large,extreme').split(',');
const LIBRARIES = (args.libraries ?? 'better,react-native-pdf').split(',');
const REMOTE = args.source === 'remote';
const SCROLL_SECONDS = args['scroll-seconds']
  ? Number(args['scroll-seconds'])
  : null;
// `--reverse-every 0` scrolls in one direction only (isolates direction changes).
const REVERSE_EVERY = Number(args['reverse-every'] ?? 5);
// The scroll window's zoom; below 1 the viewers' minScale is lowered to it.
// Up to the viewers' maxScale (4): beyond it they would clamp it, and the
// recorded zoom would not be the one measured.
const ZOOM = args.zoom == null ? null : Number(args.zoom);
if (ZOOM != null && !(ZOOM > 0 && ZOOM <= 4)) {
  console.error(`--zoom must be greater than 0 and at most 4: ${args.zoom}`);
  process.exit(1);
}

const adb = async (...command) =>
  (await run('adb', command, { maxBuffer: 64 * 1024 * 1024 })).stdout;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function main() {
  // `wm size` is always the natural (portrait) size; swipes need the rotated one.
  const [width, height] = (await adb('shell', 'dumpsys', 'window', 'displays'))
    .match(/cur=(\d+)x(\d+)/)
    .slice(1)
    .map(Number);
  const device = {
    model: (await adb('shell', 'getprop', 'ro.product.model')).trim(),
    sdk: (await adb('shell', 'getprop', 'ro.build.version.sdk')).trim(),
    abi: (await adb('shell', 'getprop', 'ro.product.cpu.abi')).trim(),
    width,
    height,
  };
  const clockTicks =
    Number((await adb('shell', 'getconf', 'CLK_TCK')).trim()) || 100;
  console.log(`Device: ${JSON.stringify(device)}`);

  const results = [];
  for (const fixture of FIXTURES) {
    for (let index = 0; index < RUNS; index++) {
      // Interleave libraries so thermal state affects both equally.
      for (const library of index % 2 === 0
        ? LIBRARIES
        : [...LIBRARIES].reverse()) {
        process.stdout.write(`${library} × ${fixture} #${index + 1} … `);
        const result = await runOnce({
          library,
          fixture,
          runId: index + 1,
          device,
          clockTicks,
        });
        results.push(result);
        console.log(
          result.app?.error ??
            `load ${result.app?.timeToLoadMs} ms, peak ${result.memory.peakPssMb} MB`,
        );
      }
    }
  }

  mkdirSync(RESULTS_DIR, { recursive: true });
  const file = join(
    RESULTS_DIR,
    `android-${new Date().toISOString().replace(/[:.]/g, '-')}.json`,
  );
  writeFileSync(file, JSON.stringify({ device, runs: RUNS, results }, null, 2));
  console.log(`\nRaw results: ${file}\n`);
  console.log(summarize(results));
}

async function runOnce({ library, fixture, runId, device, clockTicks }) {
  await adb('shell', 'am', 'force-stop', PACKAGE);
  await sleep(1500);
  await adb('logcat', '-c');

  const logcat = spawn('adb', [
    'logcat',
    '-v',
    'raw',
    'ReactNativeJS:I',
    '*:S',
  ]);
  const markers = new Map();
  const waiters = [];
  let buffer = '';
  logcat.stdout.on('data', (chunk) => {
    buffer += chunk;
    let newline;
    while ((newline = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      const match = line.match(new RegExp(`${PREFIX}_(\\w+)\\s?(.*)`));
      if (!match) continue;
      markers.set(match[1], match[2]);
      for (const waiter of [...waiters]) {
        if (waiter.marker === match[1]) {
          waiters.splice(waiters.indexOf(waiter), 1);
          waiter.resolve(match[2]);
        }
      }
    }
  });
  const waitFor = (marker, timeoutMs) =>
    markers.has(marker)
      ? Promise.resolve(markers.get(marker))
      : new Promise((resolve, reject) => {
          waiters.push({ marker, resolve });
          setTimeout(
            () => reject(new Error(`timeout waiting for ${marker}`)),
            timeoutMs,
          );
        });

  await adb(
    'shell',
    'am',
    'start',
    '-W',
    '-a',
    'android.intent.action.VIEW',
    '-d',
    `'betterpdfexample://bench?library=${library}&fixture=${fixture}&run=${runId}${SCROLL_SECONDS ? `&scrollSeconds=${SCROLL_SECONDS}` : ''}${REMOTE ? '&source=remote' : ''}${ZOOM ? `&zoom=${ZOOM}` : ''}'`,
    PACKAGE,
  );
  const pid = (await adb('shell', 'pidof', PACKAGE)).trim();

  const memory = [];
  let sampling = true;
  const sampler = (async () => {
    while (sampling) {
      const kb = await totalPssKb().catch(() => null);
      if (kb != null) memory.push({ t: Date.now(), kb });
      await sleep(500);
    }
  })();

  let app = null;
  let scroll = null;
  try {
    await waitFor('LOADED', 180_000);
    const windowMs = Number(await waitFor('SCROLL_START', 60_000)) || 10_000;
    await adb('shell', 'dumpsys', 'gfxinfo', PACKAGE, 'reset');
    const before = await threadTicks(pid);
    const started = Date.now();
    const pssBeforeScroll = memory.at(-1)?.kb ?? null;
    await swipeFor(windowMs - 2_000, device);
    const after = await threadTicks(pid);
    const seconds = (Date.now() - started) / 1000;
    const frames = parseGfxinfo(
      await adb('shell', 'dumpsys', 'gfxinfo', PACKAGE),
    );
    const pssAt = (ms) =>
      memory.find((sample) => sample.t >= started + ms)?.kb ?? null;
    scroll = {
      seconds,
      frames,
      cpu: cpuUsage(before, after, clockTicks, seconds),
      pssBeforeScrollKb: pssBeforeScroll,
      pssAfter1MinKb: pssAt(60_000),
      pssAfter5MinKb: pssAt(300_000),
      pssEndKb: memory.at(-1)?.kb ?? null,
    };
    app = JSON.parse(await waitFor('RESULT', 60_000));
  } catch (error) {
    app = { error: String(error) };
  } finally {
    sampling = false;
    await sampler;
    logcat.kill();
  }

  const peak = Math.max(...memory.map((sample) => sample.kb));
  return {
    library,
    fixture,
    source: REMOTE ? 'remote' : 'local',
    zoom: ZOOM ?? 1,
    runId,
    app,
    scroll,
    memory: {
      peakPssMb: round(peak / 1024),
      finalPssMb: round((memory.at(-1)?.kb ?? 0) / 1024),
      samples: memory.length,
    },
  };
}

async function totalPssKb() {
  const out = await adb('shell', 'dumpsys', 'meminfo', PACKAGE);
  const match = out.match(/TOTAL PSS:\s+(\d+)/) ?? out.match(/TOTAL\s+(\d+)/);
  return match ? Number(match[1]) : null;
}

/** utime+stime ticks per thread name, read from /proc/<pid>/task/<tid>/stat. */
async function threadTicks(pid) {
  const out = await adb(
    'shell',
    `for t in /proc/${pid}/task/*; do cat $t/stat 2>/dev/null; echo; done`,
  );
  const ticks = new Map();
  for (const line of out.split('\n')) {
    const match = line.match(/^(\d+) \((.*)\) \S+ (.*)$/);
    if (!match) continue;
    const fields = match[3].split(' ');
    // After "(comm) state": ppid is fields[0]; utime/stime are stat fields 14/15.
    const utime = Number(fields[10]);
    const stime = Number(fields[11]);
    // The main (UI) thread's tid equals the pid; names are truncated to 15 chars.
    const name =
      match[1] === String(pid) ? 'main' : normalizeThreadName(match[2]);
    ticks.set(name, (ticks.get(name) ?? 0) + utime + stime);
  }
  return ticks;
}

function normalizeThreadName(name) {
  if (name.startsWith('mqt_v_js') || name.startsWith('mqt_js')) return 'js';
  if (name === 'RenderThread') return 'render-thread';
  if (name.startsWith('BetterPdf-rende')) return 'betterpdf-render';
  return 'other';
}

function cpuUsage(before, after, clockTicks, seconds) {
  const usage = {};
  let total = 0;
  for (const [name, ticks] of after) {
    const delta = ticks - (before.get(name) ?? 0);
    total += delta;
    usage[name] = round((delta / clockTicks / seconds) * 100);
  }
  usage.total = round((total / clockTicks / seconds) * 100);
  return usage; // percent of one core
}

async function swipeFor(durationMs, { width, height }) {
  const low = Math.round(height * 0.8);
  const high = Math.round(height * 0.3);
  const end = Date.now() + durationMs;
  let down = true;
  let count = 0;
  while (Date.now() < end) {
    // Mostly forward flings with an occasional reversal, like reading.
    const [from, to] = down ? [low, high] : [high, low];
    // Consecutive flings in alternate columns: an injected swipe whose moves
    // arrive too late is a tap, and two taps at one spot are a double tap,
    // which zooms the viewer for the rest of the window.
    const x = Math.round(width * (count % 2 === 0 ? 0.33 : 0.67));
    await adb(
      'shell',
      'input',
      'swipe',
      String(x),
      String(from),
      String(x),
      String(to),
      '120',
    );
    count++;
    down = REVERSE_EVERY === 0 || count % REVERSE_EVERY !== 0;
  }
}

function parseGfxinfo(out) {
  const number = (pattern) => {
    const match = out.match(pattern);
    return match ? Number(match[1]) : null;
  };
  return {
    totalFrames: number(/Total frames rendered:\s+(\d+)/),
    jankyFrames: number(/Janky frames:\s+(\d+)/),
    jankyPercent: number(/Janky frames:\s+\d+ \(([\d.]+)%\)/),
    p50Ms: number(/50th percentile:\s+(\d+)ms/),
    p90Ms: number(/90th percentile:\s+(\d+)ms/),
    p99Ms: number(/99th percentile:\s+(\d+)ms/),
  };
}

function summarize(results) {
  const groups = new Map();
  for (const result of results) {
    const key = `${result.fixture}|${result.library}`;
    groups.set(key, [...(groups.get(key) ?? []), result]);
  }
  const rows = [
    '| Fixture | Library | Load (ms) | Jump (ms) | Peak PSS (MB) | Janky % | p90 frame (ms) | JS thread CPU % | Total CPU % | JS callbacks while scrolling | Failures |',
    '| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |',
  ];
  for (const [key, group] of groups) {
    const [fixture, library] = key.split('|');
    const ok = group.filter((result) => result.app && !result.app.error);
    const median = (values) => {
      const sorted = values
        .filter((value) => value != null && Number.isFinite(value))
        .sort((a, b) => a - b);
      return sorted.length ? round(sorted[Math.floor(sorted.length / 2)]) : '–';
    };
    rows.push(
      `| ${fixture} | ${library} | ${median(ok.map((r) => r.app.timeToLoadMs))} | ${median(
        ok.flatMap((r) => r.app.jumps.map((jump) => jump.latencyMs)),
      )} | ${median(group.map((r) => r.memory.peakPssMb))} | ${median(ok.map((r) => r.scroll?.frames.jankyPercent))} | ${median(
        ok.map((r) => r.scroll?.frames.p90Ms),
      )} | ${median(ok.map((r) => r.scroll?.cpu.js))} | ${median(ok.map((r) => r.scroll?.cpu.total))} | ${median(
        ok.map((r) => r.app.jsCallbacksDuringScroll),
      )} | ${group.length - ok.length} |`,
    );
  }
  if (SCROLL_SECONDS) {
    rows.push(
      '',
      '| Fixture | Library | PSS before scroll (MB) | after 1 min | after 5 min | end | peak |',
      '| --- | --- | ---: | ---: | ---: | ---: | ---: |',
    );
    const mb = (kb) => (kb == null ? '–' : round(kb / 1024));
    for (const r of results) {
      rows.push(
        `| ${r.fixture} | ${r.library} | ${mb(r.scroll?.pssBeforeScrollKb)} | ${mb(r.scroll?.pssAfter1MinKb)} | ${mb(r.scroll?.pssAfter5MinKb)} | ${mb(r.scroll?.pssEndKb)} | ${r.memory.peakPssMb} |`,
      );
    }
  }
  return rows.join('\n');
}

function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 2)
    result[argv[index].replace(/^--/, '')] = argv[index + 1];
  return result;
}

const round = (value) => Math.round(value * 10) / 10;

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

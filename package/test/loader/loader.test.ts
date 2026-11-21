// iOS's loader and cache, built for macOS, against a local server.
import { afterAll, beforeEach, describe, expect, test } from 'bun:test';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const isMac = process.platform === 'darwin';
const packageDir = join(import.meta.dir, '../..');
const out = mkdtempSync(join(tmpdir(), 'better-pdf-loader-'));
const harness = join(out, 'loader');

const SOURCES = [
  'PdfDocumentLoader',
  'PdfLoadTask',
  'PdfLoadFailure',
  'LoadedDocument',
  'ResolvedFile',
  'Base64Document',
  'ProgressThrottle',
  'DocumentCache',
  'DocumentCacheStore',
  'SharedDownload',
  'DownloadOutcome',
  'RedirectPolicy',
  'CacheMetadata',
  'CachedCopy',
  'CachedCopy+key',
  'NativePdfSource+storeKey',
  'NativePdfSource+invalidHeaderFailure',
  'URL+fileSize',
  'URL+hasPdfHeader',
  'URL+sourceUri',
];

function build(args: string[]): void {
  const result = spawnSync('xcrun', ['swiftc', ...args], { encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`swiftc failed:\n${result.stderr || result.error}`);
  }
}

const damaged = Buffer.from('%PDF-1.4\n' + 'x'.repeat(5000));
let pdf = Buffer.alloc(0);
let encrypted = Buffer.alloc(0);

type Answer = Buffer | number;
interface Request {
  path: string;
  ifNoneMatch: string | null;
}

/** What each path answers, request after request; the last answer repeats. */
let answers: Record<string, Answer[]> = {};
let requests: Request[] = [];

const server = Bun.serve({
  port: 0,
  fetch(request) {
    const path = new URL(request.url).pathname;
    const ifNoneMatch = request.headers.get('if-none-match');
    requests.push({ path, ifNoneMatch });
    const queue = answers[path] ?? [404];
    const answer = queue.length > 1 ? queue.shift()! : queue[0]!;
    if (typeof answer === 'number')
      return new Response(null, { status: answer });
    const etag = `"${path}"`;
    if (ifNoneMatch === etag) {
      return new Response(null, { status: 304, headers: { ETag: etag } });
    }
    return new Response(new Uint8Array(answer), {
      headers: { 'Content-Type': 'application/pdf', ETag: etag },
    });
  },
});
afterAll(() => server.stop(true));

const url = (path: string) => `http://127.0.0.1:${server.port}${path}`;

/** A new app: its own home directory, so its own cache. */
const newHome = () => mkdtempSync(join(tmpdir(), 'better-pdf-loader-home-'));

/** One app launch: runs the commands in order in one process. */
async function launch(home: string, commands: string[]): Promise<string[]> {
  const child = Bun.spawn([harness], {
    stdin: new TextEncoder().encode(commands.join('\n') + '\n'),
    stdout: 'pipe',
    stderr: 'pipe',
    env: { ...process.env, CFFIXED_USER_HOME: home },
  });
  const [output, errors, status] = await Promise.all([
    new Response(child.stdout).text(),
    new Response(child.stderr).text(),
    child.exited,
  ]);
  if (status !== 0)
    throw new Error(`The harness failed (${status}):\n${errors}`);
  return output.trimEnd().split('\n');
}

if (isMac) {
  build([
    '-parse-as-library',
    '-emit-module',
    '-emit-library',
    '-static',
    '-module-name',
    'NitroModules',
    join(import.meta.dir, 'NitroModules.swift'),
    '-emit-module-path',
    join(out, 'NitroModules.swiftmodule'),
    '-o',
    join(out, 'libNitroModules.a'),
  ]);
  build([
    '-I',
    out,
    '-L',
    out,
    '-lNitroModules',
    join(import.meta.dir, 'main.swift'),
    ...SOURCES.map((name) => join(packageDir, 'ios', `${name}.swift`)),
    '-o',
    harness,
  ]);
  const fixtures = spawnSync(harness, {
    input: `fixture ${join(out, 'plain.pdf')}\nfixture ${join(out, 'encrypted.pdf')} password=secret\n`,
    encoding: 'utf8',
  });
  if (fixtures.stdout !== 'written\nwritten\n') {
    throw new Error(`The fixtures were not written:\n${fixtures.stderr}`);
  }
  pdf = readFileSync(join(out, 'plain.pdf'));
  encrypted = readFileSync(join(out, 'encrypted.pdf'));
}

describe.skipIf(!isMac)('a downloaded copy that cannot be opened', () => {
  beforeEach(() => {
    answers = {};
    requests = [];
  });

  test('is not kept, so the next open asks the server again', async () => {
    answers['/doc.pdf'] = [damaged, pdf];
    const results = await launch(newHome(), [
      `load ${url('/doc.pdf')}`,
      `entry ${url('/doc.pdf')}`,
      `load ${url('/doc.pdf')}`,
      `entry ${url('/doc.pdf')}`,
    ]);
    expect(results).toEqual([
      'failed invalid-pdf',
      'entry none',
      'loaded pages=1 stale=false',
      `entry session bytes=${pdf.length}`,
    ]);
    // The copy this open downloaded is not downloaded a second time.
    expect(requests.length).toBe(2);
  });

  test('already stored is replaced by one more download, in the same open', async () => {
    answers['/doc.pdf'] = [damaged, pdf];
    const results = await launch(newHome(), [
      `download ${url('/doc.pdf')}`,
      `load ${url('/doc.pdf')}`,
      `entry ${url('/doc.pdf')}`,
    ]);
    expect(results).toEqual([
      'stored session',
      'loaded pages=1 stale=false',
      `entry session bytes=${pdf.length}`,
    ]);
    expect(requests).toEqual([
      { path: '/doc.pdf', ifNoneMatch: null },
      { path: '/doc.pdf', ifNoneMatch: null },
    ]);
  });

  test('stored persistently is replaced after a relaunch', async () => {
    answers['/doc.pdf'] = [damaged, pdf];
    const home = newHome();
    const stored = await launch(home, [
      `download ${url('/doc.pdf')} cache=persistent`,
    ]);
    expect(stored).toEqual(['stored persistent']);

    const results = await launch(home, [
      `load ${url('/doc.pdf')} cache=persistent`,
      `entry ${url('/doc.pdf')}`,
    ]);
    expect(results).toEqual([
      'loaded pages=1 stale=false',
      `entry persistent bytes=${pdf.length}`,
    ]);
    expect(requests.length).toBe(2);
  });

  test('stored persistently stays persistent when an open without cache options replaces it', async () => {
    answers['/doc.pdf'] = [damaged, pdf];
    const results = await launch(newHome(), [
      `download ${url('/doc.pdf')} cache=persistent`,
      `load ${url('/doc.pdf')}`,
      `entry ${url('/doc.pdf')}`,
    ]);
    expect(results).toEqual([
      'stored persistent',
      'loaded pages=1 stale=false',
      `entry persistent bytes=${pdf.length}`,
    ]);
  });

  test('downloaded again and still unreadable fails without a third request, and is not kept', async () => {
    answers['/doc.pdf'] = [damaged];
    const results = await launch(newHome(), [
      `download ${url('/doc.pdf')} cache=persistent`,
      `load ${url('/doc.pdf')}`,
      `entry ${url('/doc.pdf')}`,
    ]);
    expect(results).toEqual([
      'stored persistent',
      'failed invalid-pdf',
      'entry none',
    ]);
    expect(requests.length).toBe(2);
  });

  test('the server confirms unchanged (304) is downloaded without its validators', async () => {
    answers['/doc.pdf'] = [damaged, pdf];
    const results = await launch(newHome(), [
      `download ${url('/doc.pdf')}`,
      `load ${url('/doc.pdf')} maxAgeMs=0`,
    ]);
    expect(results).toEqual(['stored session', 'loaded pages=1 stale=false']);
    expect(requests.map((request) => request.ifNoneMatch)).toEqual([
      null,
      '"/doc.pdf"',
      null,
    ]);
  });

  test("shown because the server cannot answer is removed, and the open fails with the server's error", async () => {
    answers['/doc.pdf'] = [damaged, 503, 503, pdf];
    const results = await launch(newHome(), [
      `download ${url('/doc.pdf')}`,
      `load ${url('/doc.pdf')} maxAgeMs=0`,
      `entry ${url('/doc.pdf')}`,
      `load ${url('/doc.pdf')} maxAgeMs=0`,
    ]);
    expect(results).toEqual([
      'stored session',
      'failed http-error',
      'entry none',
      'loaded pages=1 stale=false',
    ]);
    expect(requests.length).toBe(4);
  });
});

describe.skipIf(!isMac)('discarding a copy', () => {
  beforeEach(() => {
    answers = {};
    requests = [];
  });

  test('kept across launches removes it from both directories', async () => {
    answers['/doc.pdf'] = [damaged, 503];
    const results = await launch(newHome(), [
      `download ${url('/doc.pdf')}`,
      `download ${url('/doc.pdf')} cache=persistent`,
      `load ${url('/doc.pdf')}`,
      `entry ${url('/doc.pdf')}`,
    ]);
    expect(results).toEqual([
      'stored session',
      'stored persistent',
      'failed http-error',
      'entry none',
    ]);
    expect(requests.length).toBe(2);
  });

  test('leaves a newer copy that replaced the opened one', async () => {
    const results = await launch(newHome(), [
      `replace ${url('/doc.pdf')}`,
      `entry ${url('/doc.pdf')}`,
    ]);
    expect(results).toEqual(['newer kept, then its own removed', 'entry none']);
  });
});

describe.skipIf(!isMac)('a copy that needs a password', () => {
  beforeEach(() => {
    answers = { '/locked.pdf': [encrypted] };
    requests = [];
  });

  test('stays stored when the password is missing or wrong', async () => {
    const results = await launch(newHome(), [
      `load ${url('/locked.pdf')}`,
      `entry ${url('/locked.pdf')}`,
      `load ${url('/locked.pdf')} password=wrong`,
      `entry ${url('/locked.pdf')}`,
      `load ${url('/locked.pdf')} password=secret`,
    ]);
    expect(results).toEqual([
      'failed password-required',
      `entry session bytes=${encrypted.length}`,
      'failed invalid-password',
      `entry session bytes=${encrypted.length}`,
      'loaded pages=1 stale=false',
    ]);
    expect(requests.length).toBe(1);
  });
});

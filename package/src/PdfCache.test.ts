import { beforeEach, describe, expect, mock, test } from 'bun:test';
import type { NativePdfSource } from './types/NativePdfSource';
import type { PdfDownloadResult } from './types/PdfDownloadResult';
import type { PdfLoadProgressEvent } from './types/PdfLoadProgressEvent';

// --- Native boundary mock --------------------------------------------------

interface PendingDownload {
  requestId: number;
  source: NativePdfSource;
  onProgress?: (event: PdfLoadProgressEvent) => void;
  finish: (result: PdfDownloadResult) => void;
}

const downloads: PendingDownload[] = [];
const cancelled: number[] = [];
const created: string[] = [];

const entry = {
  filePath: '/caches/a.pdf',
  byteSize: 10,
  cache: 'persistent' as const,
  validatedAtMs: 1,
};

const nativeCache = {
  download: (
    requestId: number,
    source: NativePdfSource,
    onProgress?: (event: PdfLoadProgressEvent) => void,
  ) =>
    new Promise<PdfDownloadResult>((finish) => {
      downloads.push({ requestId, source, onProgress, finish });
    }),
  cancelDownload: (requestId: number) => cancelled.push(requestId),
  getEntry: (source: NativePdfSource) =>
    Promise.resolve(source.cacheKey === 'present' ? entry : undefined),
  remove: () => Promise.resolve(true),
  clear: () => Promise.resolve(),
  getByteSize: () => Promise.resolve(42),
};

// Module mocks are shared by all test files: export everything the package
// imports from Nitro (PdfView.test.tsx needs the view helpers).
mock.module('react-native-nitro-modules', () => ({
  callback: <T>(f: T) => f,
  getHostComponent: () => () => null,
  NitroModules: {
    createHybridObject: (name: string) => {
      created.push(name);
      return nativeCache;
    },
  },
}));

const { PdfCache } = await import('./PdfCache');
const { PdfDownloadError } = await import('./PdfDownloadError');

// ---------------------------------------------------------------------------

const lastDownload = () => {
  const download = downloads[downloads.length - 1];
  if (download == null) throw new Error('no native download');
  return download;
};

beforeEach(() => {
  downloads.length = 0;
  cancelled.length = 0;
});

describe('PdfCache.download', () => {
  test('sends the normalized source and resolves with the entry', async () => {
    const headers: Record<string, string> = { A: '1' };
    const promise = PdfCache.download({
      uri: 'https://x.test/a.pdf',
      headers,
      cache: 'persistent',
      maxAgeMs: 0,
      cacheKey: 'doc',
    });
    headers.A = '2';
    expect(lastDownload().source).toEqual({
      uri: 'https://x.test/a.pdf',
      headers: { A: '1' },
      cache: 'persistent',
      maxAgeMs: 0,
      cacheKey: 'doc',
    });
    lastDownload().finish({ entry });
    expect(await promise).toEqual(entry);
    expect(created).toEqual(['BetterPdfCache']);
  });

  test('leaves absent options absent', () => {
    PdfCache.download({ uri: 'https://x.test/a.pdf' });
    expect(lastDownload().source).toEqual({ uri: 'https://x.test/a.pdf' });
  });

  test('passes progress through', () => {
    const events: PdfLoadProgressEvent[] = [];
    PdfCache.download(
      { uri: 'https://x.test/a.pdf' },
      { onProgress: (event) => events.push(event) },
    );
    lastDownload().onProgress?.({ loadedBytes: 5, totalBytes: 10 });
    expect(events).toEqual([{ loadedBytes: 5, totalBytes: 10 }]);
  });

  test('rejects with a PdfDownloadError carrying the code and HTTP status', async () => {
    const promise = PdfCache.download({ uri: 'https://x.test/a.pdf' });
    lastDownload().finish({
      error: { code: 'http-error', message: 'HTTP 401', httpStatus: 401 },
    });
    const error = await promise.catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(PdfDownloadError);
    expect(error).toBeInstanceOf(Error);
    expect(error).toMatchObject({
      name: 'PdfDownloadError',
      code: 'http-error',
      httpStatus: 401,
      message: 'HTTP 401',
    });
  });

  test('gives every download its own request id', () => {
    PdfCache.download({ uri: 'https://x.test/a.pdf' });
    PdfCache.download({ uri: 'https://x.test/a.pdf' });
    const [first, second] = downloads;
    expect(first?.requestId).not.toBe(second?.requestId);
  });

  test('an abort cancels the native download and rejects with an AbortError', async () => {
    const controller = new AbortController();
    const promise = PdfCache.download(
      { uri: 'https://x.test/a.pdf' },
      { signal: controller.signal },
    );
    controller.abort();
    const error = await promise.catch((caught: unknown) => caught);
    expect(cancelled).toEqual([lastDownload().requestId]);
    expect(error).toMatchObject({ name: 'AbortError' });
  });

  test('drops progress that arrives after an abort', async () => {
    const events: PdfLoadProgressEvent[] = [];
    const controller = new AbortController();
    const promise = PdfCache.download(
      { uri: 'https://x.test/a.pdf' },
      { onProgress: (event) => events.push(event), signal: controller.signal },
    );
    lastDownload().onProgress?.({ loadedBytes: 5, totalBytes: 10 });
    controller.abort();
    lastDownload().onProgress?.({ loadedBytes: 8, totalBytes: 10 });
    await promise.catch(() => undefined);
    expect(events).toEqual([{ loadedBytes: 5, totalBytes: 10 }]);
  });

  test('rejects with the signal reason when one is given', async () => {
    const controller = new AbortController();
    const promise = PdfCache.download(
      { uri: 'https://x.test/a.pdf' },
      { signal: controller.signal },
    );
    const reason = new Error('left the screen');
    controller.abort(reason);
    expect(await promise.catch((caught: unknown) => caught)).toBe(reason);
  });

  test('an already aborted signal never starts a download', async () => {
    const controller = new AbortController();
    controller.abort();
    const error = await PdfCache.download(
      { uri: 'https://x.test/a.pdf' },
      { signal: controller.signal },
    ).catch((caught: unknown) => caught);
    expect(downloads).toEqual([]);
    expect(error).toMatchObject({ name: 'AbortError' });
  });

  test('an abort after completion changes nothing', async () => {
    const controller = new AbortController();
    const promise = PdfCache.download(
      { uri: 'https://x.test/a.pdf' },
      { signal: controller.signal },
    );
    lastDownload().finish({ entry });
    expect(await promise).toEqual(entry);
    controller.abort();
    expect(cancelled).toEqual([]);
  });
});

describe('PdfCache', () => {
  test('getEntry, remove, clear and getByteSize reach the native cache', async () => {
    expect(
      await PdfCache.getEntry({
        uri: 'https://x.test/a.pdf',
        cacheKey: 'present',
      }),
    ).toEqual(entry);
    expect(
      await PdfCache.getEntry({ uri: 'https://x.test/a.pdf' }),
    ).toBeUndefined();
    expect(await PdfCache.remove({ uri: 'https://x.test/a.pdf' })).toBe(true);
    await PdfCache.clear();
    expect(await PdfCache.getByteSize()).toBe(42);
  });
});

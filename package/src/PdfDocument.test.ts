import { beforeEach, describe, expect, mock, test } from 'bun:test';
import type { NativePdfSource } from './types/NativePdfSource';
import type { PdfLoadProgressEvent } from './types/PdfLoadProgressEvent';
import type { PdfOpenResult } from './types/PdfOpenResult';

// --- Native boundary mock --------------------------------------------------

interface NativeDocument {
  source?: NativePdfSource;
  password?: string;
  onProgress?: (event: PdfLoadProgressEvent) => void;
  finishOpen: (result: PdfOpenResult) => void;
  failOpen: (error: Error) => void;
  closeCount: number;
  calls: string[];
}

const natives: NativeDocument[] = [];

function createNativeDocument() {
  const state: NativeDocument = {
    finishOpen: () => {},
    failOpen: () => {},
    closeCount: 0,
    calls: [],
  };
  natives.push(state);
  return {
    open: (
      source: NativePdfSource,
      password?: string,
      onProgress?: (event: PdfLoadProgressEvent) => void,
    ) => {
      state.source = source;
      state.password = password;
      state.onProgress = onProgress;
      return new Promise<PdfOpenResult>((finish, fail) => {
        state.finishOpen = finish;
        state.failOpen = fail;
      });
    },
    getMetadata: () => {
      state.calls.push('getMetadata');
      return Promise.resolve({ title: 'Report', createdAtMs: 1 });
    },
    getPageSizes: () => {
      state.calls.push('getPageSizes');
      return Promise.resolve([{ width: 595, height: 842 }]);
    },
    getOutline: () => {
      state.calls.push('getOutline');
      return Promise.resolve([
        { title: 'Chapter', page: 0, depth: 0 },
        { title: 'Section', page: 1, depth: 1 },
      ]);
    },
    renderPage: (page: number, options: { width: number }) => {
      state.calls.push(`renderPage(${page}, ${options.width})`);
      return Promise.resolve({
        uri: `file:///page-${page}.png`,
        width: options.width,
        height: options.width,
      });
    },
    close: () => {
      state.closeCount += 1;
    },
  };
}

// Module mocks are shared by all test files: export everything the package
// imports from React Native and Nitro.
mock.module('react-native', () => ({
  View: () => null,
  StyleSheet: { create: <T>(styles: T) => styles },
  Image: {
    resolveAssetSource: (asset: number) => ({ uri: `asset://${asset}` }),
  },
  processColor: () => null,
}));
mock.module('react-native-nitro-modules', () => ({
  callback: <T>(f: T) => f,
  getHostComponent: () => () => null,
  NitroModules: {
    createHybridObject: (name: string) => {
      if (name !== 'BetterPdfDocument') throw new Error(`Unexpected ${name}`);
      return createNativeDocument();
    },
    createNativeArrayBuffer: (size: number) => new ArrayBuffer(size),
  },
}));

const { PdfDocument } = await import('./PdfDocument');
const { PdfOpenError } = await import('./PdfOpenError');

// ---------------------------------------------------------------------------

const lastNative = () => {
  const native = natives[natives.length - 1];
  if (native == null) throw new Error('no native document');
  return native;
};

const opened = { pageCount: 3, filePath: '/caches/a.pdf', isStale: false };

async function openDocument() {
  const promise = PdfDocument.open({ uri: 'https://x.test/a.pdf' });
  lastNative().finishOpen({ document: opened });
  return promise;
}

beforeEach(() => {
  natives.length = 0;
});

describe('PdfDocument.open', () => {
  test('sends the normalized source and resolves with the document', async () => {
    const onProgress = () => {};
    const promise = PdfDocument.open(
      { uri: 'https://x.test/a.pdf', headers: { A: '1' }, cacheKey: 'a' },
      { password: 'secret', onProgress },
    );
    const native = lastNative();
    expect(native.source).toEqual({
      uri: 'https://x.test/a.pdf',
      headers: { A: '1' },
      cacheKey: 'a',
    });
    expect(native.password).toBe('secret');
    expect(native.onProgress).toBe(onProgress);
    native.finishOpen({ document: { pageCount: 7, isStale: true } });
    const document = await promise;
    expect(document.pageCount).toBe(7);
    expect(document.isStale).toBe(true);
    expect(document.filePath).toBeUndefined();
    expect(native.closeCount).toBe(0);
  });

  test('accepts every PdfView source form', async () => {
    void PdfDocument.open(42);
    expect(lastNative().source).toEqual({ uri: 'asset://42' });
    void PdfDocument.open({ filePath: '/docs/a.pdf' });
    expect(lastNative().source).toEqual({ uri: '/docs/a.pdf' });
    void PdfDocument.open({ base64: 'JVBERi0=' }, { password: 'secret' });
    const { base64, ...rest } = lastNative().source as NativePdfSource;
    expect(rest).toEqual({ uri: '' });
    expect(new TextDecoder().decode(base64)).toBe('JVBERi0=');
    expect(lastNative().password).toBe('secret');
  });

  test('a failure rejects with a PdfOpenError and releases the native object', async () => {
    const promise = PdfDocument.open({ uri: '/docs/a.pdf' });
    lastNative().finishOpen({
      error: {
        code: 'http-error',
        message: 'HTTP 404',
        httpStatus: 404,
        nativeDescription: 'IOException: 404',
      },
    });
    const error = await promise.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(PdfOpenError);
    expect(error).toMatchObject({
      name: 'PdfOpenError',
      code: 'http-error',
      message: 'HTTP 404',
      httpStatus: 404,
      nativeDescription: 'IOException: 404',
    });
    expect(lastNative().closeCount).toBe(1);
  });

  test('a native rejection is passed on and releases the native object', async () => {
    for (const signal of [undefined, new AbortController().signal]) {
      const promise = PdfDocument.open({ uri: '/docs/a.pdf' }, { signal });
      lastNative().failOpen(new Error('open() was already called'));
      const error = await promise.catch((e: unknown) => e);
      expect(error).toMatchObject({ message: 'open() was already called' });
      expect(lastNative().closeCount).toBe(1);
    }
  });

  test('an already aborted signal rejects without opening', async () => {
    const controller = new AbortController();
    controller.abort();
    const error = await PdfDocument.open(
      { uri: '/docs/a.pdf' },
      { signal: controller.signal },
    ).catch((e: unknown) => e);
    expect(error).toMatchObject({ name: 'AbortError' });
    expect(natives).toHaveLength(0);
  });

  test('an abort while opening closes the native object and rejects', async () => {
    const controller = new AbortController();
    const promise = PdfDocument.open(
      { uri: 'https://x.test/a.pdf' },
      { signal: controller.signal },
    );
    controller.abort();
    const error = await promise.catch((e: unknown) => e);
    expect(error).toMatchObject({ name: 'AbortError' });
    expect(lastNative().closeCount).toBe(1);
  });

  test('drops progress that arrives after an abort', async () => {
    const events: PdfLoadProgressEvent[] = [];
    const controller = new AbortController();
    const promise = PdfDocument.open(
      { uri: 'https://x.test/a.pdf' },
      { onProgress: (event) => events.push(event), signal: controller.signal },
    );
    lastNative().onProgress?.({ loadedBytes: 5, totalBytes: 10 });
    controller.abort();
    lastNative().onProgress?.({ loadedBytes: 8, totalBytes: 10 });
    await promise.catch(() => undefined);
    expect(events).toEqual([{ loadedBytes: 5, totalBytes: 10 }]);
  });

  test('with a signal, still resolves and maps failures', async () => {
    const controller = new AbortController();
    const success = PdfDocument.open(
      { uri: '/docs/a.pdf' },
      { signal: controller.signal },
    );
    lastNative().finishOpen({ document: opened });
    expect((await success).pageCount).toBe(3);

    const failure = PdfDocument.open(
      { uri: '/docs/b.pdf' },
      { signal: controller.signal },
    );
    lastNative().finishOpen({
      error: { code: 'password-required', message: 'Locked' },
    });
    const error = await failure.catch((e: unknown) => e);
    expect(error).toMatchObject({ code: 'password-required' });
    // A later abort does nothing.
    controller.abort();
    expect(natives[0]?.closeCount).toBe(0);
  });
});

describe('PdfDocument reads', () => {
  test('pass through to the native document', async () => {
    const document = await openDocument();
    expect(await document.getMetadata()).toEqual({
      title: 'Report',
      createdAtMs: 1,
    });
    expect(await document.getPageSizes()).toEqual([
      { width: 595, height: 842 },
    ]);
    expect(await document.renderPage(1, { width: 300 })).toEqual({
      uri: 'file:///page-1.png',
      width: 300,
      height: 300,
    });
    expect(lastNative().calls).toEqual([
      'getMetadata',
      'getPageSizes',
      'renderPage(1, 300)',
    ]);
  });

  test('getOutline rebuilds the tree', async () => {
    const document = await openDocument();
    expect(await document.getOutline()).toEqual([
      {
        title: 'Chapter',
        page: 0,
        children: [{ title: 'Section', page: 1, children: [] }],
      },
    ]);
  });
});

describe('PdfDocument.close', () => {
  test('closes the native document once; later calls reject', async () => {
    const document = await openDocument();
    document.close();
    document.close();
    expect(lastNative().closeCount).toBe(1);
    for (const call of [
      () => document.getMetadata(),
      () => document.getPageSizes(),
      () => document.getOutline(),
      () => document.renderPage(0, { width: 100 }),
    ]) {
      const error = await call().catch((e: unknown) => e);
      expect(error).toMatchObject({ message: 'The document is closed' });
    }
    expect(lastNative().calls).toEqual([]);
  });
});

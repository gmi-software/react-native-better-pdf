import { describe, expect, mock, test } from 'bun:test';

// --- Native boundary mock --------------------------------------------------

const created: string[] = [];
let reads = 0;

const nativeCache = {
  getCapabilities: () => {
    reads += 1;
    return {
      search: false,
      textSelection: false,
      formFilling: false,
      passwords: true,
    };
  },
};

// Module mocks are shared by all test files: export everything the package
// imports from Nitro.
mock.module('react-native-nitro-modules', () => ({
  callback: <T>(f: T) => f,
  getHostComponent: () => () => null,
  NitroModules: {
    createHybridObject: (name: string) => {
      created.push(name);
      return nativeCache;
    },
    createNativeArrayBuffer: (size: number) => new ArrayBuffer(size),
  },
}));

const { PdfCapabilities } = await import('./PdfCapabilities');

// ---------------------------------------------------------------------------

describe('PdfCapabilities.get', () => {
  test('creates nothing native before the first call', () => {
    expect(created).toEqual([]);
    expect(reads).toBe(0);
  });

  test('returns the four values from the native cache object', () => {
    expect(PdfCapabilities.get()).toEqual({
      search: false,
      textSelection: false,
      formFilling: false,
      passwords: true,
    });
    expect(created).toEqual(['BetterPdfCache']);
  });

  test('reads native once', () => {
    const first = PdfCapabilities.get();
    const second = PdfCapabilities.get();
    expect(second).toBe(first);
    expect(reads).toBe(1);
    expect(created).toEqual(['BetterPdfCache']);
  });

  test('cannot be changed by the caller', () => {
    const capabilities = PdfCapabilities.get();
    expect(Object.isFrozen(capabilities)).toBe(true);
  });
});

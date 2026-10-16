import { describe, expect, mock, test } from 'bun:test';

const allocated: number[] = [];

// Module mocks are shared by all test files: export everything the package
// imports from Nitro.
mock.module('react-native-nitro-modules', () => ({
  callback: <T>(f: T) => f,
  getHostComponent: () => () => null,
  NitroModules: {
    createHybridObject: () => {
      throw new Error('Not available in nativeSource tests');
    },
    createNativeArrayBuffer: (size: number) => {
      allocated.push(size);
      return new ArrayBuffer(size);
    },
  },
}));

const { base64Bytes, toNativeSource } = await import('./nativeSource');

const bytesOf = (buffer: ArrayBuffer) => [...new Uint8Array(buffer)];
const ascii = (text: string) => [...text].map((c) => c.charCodeAt(0));

describe.each([
  ['TextEncoder.encodeInto', new TextEncoder()],
  ['the fallback without TextEncoder', undefined],
])('base64Bytes with %s', (_, encoder) => {
  test('copies ASCII text one byte per character', () => {
    const text = ' JVBERi0x\r\nLjQK=\t';
    expect(bytesOf(base64Bytes(text, encoder))).toEqual(ascii(text));
  });

  test('fills from the first non-ASCII character on with 0x80', () => {
    for (const text of ['QUJDé', 'QUJD😀QUJD', 'QUJD\uD800', 'éQUJD', 'QUİJ']) {
      const index = text.search(/[\u0080-\uffff]/);
      const bytes = bytesOf(base64Bytes(text, encoder));
      expect(bytes).toHaveLength(text.length);
      expect(bytes.slice(0, index)).toEqual(ascii(text.slice(0, index)));
      expect(bytes.slice(index).every((byte) => byte === 0x80)).toBe(true);
    }
  });

  test('gives an empty text an empty buffer', () => {
    expect(base64Bytes('', encoder).byteLength).toBe(0);
  });
});

describe('toNativeSource', () => {
  test('copies the base64 text into a native buffer', () => {
    allocated.length = 0;
    const native = toNativeSource({ uri: '', base64: 'QUJD' });
    expect(allocated).toEqual([4]);
    expect(Object.keys(native)).toEqual(['uri', 'base64']);
    expect(bytesOf(native.base64 as ArrayBuffer)).toEqual(ascii('QUJD'));
  });

  test('passes other sources on without a buffer', () => {
    allocated.length = 0;
    const source = { uri: 'https://x.test/a.pdf', headers: { A: '1' } };
    expect(toNativeSource(source)).toEqual(source);
    expect(allocated).toEqual([]);
  });
});

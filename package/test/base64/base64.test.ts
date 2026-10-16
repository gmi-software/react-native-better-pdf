// Android's C++ and iOS's Swift decoder against vectors.json and each other.
import { describe, expect, mock, test } from 'bun:test';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Module mocks are shared by all test files: export everything the package
// imports from Nitro.
mock.module('react-native-nitro-modules', () => ({
  callback: <T>(f: T) => f,
  getHostComponent: () => () => null,
  NitroModules: {
    createHybridObject: () => {
      throw new Error('Not available in base64 tests');
    },
    createNativeArrayBuffer: (size: number) => new ArrayBuffer(size),
  },
}));

const { base64Bytes } = await import('../../src/nativeSource');

type Vector = { text: string; bytes?: string; error?: string };

const packageDir = join(import.meta.dir, '../..');
const vectors: Vector[] = JSON.parse(
  readFileSync(join(import.meta.dir, 'vectors.json'), 'utf8'),
);
const out = mkdtempSync(join(tmpdir(), 'better-pdf-base64-'));

function build(command: string, args: string[]): void {
  const result = spawnSync(command, args, { encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`${command} failed:\n${result.stderr || result.error}`);
  }
}

build('c++', [
  '-std=c++20',
  '-O2',
  '-I',
  join(packageDir, 'android/src/main/cpp'),
  join(import.meta.dir, 'harness.cpp'),
  join(packageDir, 'android/src/main/cpp/Base64Document.cpp'),
  '-o',
  join(out, 'cpp'),
]);
const decoders: [string, string][] = [['C++', join(out, 'cpp')]];
if (process.platform === 'darwin') {
  build('xcrun', [
    'swiftc',
    '-O',
    join(import.meta.dir, 'main.swift'),
    join(packageDir, 'ios/Base64Document.swift'),
    '-o',
    join(out, 'swift'),
  ]);
  decoders.push(['Swift', join(out, 'swift')]);
}

const hex = (bytes: ArrayBuffer | Uint8Array) =>
  Buffer.from(
    bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes),
  ).toString('hex');

function run(decoder: string, texts: string[], args: string[] = []): string[] {
  const input = texts.map((text) => hex(base64Bytes(text))).join('\n') + '\n';
  const result = spawnSync(decoder, args, {
    input,
    encoding: 'utf8',
    maxBuffer: 1 << 26,
  });
  if (result.status !== 0) throw new Error(result.stderr);
  return result.stdout.trimEnd().split('\n');
}

describe.each(decoders)('the %s decoder', (_, decoder) => {
  test('decodes and rejects every shared case', () => {
    const results = run(
      decoder,
      vectors.map((vector) => vector.text),
    );
    vectors.forEach((vector, index) => {
      const expected =
        vector.error === undefined
          ? `ok ${vector.bytes}`
          : `error ${vector.error}`;
      expect({ text: vector.text, result: results[index] }).toEqual({
        text: vector.text,
        result: expected,
      });
    });
  });

  test('decodes a long document, padded, unpadded and with line breaks', () => {
    const bytes = new Uint8Array(300_000).map(
      (_, i) => (i * 31 + (i >> 7)) & 0xff,
    );
    const padded = Buffer.from(bytes).toString('base64');
    const mime = padded.replace(/.{76}/g, '$&\r\n');
    const results = run(decoder, [padded, padded.replace(/=+$/, ''), mime]);
    for (const result of results) expect(result).toBe(`ok ${hex(bytes)}`);
  });
});

test('the C++ decoder at the production chunk size, around each flush', () => {
  const chunkSize = Number(
    spawnSync(join(out, 'cpp'), ['chunk-size'], { encoding: 'utf8' }).stdout,
  );
  // A chunk is written once fewer than 3 bytes are left in it.
  const perChunk = chunkSize - (chunkSize % 3);
  expect(perChunk).toBeGreaterThan(0);
  const bytes = new Uint8Array(2 * perChunk + 4).map(
    (_, i) => (i * 131 + (i >> 9)) & 0xff,
  );
  const sizes = [perChunk, 2 * perChunk].flatMap((size) =>
    [-3, -2, -1, 0, 1, 2, 3].map((delta) => size + delta),
  );
  const texts = sizes.map((size) =>
    Buffer.from(bytes.subarray(0, size)).toString('base64'),
  );
  const results = run(join(out, 'cpp'), texts, ['production']);
  sizes.forEach((size, index) =>
    expect(results[index]).toBe(`ok ${hex(bytes.subarray(0, size))}`),
  );
});

const alphabet = Buffer.from([...'AQR/+= \n-dat:'].map((c) => c.charCodeAt(0)))
  .toString('hex')
  .concat('80');

test.skipIf(decoders.length < 2)(
  'the C++ and Swift decoders agree on every short text',
  () => {
    const digests = decoders.map(([, decoder]) => {
      const result = spawnSync(decoder, ['exhaustive', alphabet, '6'], {
        encoding: 'utf8',
      });
      if (result.status !== 0) throw new Error(result.stderr);
      return result.stdout.trim();
    });
    expect(digests[0]).toStartWith('8108731 ');
    expect(digests[1]).toBe(digests[0]);
  },
  120_000,
);

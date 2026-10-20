import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { PdfMethodError, toPdfMethodError } from './PdfMethodError';

describe('toPdfMethodError', () => {
  test('reads the code and message of an iOS rejection', () => {
    const error = toPdfMethodError(
      new Error('[not-loaded] No document is loaded'),
    );
    expect(error).toBeInstanceOf(PdfMethodError);
    expect(error).toMatchObject({
      name: 'PdfMethodError',
      code: 'not-loaded',
      message: 'No document is loaded',
      nativeDescription: '[not-loaded] No document is loaded',
    });
  });

  test("keeps an iOS platform error's description out of the message", () => {
    const raw =
      '[io-error] The file couldn’t be saved because there isn’t enough space.\n' +
      'Error Domain=NSCocoaErrorDomain Code=640 "The file couldn’t be saved."';
    expect(toPdfMethodError(new Error(raw))).toMatchObject({
      code: 'io-error',
      message: 'The file couldn’t be saved because there isn’t enough space.',
      nativeDescription: raw,
    });
  });

  test('reads the code after the Java class name, without the stack trace', () => {
    const raw =
      'com.margelo.nitro.betterpdf.PdfMethodException: [closed] The document was closed\n' +
      '\tat com.margelo.nitro.betterpdf.SerialWorker.run$lambda$0(SerialWorker.kt:24)\n' +
      '\tat java.lang.Thread.run(Thread.java:1012)\n' +
      'Caused by: java.io.IOException: [busy] not this one';
    expect(toPdfMethodError(new Error(raw))).toMatchObject({
      code: 'closed',
      message: 'The document was closed',
      nativeDescription: raw,
    });
  });

  test('reads the code after a class name that minification renamed', () => {
    const raw =
      'b3.e: [busy] A print dialog is already open\n\tat b3.f.a(SourceFile:1)';
    expect(toPdfMethodError(new Error(raw))).toMatchObject({
      code: 'busy',
      message: 'A print dialog is already open',
    });
  });

  test('a rejection without a code is unknown, without the Java class name', () => {
    const raw =
      'java.lang.NullPointerException: Attempt to invoke a virtual method\n' +
      '\tat com.margelo.nitro.betterpdf.HybridBetterPdfView.print(HybridBetterPdfView.kt:1)';
    expect(toPdfMethodError(new Error(raw))).toMatchObject({
      code: 'unknown',
      message: 'Attempt to invoke a virtual method',
      nativeDescription: raw,
    });
  });

  test('only a code at the start of the message counts', () => {
    const raw = 'java.io.IOException: Could not write /tmp/[closed] x.pdf';
    expect(toPdfMethodError(new Error(raw))).toMatchObject({
      code: 'unknown',
      message: 'Could not write /tmp/[closed] x.pdf',
    });
    expect(toPdfMethodError(new Error('[later] what'))).toMatchObject({
      code: 'unknown',
      message: '[later] what',
    });
  });

  test('an empty message or a value that is not an Error', () => {
    const empty = toPdfMethodError(new Error(''));
    expect(empty).toMatchObject({ code: 'unknown', message: 'Unknown error' });
    expect(empty.nativeDescription).toBeUndefined();
    expect(toPdfMethodError('boom')).toMatchObject({
      code: 'unknown',
      message: 'boom',
      nativeDescription: 'boom',
    });
  });

  test('a PdfMethodError stays as it is', () => {
    const error = new PdfMethodError('closed', 'The document is closed');
    expect(toPdfMethodError(error)).toBe(error);
    expect(error.nativeDescription).toBeUndefined();
  });
});

test('native code rejects with the codes JS knows', () => {
  const read = (path: string) =>
    readFileSync(join(import.meta.dir, '..', path), 'utf8');
  const kotlin = [
    ...read(
      'android/src/main/java/com/margelo/nitro/betterpdf/PdfMethodException.kt',
    ).matchAll(/[A-Z_]+\("([a-z-]+)"\)/g),
  ].map((match) => match[1]);
  const swift = [
    ...read('ios/PdfMethodFailure.swift').matchAll(
      /case (\w+)(?: = "([a-z-]+)")?$/gm,
    ),
  ].map((match) => match[2] ?? match[1]);
  expect(kotlin).toHaveLength(10);
  expect(swift).toEqual(kotlin);
  for (const code of kotlin) {
    expect<string>(toPdfMethodError(new Error(`[${code}] x`)).code).toBe(code!);
  }
});

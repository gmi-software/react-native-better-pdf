import { NitroModules } from 'react-native-nitro-modules';
import type { NormalizedSource } from './source';
import type { NativePdfSource } from './types/NativePdfSource';

interface Encoder {
  encodeInto(
    source: string,
    destination: Uint8Array,
  ): { read?: number; written?: number };
}

// The library builds without DOM or Bun types, which declare the real one.
declare const TextEncoder: (new () => Encoder) | undefined;

const defaultEncoder =
  typeof TextEncoder === 'function' ? new TextEncoder() : undefined;

/** `text` in a native-owned buffer, one byte per character; non-ASCII becomes `0x80`. */
export function base64Bytes(
  text: string,
  encoder: Encoder | undefined = defaultEncoder,
): ArrayBuffer {
  const buffer = NitroModules.createNativeArrayBuffer(text.length);
  const bytes = new Uint8Array(buffer);
  if (encoder !== undefined) {
    const { read, written } = encoder.encodeInto(text, bytes);
    if (read !== text.length || written !== text.length) {
      bytes.fill(0x80, text.search(/[\u0080-\uffff]/));
    }
  } else {
    let index = 0;
    for (; index < text.length; index++) {
      const code = text.charCodeAt(index);
      if (code > 0x7f) break;
      bytes[index] = code;
    }
    bytes.fill(0x80, index);
  }
  return buffer;
}

export function toNativeSource(source: NormalizedSource): NativePdfSource {
  const { base64, ...rest } = source;
  return base64 === undefined ? rest : { ...rest, base64: base64Bytes(base64) };
}

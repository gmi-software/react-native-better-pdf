// Contradictory sources that compile today. At runtime `filePath` wins over
// `base64`, which wins over `uri`, and cache options only apply to `uri`
// sources; development builds warn about the ignored keys. 2.0 makes these
// compile errors, and then this file is expected to fail.
import type { PdfSource } from 'react-native-better-pdf';

declare const uri: string;
declare const filePath: string;
declare const base64: string;

export const contradictory: PdfSource[] = [
  { uri, filePath },
  { uri, base64 },
  { filePath, base64 },
  { filePath, cache: 'persistent' },
  { filePath, cacheKey: 'document', maxAgeMs: 0 },
  { base64, cache: 'session', headers: { Authorization: 'Bearer token' } },
];

import type { PdfMethodError } from '../PdfMethodError';

/**
 * Stable, platform-independent reason a `PdfViewRef` or `PdfDocument`
 * method rejected.
 *
 * @see {@linkcode PdfMethodError.code}
 */
export type PdfMethodErrorCode =
  | 'not-loaded'
  | 'closed'
  | 'invalid-argument'
  | 'not-supported'
  | 'not-permitted'
  | 'busy'
  | 'io-error'
  | 'invalid-pdf'
  | 'out-of-memory'
  | 'unknown';

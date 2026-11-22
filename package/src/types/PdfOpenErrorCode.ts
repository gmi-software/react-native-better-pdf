import type { PdfOpenError } from '../PdfOpenError';
import type { PdfErrorCode } from './PdfErrorCode';

/**
 * The codes `PdfDocument.open()` can reject with: every
 * {@linkcode PdfErrorCode} except `'page-render-failed'`, which only a
 * `PdfView` reports. `'unsupported-pdf'` and `'out-of-memory'` occur on
 * Android only.
 *
 * {@linkcode PdfOpenError.code} is typed as the whole `PdfErrorCode` until
 * 2.0; narrow it with `error.code as PdfOpenErrorCode` for an exhaustive
 * `switch`.
 */
export type PdfOpenErrorCode = Exclude<PdfErrorCode, 'page-render-failed'>;

import type { PdfDownloadError } from '../PdfDownloadError';
import type { PdfErrorCode } from './PdfErrorCode';

/**
 * The codes `PdfCache.download()` can reject with: `'invalid-source'`,
 * `'network-error'`, `'http-error'` and `'invalid-pdf'`. The document is
 * downloaded, not opened, so the password and rendering codes cannot occur.
 *
 * {@linkcode PdfDownloadError.code} is typed as the whole `PdfErrorCode`
 * until 2.0; narrow it with `error.code as PdfDownloadErrorCode` for an
 * exhaustive `switch`.
 */
export type PdfDownloadErrorCode = Exclude<
  PdfErrorCode,
  | 'file-not-found'
  | 'password-required'
  | 'invalid-password'
  | 'unsupported-pdf'
  | 'page-render-failed'
  | 'out-of-memory'
>;

import type { PdfError } from './types/PdfError';
import type { PdfErrorCode } from './types/PdfErrorCode';

/**
 * Why `PdfDocument.open()` failed. Match on {@linkcode PdfOpenError.code},
 * the same codes `PdfView` reports in `onError`: e.g. `'password-required'`
 * and `'invalid-password'` (ask for a password and open again),
 * `'unsupported-pdf'` (an encrypted document this Android version cannot
 * open), `'file-not-found'`, `'network-error'` or `'http-error'` (see
 * {@linkcode PdfOpenError.httpStatus}).
 *
 * A cancelled open rejects with an `AbortError` instead.
 */
export class PdfOpenError extends Error {
  /**
   * Stable reason; match on this. Always one of `PdfOpenErrorCode`, the type
   * to narrow it to for an exhaustive `switch`.
   */
  readonly code: PdfErrorCode;
  /** HTTP status code, set for `'http-error'`. */
  readonly httpStatus?: number;
  /** Raw platform error description, for logging only. */
  readonly nativeDescription?: string;

  constructor(error: PdfError) {
    super(error.message);
    this.name = 'PdfOpenError';
    this.code = error.code;
    if (error.httpStatus !== undefined) this.httpStatus = error.httpStatus;
    if (error.nativeDescription !== undefined) {
      this.nativeDescription = error.nativeDescription;
    }
  }
}

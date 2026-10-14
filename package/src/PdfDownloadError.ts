import type { PdfError } from './types/PdfError';
import type { PdfErrorCode } from './types/PdfErrorCode';

/**
 * Why `PdfCache.download()` failed. Match on
 * {@linkcode PdfDownloadError.code}: `'network-error'` (no connection, a
 * timeout), `'http-error'` (see {@linkcode PdfDownloadError.httpStatus}),
 * `'invalid-pdf'` (the server answered with something that is not a PDF,
 * e.g. a login page) or `'invalid-source'` (not an `http(s)://` URL, or
 * an invalid header; see `PdfUriSource.headers`).
 *
 * A cancelled download rejects with an `AbortError` instead.
 */
export class PdfDownloadError extends Error {
  /** Stable reason; match on this. */
  readonly code: PdfErrorCode;
  /** HTTP status code, set for `'http-error'`. */
  readonly httpStatus?: number;
  /** Raw platform error description, for logging only. */
  readonly nativeDescription?: string;

  constructor(error: PdfError) {
    super(error.message);
    this.name = 'PdfDownloadError';
    this.code = error.code;
    if (error.httpStatus !== undefined) this.httpStatus = error.httpStatus;
    if (error.nativeDescription !== undefined) {
      this.nativeDescription = error.nativeDescription;
    }
  }
}

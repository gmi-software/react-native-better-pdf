import type { PdfErrorCode } from './PdfErrorCode';

/**
 * A load or render failure, delivered to `PdfView`'s `onError`.
 */
export interface PdfError {
  /** Stable reason; match on this. */
  code: PdfErrorCode;
  /** Human-readable English description. Not stable — do not match on it. */
  message: string;
  /** Zero-based page index, set for `page-render-failed`. */
  page?: number;
  /**
   * Zero-based index in `PdfView`'s `source` of the document that could not
   * be loaded, set by `PdfView` for every code but `page-render-failed`
   * (`0` for a single source). Unset when several sources failed as a whole,
   * as when Android runs out of memory laying out their pages.
   */
  sourceIndex?: number;
  /** HTTP status code, set for `http-error`. */
  httpStatus?: number;
  /** Raw platform error description, for logging only. */
  nativeDescription?: string;
}

import type { PdfErrorCode } from './PdfErrorCode';

/**
 * A load or render failure, delivered to `PdfView`'s `onError`.
 */
export interface PdfError {
  /** Stable reason; match on this. */
  code: PdfErrorCode;
  /** Human-readable English description. Not stable — do not match on it. */
  message: string;
  /**
   * Zero-based page index, set when one page failed after `onLoad`
   * (`page-render-failed`, and `out-of-memory` while rendering; Android
   * only).
   */
  page?: number;
  /**
   * Zero-based index in `PdfView`'s `source` of the document that could not
   * be loaded, set by `PdfView` for load failures (`0` for a single source).
   * Unset for page failures, and when several sources failed as a whole, as
   * when Android runs out of memory laying out their pages.
   */
  sourceIndex?: number;
  /** HTTP status code, set for `http-error`. */
  httpStatus?: number;
  /** Raw platform error description, for logging only. */
  nativeDescription?: string;
}

/**
 * `PdfView`'s `onError` argument for a document that could not be loaded:
 * nothing is shown.
 */
export interface PdfLoadError extends PdfError {
  code: Exclude<PdfErrorCode, 'page-render-failed'>;
  page?: undefined;
}

/**
 * `PdfView`'s `onError` argument for a page that could not be rendered after
 * `onLoad` (Android only): the document stays open.
 */
export interface PdfPageError extends PdfError {
  code: 'page-render-failed' | 'out-of-memory';
  page: number;
  sourceIndex?: undefined;
}

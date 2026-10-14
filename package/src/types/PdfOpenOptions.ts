import type { PdfDocument } from '../PdfDocument';
import type { PdfLoadProgressEvent } from './PdfLoadProgressEvent';

/** Options of {@linkcode PdfDocument.open}. */
export interface PdfOpenOptions {
  /** Password of a protected document. */
  password?: string;
  /**
   * Download progress of a remote document, at most every 100 ms plus once
   * on completion. Not called for local files and stored copies.
   */
  onProgress?: (event: PdfLoadProgressEvent) => void;
  /**
   * Cancels opening: the promise rejects with the signal's reason (an
   * `AbortError` by default) and `onProgress` is not called again. A
   * download that a `PdfView` or `PdfCache` is also waiting for continues
   * for them.
   */
  signal?: AbortSignal;
}

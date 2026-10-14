import type { PdfCache } from '../PdfCache';
import type { PdfLoadProgressEvent } from './PdfLoadProgressEvent';

/** Options of {@linkcode PdfCache.download}. */
export interface PdfDownloadOptions {
  /**
   * Download progress, at most every 100 ms plus once on completion. Not
   * called when a stored copy is used or confirmed unchanged by the server.
   */
  onProgress?: (event: PdfLoadProgressEvent) => void;
  /**
   * Cancels the download: the promise rejects with the signal's reason (an
   * `AbortError` by default) and `onProgress` is not called again. A
   * download that a `PdfView` is also waiting for continues for the view.
   */
  signal?: AbortSignal;
}

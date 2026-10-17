/**
 * Delivered to `PdfView`'s `onLoad` once a document is open and ready to
 * display.
 */
export interface PdfLoadEvent {
  /** Number of pages in the document. */
  pageCount: number;
  /** Width of the first page in PDF points (1/72"). */
  firstPageWidth: number;
  /** Height of the first page in PDF points (1/72"). */
  firstPageHeight: number;
  /**
   * Absolute path of the local file being displayed: the file itself for
   * `file://` sources, the downloaded copy for remote ones (valid until the
   * copy is removed; see `PdfUriSource.cache`), or the extracted copy of a
   * bundled asset. `undefined` for `{ base64 }` sources (any temporary file
   * is the library's) and for Android `content://` sources. Useful for
   * sharing or printing.
   */
  filePath?: string;
  /**
   * `true` when a remote document is shown from a downloaded copy older than
   * its `maxAgeMs` because the server could not be asked for a newer version
   * (no connection, a timeout, or a 5xx response) — e.g. to show "Offline
   * copy, may be outdated". `false` otherwise, and always for local files.
   */
  isStale: boolean;
}

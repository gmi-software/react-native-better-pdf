/**
 * One of the documents a `PdfView` shows, as reported by `onLoad`: where its
 * pages are among the view's pages, and where it was loaded from.
 */
export interface PdfLoadedSource {
  /** Number of pages of this source. */
  pageCount: number;
  /** Zero-based page of the view at which this source's first page is. */
  firstPage: number;
  /**
   * Absolute path of the local file of this source: the file itself for
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

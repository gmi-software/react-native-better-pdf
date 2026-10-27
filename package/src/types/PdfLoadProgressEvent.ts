/**
 * Delivered to `PdfView`'s `onLoadProgress` while a remote document is being
 * downloaded, or on Android while a `content://` document its provider
 * streams is copied.
 *
 * Emitted at most every 100 ms, plus once when the download completes (before
 * `onLoad`). Local files, and downloaded copies that are used or confirmed
 * unchanged by the server, load without progress events. Also delivered to
 * `PdfCache.download()`'s `onProgress`.
 *
 * @example
 * ```tsx
 * onLoadProgress={({ loadedBytes, totalBytes }) =>
 *   setProgress(totalBytes === undefined ? undefined : loadedBytes / totalBytes)
 * }
 * ```
 */
export interface PdfLoadProgressEvent {
  /** Bytes downloaded so far. */
  loadedBytes: number;
  /**
   * Size of the download in bytes, when the server (or the provider)
   * reports it. `undefined` when unknown, for example without a
   * `Content-Length` header or when the response is transparently
   * decompressed.
   */
  totalBytes?: number;
}

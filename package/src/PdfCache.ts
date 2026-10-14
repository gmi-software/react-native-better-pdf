import { NitroModules } from 'react-native-nitro-modules';
import { abortable, abortReason, untilAborted } from './abort';
import { PdfDownloadError } from './PdfDownloadError';
import type { PdfDownloadOptions } from './types/PdfDownloadOptions';
import { normalizeUriSource, type PdfUriSource } from './source';
import type { BetterPdfCache } from './specs/BetterPdfCache.nitro';
import type { PdfCacheEntry } from './types/PdfCacheEntry';
import type { PdfDownloadResult } from './types/PdfDownloadResult';

let nativeCache: BetterPdfCache | undefined;
let nextRequestId = 1;

function getNativeCache(): BetterPdfCache {
  if (nativeCache === undefined) {
    nativeCache =
      NitroModules.createHybridObject<BetterPdfCache>('BetterPdfCache');
  }
  return nativeCache;
}

function unwrap(result: PdfDownloadResult): PdfCacheEntry {
  if (result.entry !== undefined) return result.entry;
  if (result.error !== undefined) throw new PdfDownloadError(result.error);
  throw new Error('The download ended without a result');
}

/**
 * The downloaded copies of remote documents, shared with every
 * `PdfView`: download documents ahead of time (e.g. for offline
 * reading), check which are available, and delete them.
 *
 * Every method takes the same {@linkcode PdfUriSource} you pass to
 * `PdfView`; copies are found by its `cacheKey`, or by `uri` and `headers`.
 *
 * @example
 * ```ts
 * // "Download for offline reading", with progress and a cancel button.
 * const source = { uri, cache: 'persistent', cacheKey: documentId } as const;
 * const controller = new AbortController();
 * const entry = await PdfCache.download(source, {
 *   onProgress: ({ loadedBytes, totalBytes }) => setProgress(loadedBytes / (totalBytes ?? loadedBytes)),
 *   signal: controller.signal,
 * });
 *
 * // On sign-out.
 * await PdfCache.clear();
 * ```
 */
export const PdfCache = {
  /**
   * Makes sure a copy of `source` no older than its `maxAgeMs` is stored,
   * downloading it — or, for an older copy, asking the server whether it
   * changed — when needed, and resolves with it. Kept for as long as
   * `source.cache` says (default `'session'`; pass `'persistent'` for offline
   * reading). Resolves at once with a copy that is fresh enough. Joins a
   * download of the same copy already in progress, e.g. by a `PdfView`.
   *
   * Unlike `PdfView`, never falls back to an outdated copy: when the server
   * cannot be reached the promise rejects with a {@linkcode PdfDownloadError}
   * and the existing copy stays stored.
   *
   * @throws {PdfDownloadError} when the download fails.
   */
  async download(
    source: PdfUriSource,
    options: PdfDownloadOptions = {},
  ): Promise<PdfCacheEntry> {
    const { onProgress, signal } = options;
    if (signal?.aborted) throw abortReason(signal);
    const cache = getNativeCache();
    const requestId = nextRequestId;
    nextRequestId += 1;
    const result = cache.download(
      requestId,
      normalizeUriSource(source),
      untilAborted(onProgress, signal),
    );
    return unwrap(
      await abortable(result, signal, () => cache.cancelDownload(requestId)),
    );
  },

  /**
   * The stored copy of `source`, or `undefined` when there is none — e.g. to
   * show which documents are available offline. Never uses the network and
   * never checks `maxAgeMs`: compare {@linkcode PdfCacheEntry.validatedAtMs}
   * yourself.
   */
  getEntry(source: PdfUriSource): Promise<PdfCacheEntry | undefined> {
    return getNativeCache().getEntry(normalizeUriSource(source));
  },

  /**
   * Deletes the stored copy of `source`, whether session or persistent.
   * Resolves `false` when there was none. A `PdfView` showing the document
   * keeps showing it; a download in progress still stores its result.
   */
  remove(source: PdfUriSource): Promise<boolean> {
    return getNativeCache().remove(normalizeUriSource(source));
  },

  /**
   * Deletes every stored copy, session and persistent — e.g. when the user
   * signs out. Open documents stay on screen; downloads in progress still
   * store their result (cancel them first). Page images from
   * `renderPage()` are not affected.
   */
  clear(): Promise<void> {
    return getNativeCache().clear();
  },

  /** Total size of the stored copies in bytes. */
  getByteSize(): Promise<number> {
    return getNativeCache().getByteSize();
  },
};

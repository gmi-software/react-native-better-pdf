import type { HybridObject } from 'react-native-nitro-modules';
import type { NativePdfSource } from '../types/NativePdfSource';
import type { PdfCacheEntry } from '../types/PdfCacheEntry';
import type { PdfDeviceCapabilities } from '../types/PdfDeviceCapabilities';
import type { PdfDownloadResult } from '../types/PdfDownloadResult';
import type { PdfLoadProgressEvent } from '../types/PdfLoadProgressEvent';

/**
 * Native store of downloaded remote documents, shared with every
 * `BetterPdfView`. Internal: apps use `PdfCache`, which adds `AbortSignal`
 * cancellation and typed errors on top.
 *
 * Every method but `getCapabilities` works off the JS thread. Sources are
 * keyed by `cacheKey`, or by `uri` and `headers` when it is absent.
 */
export interface BetterPdfCache extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  /**
   * Makes sure a copy of `source` no older than its `maxAgeMs` is stored,
   * downloading or revalidating it when needed; joins a download of the same
   * copy already in progress. `onProgress` is throttled like the view's.
   * Failures resolve with `error`; a cancelled download never resolves.
   */
  download(
    requestId: number,
    source: NativePdfSource,
    onProgress?: (event: PdfLoadProgressEvent) => void,
  ): Promise<PdfDownloadResult>;
  /** Leaves the download started with `requestId`; it stops once nothing else waits for it. */
  cancelDownload(requestId: number): void;
  /** The stored copy of `source`, without any network access. */
  getEntry(source: NativePdfSource): Promise<PdfCacheEntry | undefined>;
  /** Deletes the stored copy of `source`; resolves `false` when there was none. */
  remove(source: NativePdfSource): Promise<boolean>;
  /** Deletes every stored copy. Downloads in progress are not affected. */
  clear(): Promise<void>;
  /** Total size of all stored copies in bytes. */
  getByteSize(): Promise<number>;
  /** What this device supports; the same for the whole process. Synchronous. */
  getCapabilities(): PdfDeviceCapabilities;
}

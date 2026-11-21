package com.margelo.nitro.betterpdf

import android.os.SystemClock
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android implementation of `BetterPdfCache`: the JS side of the
 * process-wide [DocumentCache]. Methods arrive on the JS thread and return
 * at once; the work runs on the cache's threads or Nitro's promise pool.
 */
@Keep
@DoNotStrip
class HybridBetterPdfCache : HybridBetterPdfCacheSpec() {
  private val cache: DocumentCache
    get() = DocumentCache.shared(
      NitroModules.applicationContext ?: throw IllegalStateException("The React Native context is not available yet"),
    )

  /** Downloads JS may still cancel, by request id. */
  private val downloads = ConcurrentHashMap<Double, DocumentCache.Subscription>()

  override fun download(
    requestId: Double,
    source: NativePdfSource,
    onProgress: ((event: PdfLoadProgressEvent) -> Unit)?,
  ): Promise<PdfDownloadResult> {
    val promise = Promise<PdfDownloadResult>()
    val reportProgress = onProgress
    val throttle = ProgressThrottle()
    val finished = AtomicBoolean(false)
    val subscription = cache.fetch(
      source,
      allowStale = false,
      object : DocumentCache.Listener {
        override fun onProgress(loadedBytes: Long, totalBytes: Long?, isComplete: Boolean) {
          if (reportProgress == null) return
          val emit = synchronized(throttle) { throttle.shouldEmit(SystemClock.uptimeMillis(), loadedBytes, isComplete) }
          if (emit) reportProgress(PdfLoadProgressEvent(loadedBytes.toDouble(), totalBytes?.toDouble()))
        }

        override fun onReady(copy: CachedCopy, isStale: Boolean, isDownloaded: Boolean) {
          finished.set(true)
          downloads.remove(requestId)
          promise.resolve(PdfDownloadResult(entry = copy.toPdfCacheEntry(), error = null))
        }

        override fun onFailed(failure: PdfFailure) {
          finished.set(true)
          downloads.remove(requestId)
          promise.resolve(PdfDownloadResult(entry = null, error = failure.toPdfError()))
        }
      },
    )
    downloads[requestId] = subscription
    // It may have finished before it was registered.
    if (finished.get()) downloads.remove(requestId)
    return promise
  }

  override fun cancelDownload(requestId: Double) {
    downloads.remove(requestId)?.cancel()
  }

  override fun getEntry(source: NativePdfSource): Promise<PdfCacheEntry?> =
    Promise.parallel { cache.find(source)?.toPdfCacheEntry() }

  override fun remove(source: NativePdfSource): Promise<Boolean> =
    Promise.parallel { cache.remove(source) }

  override fun clear(): Promise<Unit> =
    Promise.parallel { cache.clear() }

  override fun getByteSize(): Promise<Double> =
    Promise.parallel { cache.byteSize().toDouble() }
}

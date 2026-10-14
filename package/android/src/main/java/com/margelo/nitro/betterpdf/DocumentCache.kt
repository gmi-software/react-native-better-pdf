package com.margelo.nitro.betterpdf

import android.content.Context
import android.system.Os
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Downloaded copies of remote documents, shared by every view and every
 * `PdfCache` call in the process: serves a copy that is fresh enough
 * ([CacheMetadata.isFresh]), else downloads or revalidates it. Concurrent
 * requests for the same copy share one [SharedDownload], which stops once
 * nobody waits for it any more.
 *
 * [fetch] returns at once; all disk and network work runs on [executor], and
 * listeners are called there.
 */
internal class DocumentCache(
  createStore: () -> DocumentCacheStore,
  private val executor: Executor,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  interface Listener {
    /** Download progress, unthrottled: once per received chunk. */
    fun onProgress(loadedBytes: Long, totalBytes: Long?, isComplete: Boolean)

    /**
     * The copy to open. [isStale]: older than the source's `maxAgeMs`, shown
     * because the server could not be reached (only with `allowStale`).
     */
    fun onReady(copy: CachedCopy, isStale: Boolean)

    fun onFailed(failure: PdfFailure)
  }

  /** Stops listening; the download stops once nobody else waits for it. Idempotent. */
  fun interface Subscription {
    fun cancel()
  }

  class Subscriber internal constructor(
    val source: NativePdfSource,
    val allowStale: Boolean,
    val listener: Listener,
  ) {
    // Guarded by the cache's lock.
    internal var isCancelled = false
    internal var download: SharedDownload? = null
  }

  /** Created on first use, which is always on [executor] or a promise thread: it touches the disk. */
  private val store by lazy(createStore)
  private val lock = Any()
  private val inFlight = HashMap<String, SharedDownload>()

  /**
   * Delivers the copy of the `http(s)://` [source] to [listener]. With
   * [allowStale], an existing copy is delivered (as stale) when the server
   * cannot be reached to revalidate it.
   */
  fun fetch(source: NativePdfSource, allowStale: Boolean, listener: Listener): Subscription {
    val subscriber = Subscriber(source, allowStale, listener)
    executor.execute { start(subscriber) }
    return Subscription { cancel(subscriber) }
  }

  /** The stored copy of [source], without network access. Blocking. */
  fun find(source: NativePdfSource): CachedCopy? = store.find(source.storeKey())

  /** Blocking; see [DocumentCacheStore.remove]. */
  fun remove(source: NativePdfSource): Boolean = store.remove(source.storeKey())

  /** Blocking; see [DocumentCacheStore.clear]. */
  fun clear() = store.clear()

  /** Blocking; see [DocumentCacheStore.byteSize]. */
  fun byteSize(): Long = store.byteSize()

  private fun start(subscriber: Subscriber) {
    val source = subscriber.source
    val scheme = source.uri.substringBefore(':', "").lowercase()
    if (scheme != "http" && scheme != "https") {
      return deliverFailure(subscriber, PdfFailure(PdfErrorCode.INVALID_SOURCE, "Not an http(s) URL: \"${source.uri}\""))
    }
    source.invalidHeaderFailure()?.let { return deliverFailure(subscriber, it) }
    val key = source.storeKey()
    val copy = store.find(key)
    if (copy != null && copy.metadata.isFresh(source.maxAgeMs, clock())) {
      return deliverReady(subscriber, copy, isStale = false)
    }
    synchronized(lock) {
      if (subscriber.isCancelled) return
      val download = inFlight[key] ?: SharedDownload(
        key = key,
        url = source.uri,
        headers = source.headers.orEmpty(),
        existing = copy,
        mode = if (source.cache == PdfCacheMode.PERSISTENT || copy?.mode == PdfCacheMode.PERSISTENT) {
          PdfCacheMode.PERSISTENT
        } else {
          PdfCacheMode.SESSION
        },
        store = store,
        clock = clock,
        onProgress = ::onProgress,
        onFinished = ::onFinished,
      ).also {
        inFlight[key] = it
        executor.execute(it)
      }
      download.subscribers += subscriber
      subscriber.download = download
    }
  }

  private fun cancel(subscriber: Subscriber) {
    synchronized(lock) {
      subscriber.isCancelled = true
      val download = subscriber.download ?: return
      subscriber.download = null
      download.subscribers -= subscriber
      if (download.subscribers.isEmpty() && inFlight.remove(download.key, download)) download.cancel()
    }
  }

  private fun onProgress(download: SharedDownload, loadedBytes: Long, totalBytes: Long?, isComplete: Boolean) {
    val subscribers = synchronized(lock) { download.subscribers.toList() }
    for (subscriber in subscribers) subscriber.listener.onProgress(loadedBytes, totalBytes, isComplete)
  }

  private fun onFinished(download: SharedDownload, outcome: DownloadOutcome) {
    val subscribers = synchronized(lock) {
      inFlight.remove(download.key, download)
      download.subscribers.toList().also { waiting ->
        waiting.forEach { it.download = null }
        download.subscribers.clear()
      }
    }
    for (subscriber in subscribers) {
      when (outcome) {
        is DownloadOutcome.Stored -> deliverReady(subscriber, outcome.copy, isStale = false)
        is DownloadOutcome.Failed -> {
          val stale = if (subscriber.allowStale && outcome.isServerUnreachable) store.find(download.key) else null
          if (stale != null) deliverReady(subscriber, stale, isStale = true) else deliverFailure(subscriber, outcome.failure)
        }
      }
    }
  }

  private fun deliverReady(subscriber: Subscriber, copy: CachedCopy, isStale: Boolean) {
    val delivered = if (subscriber.source.cache == PdfCacheMode.PERSISTENT) {
      try {
        store.promote(copy)
      } catch (e: IOException) {
        return deliverFailure(subscriber, PdfFailure(PdfErrorCode.NETWORK_ERROR, "Could not store the downloaded PDF", cause = e))
      }
    } else {
      copy
    }
    if (!isCancelled(subscriber)) subscriber.listener.onReady(delivered, isStale)
  }

  private fun deliverFailure(subscriber: Subscriber, failure: PdfFailure) {
    if (!isCancelled(subscriber)) subscriber.listener.onFailed(failure)
  }

  private fun isCancelled(subscriber: Subscriber) = synchronized(lock) { subscriber.isCancelled }

  companion object {
    @Volatile private var shared: DocumentCache? = null

    /** The process-wide cache. Cheap: touches no disk until first used. */
    fun shared(context: Context): DocumentCache =
      shared ?: synchronized(this) { shared ?: create(context.applicationContext).also { shared = it } }

    private fun create(context: Context): DocumentCache =
      DocumentCache(
        createStore = {
          val root = File(context.cacheDir, "BetterPdf")
          DocumentCacheStore(
            sessionDirectory = File(root, "session"),
            // Not purged by the OS, and excluded from Auto Backup: the copies
            // can be downloaded again.
            persistentDirectory = File(context.noBackupFilesDir, "BetterPdf/documents"),
            partialDirectory = File(root, "partial"),
            legacyDirectory = root,
            link = { existing, link -> Os.link(existing.path, link.path) },
          )
        },
        executor = Executors.newCachedThreadPool { runnable ->
          Thread(runnable, "BetterPdf-download").apply { isDaemon = true }
        },
      )
  }
}

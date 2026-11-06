package com.margelo.nitro.betterpdf

import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * One HTTP request for one cache key, shared by everyone waiting for that
 * copy ([DocumentCache] owns the [subscribers]). With an [existing] copy it is
 * a conditional request: `304` keeps the copy, `200` replaces it.
 *
 * Runs on a [DocumentCache] thread; [cancel] is safe from any thread and ends
 * [run] without an outcome.
 */
internal class SharedDownload(
  val key: String,
  private val url: String,
  private val headers: Map<String, String>,
  private val existing: CachedCopy?,
  /** Where a new copy is stored; joiners that want it persistent promote it. */
  private val mode: PdfCacheMode,
  private val store: DocumentCacheStore,
  private val clock: () -> Long,
  private val onProgress: (download: SharedDownload, loadedBytes: Long, totalBytes: Long?, isComplete: Boolean) -> Unit,
  private val onFinished: (download: SharedDownload, outcome: DownloadOutcome) -> Unit,
) : Runnable {
  /** Guarded by the [DocumentCache]'s lock. */
  val subscribers = mutableListOf<DocumentCache.Subscriber>()

  @Volatile private var cancelled = false

  @Volatile private var connection: HttpURLConnection? = null

  fun cancel() {
    cancelled = true
    connection?.disconnect()
  }

  override fun run() {
    val outcome = try {
      download()
    } catch (_: InterruptedException) {
      return
    } catch (e: Throwable) {
      // Anything else must still end the download: [DocumentCache] keeps it
      // in flight until [onFinished], and later requests would join it. The
      // platform rejecting the request (IllegalArgumentException, e.g. for
      // a header) is the source's fault; its message may quote a header
      // value, which may hold a token, so it is not passed on.
      if (cancelled) return
      val failure = if (e is IllegalArgumentException) {
        PdfFailure(PdfErrorCode.INVALID_SOURCE, "Downloading $url failed: the request was rejected")
      } else {
        PdfFailure(PdfErrorCode.NETWORK_ERROR, "Downloading $url failed", cause = e)
      }
      DownloadOutcome.Failed(failure, isServerUnreachable = false)
    }
    onFinished(this, outcome)
  }

  private fun download(): DownloadOutcome {
    val partial = store.newPartialFile(key)
    try {
      val connection = connect()
      val status = connection.responseCode
      if (status == HttpURLConnection.HTTP_NOT_MODIFIED && existing != null) {
        val validated = CacheMetadata(
          validatedAtMs = clock(),
          etag = connection.getHeaderField("ETag") ?: existing.metadata.etag,
          lastModified = connection.getHeaderField("Last-Modified") ?: existing.metadata.lastModified,
        )
        return DownloadOutcome.Stored(store.markValidated(existing, validated))
      }
      if (status !in 200..299) {
        val reason = if (status in RedirectPolicy.STATUSES) ": the redirect is not followed" else ""
        return DownloadOutcome.Failed(
          PdfFailure(PdfErrorCode.HTTP_ERROR, "Downloading $url failed with HTTP $status$reason", httpStatus = status),
          isServerUnreachable = status >= 500,
        )
      }
      // -1 when unknown; also cleared when the response is transparently
      // decompressed, so the byte count never exceeds a reported total.
      val totalBytes = connection.contentLengthLong.takeIf { it >= 0 }
      connection.inputStream.use { input ->
        partial.outputStream().use { output ->
          input.copyWithProgress(output, totalBytes, { cancelled }) { loadedBytes, total, isComplete ->
            onProgress(this, loadedBytes, total, isComplete)
          }
        }
      }
      val metadata = CacheMetadata(clock(), connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
      if (cancelled) throw InterruptedException()
      if (!partial.hasPdfHeader()) {
        return DownloadOutcome.Failed(
          PdfFailure(PdfErrorCode.INVALID_PDF, "The server did not return a PDF document for $url"),
          isServerUnreachable = false,
        )
      }
      return DownloadOutcome.Stored(store.commit(key, partial, mode, metadata))
    } catch (e: PdfFailure) {
      return DownloadOutcome.Failed(e, isServerUnreachable = false)
    } catch (e: MalformedURLException) {
      return DownloadOutcome.Failed(
        PdfFailure(PdfErrorCode.INVALID_SOURCE, "Unsupported PDF source: \"$url\"", cause = e),
        isServerUnreachable = false,
      )
    } catch (e: IOException) {
      if (cancelled) throw InterruptedException()
      return DownloadOutcome.Failed(
        PdfFailure(PdfErrorCode.NETWORK_ERROR, "Downloading $url failed", cause = e),
        isServerUnreachable = true,
      )
    } finally {
      this.connection?.disconnect()
      this.connection = null
      // Never leave a partial file behind; a no-op once committed.
      partial.delete()
    }
  }

  /**
   * Requests [url] and the redirects [RedirectPolicy] follows: the connection
   * with the answer. Throws [PdfFailure] after too many redirects.
   */
  private fun connect(): HttpURLConnection {
    var target = URL(url)
    val redirects = RedirectPolicy(target)
    while (true) {
      val connection = open(target, withHeaders = redirects.sendsHeaders)
      if (connection.responseCode !in RedirectPolicy.STATUSES) return connection
      when (val next = redirects.next(target, connection.getHeaderField("Location"))) {
        RedirectPolicy.Next.Refuse -> return connection
        RedirectPolicy.Next.TooMany ->
          throw PdfFailure(PdfErrorCode.NETWORK_ERROR, "Downloading $url failed: more than ${RedirectPolicy.MAX_REDIRECTS} redirects")
        is RedirectPolicy.Next.Follow -> {
          connection.disconnect()
          target = next.url
        }
      }
    }
  }

  private fun open(target: URL, withHeaders: Boolean): HttpURLConnection {
    val connection = (target.openConnection() as HttpURLConnection).apply {
      val timeoutMs = if (existing != null) REVALIDATION_TIMEOUT_MS else TIMEOUT_MS
      connectTimeout = timeoutMs
      readTimeout = timeoutMs
      instanceFollowRedirects = false
      // The copies in the store are the cache; an app-installed
      // HttpResponseCache must not answer instead of the server.
      useCaches = false
      if (withHeaders) headers.forEach { (name, value) -> setRequestProperty(name, value) }
      existing?.metadata?.etag?.let { setRequestProperty("If-None-Match", it) }
      existing?.metadata?.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
    }
    this.connection = connection
    if (cancelled) throw InterruptedException()
    return connection
  }

  private companion object {
    const val TIMEOUT_MS = 30_000

    /** Shorter, so a dead network shows the existing copy soon. */
    const val REVALIDATION_TIMEOUT_MS = 10_000
  }
}

package com.margelo.nitro.betterpdf

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Android implementation of `BetterPdfDocument`: a document opened without
 * a view.
 *
 * Holds only the file descriptor between calls; each read opens what it
 * needs on a duplicate and closes it ([DocumentReads]), so PDFium keeps
 * nothing. Calls run one at a time, in order, on the document's own thread,
 * which exits while idle. Methods arrive on the JS thread and return at once.
 */
@Keep
@DoNotStrip
class HybridBetterPdfDocument : HybridBetterPdfDocumentSpec() {
  private val context: Context
    get() = NitroModules.applicationContext ?: throw IllegalStateException("The React Native context is not available yet")

  private val worker = ThreadPoolExecutor(0, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
    Thread(task, "BetterPdf-document").apply { isDaemon = true }
  }

  private class Opened(val reads: DocumentReads, val pageCount: Int)

  private val lock = Any()
  private var openCalled = false
  @Volatile private var closed = false
  private var resolver: PdfSourceResolver? = null
  private var master: ParcelFileDescriptor? = null
  private var opened: Opened? = null

  /** Nominal: Nitro reads it when the object reaches JS, before `open()`. */
  override val memorySize: Long
    get() = 1L shl 20

  override fun open(
    source: NativePdfSource,
    password: String?,
    onProgress: ((event: PdfLoadProgressEvent) -> Unit)?,
  ): Promise<PdfOpenResult> {
    val promise = Promise<PdfOpenResult>()
    val resolver = PdfSourceResolver(context.applicationContext)
    synchronized(lock) {
      if (openCalled) return Promise.rejected(IllegalStateException("open() was already called"))
      openCalled = true
      // After close() the promise never resolves.
      if (closed) return promise
      this.resolver = resolver
    }
    execute(onClosed = {}) {
      val result = try {
        PdfOpenResult(document = openOnWorker(resolver, source, password, onProgress), error = null)
      } catch (_: InterruptedException) {
        // Closed while opening.
        return@execute
      } catch (failure: PdfFailure) {
        PdfOpenResult(document = null, error = failure.toPdfError())
      } catch (error: OutOfMemoryError) {
        PdfOpenResult(document = null, error = PdfFailure(PdfErrorCode.OUT_OF_MEMORY, "Out of memory while opening the PDF", cause = error).toPdfError())
      } catch (error: Exception) {
        // Malformed documents can surface as arbitrary exceptions from the
        // platform renderer; an uncaught one would end the process.
        PdfOpenResult(document = null, error = PdfFailure(PdfErrorCode.INVALID_PDF, "The PDF could not be opened", cause = error).toPdfError())
      }
      if (!closed) promise.resolve(result)
    }
    return promise
  }

  /** Resolves the source and checks that the document opens. Worker thread. */
  private fun openOnWorker(
    resolver: PdfSourceResolver,
    source: NativePdfSource,
    password: String?,
    onProgress: ((event: PdfLoadProgressEvent) -> Unit)?,
  ): PdfOpenedDocument {
    val throttle = ProgressThrottle()
    val resolved = resolver.open(source) { loadedBytes, totalBytes, isComplete ->
      if (onProgress == null || closed) return@open
      val emit = synchronized(throttle) { throttle.shouldEmit(SystemClock.uptimeMillis(), loadedBytes, isComplete) }
      if (emit) onProgress(PdfLoadProgressEvent(loadedBytes.toDouble(), totalBytes?.toDouble()))
    }
    val descriptor = resolved.descriptor
    val pageCount = try {
      // Checks the password and counts pages; nothing stays open.
      DocumentRenderer.openOrFail(descriptor.dup(), password).use { it.pageCount }
    } catch (error: Throwable) {
      descriptor.closeQuietly()
      throw error
    }
    if (pageCount <= 0) {
      descriptor.closeQuietly()
      throw PdfFailure(PdfErrorCode.INVALID_PDF, "The PDF has no pages")
    }
    synchronized(lock) {
      if (closed) {
        descriptor.closeQuietly()
        throw InterruptedException()
      }
      master = descriptor
      this.resolver = null
      opened = Opened(DocumentReads(password, ::duplicateDescriptor), pageCount)
    }
    return PdfOpenedDocument(pageCount.toDouble(), resolved.file?.path, resolved.isStale)
  }

  override fun getMetadata(): Promise<PdfDocumentMetadata> = read { it.reads.readMetadata() }

  override fun getPageSizes(): Promise<Array<PdfPageSize>> = read { it.reads.readPdfPageSizes(it.pageCount) }

  override fun getOutline(): Promise<Array<PdfOutlineEntry>> = read { it.reads.readOutlineEntries() }

  override fun renderPage(page: Double, options: PdfRenderPageOptions): Promise<PdfPageImage> {
    val pageCount = synchronized(lock) { opened?.pageCount } ?: return Promise.rejected(notOpen())
    val request = try {
      PageImageRequest.of(page, pageCount, options)
    } catch (error: IllegalArgumentException) {
      return Promise.rejected(error)
    }
    val context = context
    return read { request.render(it.reads, context, request.page) }
  }

  override fun close() {
    val (resolver, master) = synchronized(lock) {
      if (closed) return
      closed = true
      val pending = resolver to master
      this.resolver = null
      this.master = null
      opened = null
      pending
    }
    resolver?.cancel()
    master?.closeQuietly()
    // Queued calls still run and reject; reads in progress keep their own
    // duplicate of the descriptor and finish.
    worker.shutdown()
  }

  override fun dispose() {
    close()
    super.dispose()
  }

  private fun <T> read(block: (Opened) -> T): Promise<T> {
    val opened = synchronized(lock) { opened } ?: return Promise.rejected(notOpen())
    val promise = Promise<T>()
    execute(onClosed = { promise.reject(notOpen()) }) {
      try {
        promise.resolve(block(opened))
      } catch (error: Throwable) {
        promise.reject(error)
      }
    }
    return promise
  }

  /** Runs [task] on the worker, or [onClosed] instead once the document is closed. */
  private fun execute(onClosed: () -> Unit, task: () -> Unit) {
    try {
      worker.execute { if (closed) onClosed() else task() }
    } catch (_: RejectedExecutionException) {
      onClosed()
    }
  }

  /** A duplicate of the open document's descriptor, the caller's to close. */
  private fun duplicateDescriptor(): ParcelFileDescriptor =
    synchronized(lock) { master?.dup() } ?: throw notOpen()

  private fun notOpen() = IllegalStateException(if (closed) "The document is closed" else "The document is not open")

  private companion object {
    /** The worker thread exits after this long without calls. */
    const val IDLE_SECONDS = 5L
  }
}

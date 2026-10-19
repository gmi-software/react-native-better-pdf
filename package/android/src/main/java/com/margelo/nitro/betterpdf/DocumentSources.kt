package com.margelo.nitro.betterpdf

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Trace
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The files behind a [PdfDocumentSession]'s document, one per source: each
 * source's descriptor and, on the session's worker thread, its renderer.
 *
 * PDFium keeps every object it parsed for a page (for scanned documents: the
 * compressed image data) for the lifetime of the document, even after the
 * page is closed. Measured: opening all pages of a 395 MB, 1000-page scan
 * retained 400 MB of native heap. Hence a source's renderer is re-created
 * from a duplicate of its descriptor every
 * [PdfDocumentSession.RECYCLE_AFTER_PAGES] page opens, which releases
 * everything PDFium cached. Creating a renderer is cheap (measured 3–27 ms,
 * no retained memory) because it does not touch pages; so at most
 * [MAX_OPEN_RENDERERS] sources keep one open.
 *
 * Threads: the worker resolves, opens and closes; reads and writes take
 * duplicates of the descriptors from any thread, the search from the main
 * thread.
 */
internal class DocumentSources(
  sources: List<NativePdfSource>,
  context: Context,
  private val openSource: PdfSourceResolver.(NativePdfSource, (Long, Long?, Boolean) -> Unit) -> ResolvedSource,
  private val openRenderer: (ParcelFileDescriptor, String?) -> DocumentRenderer,
  /** The form as filled in so far, applied to every renderer opened. */
  private val formEdits: () -> List<FormEdit>,
  private val rendererRecycles: AtomicLong,
) {
  inner class Segment(val index: Int, val source: NativePdfSource) {
    val password: String? get() = source.password
    val resolver = PdfSourceResolver(appContext)
    /** Under [lock]: reads duplicate it on other threads. */
    var master: ParcelFileDescriptor? = null
    var renderer: DocumentRenderer? = null
    var pagesOpenedByRenderer = 0
    var filePath: String? = null
      private set
    var isStale = false
      private set

    /** Set once the file's links are read: the renderer's are not read then. */
    @Volatile var hasFileLinks = false

    /** Outline, page sizes and page images, each on its own duplicate of the descriptor. */
    val reads = DocumentReads(source.password) { duplicate(this) }

    fun resolved(file: ResolvedSource) {
      filePath = file.file?.path
      isStale = file.isStale
      synchronized(lock) { master = file.descriptor }
    }
  }

  private val appContext = context.applicationContext
  private val lock = Any()
  val segments = sources.mapIndexed { index, source -> Segment(index, source) }

  /** The source whose file is the whole document; null with several. */
  val single: Segment? = segments.singleOrNull()

  /** Segments with an open renderer, least recently used first. Worker thread. */
  private val openRenderers = ArrayDeque<Segment>()

  /**
   * Resolves every source to a descriptor, the first on the calling thread
   * and the others [MAX_PARALLEL_OPENS] at a time (downloads take their
   * time). The first that fails cancels the others, and is the failure
   * reported. Returns once no source is resolving any more.
   */
  fun resolveAll(progress: SourcesProgress) {
    if (segments.isEmpty()) throw PdfFailure(PdfErrorCode.INVALID_SOURCE, "No PDF source was given", sourceIndex = 0)
    val failure = AtomicReference<Throwable?>()
    fun resolveOrCancel(segment: Segment) {
      try {
        segment.attributed {
          val file = segment.resolver.openSource(segment.source) { loadedBytes, totalBytes, isComplete ->
            progress.report(segment.index, loadedBytes, totalBytes, isComplete)
          }
          segment.resolved(file)
        }
      } catch (error: Throwable) {
        if (failure.compareAndSet(null, error)) cancel()
      }
    }
    val others = segments.drop(1)
    val pool = if (others.isEmpty()) null else Executors.newFixedThreadPool(minOf(others.size, MAX_PARALLEL_OPENS)) {
      Thread(it, "BetterPdf-open").apply { isDaemon = true }
    }
    try {
      others.forEach { segment -> pool!!.execute { resolveOrCancel(segment) } }
      resolveOrCancel(segments[0])
    } catch (error: Throwable) {
      // No thread could be started for a source.
      if (failure.compareAndSet(null, error)) cancel()
    } finally {
      pool?.shutdown()
      // cancel() (also from the session's close()) makes every resolve end,
      // so none sets a descriptor after the session closes them.
      while (pool?.awaitTermination(1, TimeUnit.SECONDS) == false) continue
    }
    failure.get()?.let { throw it }
  }

  /** Opens every source's renderer, which stays open: its page count and the size of its first page. Worker thread. */
  fun openFirstPages(): List<Pair<Int, PdfDocumentLayout.PageSize>> = segments.map { segment ->
    segment.attributed {
      val renderer = rendererFor(segment)
      val pageCount = renderer.pageCount
      if (pageCount <= 0) throw PdfFailure(PdfErrorCode.INVALID_PDF, "The PDF has no pages")
      val size = renderer.openPage(0).use { page -> PdfDocumentLayout.PageSize(page.width.toFloat(), page.height.toFloat()) }
      segment.pagesOpenedByRenderer = 1
      pageCount to size
    }
  }

  /** Opens [at] with its source's renderer; [counted] page opens count towards re-creating it. Worker thread. */
  fun openPage(at: SourcePage, counted: Boolean): DocumentPage {
    val segment = segments[at.source]
    val page = rendererFor(segment).openPage(at.page)
    if (counted) segment.pagesOpenedByRenderer++
    return page
  }

  /**
   * The renderer to open [segment]'s next page with: opened when it has
   * none, re-created when PDFium's cache has grown. Room is made first, so
   * no more than [MAX_OPEN_RENDERERS] are ever open.
   */
  private fun rendererFor(segment: Segment): DocumentRenderer {
    val current = segment.renderer
    if (current != null && segment.pagesOpenedByRenderer < PdfDocumentSession.RECYCLE_AFTER_PAGES) {
      if (openRenderers.lastOrNull() !== segment) {
        openRenderers.remove(segment)
        openRenderers.addLast(segment)
      }
      return current
    }
    Trace.beginSection("BetterPdf#recycleRenderer")
    try {
      closeRenderer(segment)
      while (openRenderers.size >= MAX_OPEN_RENDERERS) closeRenderer(openRenderers.first())
      val descriptor = synchronized(lock) { segment.master } ?: throw IllegalStateException("Document closed")
      return openRenderer(descriptor.dup(), segment.password).also {
        segment.renderer = it
        segment.pagesOpenedByRenderer = 0
        if (current != null) rendererRecycles.incrementAndGet()
        // It starts from the file: every page's form as filled in, before it renders.
        it.applyEdits(formEdits())
        openRenderers.addLast(segment)
      }
    } finally {
      Trace.endSection()
    }
  }

  private fun closeRenderer(segment: Segment) {
    try {
      segment.renderer?.close()
    } catch (_: RuntimeException) {
    }
    segment.renderer = null
    openRenderers.remove(segment)
  }

  /**
   * A duplicate of [segment]'s open descriptor, ours to close. Taken under
   * the lock so the worker cannot close (and the OS reuse) the descriptor
   * number in between.
   */
  fun duplicate(segment: Segment): ParcelFileDescriptor =
    synchronized(lock) { segment.master?.dup() } ?: throw IllegalStateException("The document is not open")

  /** A duplicate of every source's descriptor, for a search; null unless all could be taken. Main thread: never throws. */
  fun duplicateAll(): List<PdfSearchTask.Source>? {
    val taken = ArrayList<PdfSearchTask.Source>(segments.size)
    try {
      synchronized(lock) {
        for (segment in segments) {
          val descriptor = segment.master?.dup() ?: break
          taken.add(PdfSearchTask.Source(descriptor, segment.password))
        }
      }
    } catch (_: IOException) {
    }
    if (taken.size == segments.size) return taken
    taken.forEach { it.descriptor.closeQuietly() }
    return null
  }

  /** Stops resolving the sources: downloads in progress end. */
  fun cancel() = segments.forEach { it.resolver.cancel() }

  /** Closes every renderer and descriptor. Worker thread. */
  fun closeAll() = segments.forEach { segment ->
    closeRenderer(segment)
    synchronized(lock) {
      segment.master?.closeQuietly()
      segment.master = null
    }
  }

  /** [block]'s failure as one of this source's: what cannot be opened is named by its index. */
  private inline fun <T> Segment.attributed(block: () -> T): T =
    try {
      block()
    } catch (failure: PdfFailure) {
      throw if (failure.sourceIndex == null) failure.ofSource(index) else failure
    } catch (error: OutOfMemoryError) {
      throw PdfFailure(PdfErrorCode.OUT_OF_MEMORY, "Out of memory while opening the PDF", cause = error, sourceIndex = index)
    } catch (error: IOException) {
      throw PdfFailure(PdfErrorCode.INVALID_PDF, "The PDF could not be opened", cause = error, sourceIndex = index)
    } catch (error: RuntimeException) {
      // Malformed documents can surface as arbitrary runtime exceptions from
      // the platform renderer; never let them crash the app.
      throw PdfFailure(PdfErrorCode.INVALID_PDF, "The PDF could not be opened", cause = error, sourceIndex = index)
    }

  internal companion object {
    /**
     * Sources with an open renderer at a time: the pages around the reader
     * lie in at most two, so swiping across a source's end opens none.
     */
    const val MAX_OPEN_RENDERERS = 3

    /** Sources resolved at a time besides the first; the rest wait. */
    const val MAX_PARALLEL_OPENS = 4
  }
}

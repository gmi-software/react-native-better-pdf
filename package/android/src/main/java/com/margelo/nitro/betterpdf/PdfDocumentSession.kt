package com.margelo.nitro.betterpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Trace
import com.margelo.nitro.betterpdf.outline.FormFieldEntry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One open document: resolves the source, owns the [DocumentRenderer] and its file
 * descriptor, and renders pages (and resolves text selections) on a single
 * dedicated thread.
 *
 * Why a single thread: `PdfRenderer` allows only one open page at a time, so
 * parallel rendering of one document is impossible. Why a replaceable queue
 * instead of an executor: once `Page.render` starts it cannot be interrupted,
 * so the only effective cancellation is to drop work *before* it starts. The
 * UI thread re-submits the full wanted list on every viewport change and
 * anything no longer wanted is discarded.
 *
 * Lifecycle: every native resource (renderer, FD, open page) is created and
 * closed on the worker thread. [close] only signals; the worker finishes the
 * render in progress (bounded by viewport-sized bitmaps), then closes the
 * renderer, which closes the FD. Listener callbacks are delivered on the main
 * thread and never after [close].
 */
class PdfDocumentSession internal constructor(
  context: Context,
  private val source: NativePdfSource,
  private val password: String?,
  private val bitmapPool: BitmapPool,
  private val listener: Listener,
  // What needs a device; JVM tests (PdfDocumentSessionTest) pass their own.
  private val mainThread: Executor = Handler(Looper.getMainLooper()).let { handler -> Executor { handler.post(it) } },
  private val openSource: PdfSourceResolver.(NativePdfSource, (Long, Long?, Boolean) -> Unit) -> ResolvedSource = PdfSourceResolver::open,
  private val openRenderer: (ParcelFileDescriptor, String?) -> DocumentRenderer = DocumentRenderer::openOrFail,
) {
  interface Listener {
    /**
     * [pageSizes] holds the real size of page 0 and an estimate (page 0's
     * size) for every other page; see [onPageMeasured].
     */
    /**
     * [filePath]: the local file being displayed, when there is one.
     * [isStale]: a remote document's outdated copy, shown because the server
     * could not be reached.
     */
    fun onOpened(pageSizes: List<PdfDocumentLayout.PageSize>, filePath: String?, isStale: Boolean)

    /** A page was opened for the first time and its size differs from the estimate. */
    fun onPageMeasured(page: Int, size: PdfDocumentLayout.PageSize)

    /** A page was opened for the first time and has links (the renderer's, until [onDocumentLinks]). */
    fun onPageLinks(page: Int, links: List<PageLink>)

    /** Every page's links, read from the file; they replace the renderer's. */
    fun onDocumentLinks(links: Map<Int, List<PageLink>>)

    fun onFailed(failure: PdfFailure)

    /** Download progress of a remote source, throttled by [ProgressThrottle]. */
    fun onLoadProgress(loadedBytes: Long, totalBytes: Long?)
    fun onRendered(request: RenderRequest, bitmap: Bitmap)
    fun onRenderFailed(request: RenderRequest, failure: PdfFailure)

    /** [selection] is null where [query] selects no text, or when the page could not be read. */
    fun onTextSelected(query: TextSelectionQuery, selection: PageTextSelection?)

    /** [text] is null where the renderer exposes no text (before Android 15) or it could not be read. */
    fun onPageText(page: Int, text: String?)

    /** The renderer's form widgets of [page]: once it renders, again after each change to the form there. */
    fun onPageFormWidgets(page: Int, widgets: List<FormWidget>)

    /** The form changed on [page]; its pixels are stale. */
    fun onFormPageChanged(page: Int)

    /** The reader changed [field] (with its new value). */
    fun onFormFieldChanged(field: FormFieldEntry)
  }

  private val resolver = PdfSourceResolver(context.applicationContext)
  private val cacheDirectory: File = context.applicationContext.cacheDir
  private val queue = RenderQueue<DocumentWork>()
  private val closed: Boolean get() = queue.isClosed

  val rendersCancelled: Long get() = queue.cancelled
  val pagesRendered = AtomicLong()
  val rendererRecycles = AtomicLong()
  private val renderNanos = AtomicLong()

  val meanRenderMs: Double
    get() {
      val count = pagesRendered.get()
      return if (count == 0L) -1.0 else renderNanos.get() / count / 1_000_000.0
    }

  private val thread = Thread(::run, "BetterPdf-render").apply { isDaemon = true }

  fun start() = thread.start()

  /**
   * Replaces the queue with [requests] (highest priority first). Queued
   * requests that are no longer wanted are dropped and counted as cancelled.
   */
  fun submit(requests: List<DocumentWork>) = queue.submit(requests)

  /**
   * Resolves [query] before any queued render, replacing a query that has
   * not started (a handle drag sends one per move). The result arrives
   * through [Listener.onTextSelected].
   */
  fun selectText(query: TextSelectionQuery) = queue.submitUrgent(query)

  /** Applies the reader's [edit], after earlier form work; see [Listener.onFormFieldChanged]. */
  fun editForm(edit: FormEdit) = queue.submitInOrder(FormWork.ReaderEdit(edit))

  /** The form's fields with their current values, after earlier form work; [done] on the main thread. */
  fun readFormFields(done: (Result<List<FormFieldEntry>>) -> Unit) = submitForm(FormWork.ReadFields(done))

  /** Sets fields' values (all or none of them); [done] on the main thread. */
  fun setFormValues(values: List<NativePdfFormValue>, done: (Throwable?) -> Unit) = submitForm(FormWork.SetValues(values, done))

  /**
   * [done] on the main thread once earlier form work is done (writes and
   * page images see it), with an error instead when the document closed.
   */
  fun afterFormWork(done: (Throwable?) -> Unit) = submitForm(FormWork.Barrier(done))

  /** Edits made to the form so far, for renderers of other threads. */
  val formEdits: List<FormEdit> get() = form.edits

  private fun submitForm(work: FormWork) {
    if (closed) work.fail(IllegalStateException("The document is closed")) else queue.submitInOrder(work)
  }

  /** Outline, metadata, page sizes and page images, each on its own duplicate of the descriptor. */
  internal val reads = DocumentReads(password, ::duplicateDescriptor)

  private val readWorker = SerialWorker("BetterPdf-read")
  private val writeWorker = SerialWorker("BetterPdf-write")

  /**
   * Runs [work], a read of [reads], on the document's read thread: one at
   * a time, in call order, like `PdfDocument`'s reads, so a burst of page
   * images holds one renderer and one bitmap at a time, and the first ones
   * arrive first. [done] gets the result on that thread; once the document
   * is closed, an error instead.
   */
  fun <T> read(work: () -> T, done: (Result<T>) -> Unit) = readWorker.run(work, done)

  /** Like [read], on a thread for writes (exports), which take seconds for long documents. */
  fun <T> write(work: () -> T, done: (Result<T>) -> Unit) = writeWorker.run(work, done)

  /**
   * Writes the document to [destination] for printing: the file as it is,
   * or, when it was opened with a password, a copy without the protection
   * written by a separate renderer (the print spooler opens documents
   * without a password). The copy goes straight to [destination], never to
   * disk. Devices that cannot open protected documents opened this one
   * without the password, so the file as it is prints. Positional reads
   * leave the shared file offset alone. Blocking: call off the main
   * thread. Does not close [destination].
   */
  fun writeForPrinting(destination: ParcelFileDescriptor) {
    val edits = formEdits
    // A filled-in form prints as shown: the edits applied to a new renderer.
    if ((!password.isNullOrEmpty() || edits.isNotEmpty()) && DocumentRenderer.supportsPasswords) {
      DocumentRenderer.open(duplicateDescriptor(), password).use {
        it.applyEdits(edits)
        it.writeUnprotected(destination)
      }
      return
    }
    copyFile(destination)
  }

  /**
   * Writes the document as shown — its form filled in, its password
   * protection kept — to [destination], with a renderer of its own; devices
   * that cannot write documents (or fill forms in) copy the file. Blocking:
   * call off the main thread. Does not close [destination].
   */
  fun writeDocument(destination: ParcelFileDescriptor) {
    if (!DocumentRenderer.supportsForms) {
      copyFile(destination)
      return
    }
    DocumentRenderer.open(duplicateDescriptor(), password).use {
      it.applyEdits(formEdits)
      it.writeProtected(destination)
    }
  }

  /** The file as it is, by positional reads (the shared file offset stays). */
  private fun copyFile(destination: ParcelFileDescriptor) {
    ParcelFileDescriptor.AutoCloseInputStream(duplicateDescriptor()).channel.use { source ->
      val target = Channels.newChannel(FileOutputStream(destination.fileDescriptor))
      val size = source.size()
      var position = 0L
      while (position < size) {
        val written = source.transferTo(position, size - position, target)
        if (written <= 0) throw IOException("The document could not be written")
        position += written
      }
    }
  }

  /**
   * A duplicate of the open document's descriptor, ours to close. Taken
   * under the lock so the worker cannot close (and the OS reuse) the
   * descriptor number in between.
   */
  private fun duplicateDescriptor(): ParcelFileDescriptor =
    synchronized(descriptorLock) { master?.dup() } ?: throw IllegalStateException("The document is not open")

  /**
   * Starts searching the open document on its own thread (see
   * [PdfSearchTask]); null when the document is not open. Main thread.
   */
  internal fun startSearch(query: String, pageCount: Int, startPage: Int, pages: Int, listener: PdfSearchTask.Listener): PdfSearchTask? {
    // On the main thread: a failed dup (descriptors exhausted) must not throw.
    val descriptor = try {
      synchronized(descriptorLock) { master?.dup() }
    } catch (_: IOException) {
      null
    } ?: return null
    return PdfSearchTask(descriptor, password, pageCount, startPage, pages, query, listener).also { it.start() }
  }

  fun close() {
    if (closed) return
    val dropped = queue.close()
    // Reads and writes in progress finish on their own duplicate of the descriptor.
    readWorker.close()
    writeWorker.close()
    resolver.cancel()
    // Posts already queued stay: `post` drops listener calls once closed and
    // releases their bitmaps, and form work's results still settle promises.
    dropped.forEach { (it as? FormWork)?.fail(IllegalStateException("The document was closed")) }
  }

  // region Worker thread

  /*
   * Worker-thread state. PDFium keeps every object it parsed for a page (for
   * scanned documents: the compressed image data) for the lifetime of the
   * document, even after the page is closed. Measured: opening all pages of a
   * 395 MB, 1000-page scan retained 400 MB of native heap. Hence:
   * - page sizes are not measured up front; each page is measured when it is
   *   first opened for rendering, and
   * - the renderer is re-created from a duplicate of the original file
   *   descriptor every [RECYCLE_AFTER_PAGES] page opens, which releases
   *   everything PDFium cached. Creating a renderer is cheap (measured 3–27 ms,
   *   no retained memory) because it does not touch pages.
   */
  private var master: ParcelFileDescriptor? = null
  /** Guards [master] against [reads] duplicating it while it closes. */
  private val descriptorLock = Any()
  private var renderer: DocumentRenderer? = null
  private var pagesOpenedByRenderer = 0
  private var filePath: String? = null
  private var isStale = false
  private lateinit var knownSizes: Array<PdfDocumentLayout.PageSize>
  private lateinit var measured: BooleanArray
  private lateinit var linksRead: BooleanArray
  private lateinit var formWidgetsRead: BooleanArray
  /** How pages rendered, which picks the next [PixelGuess]; render thread only. */
  private lateinit var colourPages: BooleanArray
  private lateinit var greyPages: BooleanArray
  private var lastNewPageHadColour = false
  /** The renderer can show and edit a form here (decided at open). */
  private var canFillForm = false
  private val form = DocumentForm { reads.readFormFields(cacheDirectory) }

  private fun run() {
    try {
      Trace.beginSection("BetterPdf#open")
      val firstPage = try {
        open()
      } finally {
        Trace.endSection()
      }
      if (closed) return
      val sizes = List(knownSizes.size) { firstPage }
      val path = filePath
      val stale = isStale
      post { listener.onOpened(sizes, path, stale) }
      readLinksFromFile(sizes.size)
      renderLoop()
    } catch (_: InterruptedException) {
      // Closed while resolving the source.
    } catch (failure: PdfFailure) {
      post { listener.onFailed(failure) }
    } catch (error: OutOfMemoryError) {
      post { listener.onFailed(PdfFailure(PdfErrorCode.OUT_OF_MEMORY, "Out of memory while opening the PDF", cause = error)) }
    } catch (error: Exception) {
      // Malformed documents can surface as arbitrary runtime exceptions from
      // the platform renderer, and copying a source can fail with I/O errors
      // (Kotlin does not make anyone catch those); never let them crash the app.
      post { listener.onFailed(PdfFailure(PdfErrorCode.INVALID_PDF, "The PDF could not be opened", cause = error)) }
    } finally {
      closeRenderer()
      synchronized(descriptorLock) {
        master?.closeQuietly()
        master = null
      }
      // When this thread stops on its own (the document did not open), close
      // as the owner would, so queued form work fails instead of waiting for
      // good. Posted, so what this thread posted arrives first; a no-op once
      // closed.
      mainThread.execute { close() }
    }
  }

  /** Opens the document and returns the size of page 0. */
  private fun open(): PdfDocumentLayout.PageSize {
    val progressThrottle = ProgressThrottle()
    val resolved = resolver.openSource(source) { loadedBytes, totalBytes, isComplete ->
      if (progressThrottle.shouldEmit(SystemClock.uptimeMillis(), loadedBytes, isComplete)) {
        post { listener.onLoadProgress(loadedBytes, totalBytes) }
      }
    }
    val descriptor = resolved.descriptor
    filePath = resolved.file?.path
    isStale = resolved.isStale
    synchronized(descriptorLock) { master = descriptor }
    if (closed) throw InterruptedException()
    val renderer = createRenderer(descriptor.dup())
    this.renderer = renderer
    canFillForm = try {
      renderer.canFillForm
    } catch (_: RuntimeException) {
      false
    }
    val pageCount = renderer.pageCount
    if (pageCount <= 0) throw PdfFailure(PdfErrorCode.INVALID_PDF, "The PDF has no pages")
    val firstPage = renderer.openPage(0).use { page -> PdfDocumentLayout.PageSize(page.width.toFloat(), page.height.toFloat()) }
    pagesOpenedByRenderer = 1
    knownSizes = Array(pageCount) { firstPage }
    measured = BooleanArray(pageCount).also { it[0] = true }
    linksRead = BooleanArray(pageCount)
    formWidgetsRead = BooleanArray(pageCount)
    colourPages = BooleanArray(pageCount)
    greyPages = BooleanArray(pageCount)
    return firstPage
  }

  private fun createRenderer(descriptor: ParcelFileDescriptor): DocumentRenderer =
    openRenderer(descriptor, password)

  /** The renderer to open the next page with, re-created when PDFium's cache has grown. */
  private fun rendererForNextPage(): DocumentRenderer {
    val current = renderer
    if (current != null && pagesOpenedByRenderer < RECYCLE_AFTER_PAGES) return current
    Trace.beginSection("BetterPdf#recycleRenderer")
    try {
      closeRenderer()
      val descriptor = master ?: throw IllegalStateException("Document closed")
      return createRenderer(descriptor.dup()).also {
        renderer = it
        pagesOpenedByRenderer = 0
        rendererRecycles.incrementAndGet()
        // It starts from the file: every page's form as filled in, before it renders.
        it.applyEdits(form.edits)
      }
    } finally {
      Trace.endSection()
    }
  }

  private fun closeRenderer() {
    try {
      renderer?.close()
    } catch (_: RuntimeException) {
    }
    renderer = null
  }

  private fun renderLoop() {
    while (true) {
      val work = queue.take() ?: return
      try {
        when (work) {
          is RenderRequest -> render(work)
          is TextSelectionQuery -> resolveSelection(work)
          is PageTextQuery -> readPageText(work)
          is FormWork -> doFormWork(work)
        }
      } catch (error: InterruptedException) {
        throw error
      } catch (error: Throwable) {
        // An Error the work's own handling lets through (out of memory where
        // none was expected, a stack overflow): only this work fails.
        failWork(work, error)
      } finally {
        queue.finish()
      }
    }
  }

  /** Reports [work] as failed with [error]: its listener call, or its promise. */
  private fun failWork(work: DocumentWork, error: Throwable) {
    when (work) {
      is RenderRequest ->
        postRenderFailed(work, PdfFailure(PdfErrorCode.PAGE_RENDER_FAILED, "Rendering page ${work.page} failed", page = work.page, cause = error))
      is TextSelectionQuery -> post { listener.onTextSelected(work, null) }
      is PageTextQuery -> post { listener.onPageText(work.page, null) }
      is FormWork -> mainThread.execute { work.fail(IllegalStateException("The form could not be read or changed", error)) }
    }
  }

  private fun resolveSelection(query: TextSelectionQuery) {
    Trace.beginSection("BetterPdf#selectText")
    val selection = try {
      // Not counted towards RECYCLE_AFTER_PAGES: a handle drag re-opens the
      // same, already parsed page for every move (~100 per second).
      val page = rendererForNextPage().openPage(query.page)
      page.use {
        recordSize(query.page, PdfDocumentLayout.PageSize(page.width.toFloat(), page.height.toFloat()))
        recordLinks(query.page, page)
        page.selectText(query)
      }
    } catch (_: Exception) {
      // A page whose text cannot be read (or a renderer that could not be
      // re-created) has nothing to select.
      null
    } finally {
      Trace.endSection()
    }
    post { listener.onTextSelected(query, selection) }
  }

  private fun readPageText(query: PageTextQuery) {
    val text = try {
      rendererForNextPage().openPage(query.page).use { page ->
        recordSize(query.page, PdfDocumentLayout.PageSize(page.width.toFloat(), page.height.toFloat()))
        page.readText()
      }
    } catch (_: Exception) {
      // A page whose text cannot be read is announced by its label only.
      null
    }
    post { listener.onPageText(query.page, text) }
  }

  private fun render(request: RenderRequest) {
    val start = SystemClock.elapsedRealtimeNanos()
    var bitmap: Bitmap? = null
    Trace.beginSection("BetterPdf#renderPage")
    try {
      val page = rendererForNextPage().openPage(request.page)
      pagesOpenedByRenderer++
      page.use {
        val size = PdfDocumentLayout.PageSize(page.width.toFloat(), page.height.toFloat())
        recordSize(request.page, size)
        recordLinks(request.page, page)
        recordFormWidgets(request.page, page)
        val isNew = !colourPages[request.page] && !greyPages[request.page]
        val guess = when {
          colourPages[request.page] -> PixelGuess.COLOUR
          greyPages[request.page] -> PixelGuess.PROBABLY_GREY
          lastNewPageHadColour -> PixelGuess.PROBABLY_COLOUR
          else -> PixelGuess.PROBABLY_GREY
        }
        val target = when (request) {
          is RenderRequest.Base -> {
            val (width, height) = baseBitmapSize(request, size)
            val matrix = Matrix().apply { setScale(width / size.widthPoints, height / size.heightPoints) }
            page.renderPixels(bitmapPool, width, height, width, height, matrix, guess)
          }
          is RenderRequest.Tile -> {
            val matrix = Matrix().apply {
              setScale(request.scale, request.scale)
              postTranslate(request.dx, request.dy)
            }
            page.renderPixels(bitmapPool, request.bitmapWidth, request.bitmapHeight, request.usedWidth, request.usedHeight, matrix, guess)
          }
        }
        bitmap = target
        val hasColour = target.config == Bitmap.Config.ARGB_8888
        if (hasColour) colourPages[request.page] = true else greyPages[request.page] = true
        if (isNew) lastNewPageHadColour = hasColour
        pagesRendered.incrementAndGet()
        renderNanos.addAndGet(SystemClock.elapsedRealtimeNanos() - start)
        bitmap = null
        post(onDropped = { bitmapPool.release(target) }) { listener.onRendered(request, target) }
      }
    } catch (error: OutOfMemoryError) {
      postRenderFailed(request, PdfFailure(PdfErrorCode.OUT_OF_MEMORY, "Out of memory rendering page ${request.page}", page = request.page, cause = error))
    } catch (error: Exception) {
      // Also a renderer that could not be re-created (see rendererForNextPage):
      // this page fails, the next work creates one again.
      postRenderFailed(request, PdfFailure(PdfErrorCode.PAGE_RENDER_FAILED, "Rendering page ${request.page} failed", page = request.page, cause = error))
    } finally {
      bitmap?.let(bitmapPool::release)
      Trace.endSection()
    }
  }

  /** After [RenderQueue.finish]: a retry the listener submits must not be dropped as the request in flight. */
  private fun postRenderFailed(request: RenderRequest, failure: PdfFailure) {
    queue.finish()
    post { listener.onRenderFailed(request, failure) }
  }

  private fun recordSize(page: Int, size: PdfDocumentLayout.PageSize) {
    if (measured[page]) return
    measured[page] = true
    if (knownSizes[page] == size) return
    knownSizes[page] = size
    post { listener.onPageMeasured(page, size) }
  }

  /** Set once the file's links are read: the renderer's are not read then. */
  @Volatile private var hasFileLinks = false

  /**
   * Reads every page's links from the file, on a thread of its own (its own
   * duplicate of the descriptor) so the first pages render meanwhile. A file
   * the parser cannot read (encrypted, malformed) keeps the renderer's links.
   */
  private fun readLinksFromFile(pageCount: Int) {
    Thread({
      val links = try {
        reads.readLinks(pageCount)
      } catch (_: Exception) {
        return@Thread
      } catch (_: OutOfMemoryError) {
        return@Thread
      }
      hasFileLinks = true
      post { listener.onDocumentLinks(links) }
    }, "BetterPdf-links").apply { isDaemon = true }.start()
  }

  /**
   * Reads a page's links the first time it is opened, while it is open anyway
   * for rendering. A malformed link annotation must not cost the page its
   * pixels, so read failures leave the page without links (its taps are then
   * reported as page taps).
   */
  private fun recordLinks(page: Int, openPage: DocumentPage) {
    if (linksRead[page] || hasFileLinks) return
    linksRead[page] = true
    val links = try {
      openPage.readLinks()
    } catch (_: RuntimeException) {
      emptyList()
    }
    if (links.isNotEmpty()) post { listener.onPageLinks(page, links) }
  }

  /** Reads a page's form widgets the first time it renders (it is open anyway). */
  private fun recordFormWidgets(page: Int, openPage: DocumentPage) {
    if (!canFillForm || formWidgetsRead[page]) return
    formWidgetsRead[page] = true
    val widgets = try {
      openPage.formWidgets()
    } catch (_: RuntimeException) {
      return
    }
    form.updatePage(page, widgets)
    if (widgets.isNotEmpty()) post { listener.onPageFormWidgets(page, widgets) }
  }

  private fun doFormWork(work: FormWork) {
    when (work) {
      is FormWork.ReaderEdit -> applyReaderEdit(work.edit)
      is FormWork.Barrier -> post(onDropped = { work.done(IllegalStateException("The document was closed")) }) { work.done(null) }
      is FormWork.ReadFields -> {
        val result = try {
          Result.success(form.fields())
        } catch (error: Exception) {
          Result.failure(IllegalStateException("The form could not be read", error))
        } catch (error: OutOfMemoryError) {
          Result.failure(IllegalStateException("Out of memory while reading the form", error))
        }
        mainThread.execute { work.done(result) }
      }
      is FormWork.SetValues -> {
        val error = try {
          setFormValues(work.values)
          null
        } catch (error: Exception) {
          error
        } catch (error: OutOfMemoryError) {
          IllegalStateException("Out of memory while filling the form in", error)
        }
        mainThread.execute { work.done(error) }
      }
    }
  }

  private fun applyReaderEdit(edit: FormEdit) {
    if (!canFillForm) return
    try {
      applyEdits(listOf(edit))
    } catch (_: Exception) {
      // A widget that does not take the edit (read-only) stays as it is.
      return
    }
    val field = try {
      form.fields()
      form.fieldAt(edit.page, edit.widgetIndex)
    } catch (_: Exception) {
      null
    } ?: return
    post { listener.onFormFieldChanged(field) }
  }

  private fun setFormValues(values: List<NativePdfFormValue>) {
    if (!canFillForm) {
      throw UnsupportedOperationException("Filling forms requires Android 15, or Android 12–14 with the updated PDF module (SDK extension 13)")
    }
    val fields = form.fields()
    val targets = values.map { value ->
      val index = fields.indexOfFirst { it.name == value.name }
      if (index < 0) throw FormValueException("The form has no field named \"${value.name}\"")
      index to value
    }
    // Each field's pages are read first: edits depend on what they show.
    targets.flatMap { (index, _) -> fields[index].widgets.map { it.page } }.distinct()
      .filter { form.widgetsOf(it) == null }
      .forEach(::readFormWidgets)
    // Every value is checked before anything changes.
    val edits = targets.flatMap { (index, value) -> form.fields()[index].plannedEdits(value, form.placedWidgets(index)) }
    applyEdits(edits)
  }

  /** Applies [edits] in order, keeps them, and reads the changed pages again. */
  private fun applyEdits(edits: List<FormEdit>) {
    var index = 0
    while (index < edits.size) {
      val pageIndex = edits[index].page
      rendererForNextPage().openPage(pageIndex).use { page ->
        while (index < edits.size && edits[index].page == pageIndex) {
          page.applyFormEdit(edits[index])
          form.record(edits[index])
          index++
        }
        val widgets = page.formWidgets()
        form.updatePage(pageIndex, widgets)
        formWidgetsRead[pageIndex] = true
        post {
          listener.onPageFormWidgets(pageIndex, widgets)
          listener.onFormPageChanged(pageIndex)
        }
      }
    }
  }

  private fun readFormWidgets(pageIndex: Int) {
    val widgets = rendererForNextPage().openPage(pageIndex).use { it.formWidgets() }
    form.updatePage(pageIndex, widgets)
    formWidgetsRead[pageIndex] = true
    if (widgets.isNotEmpty()) post { listener.onPageFormWidgets(pageIndex, widgets) }
  }

  /** Fit-width bitmap at the page's real aspect ratio, capped at the request's pixel budget. */
  private fun baseBitmapSize(request: RenderRequest.Base, size: PdfDocumentLayout.PageSize): Pair<Int, Int> {
    val safe = size.sanitized()
    var width = request.bitmapWidth.toDouble()
    var height = width * safe.heightPoints / safe.widthPoints
    val pixels = width * height
    if (pixels > request.maxPixels) {
      val factor = sqrt(request.maxPixels / pixels)
      width *= factor
      height *= factor
    }
    return max(1, width.roundToInt()) to max(1, height.roundToInt())
  }

  /** Runs [block] on the main thread unless the session was closed meanwhile. */
  private inline fun post(crossinline onDropped: () -> Unit = {}, crossinline block: () -> Unit) {
    mainThread.execute {
      if (closed) onDropped() else block()
    }
  }

  // endregion

  internal companion object {
    /**
     * Page opens per renderer before it is re-created. Bounds PDFium's
     * per-document object cache to roughly this many pages' worth of parsed
     * objects. Not tuned: the value is a bound whose effect (flat memory over
     * a 5-minute scroll soak) is verified in docs/performance/results.md.
     */
    const val RECYCLE_AFTER_PAGES = 48
  }
}

package com.margelo.nitro.betterpdf

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import com.margelo.nitro.betterpdf.TextSelectionQuery.Boundary
import com.margelo.nitro.betterpdf.outline.FormFieldEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The render thread of [PdfDocumentSession] with a fake renderer. The test
 * thread is the main thread: it runs what the session posts, in order, in
 * [runUntil]. On the JVM `Bitmap.createBitmap` returns null, so renders
 * reach the renderer and then fail; a render callback shows the work ran.
 */
class PdfDocumentSessionTest {
  private val mainThread = LinkedBlockingQueue<Runnable>()
  private val listener = RecordingListener()
  private val renderer = FakeRenderer()

  @Test
  fun `an Error while selecting text fails only the selection`() {
    val selecting = CountDownLatch(1)
    val queued = CountDownLatch(1)
    renderer.onSelectText = {
      selecting.countDown()
      // The form work and the render below are queued behind the selection.
      queued.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      throw OutOfMemoryError("Selecting text")
    }
    val session = start()
    runUntil("the document to open") { listener.opened }

    val query = TextSelectionQuery(generation = 1, page = 1, start = Boundary.Point(10f, 10f), stop = Boundary.Point(50f, 10f))
    session.selectText(query)
    assertTrue(selecting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    var fields: Result<List<FormFieldEntry>>? = null
    session.readFormFields { fields = it }
    var formWorkDone = false
    var formWorkError: Throwable? = null
    session.afterFormWork {
      formWorkDone = true
      formWorkError = it
    }
    val render = RenderRequest.Base(page = 2, bitmapWidth = 100, bitmapHeight = 100, maxPixels = 10_000)
    session.submit(listOf(render))
    queued.countDown()

    runUntil("the form read to settle") { fields != null }
    runUntil("the form work to finish") { formWorkDone }
    runUntil("the render to run") { render in listener.renderResults }
    assertEquals(listOf(query to null), listener.selections)
    assertNull(formWorkError)
    assertTrue(2 in renderer.openedPages)
    assertEquals(emptyList<PdfFailure>(), listener.failures)
    session.close()
  }

  @Test
  fun `an Error while rendering or reading text fails only that work`() {
    renderer.onOpenPage = { if (it == 1) throw StackOverflowError("Opening page 1") }
    val session = start()
    runUntil("the document to open") { listener.opened }

    val failing = RenderRequest.Base(page = 1, bitmapWidth = 100, bitmapHeight = 100, maxPixels = 10_000)
    val next = RenderRequest.Base(page = 2, bitmapWidth = 100, bitmapHeight = 100, maxPixels = 10_000)
    session.submit(listOf(failing, PageTextQuery(page = 1), next))

    runUntil("the next render to run") { next in listener.renderResults }
    val failure = listener.renderFailures.getValue(failing)
    assertEquals(PdfErrorCode.PAGE_RENDER_FAILED, failure.code)
    assertEquals(1, failure.page)
    assertTrue(failure.cause is StackOverflowError)
    assertEquals(listOf(1 to null), listener.pageTexts)
    assertEquals(emptyList<PdfFailure>(), listener.failures)
    session.close()
  }

  @Test
  fun `a render submitted again from its failure report runs again`() {
    val failing = RenderRequest.Base(page = 1, bitmapWidth = 100, bitmapHeight = 100, maxPixels = 10_000)
    val attempts = AtomicInteger()
    val retried = CountDownLatch(1)
    renderer.onOpenPage = { if (it == 1 && attempts.incrementAndGet() == 2) retried.countDown() }
    lateinit var session: PdfDocumentSession
    listener.afterRenderFailed = { request -> if (attempts.get() == 1) session.submit(listOf(request)) }
    // The listener runs before the render thread moves on, as when the main thread is quicker.
    session = start(runOnMain = Executor { it.run() })

    session.submit(listOf(failing))
    assertTrue("The render did not run again", retried.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    session.close()
  }

  @Test
  fun `a document that could not be opened settles form work`() {
    val session = start(openRenderer = { _, _ -> throw PdfFailure(PdfErrorCode.INVALID_PDF, "Not a PDF") })

    runUntil("the failure") { listener.failures.isNotEmpty() }
    assertEquals(PdfErrorCode.INVALID_PDF, listener.failures.single().code)
    var fields: Result<List<FormFieldEntry>>? = null
    session.readFormFields { fields = it }
    runUntil("the form read to settle") { fields != null }
    assertTrue(fields!!.isFailure)
  }

  @Test
  fun `reads run one at a time on one thread, in call order`() {
    val session = start()
    runUntil("the document to open") { listener.opened }

    val running = AtomicInteger()
    val mostAtOnce = AtomicInteger()
    val threads = ConcurrentHashMap.newKeySet<Thread>()
    val results = LinkedBlockingQueue<Result<Int>>()
    repeat(READS) { index ->
      session.read({
        mostAtOnce.accumulateAndGet(running.incrementAndGet()) { most, now -> maxOf(most, now) }
        threads += Thread.currentThread()
        Thread.sleep(2)
        running.decrementAndGet()
        index
      }) { results.add(it) }
    }

    val settled = List(READS) { results.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: throw AssertionError("Timed out waiting for read $it") }
    assertEquals(List(READS) { Result.success(it) }, settled)
    assertEquals(1, mostAtOnce.get())
    assertEquals(listOf("BetterPdf-read"), threads.map { it.name })
    session.close()
  }

  @Test
  fun `reads queued when the document closes fail without running`() {
    val session = start()
    runUntil("the document to open") { listener.opened }

    val reading = CountDownLatch(1)
    val release = CountDownLatch(1)
    val ran = ConcurrentLinkedQueue<Int>()
    val results = LinkedBlockingQueue<Pair<Int, Result<Int>>>()
    session.read({
      reading.countDown()
      release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      ran += 0
      0
    }) { results.add(0 to it) }
    assertTrue(reading.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    for (index in 1..3) session.read({ ran += index; index }) { results.add(index to it) }
    session.close()
    session.read({ ran += 4; 4 }) { results.add(4 to it) }
    release.countDown()

    val settled = List(5) { results.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: throw AssertionError("Timed out waiting for a read") }.toMap()
    // The read in progress finishes.
    assertEquals(Result.success(0), settled[0])
    for (index in 1..4) assertTrue(settled.getValue(index).exceptionOrNull() is IllegalStateException)
    assertEquals(listOf(0), ran.toList())
  }

  @Test
  fun `a write does not wait for reads`() {
    val session = start()
    runUntil("the document to open") { listener.opened }

    val reading = CountDownLatch(1)
    val release = CountDownLatch(1)
    session.read({
      reading.countDown()
      release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }) {}
    assertTrue(reading.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    val written = LinkedBlockingQueue<Result<String>>()
    session.write({ Thread.currentThread().name }) { written.add(it) }

    assertEquals(Result.success("BetterPdf-write"), written.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    release.countDown()
    session.close()
  }

  @Test
  fun `pages of several sources open on their source's renderer at its own page`() {
    val renderers = listOf(FakeRenderer(pageCount = 3), FakeRenderer(pageCount = 12), FakeRenderer(pageCount = 5))
    val session = start(sources = 3, openRenderer = { _, password -> renderers[password!!.toInt()] })
    runUntil("the document to open") { listener.opened }

    val requests = listOf(4, 15).map { RenderRequest.Base(page = it, bitmapWidth = 100, bitmapHeight = 100, maxPixels = 10_000) }
    session.submit(requests)

    runUntil("the renders to run") { listener.renderResults.containsAll(requests) }
    assertTrue(1 in renderers[1].openedPages)
    assertTrue(0 in renderers[2].openedPages)
    assertEquals(listOf(0), renderers[0].openedPages.toList())
    session.close()
  }

  @Test
  fun `at most three sources keep a renderer open`() {
    val open = AtomicInteger()
    val mostOpen = AtomicInteger()
    val session = start(sources = 5, openRenderer = { _, _ ->
      mostOpen.accumulateAndGet(open.incrementAndGet()) { most, now -> maxOf(most, now) }
      FakeRenderer(pageCount = 1, onClose = { open.decrementAndGet() })
    })
    runUntil("the document to open") { listener.opened }

    val requests = (0 until 5).map { RenderRequest.Base(page = it, bitmapWidth = 100, bitmapHeight = 100, maxPixels = 10_000) }
    session.submit(requests)

    runUntil("the renders to run") { listener.renderResults.containsAll(requests) }
    assertEquals(DocumentSources.MAX_OPEN_RENDERERS, mostOpen.get())
    session.close()
  }

  @Test
  fun `a renderer that fails for one of several sources reports that source`() {
    start(sources = 3, openRenderer = { _, password ->
      if (password == "2") throw PdfFailure(PdfErrorCode.PASSWORD_REQUIRED, "Locked") else FakeRenderer()
    })

    runUntil("the failure") { listener.failures.isNotEmpty() }
    assertEquals(PdfErrorCode.PASSWORD_REQUIRED, listener.failures.single().code)
    assertEquals(2, listener.failures.single().sourceIndex)
  }

  @Test
  fun `a source that cannot be resolved reports its index`() {
    start(sources = 3, openSource = { source -> if (source.uri == "/1.pdf") throw PdfFailure(PdfErrorCode.FILE_NOT_FOUND, "Missing") else null })

    runUntil("the failure") { listener.failures.isNotEmpty() }
    assertEquals(PdfErrorCode.FILE_NOT_FOUND, listener.failures.single().code)
    assertEquals(1, listener.failures.single().sourceIndex)
  }

  @Test
  fun `running out of memory resolving one of several sources reports that source`() {
    start(sources = 3, openSource = { source -> if (source.uri == "/1.pdf") throw OutOfMemoryError("Resolving") else null })

    runUntil("the failure") { listener.failures.isNotEmpty() }
    assertEquals(PdfErrorCode.OUT_OF_MEMORY, listener.failures.single().code)
    assertEquals(1, listener.failures.single().sourceIndex)
  }

  @Test
  fun `sources resolve a few at a time`() {
    val resolving = AtomicInteger()
    val mostResolving = AtomicInteger()
    start(sources = 20, openRenderer = { _, _ -> FakeRenderer(pageCount = 1) }, openSource = {
      mostResolving.accumulateAndGet(resolving.incrementAndGet()) { most, now -> maxOf(most, now) }
      Thread.sleep(20)
      resolving.decrementAndGet()
      null
    })
    runUntil("the document to open") { listener.opened }

    // The first on the session's thread, the others on the pool.
    assertTrue(mostResolving.get() in 2..DocumentSources.MAX_PARALLEL_OPENS + 1)
  }

  @Test
  fun `running out of memory over the whole document names a source only when there is one`() {
    // Too many pages for the document's page arrays, though each source opens.
    start(sources = 1, openRenderer = { _, _ -> FakeRenderer(pageCount = 2_000_000_000) })
    runUntil("the failure") { listener.failures.size == 1 }
    assertEquals(PdfErrorCode.OUT_OF_MEMORY, listener.failures.last().code)
    assertEquals(0, listener.failures.last().sourceIndex)

    start(sources = 2, openRenderer = { _, _ -> FakeRenderer(pageCount = 1_000_000_000) })
    runUntil("the failure") { listener.failures.size == 2 }
    assertEquals(PdfErrorCode.OUT_OF_MEMORY, listener.failures.last().code)
    assertNull(listener.failures.last().sourceIndex)
  }

  /**
   * A session of [sources] files, `/0.pdf`, `/1.pdf` …, each with its index
   * as password. [openSource] may throw for a source; it returns null to
   * resolve it.
   */
  private fun start(
    sources: Int = 1,
    openRenderer: (ParcelFileDescriptor, String?) -> DocumentRenderer = { _, _ -> renderer },
    openSource: (NativePdfSource) -> Unit? = { null },
    runOnMain: Executor = Executor { mainThread.add(it) },
  ): PdfDocumentSession {
    val context = object : ContextWrapper(null) {
      override fun getApplicationContext() = this

      override fun getCacheDir() = File(System.getProperty("java.io.tmpdir")!!)
    }
    // Stands for the document's descriptor; the fake renderer never reads it.
    val descriptor = object : ParcelFileDescriptor(null as ParcelFileDescriptor?) {
      override fun dup(): ParcelFileDescriptor = this

      override fun close() = Unit
    }
    return PdfDocumentSession(
      context,
      List(sources) { NativePdfSource("/$it.pdf", base64 = null, headers = null, cache = null, maxAgeMs = null, cacheKey = null, password = "$it") },
      bitmapPool = BitmapPool(maxBytes = 0),
      listener = listener,
      mainThread = runOnMain,
      openSource = { source, _ ->
        openSource(source)
        ResolvedSource(descriptor, file = null)
      },
      openRenderer = openRenderer,
    ).also { it.start() }
  }

  /** Runs what the session posts to the main thread until [condition] holds. */
  private fun runUntil(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (!condition()) {
      val posted = mainThread.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
        ?: throw AssertionError("Timed out waiting for $what")
      posted.run()
    }
  }

  /** Listener calls, on the test thread. */
  private class RecordingListener : PdfDocumentSession.Listener {
    var opened = false
    val failures = mutableListOf<PdfFailure>()
    val renderResults = mutableListOf<RenderRequest>()
    val renderFailures = mutableMapOf<RenderRequest, PdfFailure>()
    val selections = mutableListOf<Pair<TextSelectionQuery, PageTextSelection?>>()
    val pageTexts = mutableListOf<Pair<Int, String?>>()
    var afterRenderFailed: (RenderRequest) -> Unit = {}

    override fun onOpened(pageSizes: List<PdfDocumentLayout.PageSize>, sources: List<PdfDocumentSession.OpenedSource>) {
      opened = true
    }

    override fun onFailed(failure: PdfFailure) {
      failures += failure
    }

    override fun onRendered(request: RenderRequest, bitmap: Bitmap) {
      renderResults += request
    }

    override fun onRenderFailed(request: RenderRequest, failure: PdfFailure) {
      renderResults += request
      renderFailures[request] = failure
      afterRenderFailed(request)
    }

    override fun onTextSelected(query: TextSelectionQuery, selection: PageTextSelection?) {
      selections += query to selection
    }

    override fun onPageText(page: Int, text: String?) {
      pageTexts += page to text
    }

    override fun onPageMeasured(page: Int, size: PdfDocumentLayout.PageSize) = Unit

    override fun onPageLinks(page: Int, links: List<PageLink>) = Unit

    override fun onDocumentLinks(pages: IntRange, links: Map<Int, List<PageLink>>) = Unit

    override fun onLoadProgress(loadedBytes: Long, totalBytes: Long?) = Unit

    override fun onPageFormWidgets(page: Int, widgets: List<FormWidget>) = Unit

    override fun onFormPageChanged(page: Int) = Unit

    override fun onFormFieldChanged(field: FormFieldEntry) = Unit
  }

  /** US Letter pages; set its hooks before [start]. They run on the render thread. */
  private class FakeRenderer(override val pageCount: Int = 3, private val onClose: () -> Unit = {}) : DocumentRenderer {
    var onOpenPage: (Int) -> Unit = {}
    var onSelectText: (TextSelectionQuery) -> PageTextSelection? = { null }
    val openedPages = ConcurrentLinkedQueue<Int>()

    override val canFillForm = false

    override fun openPage(index: Int): DocumentPage {
      onOpenPage(index)
      openedPages += index
      return FakePage(index)
    }

    override fun writeUnprotected(destination: ParcelFileDescriptor) = Unit

    override fun writeProtected(destination: ParcelFileDescriptor) = Unit

    override fun close() = onClose()

    private inner class FakePage(private val index: Int) : DocumentPage {
      override val width = 612
      override val height = 792

      override fun renderForDisplay(target: Bitmap, clip: Rect?, matrix: Matrix) = Unit

      override fun readLinks() = emptyList<PageLink>()

      override fun searchText(query: String): List<List<PageRect>>? = null

      override fun readText() = "Page $index"

      override fun selectText(query: TextSelectionQuery) = onSelectText(query)

      override fun formWidgets() = emptyList<FormWidget>()

      override fun applyFormEdit(edit: FormEdit) = Unit

      override fun close() = Unit
    }
  }

  private companion object {
    const val TIMEOUT_SECONDS = 5L
    const val READS = 20
  }
}

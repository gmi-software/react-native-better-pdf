package com.margelo.nitro.betterpdf

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintJob
import android.print.PrintJobInfo
import android.print.PrintManager
import android.view.View
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.betterpdf.outline.FormFieldEntry
import com.margelo.nitro.core.Promise
import com.margelo.nitro.core.resolve
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Android implementation of `BetterPdfView`.
 *
 * Threading: props, `afterUpdate` and `onDropView` arrive on the UI thread.
 * Methods called through `hybridRef` arrive on the JS thread and are posted to
 * the UI thread. Nitro dispatches callback invocations to the JS thread.
 */
@Keep
@DoNotStrip
class HybridBetterPdfView(
  val context: ThemedReactContext,
) : HybridBetterPdfViewSpec(),
  PdfDocumentSession.Listener,
  PdfDocumentView.Listener {
  private val documentView = PdfDocumentView(context).also { it.listener = this }
  private val container = DocumentContainer(context, documentView)
  private val formFiller = FormFiller(container, documentView)
  private val pageOverlays = PageOverlays(documentView, host = container).also { overlays ->
    documentView.onPagesMoved = {
      overlays.layout()
      formFiller.place()
    }
    container.onLaidOut = formFiller::place
  }
  private val searchController = SearchController(documentView) { event -> onSearchChange?.invoke(event) }
  override val view: View = container

  private val mainHandler = Handler(Looper.getMainLooper())
  private var session: PdfDocumentSession? = null
  /**
   * The session once its document has loaded, with what JS-thread methods
   * need of it — together, so a document swapped in meanwhile cannot mix
   * one session with another's page count.
   */
  private class LoadedDocument(val session: PdfDocumentSession, val pageCount: Int, val file: File?) {
    /** The displayed file's name without extension, for exported copies. */
    val name: String get() = file?.nameWithoutExtension?.takeIf { it.isNotEmpty() } ?: "document"
  }

  @Volatile private var loaded: LoadedDocument? = null
  private var needsReload = false
  private var needsScaleUpdate = false
  private var needsLayoutModeUpdate = false

  // region Props

  override var source: NativePdfSource? = null
    set(value) {
      if (!value.isSameSource(field)) needsReload = true
      field = value
    }
  override var password: String? = null
    set(value) {
      if (value != field) needsReload = true
      field = value
    }
  override var initialPosition: PdfInitialPosition? = null
  override var minScale: Double? = null
    set(value) {
      field = value
      needsScaleUpdate = true
    }
  override var maxScale: Double? = null
    set(value) {
      field = value
      needsScaleUpdate = true
    }
  override var pageSpacing: Double? = null
    set(value) {
      field = value
      documentView.setPageSpacing(value)
    }
  override var horizontal: Boolean? = null
    set(value) {
      field = value
      needsLayoutModeUpdate = true
    }
  override var enablePaging: Boolean? = null
    set(value) {
      field = value
      needsLayoutModeUpdate = true
    }
  override var enableRTL: Boolean? = null
    set(value) {
      field = value
      needsLayoutModeUpdate = true
    }
  override var singlePage: Boolean? = null
    set(value) {
      field = value
      needsLayoutModeUpdate = true
    }
  override var fitPolicy: PdfFitPolicy? = null
    set(value) {
      field = value
      needsLayoutModeUpdate = true
    }
  override var spreadMode: PdfSpreadMode? = null
    set(value) {
      field = value
      needsLayoutModeUpdate = true
    }
  override var colorScheme: PdfColorScheme? = null
    set(value) {
      field = value
      documentView.isDark = value == PdfColorScheme.DARK
    }
  override var overlayLayerId: String? = null
    set(value) {
      field = value
      pageOverlays.layerId = value
      layoutOverlaysSoon()
    }
  override var overlays: Array<PdfOverlayPlacement>? = null
    set(value) {
      field = value
      pageOverlays.placements = value ?: emptyArray()
      layoutOverlaysSoon()
    }
  override var contentInset: NativeContentInset? = null
    set(value) {
      field = value
      val density = context.resources.displayMetrics.density
      documentView.setContentInset(
        ((value?.top ?: 0.0).coerceAtLeast(0.0) * density).toFloat(),
        ((value?.bottom ?: 0.0).coerceAtLeast(0.0) * density).toFloat(),
      )
    }
  override var enableFormFilling: Boolean? = null
    set(value) {
      field = value
      documentView.isFormFillingEnabled = value ?: true
      if (value == false) formFiller.commit()
    }
  override var enableDoubleTapZoom: Boolean? = null
    set(value) {
      field = value
      documentView.isDoubleTapZoomEnabled = value ?: true
    }
  override var scrollEnabled: Boolean? = null
    set(value) {
      field = value
      documentView.isUserScrollEnabled = value ?: true
    }
  override var pageAccessibilityLabel: String? = null
    set(value) {
      field = value
      documentView.pageAccessibilityLabel = value ?: "Page {page} of {pageCount}"
    }
  // processColor output: signed on Android, unsigned on iOS; either way the
  // low 32 bits are the ARGB value (Double.toInt() would clamp instead).
  override var searchHighlightColor: Double? = null
    set(value) {
      field = value
      documentView.searchMatchColor = value?.toLong()?.toInt()
    }
  override var searchCurrentHighlightColor: Double? = null
    set(value) {
      field = value
      documentView.searchCurrentColor = value?.toLong()?.toInt()
    }
  override var showsVerticalScrollIndicator: Boolean? = null
    set(value) {
      field = value
      documentView.isVerticalScrollBarEnabled = value ?: true
    }
  override var showsHorizontalScrollIndicator: Boolean? = null
    set(value) {
      field = value
      documentView.isHorizontalScrollBarEnabled = value ?: true
    }
  override var enableTextSelection: Boolean? = null
    set(value) {
      field = value
      documentView.isTextSelectionEnabled = value ?: true
    }
  override var onLoad: ((event: PdfLoadEvent) -> Unit)? = null
  override var onLoadProgress: ((event: PdfLoadProgressEvent) -> Unit)? = null
  override var onError: ((error: PdfError) -> Unit)? = null
  override var onPageChange: ((event: PdfPageChangeEvent) -> Unit)? = null
  override var onLinkPress: ((event: PdfLinkPressEvent) -> Unit)? = null
  override var onPageTap: ((event: PdfPageTapEvent) -> Unit)? = null
  override var onScaleChange: ((event: PdfScaleChangeEvent) -> Unit)? = null
  override var onViewportChange: ((event: PdfViewportChangeEvent) -> Unit)? = null
  override var onSearchChange: ((event: PdfSearchChangeEvent) -> Unit)? = null
  override var onTextSelectionChange: ((event: PdfTextSelectionChangeEvent) -> Unit)? = null
  override var onFormChange: ((event: NativePdfFormChangeEvent) -> Unit)? = null

  // endregion

  override fun afterUpdate() {
    if (needsLayoutModeUpdate) {
      needsLayoutModeUpdate = false
      val horizontal = horizontal ?: false
      val paged = enablePaging ?: false
      val singlePage = singlePage ?: false
      // Default: fit width when scrolling vertically through pages, else whole pages.
      val fit = fitPolicy?.toLayoutFit()
        ?: if (horizontal || paged || singlePage) PdfDocumentLayout.Fit.PAGE else PdfDocumentLayout.Fit.WIDTH
      documentView.setLayoutMode(
        LayoutMode(
          horizontal,
          paged,
          fit,
          singlePage,
          isRightToLeft = enableRTL ?: false,
          spread = spreadMode?.toLayoutSpread() ?: PdfDocumentLayout.Spread.NONE,
        ),
      )
    }
    if (needsScaleUpdate) {
      needsScaleUpdate = false
      documentView.setScaleLimits(minScale, maxScale)
    }
    if (needsReload) {
      needsReload = false
      reload()
    }
  }

  override fun onDropView() {
    // No events into JS for a dropped view (clearing the document would
    // report a cleared text selection), as on iOS.
    documentView.listener = null
    documentView.onPagesMoved = null
    closeSession()
    // JS may keep this hybrid object alive (through `hybridRef`) until its GC
    // runs; hold no pixels in the meantime. (Measured on iOS: ~7.5 MB per
    // closed viewer when only the document was released.)
    documentView.bitmapPool.clear()
  }

  /**
   * Overlay views mount before the props that place them, but may get laid
   * out in the same frame: place them once React Native is done.
   */
  private fun layoutOverlaysSoon() {
    mainHandler.post { if (documentView.onPagesMoved != null) pageOverlays.layout() }
  }

  // region Methods (JS thread)

  override fun goToPage(page: Double, animated: Boolean) {
    if (!page.isFinite()) return
    mainHandler.post { documentView.goToPage(page.toInt(), animated) }
  }

  override fun zoomTo(scale: Double, animated: Boolean) {
    if (!scale.isFinite()) return
    mainHandler.post { documentView.zoomTo(scale.toFloat(), animated) }
  }

  override fun goToViewport(viewport: PdfViewport) {
    val values = listOf(viewport.page, viewport.x, viewport.y, viewport.scale)
    if (!values.all { it.isFinite() } || viewport.scale <= 0) return
    val point = PagePoint(viewport.page.toInt(), viewport.x.toFloat(), viewport.y.toFloat())
    mainHandler.post { documentView.goToViewport(point, viewport.scale.toFloat()) }
  }

  override fun search(query: String) {
    mainHandler.post { searchController.search(query) }
  }

  override fun searchNext() {
    mainHandler.post { searchController.next() }
  }

  override fun searchPrevious() {
    mainHandler.post { searchController.previous() }
  }

  override fun clearSearch() {
    mainHandler.post { searchController.clear() }
  }

  override fun getFormFields(): Promise<Array<NativePdfFormField>> {
    val session = loaded?.session ?: return Promise.rejected(IllegalStateException("No document is loaded"))
    val promise = Promise<Array<NativePdfFormField>>()
    mainHandler.post {
      // Text being typed is read too.
      formFiller.commit()
      session.readFormFields { result ->
        result.fold({ fields -> promise.resolve(fields.map { it.toNative() }.toTypedArray()) }, promise::reject)
      }
    }
    return promise
  }

  override fun setFormFieldValues(values: Array<NativePdfFormValue>): Promise<Unit> {
    val session = loaded?.session ?: return Promise.rejected(IllegalStateException("No document is loaded"))
    val promise = Promise<Unit>()
    mainHandler.post {
      // Text being typed goes first, so it does not overwrite these values.
      formFiller.commit()
      session.setFormValues(values.toList()) { error -> if (error == null) promise.resolve() else promise.reject(error) }
    }
    return promise
  }

  override fun exportDocument(filePath: String): Promise<PdfExportResult> {
    val document = loaded ?: return Promise.rejected(IllegalStateException("No document is loaded"))
    val session = document.session
    val target = if (filePath.isEmpty()) null else File(filePath)
    // As on iOS, where replacing a directory would delete everything in it.
    if (target != null && (filePath.endsWith("/") || target.isDirectory)) {
      return Promise.rejected(IllegalArgumentException("The export path is a directory, not a file: $filePath"))
    }
    // The displayed file is read while the copy is written, and is never changed.
    if (target != null && document.file != null && target.canonicalFile == document.file.canonicalFile) {
      return Promise.rejected(IllegalArgumentException("The export cannot replace the displayed document's file"))
    }
    return afterFormSettles(session) {
      val file = target ?: newExportFile(document.name)
      // Written beside the target and renamed over it: a failed write
      // leaves an existing file as it was. The copy's name does not repeat
      // the target's, which may already be close to the 255-byte limit.
      val partial = File(file.absoluteFile.parentFile, ".${UUID.randomUUID()}.partial")
      try {
        ParcelFileDescriptor.open(
          partial,
          ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).use(session::writeDocument)
        if (!partial.renameTo(file)) throw IOException("Could not write $file")
      } finally {
        partial.delete()
      }
      PdfExportResult(file.absolutePath)
    }
  }

  /** A new file in the cache directory with the document's file name. */
  private fun newExportFile(name: String): File {
    val directory = File(context.cacheDir, "BetterPdfExports/${UUID.randomUUID()}")
    if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Could not create $directory")
    return File(directory, "$name.pdf")
  }

  /**
   * Runs [block] off the main thread once text being typed is applied and
   * earlier form work is done, so it sees the form as the reader left it.
   */
  private fun <T> afterFormSettles(session: PdfDocumentSession, block: () -> T): Promise<T> {
    val promise = Promise<T>()
    mainHandler.post {
      formFiller.commit()
      session.afterFormWork { error ->
        if (error != null) {
          promise.reject(error)
        } else {
          Promise.parallel(block).then(promise::resolve).catch(promise::reject)
        }
      }
    }
    return promise
  }

  override fun getOutline(): Promise<Array<PdfOutlineEntry>> {
    val session = loaded?.session ?: return Promise.rejected(IllegalStateException("No document is loaded"))
    return Promise.parallel { session.reads.readOutlineEntries() }
  }

  override fun getPageSizes(): Promise<Array<PdfPageSize>> {
    val document = loaded ?: return Promise.rejected(IllegalStateException("No document is loaded"))
    return Promise.parallel { document.session.reads.readPdfPageSizes(document.pageCount) }
  }

  override fun renderPage(page: Double, options: PdfRenderPageOptions): Promise<PdfPageImage> {
    val document = loaded ?: return Promise.rejected(IllegalStateException("No document is loaded"))
    val session = document.session
    val request = try {
      PageImageRequest.of(page, document.pageCount, options)
    } catch (error: IllegalArgumentException) {
      return Promise.rejected(error)
    }
    return afterFormSettles(session) { request.render(session.reads, context, session.formEdits) }
  }

  override fun print(jobName: String): Promise<PdfPrintResult> {
    val document = loaded ?: return Promise.rejected(IllegalStateException("No document is loaded"))
    val promise = Promise<PdfPrintResult>()
    mainHandler.post {
      // A form prints as filled in, text being typed included.
      formFiller.commit()
      document.session.afterFormWork { error ->
        when {
          error != null -> promise.reject(error)
          // Closed or replaced meanwhile: no dialog over another screen.
          loaded !== document -> promise.reject(IllegalStateException("The document was closed"))
          else -> openPrintDialog(document.session, jobName, document.pageCount, promise)
        }
      }
    }
    return promise
  }

  private fun openPrintDialog(session: PdfDocumentSession, jobName: String, pageCount: Int, promise: Promise<PdfPrintResult>) {
    val activity = context.currentActivity
    when {
      activity == null -> promise.reject(IllegalStateException("Printing needs the app in the foreground"))
      printJob != null -> promise.reject(IllegalStateException("A print dialog is already open"))
      else -> try {
        var job: PrintJob? = null
        val adapter = PdfPrintAdapter(printFileName(jobName), pageCount, session::writeForPrinting) {
          printJob = null
          val opened = job
          if (opened == null) promise.resolve(PdfPrintResult(isSubmitted = false)) else settlePrint(opened, promise, PRINT_SETTLE_POLLS)
        }
        job = activity.getSystemService(PrintManager::class.java).print(jobName, adapter, null)
        printJob = job
      } catch (error: Exception) {
        promise.reject(error)
      }
    }
  }

  /** The job of the open print dialog (main thread). */
  private var printJob: PrintJob? = null

  /**
   * Resolves once [job] has left the state it is created in. When the
   * dialog closes, a saved or printed job is already queued or completed,
   * but a cancelled one is still "created" and turns cancelled a moment
   * later (measured: under 500 ms on API 37), so wait for that.
   */
  private fun settlePrint(job: PrintJob, promise: Promise<PdfPrintResult>, polls: Int) {
    when (job.info.state) {
      PrintJobInfo.STATE_CREATED ->
        if (polls > 0) {
          mainHandler.postDelayed({ settlePrint(job, promise, polls - 1) }, PRINT_SETTLE_INTERVAL_MS)
        } else {
          promise.resolve(PdfPrintResult(isSubmitted = false))
        }
      PrintJobInfo.STATE_CANCELED -> promise.resolve(PdfPrintResult(isSubmitted = false))
      PrintJobInfo.STATE_FAILED -> promise.reject(IllegalStateException("The print job failed"))
      else -> promise.resolve(PdfPrintResult(isSubmitted = true))
    }
  }

  /** Saving as PDF names the file after the document; it needs the extension. */
  private fun printFileName(jobName: String): String =
    if (jobName.endsWith(".pdf", ignoreCase = true)) jobName else "$jobName.pdf"

  override fun getStats(): PdfViewStats {
    val session = session
    return PdfViewStats(
      pageCount = documentView.pageCount.toDouble(),
      residentPages = documentView.statResidentPages.toDouble(),
      cacheBytes = documentView.statCacheBytes.toDouble(),
      cacheBudgetBytes = documentView.cacheBudgetBytes.toDouble(),
      cacheHits = documentView.statHits.toDouble(),
      cacheMisses = documentView.statMisses.toDouble(),
      bitmapAllocations = documentView.bitmapPool.allocations.toDouble(),
      pagesRendered = (session?.pagesRendered?.get() ?: 0L).toDouble(),
      rendersCancelled = (session?.rendersCancelled ?: 0L).toDouble(),
      meanRenderMs = session?.meanRenderMs ?: -1.0,
    )
  }

  // endregion

  // region Loading

  private fun reload() {
    closeSession()
    val source = source ?: return
    session = PdfDocumentSession(context, source, password, documentView.bitmapPool, this).also { it.start() }
  }

  private fun closeSession() {
    formFiller.cancel()
    searchController.onDocumentChanged(null)
    loaded = null
    session?.close()
    session = null
    documentView.clearDocument()
  }

  // endregion

  // region PdfDocumentSession.Listener (UI thread)

  override fun onOpened(pageSizes: List<PdfDocumentLayout.PageSize>, filePath: String?, isStale: Boolean) {
    val session = session ?: return
    val position = initialPosition
    val initial = position?.page?.takeIf { it.isFinite() }?.toInt() ?: 0
    val scale = position?.scale?.takeIf { it.isFinite() && it > 0 }?.toFloat() ?: 1f
    documentView.setDocument(session, pageSizes, initial, scale, position?.pagePoint)
    loaded = LoadedDocument(session, pageSizes.size, filePath?.let(::File))
    searchController.onDocumentChanged(session)
    val first = pageSizes.first()
    onLoad?.invoke(
      PdfLoadEvent(
        pageCount = pageSizes.size.toDouble(),
        firstPageWidth = first.widthPoints.toDouble(),
        firstPageHeight = first.heightPoints.toDouble(),
        filePath = filePath,
        isStale = isStale,
      ),
    )
  }

  override fun onLoadProgress(loadedBytes: Long, totalBytes: Long?) {
    onLoadProgress?.invoke(PdfLoadProgressEvent(loadedBytes.toDouble(), totalBytes?.toDouble()))
  }

  override fun onFailed(failure: PdfFailure) {
    onError?.invoke(failure.toPdfError())
  }

  override fun onPageMeasured(page: Int, size: PdfDocumentLayout.PageSize) {
    documentView.onPageMeasured(page, size)
  }

  override fun onPageText(page: Int, text: String?) {
    documentView.onPageText(page, text)
  }

  override fun onDocumentLinks(links: Map<Int, List<PageLink>>) {
    documentView.onDocumentLinks(links)
  }

  override fun onPageFormWidgets(page: Int, widgets: List<FormWidget>) {
    documentView.onPageFormWidgets(page, widgets)
  }

  override fun onFormPageChanged(page: Int) {
    documentView.onFormPageChanged(page)
  }

  override fun onFormFieldChanged(field: FormFieldEntry) {
    onFormChange?.invoke(NativePdfFormChangeEvent(field.toNative()))
  }

  override fun onFormWidgetTapped(page: Int, widget: FormWidget) {
    formFiller.onWidgetTapped(page, widget)
  }

  override fun onPageLinks(page: Int, links: List<PageLink>) {
    documentView.onPageLinks(page, links)
  }

  override fun onRendered(request: RenderRequest, bitmap: Bitmap) {
    documentView.onRendered(request, bitmap)
  }

  override fun onRenderFailed(request: RenderRequest, failure: PdfFailure) {
    documentView.onRenderFailed(request, failure)
  }

  override fun onTextSelected(query: TextSelectionQuery, selection: PageTextSelection?) {
    documentView.onTextSelected(query, selection)
  }

  // endregion

  // region PdfDocumentView.Listener (UI thread)

  override fun onPageChanged(page: Int, pageCount: Int) {
    onPageChange?.invoke(PdfPageChangeEvent(page.toDouble(), pageCount.toDouble()))
  }

  override fun onRenderFailed(failure: PdfFailure) {
    onError?.invoke(failure.toPdfError())
  }

  override fun onLinkPressed(url: String) {
    onLinkPress?.invoke(PdfLinkPressEvent(url))
  }

  override fun onScaleSettled(scale: Float) {
    onScaleChange?.invoke(PdfScaleChangeEvent(scale.toDouble()))
  }

  override fun onViewportSettled(viewport: DocumentViewport) {
    onViewportChange?.invoke(viewport.toPdfViewportChangeEvent())
  }

  override fun onTextSelectionChanged(text: String) {
    onTextSelectionChange?.invoke(PdfTextSelectionChangeEvent(text))
  }

  override fun onPageTapped(point: PagePoint) {
    // A tap beside the field being edited ends editing.
    formFiller.commit()
    onPageTap?.invoke(PdfPageTapEvent(point.page.toDouble(), point.x.toDouble(), point.y.toDouble()))
  }

  // endregion

  private companion object {
    const val PRINT_SETTLE_INTERVAL_MS = 50L

    /** 2 s in all. */
    const val PRINT_SETTLE_POLLS = 40
  }
}

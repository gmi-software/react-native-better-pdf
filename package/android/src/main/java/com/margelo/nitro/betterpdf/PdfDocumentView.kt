package com.margelo.nitro.betterpdf

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Android PDF viewer: one `View` that draws the visible pages of a
 * document directly onto its canvas.
 *
 * - **Virtualization**: there are no child views. Each frame draws only the
 *   pages intersecting the viewport ([PdfDocumentLayout.visibleRange]).
 * - **Base layer**: every page is rendered once at fit-width resolution (half
 *   or a quarter of it zoomed out, see [BaseResolution]) and kept in a
 *   byte-budgeted LRU ([LruByteCache]); evicted bitmaps go back to a
 *   [BitmapPool] for reuse. Grey pages take one byte per pixel ([renderPixels]).
 * - **Zoom tiles**: when zoomed beyond the base resolution, each visible page
 *   gets one viewport-sized, full-resolution crop rendered after the gesture
 *   settles. Memory stays O(viewport) no matter the zoom level.
 * - **Prefetch**: [PrefetchPlanner] orders visible pages, then pages ahead in
 *   the scroll direction, then behind. The queue is replaced on every change.
 * - **Gestures**: scroll, fling, pinch and double-tap are handled here, on the
 *   UI thread, with no JS involvement. Long press and selection handles
 *   belong to [TextSelectionController].
 * - **Scroll indicators**: the platform's own fading scroll bars, fed from the
 *   viewport state via `computeVertical/HorizontalScroll*`. The ScrollView
 *   style is the only public way to have `View` initialize its scroll bar
 *   drawables for a view created in code (`initializeScrollbars` was removed
 *   from the SDK).
 *
 * Threading: everything in this class runs on the UI thread except the
 * `@Volatile` stats fields, which the JS thread reads.
 */
@SuppressLint("ViewConstructor")
class PdfDocumentView(context: Context) :
  View(context, null, android.R.attr.scrollViewStyle),
  PdfAccessibilityHost,
  SearchController.Host {
  interface Listener {
    fun onPageChanged(page: Int, pageCount: Int)
    fun onRenderFailed(failure: PdfFailure)
    fun onLinkPressed(url: String)
    fun onPageTapped(point: PagePoint)

    /** The zoom settled at a new [scale] (relative to the fitted page). */
    fun onScaleSettled(scale: Float)

    /** The viewport settled at a new position; also once per document after its first layout. */
    fun onViewportSettled(viewport: DocumentViewport)

    /** The text selection settled at new [text]; empty when cleared. */
    fun onTextSelectionChanged(text: String)

    /** The reader tapped a form widget they can fill in. */
    fun onFormWidgetTapped(page: Int, widget: FormWidget)
  }

  var listener: Listener? = null

  /**
   * Called whenever pages move on screen — every scroll and zoom frame,
   * relayouts, and when the document is cleared. Read positions with
   * [pageRectInView].
   */
  var onPagesMoved: (() -> Unit)? = null

  // region Document state

  private var session: PdfDocumentSession? = null
  private var pageSizes: MutableList<PdfDocumentLayout.PageSize> = ArrayList()
  /** A measured page size arrived while the viewport was moving. */
  private var relayoutPending = false
  private var layout: PdfDocumentLayout? = null
  private var pendingInitialPage: Int? = null
  /** With [pendingInitialPage]: the page point to put at the top-left corner, instead of the page's top. */
  private var pendingInitialPoint: PagePoint? = null
  private val renderFailures = RenderFailures()
  /** Set once [onDocumentLinks] arrived for the document. */
  private var hasFileLinks = false

  /** Form widgets of pages rendered so far that have any. */
  private val pageWidgets = HashMap<Int, List<FormWidget>>()
  /** Pages whose form changed since their bitmap was rendered. */
  private val staleFormPages = HashSet<Int>()

  /** Taps on form widgets fill them in (else they are page taps). */
  var isFormFillingEnabled = true

  /** Links of pages rendered so far that have any (see [PdfDocumentSession.Listener.onPageLinks]). */
  private val pageLinks = HashMap<Int, List<PageLink>>()
  private var reportedOutOfMemory = false

  override val pageCount: Int get() = pageSizes.size

  /** The reported current page, 0 before a document is shown. */
  override val currentPageIndex: Int get() = currentPage.coerceAtLeast(0)

  /** Matches of the active search, highlighted on screen; null when none. */
  override var searchResults: SearchResults? = null
    set(value) {
      field = value
      invalidate()
    }
  private val searchMatchPaint = Paint().apply { color = SEARCH_MATCH_COLOR }
  private val searchCurrentPaint = Paint().apply { color = SEARCH_CURRENT_COLOR }

  /** ARGB highlight of search matches; null restores the default. */
  var searchMatchColor: Int? = null
    set(value) {
      field = value
      searchMatchPaint.color = value ?: SEARCH_MATCH_COLOR
      invalidate()
    }

  /** ARGB highlight of the current search match; null restores the default. */
  var searchCurrentColor: Int? = null
    set(value) {
      field = value
      searchCurrentPaint.color = value ?: SEARCH_CURRENT_COLOR
      invalidate()
    }

  /**
   * Pages drawn dark: lightness inverted, hues kept ([DARK_PAGES]). A colour
   * filter on drawing only — rendered bitmaps, the cache and page images
   * keep the document's colours; highlights and the selection are drawn
   * unfiltered.
   */
  var isDark = false
    set(value) {
      if (field == value) return
      field = value
      bitmapPaint.colorFilter = if (value) DARK_PAGES else null
      coveragePaint.colorFilter = if (value) DARK_COVERAGE else COVERAGE
      placeholderPaint.color = if (value) Color.BLACK else Color.WHITE
      invalidate()
    }

  // endregion

  // region Viewport state (zoomed content pixels)

  private var scale = 1f
  private var offsetX = 0f
  private var offsetY = 0f
  /** `contentInset` in view pixels, as set; applied through [clearTop] / [clearBottom]. */
  private var contentInsetTop = 0f
  private var contentInsetBottom = 0f
  private var minScale = 1f
  private var maxScale = 4f
  private var spacingPx = 8f * resources.displayMetrics.density
  private var direction = PrefetchPlanner.Direction.FORWARD
  private var axis = PdfDocumentLayout.Axis.VERTICAL
  private var isPaged = false
  private var fit = PdfDocumentLayout.Fit.WIDTH
  /** Single-page mode: only [shownPage] is laid out for scrolling and rendered; touches pass through. */
  private var isSinglePage = false
  private var isRightToLeft = false
  /** Facing pages requested; the layout applies them to vertical continuous layouts only. */
  private var spread = PdfDocumentLayout.Spread.NONE
  /** Last zoom sent through [Listener.onScaleSettled]. */
  private var lastReportedScale = 1f
  /** Last position sent through [Listener.onViewportSettled]; null until the document's first. */
  private var lastReportedViewport: DocumentViewport? = null

  /**
   * Whether dragging and flinging scroll the document. When false the drag
   * is left to the parents (a surrounding ScrollView scrolls instead); pinch,
   * double-tap, taps and programmatic navigation keep working.
   */
  var isUserScrollEnabled = true

  /**
   * Whether a double tap toggles zoom. When false, double taps are not
   * detected at all: every tap is handled on finger up, without waiting for
   * the double-tap timeout.
   */
  var isDoubleTapZoomEnabled = true
    set(value) {
      field = value
      gestureDetector.setOnDoubleTapListener(if (value) gestureListener else null)
    }

  /**
   * Whether a long press selects text. PdfRenderer exposes page text from
   * Android 15 (API 35); earlier versions keep long presses inert. The
   * detector's long press stays on either way: turning it off would make a
   * long press end as a tap.
   */
  var isTextSelectionEnabled = true
    set(value) {
      field = value
      textSelection.isEnabled = value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    }
  private var shownPage = 0
  /** Set when a fling in paged mode was turned into a page snap. */
  private var pagingFlingHandled = false
  private var currentPage = -1
  /**
   * The page a layout switch keeps: [currentPage], except that with facing
   * pages a page the reader was on stays while it is on screen — spreads
   * report the first page of the row under the centre, which on a tall view
   * can be the row below the one navigated to. Switching to spreads and
   * back returns to the reader's page.
   */
  private var readingPage = -1
  private var previousVisible: IntRange = IntRange.EMPTY

  // region Accessibility

  /**
   * Page label for screen readers; `{page}` (1-based) and `{pageCount}` are
   * replaced.
   */
  var pageAccessibilityLabel = DEFAULT_PAGE_ACCESSIBILITY_LABEL
    set(value) {
      field = value
      accessibilityHelper.invalidateRoot()
    }
  /** Text of pages read for screen readers (null: none available); bounded by [MAX_PAGE_TEXTS]. */
  private val pageTexts = HashMap<Int, String?>()
  private val pagesAwaitingText = HashSet<Int>()
  private val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
  private val accessibilityHelper = PdfAccessibilityHelper(this, this)
  private val accessibilityStateListener = AccessibilityManager.AccessibilityStateChangeListener { isEnabled ->
    if (!isEnabled) pagesAwaitingText.clear()
    accessibilityHelper.invalidateRoot()
  }
  private val touchExplorationListener = AccessibilityManager.TouchExplorationStateChangeListener { isEnabled ->
    // A screen reader just turned on: read the visible pages' text.
    if (isEnabled) requestRenders(force = true)
  }

  /** Any accessibility service (TalkBack, Switch Access, UI automation) is on. */
  private val isAccessibilityActive: Boolean get() = accessibilityManager.isEnabled

  private val isScreenReaderActive: Boolean get() = accessibilityManager.isTouchExplorationEnabled

  init {
    ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
  }

  // endregion
  private var lastSubmitted: List<DocumentWork> = emptyList()

  // endregion

  // region Memory

  val bitmapPool = BitmapPool(maxBytes = MemoryBudgets.MIN_POOL_BYTES)
  /**
   * Counts pages at 4 bytes per pixel, as the render plan does, so grey
   * pages keep the same pages in less memory. Before API 26 it counts the
   * bytes pages take: those are on the Java heap ([javaHeapCaps]).
   */
  private val cache = LruByteCache<Int, Bitmap>(
    budgetBytes = defaultCacheBudget(),
    sizeOf = { if (BITMAPS_ON_JAVA_HEAP) it.allocationByteCount.toLong() else it.width.toLong() * it.height * ARGB_BYTES_PER_PIXEL },
    onEvict = { _, bitmap -> releaseAfterFrame(bitmap) },
  )

  private class Tile(val bitmap: Bitmap, val contentRect: RectF, val usedWidth: Int, val usedHeight: Int)

  private val tiles = HashMap<Int, Tile>()
  /** After memory pressure, render only visible pages until this uptime. */
  private var constrainedUntilMs = 0L
  /** The largest base bitmap of the last render plan, in bytes. */
  private var plannedBaseBytes = 0L
  /** Whether the last plan's base layer is below screen resolution, so tiles are drawn at zoom 1. */
  private var plannedTilesAtFit = false
  private val pageFormats = PageFormats()
  /** Whether every page of the last plan is grey coverage, or expected to be ([pageFormats]). */
  private var plannedGrey = false
  private var tileGeneration = 0

  // endregion

  // region Stats (written on UI thread, read from JS thread)

  @Volatile var statResidentPages = 0
    private set
  @Volatile var statCacheBytes = 0L
    private set
  @Volatile var statHits = 0L
    private set
  @Volatile var statMisses = 0L
    private set
  val cacheBudgetBytes: Long get() = cache.budgetBytes

  // endregion

  private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  private val coveragePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = COVERAGE }
  private val placeholderPaint = Paint().apply { color = Color.WHITE }
  private val drawRect = RectF()
  private val tileSource = Rect()

  private val scroller = OverScroller(context)
  private var isTouching = false
  private var zoomAnimator: ValueAnimator? = null
  private val settleRunnable = Runnable { onSettled() }

  init {
    // Set by the ScrollView style; a PDF surface never takes key focus from touch.
    isFocusableInTouchMode = false
    isVerticalScrollBarEnabled = true
    isHorizontalScrollBarEnabled = true
  }

  private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(e: MotionEvent): Boolean = true

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
      if (scaleDetector.isInProgress) return true
      if (!isUserScrollEnabled) return false
      scrollByContent(distanceX, distanceY)
      return true
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
      if (scaleDetector.isInProgress) return true
      if (!isUserScrollEnabled) return false
      val mainVelocity = if (isHorizontal) velocityX else velocityY
      val bounds = scrollBounds()
      if (isPaged && !isZoomedIn) {
        pagingFlingHandled = true
        val page = nearestPage()
        val position = mainOffset / scale
        val scrollsWithinPage = if (mainVelocity < 0) {
          position < pageScrollEnd(page) - 1f
        } else {
          position > pageScrollStart(page) + 1f
        }
        if (!scrollsWithinPage) {
          val target = pageForFling(mainVelocity)
          // Going back in scroll order lands on the end of the previous page.
          snapToPage(target, atEnd = layout?.let { it.slotStart(target) < it.slotStart(page) } ?: false)
          return true
        }
        // A page longer than the view scrolls freely up to its edges first.
        val start = pageScrollStart(page) * scale
        val end = pageScrollEnd(page) * scale
        if (isHorizontal) bounds.set(start, bounds.top, end, bounds.bottom) else bounds.set(bounds.left, start, bounds.right, end)
      }
      scroller.fling(
        offsetX.roundToInt(), offsetY.roundToInt(),
        -velocityX.roundToInt(), -velocityY.roundToInt(),
        bounds.left.roundToInt(), bounds.right.roundToInt(),
        bounds.top.roundToInt(), bounds.bottom.roundToInt(),
      )
      if (mainVelocity != 0f) {
        direction = directionForOffsetChange(-mainVelocity)
      }
      postInvalidateOnAnimation()
      return true
    }

    // Confirmed only after the double-tap window, so a double tap zooms
    // without also reporting a page tap.
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
      if (isDoubleTapZoomEnabled) handleTap(e.x, e.y)
      return true
    }

    // Nothing to wait for without double-tap zoom.
    override fun onSingleTapUp(e: MotionEvent): Boolean {
      if (!isDoubleTapZoomEnabled) handleTap(e.x, e.y)
      return true
    }

    override fun onLongPress(e: MotionEvent) {
      if (!scaleDetector.isInProgress) textSelection.onLongPress(e.x, e.y)
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
      val target = if (scale > minScale * 1.05f) minScale else min(maxScale, max(minScale * 2f, 2f))
      animateZoom(target, e.x, e.y)
      return true
    }
  }

  private val gestureDetector = GestureDetector(context, gestureListener)

  private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
      // Pinches are ours even when drags go to the parents.
      parent?.requestDisallowInterceptTouchEvent(true)
      return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
      zoomAround(scale * detector.scaleFactor, detector.focusX, detector.focusY)
      return true
    }
  }).apply {
    // Double-tap-and-drag would conflict with double-tap zoom.
    isQuickScaleEnabled = false
  }

  private val textSelection = TextSelectionController(
    this,
    object : TextSelectionController.Host {
      override fun mapToView(page: Int, left: Float, top: Float, right: Float, bottom: Float, out: RectF): Boolean {
        val layout = layout ?: return false
        if (page >= layout.pageCount) return false
        val pixels = layout.pointsToPixels(page)
        val pageLeft = layout.pageLeft(page)
        val pageTop = layout.pageTop(page)
        out.set(
          (pageLeft + left * pixels) * scale - offsetX,
          (pageTop + top * pixels) * scale - offsetY,
          (pageLeft + right * pixels) * scale - offsetX,
          (pageTop + bottom * pixels) * scale - offsetY,
        )
        return true
      }

      override fun pagePointAt(viewX: Float, viewY: Float): PagePoint? =
        layout?.pagePointAt((offsetX + viewX) / scale, (offsetY + viewY) / scale)

      override fun pagePointOn(page: Int, viewX: Float, viewY: Float): PagePoint? {
        val layout = layout ?: return null
        if (page >= layout.pageCount) return null
        val pixels = layout.pointsToPixels(page)
        val x = ((offsetX + viewX) / scale - layout.pageLeft(page)) / pixels
        val y = ((offsetY + viewY) / scale - layout.pageTop(page)) / pixels
        return PagePoint(page, x.coerceIn(0f, layout.pageWidth(page) / pixels), y.coerceIn(0f, layout.pageHeight(page) / pixels))
      }

      override fun pageSize(page: Int): PdfDocumentLayout.PageSize? = pageSizes.getOrNull(page)

      override fun submit(query: TextSelectionQuery) {
        session?.selectText(query)
      }

      override fun onSelectionSettled(text: String) {
        listener?.onTextSelectionChanged(text)
      }
    },
  )

  init {
    // Applies the platform check.
    isTextSelectionEnabled = true
  }

  private val memoryCallbacks = object : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
      when (TrimPolicy.of(level)) {
        TrimPolicy.Response.NONE -> Unit
        TrimPolicy.Response.RELEASE -> releaseWhileHidden()
        TrimPolicy.Response.RELEASE_AND_BACK_OFF -> trimToVisible()
      }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = trimToVisible()
  }

  // region Public API (UI thread)

  /** Starts showing [session]'s document; called once its page sizes are known. */
  fun setDocument(
    session: PdfDocumentSession,
    pageSizes: List<PdfDocumentLayout.PageSize>,
    initialPage: Int,
    initialScale: Float,
    initialPoint: PagePoint?,
  ) {
    clearDocument()
    this.session = session
    this.pageSizes = pageSizes.toMutableList()
    val page = initialPage.coerceIn(0, pageSizes.size - 1)
    pendingInitialPage = page
    // A point on a page outside the document is dropped, as in goToViewport.
    pendingInitialPoint = initialPoint?.takeIf { it.page == page }
    // Applied by rebuildLayout's initial-page branch (clamped, cross-axis centred).
    scale = initialScale
    rebuildLayout()
    // The initial zoom is not a change.
    lastReportedScale = scale
    // Hint at the document's length, as scrolling containers do on first show.
    awakenScrollBars()
  }

  /** Drops the document and every bitmap it produced. Does not close the session. */
  fun clearDocument() {
    scroller.forceFinished(true)
    zoomAnimator?.cancel()
    removeCallbacks(settleRunnable)
    textSelection.clear()
    session = null
    pageSizes = ArrayList()
    relayoutPending = false
    layout = null
    pendingInitialPage = null
    pendingInitialPoint = null
    lastReportedViewport = null
    renderFailures.clear()
    pageFormats.clear()
    plannedGrey = false
    pageLinks.clear()
    hasFileLinks = false
    pageWidgets.clear()
    staleFormPages.clear()
    pageTexts.clear()
    pagesAwaitingText.clear()
    reportedOutOfMemory = false
    cache.clear()
    clearTiles()
    scale = minScale
    offsetX = 0f
    offsetY = 0f
    currentPage = -1
    readingPage = -1
    previousVisible = IntRange.EMPTY
    lastSubmitted = emptyList()
    statHits = 0
    statMisses = 0
    updateCacheStats()
    invalidate()
    onPagesMoved?.invoke()
  }

  /**
   * Space in view pixels (not zoomed) pages scroll under, top and bottom —
   * e.g. a translucent header. Positions, the current page and the end
   * refer to the area between; vertical continuous layouts only.
   */
  fun setContentInset(topPx: Float, bottomPx: Float) {
    if (topPx == contentInsetTop && bottomPx == contentInsetBottom) return
    contentInsetTop = topPx
    contentInsetBottom = bottomPx
    // Keeps the reader's place at the top of the area, and fits pages into it.
    rebuildLayout()
  }

  /** The space kept clear at the bottom now, in view pixels. */
  val bottomClearance: Float get() = clearBottom

  fun setScaleLimits(min: Double?, max: Double?) {
    val newMin = min?.toFloat()?.takeIf { it.isFinite() && it > 0f } ?: 1f
    val newMax = (max?.toFloat()?.takeIf { it.isFinite() } ?: 4f).coerceAtLeast(newMin)
    if (newMin == minScale && newMax == maxScale) return
    minScale = newMin
    maxScale = newMax
    zoomAround(scale, width / 2f, areaCenterY)
  }

  fun setPageSpacing(spacingDp: Double?) {
    val px = (spacingDp?.toFloat()?.takeIf { it.isFinite() && it >= 0f } ?: 8f) * resources.displayMetrics.density
    if (px == spacingPx) return
    spacingPx = px
    rebuildLayout()
  }

  /** Content-space (unzoomed) rectangles of [page]'s search matches. */
  private fun drawSearchMatches(canvas: Canvas, layout: PdfDocumentLayout, page: Int) {
    val results = searchResults ?: return
    val matches = results.matchesOn(page)
    if (matches.isEmpty()) return
    val current = results.currentMatch
    val pointsToPixels = layout.pointsToPixels(page)
    val left = layout.pageLeft(page)
    val top = layout.pageTop(page)
    matches.forEachIndexed { index, rects ->
      val paint = if (current?.page == page && current.indexOnPage == index) searchCurrentPaint else searchMatchPaint
      for (rect in rects) {
        drawRect.set(
          left + rect.left * pointsToPixels,
          top + rect.top * pointsToPixels,
          left + rect.right * pointsToPixels,
          top + rect.bottom * pointsToPixels,
        )
        canvas.drawRect(drawRect, paint)
      }
    }
  }

  /**
   * Scrolls [match] into view: nothing when it is already fully visible
   * along the scroll axis, else it lands a third down the view; when zoomed
   * wider than the view it is also centred across.
   */
  override fun revealSearchMatch(match: SearchMatch) {
    val layout = layout ?: return
    if (match.page >= layout.pageCount || match.rects.isEmpty()) return
    invalidate()
    if (isSinglePage) {
      goToPage(match.page, animated = false)
      return
    }
    val pointsToPixels = layout.pointsToPixels(match.page)
    val left = layout.pageLeft(match.page) + match.rects.minOf { it.left } * pointsToPixels
    val top = layout.pageTop(match.page) + match.rects.minOf { it.top } * pointsToPixels
    val right = layout.pageLeft(match.page) + match.rects.maxOf { it.right } * pointsToPixels
    val bottom = layout.pageTop(match.page) + match.rects.maxOf { it.bottom } * pointsToPixels
    scroller.forceFinished(true)
    // Across the scroll axis (only moves when the content is wider than the view).
    if (isHorizontal) {
      setOffsets(offsetX, (top + bottom) / 2f * scale - height / 2f)
    } else {
      setOffsets((left + right) / 2f * scale - width / 2f, offsetY)
    }
    val mainStart = if (isHorizontal) left else top
    val mainEnd = if (isHorizontal) right else bottom
    val visibleStart = areaStart / scale
    val visibleEnd = (areaStart + areaLength) / scale
    if (mainStart >= visibleStart && mainEnd <= visibleEnd) {
      scheduleSettle()
      return
    }
    var target = mainStart - areaLength / scale * SEARCH_REVEAL_FRACTION
    if (isPaged && !isZoomedIn) target = target.coerceIn(pageScrollStart(match.page), pageScrollEnd(match.page))
    scrollToMain(target, animated = true)
  }

  /**
   * Jumps so [point] (a page point in PDF points) is at the top-left corner,
   * at [scale]; see [DocumentViewport]. A page outside the document is
   * clamped and shown from its top.
   */
  fun goToViewport(point: PagePoint, scale: Float) {
    if (pageSizes.isEmpty()) return
    val page = point.page.coerceIn(0, pageSizes.size - 1)
    scroller.forceFinished(true)
    zoomAnimator?.cancel()
    this.scale = scale.coerceIn(minScale, maxScale)
    val layout = layout
    if (layout == null) {
      pendingInitialPage = page
      pendingInitialPoint = point.takeIf { it.page == page }
      return
    }
    if (isSinglePage) shownPage = page
    readingPage = page
    val target = if (point.page == page) point else PagePoint(page, 0f, 0f)
    setOffsets(layout.contentX(target) * this.scale, layout.contentY(target) * this.scale - clearTop)
    scheduleSettle()
  }

  fun goToPage(page: Int, animated: Boolean) {
    val layout = layout ?: run {
      pendingInitialPage = page
      pendingInitialPoint = null
      return
    }
    val target = page.coerceIn(0, layout.pageCount - 1)
    // Navigation names the reader's page, also within a spread whose first
    // page is reported (see readingPage).
    readingPage = target
    if (isSinglePage) {
      // Nothing to animate through: the other pages are not shown.
      scroller.forceFinished(true)
      shownPage = target
      setMainOffset(layout.slotStart(target) * scale)
      scheduleSettle()
      return
    }
    scrollToMain(layout.slotStart(target), animated)
  }

  /**
   * Switches between vertical and horizontal layout, continuous or paged
   * scrolling, a single static page, and how pages fit the view. Keeps the
   * current page and resets zoom. A single page uses the paged layout (page
   * centred in a viewport-sized slot) restricted to that slot.
   */
  fun setLayoutMode(mode: LayoutMode) {
    val newAxis = if (mode.isHorizontal && !mode.isSinglePage) PdfDocumentLayout.Axis.HORIZONTAL else PdfDocumentLayout.Axis.VERTICAL
    val newPaged = mode.isPaged || mode.isSinglePage
    if (
      newAxis == axis && newPaged == isPaged && mode.fit == fit &&
      mode.isSinglePage == isSinglePage && mode.isRightToLeft == isRightToLeft && mode.spread == spread
    ) {
      return
    }
    axis = newAxis
    isPaged = newPaged
    isSinglePage = mode.isSinglePage
    isRightToLeft = mode.isRightToLeft
    spread = mode.spread
    fit = mode.fit
    scroller.forceFinished(true)
    zoomAnimator?.cancel()
    textSelection.clear()
    if (layout != null) pendingInitialPage = (if (readingPage >= 0) readingPage else currentPage).coerceAtLeast(0)
    scale = minScale
    offsetX = 0f
    offsetY = 0f
    rebuildLayout()
  }

  fun onPageLinks(page: Int, links: List<PageLink>) {
    if (!hasFileLinks) pageLinks[page] = links
  }

  /** Links read from the file replace the renderer's, which miss some forms and need Android 15. */
  fun onDocumentLinks(links: Map<Int, List<PageLink>>) {
    pageLinks.clear()
    pageLinks.putAll(links)
    hasFileLinks = true
  }

  fun onPageFormWidgets(page: Int, widgets: List<FormWidget>) {
    pageWidgets[page] = widgets
  }

  /**
   * The form changed on [page]: render it again. Its bitmap stays on screen
   * until then. Results of renders that ran before the change have arrived
   * already (the render thread posts in order), so the next one is current.
   */
  fun onFormPageChanged(page: Int) {
    staleFormPages += page
    tiles.remove(page)?.let { releaseAfterFrame(it.bitmap) }
    requestRenders(force = true)
  }

  /** Applies the reader's change to the form (see [PdfDocumentSession.editForm]). */
  fun editForm(edit: FormEdit) {
    session?.editForm(edit)
  }

  /** Moves the content by ([dx], [dy]) view pixels, within the scrollable area, and reports where it settles. */
  fun panBy(dx: Float, dy: Float) {
    scrollByContent(dx, dy)
    scheduleSettle()
  }

  fun onPageText(page: Int, text: String?) {
    val layout = layout ?: return
    if (page >= layout.pageCount) return
    pageTexts[page] = text
    pagesAwaitingText -= page
    if (pageTexts.size > MAX_PAGE_TEXTS) {
      // Keep the pages nearest to the reader.
      pageTexts.keys.sortedByDescending { kotlin.math.abs(it - currentPage) }
        .take(pageTexts.size - MAX_PAGE_TEXTS)
        .forEach { pageTexts.remove(it) }
    }
    accessibilityHelper.invalidateVirtualView(page)
  }

  // region PdfAccessibilityHost

  override val accessibilityPageCount: Int get() = layout?.pageCount ?: 0

  override fun accessibilityVisiblePages(): IntRange = visiblePages()

  override fun accessibilityPageBounds(page: Int, out: RectF) {
    val layout = layout
    if (layout == null || page >= layout.pageCount) {
      out.setEmpty()
      return
    }
    out.set(
      layout.pageLeft(page) * scale - offsetX,
      layout.pageTop(page) * scale - offsetY,
      layout.pageRight(page) * scale - offsetX,
      layout.pageBottom(page) * scale - offsetY,
    )
    if (!out.intersect(0f, 0f, width.toFloat(), height.toFloat())) out.setEmpty()
  }

  override fun accessibilityPageAt(x: Float, y: Float): Int =
    layout?.pagePointAt((offsetX + x) / scale, (offsetY + y) / scale)?.page ?: -1

  override fun accessibilityPageLabel(page: Int): String = pageAccessibilityLabel
    .replace("{page}", (page + 1).toString())
    .replace("{pageCount}", accessibilityPageCount.toString())

  override fun accessibilityPageText(page: Int): String? {
    if (page !in pageTexts && pagesAwaitingText.add(page)) requestRenders()
    return pageTexts[page]
  }

  override fun dispatchHoverEvent(event: MotionEvent): Boolean =
    accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

  /** TalkBack's scroll gestures: a page in paged layouts, else most of a screen. */
  override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
    val layout = layout
    val forward = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    if (layout == null || isSinglePage || (!forward && action != AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
      return super.performAccessibilityAction(action, arguments)
    }
    if (isPaged) {
      val target = if (forward) layout.nextInScrollOrder(currentPage) else layout.previousInScrollOrder(currentPage)
      if (target !in 0 until layout.pageCount) return false
      snapToPage(target)
    } else {
      val delta = areaLength * ACCESSIBILITY_SCROLL_FRACTION * if (forward) 1 else -1
      scrollToMain((areaStart + delta) / scale, animated = true)
    }
    return true
  }

  // endregion

  fun onTextSelected(query: TextSelectionQuery, selection: PageTextSelection?) {
    textSelection.onResolved(query, selection)
  }

  /**
   * A confirmed single tap: follow an internal link natively, report a URL
   * link, or report a page tap. Taps in gaps between pages are ignored.
   */
  private fun handleTap(viewX: Float, viewY: Float) {
    // As on iOS, a tap drops the selection and still counts as a tap.
    textSelection.clear()
    val layout = layout ?: return
    val point = layout.pagePointAt((offsetX + viewX) / scale, (offsetY + viewY) / scale) ?: return
    val link = pageLinks[point.page]?.firstOrNull { it.contains(point.x, point.y) }
    val widget = if (link == null && isFormFillingEnabled) {
      pageWidgets[point.page]?.firstOrNull { it.isFillable && it.rect.contains(point.x, point.y) }
    } else {
      null
    }
    when {
      link is PageLink.Goto -> goToPage(link.page, animated = true)
      link is PageLink.Url -> listener?.onLinkPressed(link.url)
      widget != null -> listener?.onFormWidgetTapped(point.page, widget)
      else -> listener?.onPageTapped(point)
    }
  }

  /** Scrolls so unzoomed main-axis [contentPosition] starts the area between the clearances. */
  private fun scrollToMain(contentPosition: Float, animated: Boolean, durationMs: Int = 300) {
    scroller.forceFinished(true)
    val target = clampMain(contentPosition * scale - clearTop)
    if (!animated) {
      setMainOffset(target)
      scheduleSettle()
      return
    }
    // Animating across hundreds of pages would render every page on the way.
    // Jump to one viewport before the target and animate the rest.
    val distance = target - mainOffset
    if (abs(distance) > mainViewportLength * 3) {
      setMainOffset(target - mainViewportLength * Math.signum(distance))
    }
    val delta = (target - mainOffset).roundToInt()
    if (delta == 0) {
      scheduleSettle()
      return
    }
    direction = directionForOffsetChange(delta.toFloat())
    scroller.startScroll(
      offsetX.roundToInt(), offsetY.roundToInt(),
      if (isHorizontal) delta else 0, if (isHorizontal) 0 else delta,
      durationMs,
    )
    postInvalidateOnAnimation()
  }

  /** Paged mode: the page the viewport should settle on after a fling. */
  private fun pageForFling(mainVelocity: Float): Int {
    val layout = layout ?: return 0
    val position = mainOffset / scale
    val start = layout.slotAt(position)
    val alignedWithStart = abs(position - layout.slotStart(start)) < 1f
    val target = when {
      abs(mainVelocity) < minimumPagingFlingVelocity -> layout.slotAt(position + mainViewportLength / scale / 2f)
      mainVelocity < 0 -> layout.nextInScrollOrder(start)
      alignedWithStart -> layout.previousInScrollOrder(start)
      else -> start
    }
    return target.coerceIn(0, layout.pageCount - 1)
  }

  /** Paged mode: scrolls to the start (or, going back, the end) of [page]. */
  private fun snapToPage(page: Int, atEnd: Boolean = false) {
    val target = if (atEnd) pageScrollEnd(page) else pageScrollStart(page)
    scrollToMain(target, animated = true, durationMs = PAGE_SNAP_DURATION_MS)
  }

  /**
   * Paged mode: scrolls back inside the nearest page's scroll range when
   * outside it. Returns whether it started scrolling.
   */
  private fun settleOnPage(): Boolean {
    if (layout == null) return false
    val page = nearestPage()
    val position = mainOffset / scale
    val target = position.coerceIn(pageScrollStart(page), pageScrollEnd(page))
    if (abs(target - position) * scale <= 1f) return false
    scrollToMain(target, animated = true, durationMs = PAGE_SNAP_DURATION_MS)
    return true
  }

  /** Paged mode: first main-axis content position showing [page]. */
  private fun pageScrollStart(page: Int): Float = layout?.slotStart(page) ?: 0f

  /** Paged mode: last main-axis content position still showing only [page]. */
  private fun pageScrollEnd(page: Int): Float {
    val layout = layout ?: return 0f
    return max(layout.slotStart(page), layout.slotEnd(page) - mainViewportLength / scale)
  }

  /** Paged mode: the page whose slot holds the viewport centre. */
  private fun nearestPage(): Int {
    val layout = layout ?: return 0
    return layout.slotAt((mainOffset + mainViewportLength / 2f) / scale)
  }

  fun zoomTo(target: Float, animated: Boolean) {
    if (animated) {
      animateZoom(target, width / 2f, areaCenterY)
    } else {
      zoomAround(target, width / 2f, areaCenterY)
      scheduleSettle()
    }
  }

  // endregion

  // region Session callbacks (UI thread)

  fun onRendered(request: RenderRequest, bitmap: Bitmap) {
    val layout = layout
    if (layout == null || request.page >= layout.pageCount) {
      bitmapPool.release(bitmap)
      return
    }
    renderFailures.onRendered(request)
    if (!isAttachedToWindow) {
      bitmapPool.release(bitmap)
      return
    }
    when (request) {
      is RenderRequest.Base -> {
        // Finished after the window hid (the queue was dropped then); renders again on return.
        if (windowVisibility != VISIBLE && request.page !in visiblePages()) {
          bitmapPool.release(bitmap)
          return
        }
        cache.put(request.page, bitmap, protectedKeys = visiblePages().toSet())
        pageFormats.onRendered(request.page, bitmap.isCoverage)
        staleFormPages -= request.page
        updateCacheStats()
      }
      is RenderRequest.Tile -> {
        if (request.generation != tileGeneration || request.page !in visiblePages()) {
          bitmapPool.release(bitmap)
          return
        }
        val contentRect = RectF(
          request.contentLeft,
          request.contentTop,
          request.contentLeft + request.usedWidth / request.viewScale,
          request.contentTop + request.usedHeight / request.viewScale,
        )
        tiles.put(request.page, Tile(bitmap, contentRect, request.usedWidth, request.usedHeight))
          ?.let { releaseAfterFrame(it.bitmap) }
      }
    }
    invalidate()
    requestRenders()
  }

  fun onRenderFailed(request: RenderRequest, failure: PdfFailure) {
    if (failure.code == PdfErrorCode.OUT_OF_MEMORY) {
      trimToVisible()
      if (!reportedOutOfMemory) {
        reportedOutOfMemory = true
        listener?.onRenderFailed(failure)
      }
      return
    }
    if (renderFailures.onFailed(request)) {
      listener?.onRenderFailed(failure)
    } else {
      requestRenders(force = true)
    }
  }

  /**
   * Page sizes start as estimates (page 0's size) and are corrected as pages
   * are first rendered. Relayout keeps the top-most visible page anchored;
   * while a fling or zoom animation runs it waits for the viewport to settle
   * so the scroller's coordinates are not moved underneath it.
   */
  fun onPageMeasured(page: Int, size: PdfDocumentLayout.PageSize) {
    if (page >= pageSizes.size) return
    pageSizes[page] = size
    if (isTouching || !scroller.isFinished || zoomAnimator != null) {
      relayoutPending = true
    } else {
      rebuildLayout()
    }
  }

  // endregion

  // region View lifecycle

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    accessibilityManager.addAccessibilityStateChangeListener(accessibilityStateListener)
    accessibilityManager.addTouchExplorationStateChangeListener(touchExplorationListener)
    context.applicationContext.registerComponentCallbacks(memoryCallbacks)
    bitmapPool.setMaxBytes(poolBudget())
    requestRenders(force = true)
  }

  override fun onDetachedFromWindow() {
    // Hidden screens (e.g. under a native stack) are detached; hold no pixels
    // while invisible. The document stays open and re-renders on attach.
    context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
    accessibilityManager.removeAccessibilityStateChangeListener(accessibilityStateListener)
    accessibilityManager.removeTouchExplorationStateChangeListener(touchExplorationListener)
    scroller.forceFinished(true)
    zoomAnimator?.cancel()
    removeCallbacks(settleRunnable)
    // Also ends the selection toolbar, which must not outlive the window.
    textSelection.clear()
    session?.submit(emptyList())
    lastSubmitted = emptyList()
    // Pool budget 0 first: evicted bitmaps (including releases still queued
    // for the next frame) are dropped instead of being pooled while hidden.
    bitmapPool.clear()
    cache.clear()
    clearTiles()
    updateCacheStats()
    super.onDetachedFromWindow()
  }

  override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    if (visibility == VISIBLE) {
      requestRenders()
    } else {
      session?.submit(emptyList())
      lastSubmitted = emptyList()
    }
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    cache.updateBudget(defaultCacheBudget(), visiblePages().toSet())
    if (isAttachedToWindow) bitmapPool.setMaxBytes(poolBudget())
    if (w != oldw || (layoutDependsOnHeight && h != oldh)) {
      // A fling or zoom animation would go on in the old layout's coordinates (rotation).
      scroller.forceFinished(true)
      zoomAnimator?.cancel()
      rebuildLayout()
    } else {
      clampAndRefresh()
    }
  }

  override fun computeVerticalScrollRange(): Int =
    if (isSinglePage) height else max(height, ((layout?.contentHeight ?: 0f) * scale + clearTop + clearBottom).roundToInt())

  override fun computeVerticalScrollOffset(): Int = if (isSinglePage) 0 else (offsetY + clearTop).roundToInt()

  override fun computeVerticalScrollExtent(): Int = height

  override fun computeHorizontalScrollRange(): Int =
    max(width, ((layout?.contentWidth ?: 0f) * scale).roundToInt())

  /** Negative while a zoomed-out page is centred; the bar is hidden then anyway. */
  override fun computeHorizontalScrollOffset(): Int = max(0, offsetX.roundToInt())

  override fun computeHorizontalScrollExtent(): Int = width

  override fun onDraw(canvas: Canvas) {
    val layout = layout ?: return
    val visible = visiblePages()
    if (visible.isEmpty()) return
    canvas.save()
    // React Native parents disable clipChildren; never paint outside our bounds.
    canvas.clipRect(0, 0, width, height)
    canvas.save()
    canvas.translate(-offsetX, -offsetY)
    canvas.scale(scale, scale)
    for (page in visible) {
      drawRect.set(layout.pageLeft(page), layout.pageTop(page), layout.pageRight(page), layout.pageBottom(page))
      val bitmap = cache.get(page)
      if (bitmap != null) {
        canvas.drawBitmap(bitmap, null, drawRect, paintFor(bitmap))
      } else {
        canvas.drawRect(drawRect, placeholderPaint)
      }
      tiles[page]?.let { tile ->
        tileSource.set(0, 0, tile.usedWidth, tile.usedHeight)
        canvas.drawBitmap(tile.bitmap, tileSource, tile.contentRect, paintFor(tile.bitmap))
      }
      drawSearchMatches(canvas, layout, page)
    }
    canvas.restore()
    textSelection.draw(canvas)
    canvas.restore()
  }

  override fun computeScroll() {
    if (scroller.computeScrollOffset()) {
      setOffsets(scroller.currX.toFloat(), scroller.currY.toFloat())
      if (scroller.isFinished) scheduleSettle() else postInvalidateOnAnimation()
    }
  }

  @SuppressLint("ClickableViewAccessibility") // No click semantics; this is a scroll surface.
  override fun onTouchEvent(event: MotionEvent): Boolean {
    // A single page is static (a thumbnail): touches go to the parents.
    if (layout == null || isSinglePage) return false
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        // Keep drags from scrolling parents — unless drags are theirs.
        if (isUserScrollEnabled) parent?.requestDisallowInterceptTouchEvent(true)
        pagingFlingHandled = false
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        removeCallbacks(settleRunnable)
        isTouching = true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isTouching = false
    }
    // A gesture that starts on a selection handle is a handle drag throughout.
    if (textSelection.onTouchEvent(event)) {
      if (!isTouching) scheduleSettle()
      return true
    }
    scaleDetector.onTouchEvent(event)
    gestureDetector.onTouchEvent(event)
    if (event.actionMasked == MotionEvent.ACTION_UP && isPaged && !isZoomedIn && !pagingFlingHandled) {
      settleOnPage()
    }
    if (!isTouching && scroller.isFinished && zoomAnimator == null) scheduleSettle()
    return true
  }

  // endregion

  // region Geometry

  private fun rebuildLayout() {
    if (width <= 0 || (layoutDependsOnHeight && height <= 0) || pageSizes.isEmpty()) {
      layout = null
      invalidate()
      onPagesMoved?.invoke()
      return
    }
    // Keep the same point of the same page at the viewport start across relayouts.
    val old = layout
    var anchorPage = -1
    var anchorFraction = 0f
    val sameMode = old != null && old.axis == axis && old.isPaged == isPaged && old.fit == fit &&
      old.isReversed == (isRightToLeft && isHorizontal) &&
      old.spread == (if (axis == PdfDocumentLayout.Axis.VERTICAL && !isPaged) spread else PdfDocumentLayout.Spread.NONE)
    if (old != null && sameMode && old.pageCount == pageSizes.size) {
      val position = areaStart / scale
      anchorPage = old.slotAt(position)
      anchorFraction = (position - old.slotStart(anchorPage)) / (old.slotEnd(anchorPage) - old.slotStart(anchorPage))
    }
    // Pages fitting the height fit between the clearances.
    val fitHeight = height - clearTop - clearBottom
    val newLayout = PdfDocumentLayout(pageSizes, width.toFloat(), spacingPx, fitHeight, axis, isPaged, fit, isRightToLeft, spread)
    layout = newLayout
    // Tiles are positioned in the old layout's coordinates.
    clearTiles()
    lastSubmitted = emptyList()

    val initial = pendingInitialPage
    val initialPoint = pendingInitialPoint
    if (initial != null && initialPoint != null && initialPoint.page in 0 until newLayout.pageCount) {
      pendingInitialPage = null
      pendingInitialPoint = null
      shownPage = initialPoint.page
      readingPage = initialPoint.page
      scale = scale.coerceIn(minScale, maxScale)
      // Clamped by clampAndRefresh below.
      offsetX = newLayout.contentX(initialPoint) * scale
      offsetY = newLayout.contentY(initialPoint) * scale - clearTop
    } else if (initial != null) {
      pendingInitialPage = null
      pendingInitialPoint = null
      shownPage = initial.coerceIn(0, newLayout.pageCount - 1)
      readingPage = shownPage
      scale = scale.coerceIn(minScale, maxScale)
      setMainOffsetRaw(clampMain(newLayout.slotStart(initial.coerceIn(0, newLayout.pageCount - 1)) * scale - clearTop))
      // Content wider than the view (pages of different widths) starts centred.
      val crossContent = (if (isHorizontal) newLayout.contentHeight else newLayout.contentWidth) * scale
      val crossViewport = (if (isHorizontal) height else width).toFloat()
      val crossOffset = max(0f, (crossContent - crossViewport) / 2f)
      if (isHorizontal) offsetY = crossOffset else offsetX = crossOffset
    } else if (anchorPage >= 0) {
      val slotLength = newLayout.slotEnd(anchorPage) - newLayout.slotStart(anchorPage)
      setMainOffsetRaw(clampMain((newLayout.slotStart(anchorPage) + anchorFraction * slotLength) * scale - clearTop))
    }
    clampAndRefresh()
    // Tiles were dropped and the position may have moved (rotation, first layout).
    scheduleSettle()
  }

  private val isHorizontal: Boolean get() = axis == PdfDocumentLayout.Axis.HORIZONTAL

  /** Prefetch direction for a main-axis offset change; RTL runs against the offset. */
  private fun directionForOffsetChange(delta: Float): PrefetchPlanner.Direction {
    val forward = (delta > 0) != (layout?.isReversed == true)
    return if (forward) PrefetchPlanner.Direction.FORWARD else PrefetchPlanner.Direction.BACKWARD
  }

  /** Whether page geometry depends on the view height, not only its width. */
  private val layoutDependsOnHeight: Boolean
    get() = isHorizontal || isPaged || fit != PdfDocumentLayout.Fit.WIDTH

  /** Scroll offset along the main axis, in zoomed pixels. */
  private val mainOffset: Float get() = if (isHorizontal) offsetX else offsetY

  private val mainViewportLength: Float get() = (if (isHorizontal) width else height).toFloat()

  /** `contentInset` applies to vertical continuous layouts only. */
  private val hasClearance: Boolean get() = !isHorizontal && !isPaged && !isSinglePage

  /** Space at the top pages scroll under, in view pixels (not zoomed). */
  private val clearTop: Float get() = if (hasClearance) contentInsetTop else 0f

  private val clearBottom: Float get() = if (hasClearance) contentInsetBottom else 0f

  /** Main-axis start of the area between the clearances, in zoomed content pixels. */
  private val areaStart: Float get() = mainOffset + clearTop

  private val areaLength: Float get() = max(1f, mainViewportLength - clearTop - clearBottom)

  /** The vertical centre of the area between the clearances, in view pixels. */
  private val areaCenterY: Float get() = clearTop + (height - clearTop - clearBottom) / 2f

  private val isZoomedIn: Boolean get() = scale > minScale * 1.01f

  private val minimumPagingFlingVelocity: Float = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f

  private fun scrollBounds(): RectF {
    val layout = layout ?: return RectF()
    val contentWidth = layout.contentWidth * scale
    val contentHeight = layout.contentHeight * scale
    // Content smaller than the view is centred, except a short vertical
    // document, which starts at the top.
    val minX = if (contentWidth < width) -(width - contentWidth) / 2f else 0f
    val maxX = if (contentWidth < width) minX else contentWidth - width
    // A vertical document may scroll its clearances' height past each end.
    val minY = if (contentHeight < height && isHorizontal) -(height - contentHeight) / 2f else -clearTop
    val maxY = if (contentHeight + clearTop + clearBottom < height) minY else contentHeight - height + clearBottom
    if (isSinglePage && shownPage < layout.pageCount) {
      // Vertical, paged layout: scrolling is confined to the shown page's slot.
      val start = layout.slotStart(shownPage) * scale
      val end = max(start, layout.slotEnd(shownPage) * scale - height)
      return RectF(minX, start, maxX, end)
    }
    return RectF(minX, minY, maxX, maxY)
  }

  private fun clampMain(position: Float): Float {
    val bounds = scrollBounds()
    return if (isHorizontal) position.coerceIn(bounds.left, bounds.right) else position.coerceIn(bounds.top, bounds.bottom)
  }

  private fun setMainOffset(position: Float) {
    if (isHorizontal) setOffsets(position, offsetY) else setOffsets(offsetX, position)
  }

  /** Sets the main offset without the viewport-change side effects (layout rebuild). */
  private fun setMainOffsetRaw(position: Float) {
    if (isHorizontal) offsetX = position else offsetY = position
  }

  private fun scrollByContent(dx: Float, dy: Float) {
    val mainDelta = if (isHorizontal) dx else dy
    if (mainDelta != 0f) {
      direction = directionForOffsetChange(mainDelta)
    }
    setOffsets(offsetX + dx, offsetY + dy)
  }

  private fun setOffsets(x: Float, y: Float) {
    val bounds = scrollBounds()
    offsetX = x.coerceIn(bounds.left, bounds.right)
    offsetY = y.coerceIn(bounds.top, bounds.bottom)
    onViewportChanged()
    awakenScrollBars()
  }

  private fun clampAndRefresh() = setOffsets(offsetX, offsetY)

  /** Zooms to [target] keeping the content under ([focusX], [focusY]) fixed. */
  private fun zoomAround(target: Float, focusX: Float, focusY: Float) {
    val newScale = target.coerceIn(minScale, maxScale)
    if (layout == null) {
      scale = newScale
      return
    }
    val contentX = (offsetX + focusX) / scale
    val contentY = (offsetY + focusY) / scale
    scale = newScale
    setOffsets(contentX * newScale - focusX, contentY * newScale - focusY)
  }

  private fun animateZoom(target: Float, focusX: Float, focusY: Float) {
    zoomAnimator?.cancel()
    val clamped = target.coerceIn(minScale, maxScale)
    zoomAnimator = ValueAnimator.ofFloat(scale, clamped).apply {
      duration = 250
      interpolator = DecelerateInterpolator()
      addUpdateListener { zoomAround(it.animatedValue as Float, focusX, focusY) }
      addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
          if (zoomAnimator === animation) zoomAnimator = null
          scheduleSettle()
        }
      })
      start()
    }
  }

  /**
   * Where the rectangle [x], [y], [width] × [height] — PDF points from the
   * top-left corner of [page], as [Listener.onPageTapped] reports them — is
   * on screen now, in this view's pixels, into [out]. False when the page is
   * not on screen.
   */
  fun pageRectInView(page: Int, x: Float, y: Float, width: Float, height: Float, out: RectF): Boolean {
    val layout = layout ?: return false
    if (page !in visiblePages()) return false
    val pixels = layout.pointsToPixels(page) * scale
    val left = layout.pageLeft(page) * scale - offsetX + x * pixels
    val top = layout.pageTop(page) * scale - offsetY + y * pixels
    out.set(left, top, left + width * pixels, top + height * pixels)
    return true
  }

  private fun visiblePages(): IntRange {
    val layout = layout ?: return IntRange.EMPTY
    if (width <= 0 || height <= 0) return IntRange.EMPTY
    if (isSinglePage) return if (shownPage < layout.pageCount) shownPage..shownPage else IntRange.EMPTY
    return layout.visibleRange(mainOffset / scale, (mainOffset + mainViewportLength) / scale)
  }

  // endregion

  // region Viewport → rendering

  private fun onViewportChanged() {
    val layout = layout ?: return
    val visible = visiblePages()
    if (visible != previousVisible) {
      for (page in visible) {
        if (page in previousVisible) continue
        if (cache.contains(page)) statHits++ else statMisses++
      }
      previousVisible = visible
      if (isAccessibilityActive) accessibilityHelper.invalidateRoot()
    }
    val bounds = scrollBounds()
    val mainStart = if (isHorizontal) bounds.left else bounds.top
    val mainEnd = if (isHorizontal) bounds.right else bounds.bottom
    // Scrolled to the far end: the last page, even when it is too short to
    // reach the viewport centre. In RTL the last page is at the start.
    val lastPageEdgeReached = if (layout.isReversed) {
      mainOffset <= mainStart + 1f && mainEnd > mainStart
    } else {
      mainOffset >= mainEnd - 1f && mainOffset > mainStart
    }
    val page = when {
      isSinglePage -> shownPage
      lastPageEdgeReached -> layout.pageCount - 1
      else -> layout.pageAt((areaStart + areaLength / 2f) / scale)
    }
    val keepsReadingPage = layout.spread != PdfDocumentLayout.Spread.NONE &&
      readingPage in layout.visibleRange(areaStart / scale, (areaStart + areaLength) / scale)
    if (page >= 0 && !keepsReadingPage) readingPage = page
    if (page >= 0 && page != currentPage) {
      currentPage = page
      listener?.onPageChanged(page, layout.pageCount)
      // Read by TalkBack for the focused document, like a list's position.
      ViewCompat.setStateDescription(this, accessibilityPageLabel(page))
    }
    textSelection.onViewportMoved()
    requestRenders()
    invalidate()
    onPagesMoved?.invoke()
  }

  private fun scheduleSettle() {
    removeCallbacks(settleRunnable)
    postDelayed(settleRunnable, SETTLE_DELAY_MS)
  }

  /** The viewport stopped moving: render zoom tiles for what is on screen. */
  private fun onSettled() {
    if (isTouching || !scroller.isFinished || zoomAnimator != null) return
    if (relayoutPending) {
      relayoutPending = false
      rebuildLayout()
    }
    // Paged mode: after zooming back out, settle on a page again.
    if (isPaged && !isZoomedIn && settleOnPage()) return
    if (layout != null && abs(scale - lastReportedScale) > SCALE_EPSILON) {
      lastReportedScale = scale
      listener?.onScaleSettled(scale)
    }
    reportViewport()
    tileGeneration++
    val visible = visiblePages()
    tiles.keys.filter { it !in visible }.forEach { page -> tiles.remove(page)?.let { releaseAfterFrame(it.bitmap) } }
    requestRenders(force = true)
    textSelection.onViewportSettled()
  }

  private fun reportViewport() {
    val viewport = currentViewport() ?: return
    val last = lastReportedViewport
    if (last != null && last.isCloseTo(viewport)) return
    lastReportedViewport = viewport
    listener?.onViewportSettled(viewport)
  }

  private fun currentViewport(): DocumentViewport? {
    val layout = layout ?: return null
    if (width <= 0 || height <= 0) return null
    val anchor = layout.pagePointNear(offsetX / scale, (offsetY + clearTop) / scale) ?: return null
    val visible = visiblePages()
    if (visible.isEmpty()) return null
    val isAtEnd = if (isSinglePage) {
      shownPage == layout.pageCount - 1
    } else {
      layout.isEndVisible(areaStart / scale, (areaStart + areaLength) / scale)
    }
    return DocumentViewport(anchor, scale, visible, isAtEnd)
  }

  private fun requestRenders(force: Boolean = false) {
    val session = session ?: return
    val layout = layout ?: return
    // Nothing renders while the app's UI is hidden; onWindowVisibilityChanged resumes.
    if (!isAttachedToWindow || windowVisibility != VISIBLE || width <= 0 || height <= 0) return
    val visible = visiblePages()
    val center = layout.pageAt((areaStart + areaLength / 2f) / scale)
    // A single page never shows its neighbours; don't spend memory on them.
    val planned = if (isSinglePage) visible.toList() else PrefetchPlanner.plan(visible, center, layout.pageCount, direction)

    val bases = planned.filter(renderFailures::allowsBase).map { baseRequest(layout, it) }
    val argbBytes = bases.map { it.bitmapWidth.toLong() * it.bitmapHeight * ARGB_BYTES_PER_PIXEL }
    val plannedPages = bases.map { it.page }
    plannedGrey = plannedPages.all(pageFormats::isGrey)
    val pageBytes = if (BITMAPS_ON_JAVA_HEAP) pageFormats.cacheBytes(plannedPages, argbBytes) else argbBytes
    val constrained = SystemClock.uptimeMillis() < constrainedUntilMs
    val fit = PrefetchPlanner.fitToBudget(
      pageBytes = pageBytes,
      minKeep = bases.count { it.page in visible },
      baseBudget = if (constrained) 0L else defaultCacheBudget(),
      maxBudget = if (constrained) 0L else maxCacheBudget(),
    )
    if (fit.budgetBytes != cache.budgetBytes) {
      cache.updateBudget(fit.budgetBytes, visible.toSet())
      updateCacheStats()
    }
    plannedBaseBytes = argbBytes.take(fit.keep).maxOrNull() ?: 0L
    plannedTilesAtFit = bases.take(fit.keep).any {
      baseRequest(layout, it.page, scale = 1f).bitmapWidth * TILE_THRESHOLD < layout.pageWidth(it.page)
    }
    val poolBudget = if (constrained) 0L else poolBudget()
    if (poolBudget != bitmapPool.budgetBytes) bitmapPool.setMaxBytes(poolBudget)

    val requests = ArrayList<DocumentWork>(fit.keep + visible.count() * 2)
    val settled = !isTouching && scroller.isFinished && zoomAnimator == null
    for (index in 0 until fit.keep) {
      val base = bases[index]
      val page = base.page
      val cached = cache.get(page)
      // Height follows the page's real aspect ratio (decided on the render
      // thread), so only the width identifies a stale resolution.
      if (cached == null || cached.width != base.bitmapWidth || page in staleFormPages) {
        requests += base
      }
    }
    if (settled) {
      // Tiles go after visible base pages but before prefetch.
      val tileRequests = visible.mapNotNull { page -> tileRequest(layout, page) }
      val insertAt = requests.indexOfFirst { (it as? RenderRequest)?.page !in visible }.let { if (it < 0) requests.size else it }
      requests.addAll(insertAt, tileRequests)
    }
    if (isScreenReaderActive) {
      // Visible pages' text for screen readers, after their pixels but
      // before prefetch; dropped with the rest when they scroll away.
      val textQueries = visible.filter { it !in pageTexts }.map { PageTextQuery(it) }
      val insertAt = requests.indexOfFirst { (it as? RenderRequest)?.page !in visible }.let { if (it < 0) requests.size else it }
      requests.addAll(insertAt, textQueries)
    } else if (pagesAwaitingText.isNotEmpty()) {
      requests += visible.filter { it in pagesAwaitingText && it !in pageTexts }.map { PageTextQuery(it) }
    }
    if (!force && requests == lastSubmitted) return
    lastSubmitted = requests
    session.submit(requests)
  }

  private fun baseRequest(layout: PdfDocumentLayout, page: Int, scale: Float = this.scale): RenderRequest.Base {
    val size = BaseResolution.size(layout.pageWidth(page), layout.pageHeight(page), width, height, scale)
    return RenderRequest.Base(page, size.width, size.height, size.maxPixels)
  }

  private fun tileRequest(layout: PdfDocumentLayout, page: Int): RenderRequest.Tile? {
    if (!renderFailures.allowsTile(page)) return null
    // A page without a base bitmap may still have an estimated size; its tile
    // geometry would be wrong. Tiles follow once the base layer is in.
    val base = cache.get(page) ?: return null
    // Pixels of base bitmap per unzoomed content pixel.
    val baseResolution = base.width / layout.pageWidth(page)
    if (scale <= baseResolution * TILE_THRESHOLD) {
      tiles.remove(page)?.let { releaseAfterFrame(it.bitmap) }
      return null
    }
    val viewLeft = offsetX / scale
    val viewTop = offsetY / scale
    val left = max(layout.pageLeft(page), viewLeft)
    val top = max(layout.pageTop(page), viewTop)
    val right = min(layout.pageRight(page), viewLeft + width / scale)
    val bottom = min(layout.pageBottom(page), viewTop + height / scale)
    if (right <= left || bottom <= top) return null
    val usedWidth = min(width, ceil((right - left) * scale).toInt())
    val usedHeight = min(height, ceil((bottom - top) * scale).toInt())
    return RenderRequest.Tile(
      page = page,
      generation = tileGeneration,
      bitmapWidth = width,
      bitmapHeight = height,
      usedWidth = usedWidth,
      usedHeight = usedHeight,
      scale = layout.pointsToPixels(page) * scale,
      dx = (layout.pageLeft(page) - left) * scale,
      dy = (layout.pageTop(page) - top) * scale,
      contentLeft = left,
      contentTop = top,
      viewScale = scale,
    )
  }

  // endregion

  // region Memory management

  private fun trimToVisible() {
    constrainedUntilMs = SystemClock.uptimeMillis() + MEMORY_PRESSURE_BACKOFF_MS
    releaseOffscreen()
  }

  /** No backoff: nothing scrolls until the UI shows again ([onWindowVisibilityChanged]). */
  private fun releaseWhileHidden() {
    session?.submit(emptyList())
    lastSubmitted = emptyList()
    releaseOffscreen()
  }

  private fun releaseOffscreen() {
    val visible = visiblePages().toSet()
    cache.trimTo(0, visible)
    bitmapPool.clear()
    updateCacheStats()
  }

  private fun clearTiles() {
    tiles.values.forEach { releaseAfterFrame(it.bitmap) }
    tiles.clear()
    tileGeneration++
  }

  /**
   * Returns [bitmap] to the pool once the frame that may still reference it
   * has been drawn, so the renderer never overwrites pixels on screen.
   */
  private fun releaseAfterFrame(bitmap: Bitmap) {
    if (isAttachedToWindow) {
      postOnAnimation { bitmapPool.release(bitmap) }
    } else {
      // Not attached: no frame can reference it, and a posted runnable would
      // wait for the next attach.
      bitmapPool.release(bitmap)
    }
  }

  private fun paintFor(bitmap: Bitmap): Paint = if (bitmap.isCoverage) coveragePaint else bitmapPaint

  private fun poolBudget(): Long = javaHeapCaps()?.poolBytes ?: unboundedPoolBudget()

  private fun unboundedPoolBudget(): Long = MemoryBudgets.pool(width, height, plannedBaseBytes, plannedTilesAtFit, plannedGrey)

  /** Null from API 26, where bitmap pixels are native. */
  private fun javaHeapCaps(): MemoryBudgets.HeapCaps? =
    if (BITMAPS_ON_JAVA_HEAP) MemoryBudgets.javaHeapCaps(Runtime.getRuntime().maxMemory(), viewportBytes(), unboundedPoolBudget(), plannedGrey) else null

  private fun maxCacheBudget(): Long = min(MAX_CACHE_BYTES, javaHeapCaps()?.cacheBytes ?: MAX_CACHE_BYTES)

  private fun viewportBytes(): Long = max(1, width).toLong() * max(1, height) * ARGB_BYTES_PER_PIXEL

  private fun updateCacheStats() {
    statResidentPages = cache.count
    statCacheBytes = cache.values.sumOf { it.allocationByteCount.toLong() }
  }

  private fun defaultCacheBudget(): Long {
    val heapBased = Runtime.getRuntime().maxMemory() / 6
    // Always room for the visible pages plus a little look-ahead.
    val argb = heapBased.coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES).coerceAtLeast(viewportBytes() * 4)
    val budget = if (BITMAPS_ON_JAVA_HEAP && plannedGrey) argb / ARGB_BYTES_PER_PIXEL else argb
    return budget.coerceAtMost(maxCacheBudget())
  }

  // endregion

  private companion object {
    /**
     * Inversion, then a 180° hue rotation (the luminance-preserving matrix
     * of CSS `hue-rotate`): white paper turns black, black text white, and
     * a red heading light red rather than cyan.
     */
    val DARK_PAGES = ColorMatrixColorFilter(darkPages())

    /**
     * Coverage back to its greys (255 − alpha) in one draw; paper and then
     * coverage would blend twice at an anti-aliased clip's edge.
     */
    val COVERAGE = ColorMatrixColorFilter(coverageToGrey())
    val DARK_COVERAGE = ColorMatrixColorFilter(coverageToGrey().apply { postConcat(darkPages()) })

    private fun darkPages() = ColorMatrix(
      floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
      ),
    ).apply {
      postConcat(
        ColorMatrix(
          floatArrayOf(
            -0.574f, 1.43f, 0.144f, 0f, 0f,
            0.426f, 0.43f, 0.144f, 0f, 0f,
            0.426f, 1.43f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
          ),
        ),
      )
    }

    private fun coverageToGrey() = ColorMatrix(
      floatArrayOf(
        0f, 0f, 0f, -1f, 255f,
        0f, 0f, 0f, -1f, 255f,
        0f, 0f, 0f, -1f, 255f,
        0f, 0f, 0f, 0f, 255f,
      ),
    )

    /** Translucent yellow and orange, readable over black text on white. */
    const val SEARCH_MATCH_COLOR = 0x66FFD600
    const val SEARCH_CURRENT_COLOR = 0x99FF8C00.toInt()
    /** A revealed match lands this far down (along) the view. */
    const val SEARCH_REVEAL_FRACTION = 0.3f
    const val DEFAULT_PAGE_ACCESSIBILITY_LABEL = "Page {page} of {pageCount}"
    const val MAX_PAGE_TEXTS = 32
    const val ACCESSIBILITY_SCROLL_FRACTION = 0.9f
    const val SETTLE_DELAY_MS = 80L

    /** Zoom changes below this (0.1%) are not reported. */
    const val SCALE_EPSILON = 0.001f
    const val PAGE_SNAP_DURATION_MS = 250
    /** How long to keep only visible pages after a memory-pressure signal. */
    const val MEMORY_PRESSURE_BACKOFF_MS = 30_000L
    /** Render zoom tiles once the screen shows 15% more pixels than the base layer has. */
    const val TILE_THRESHOLD = 1.15f
    const val MIN_CACHE_BYTES = 24L * 1024 * 1024
    const val MAX_CACHE_BYTES = 128L * 1024 * 1024
    const val ARGB_BYTES_PER_PIXEL = 4

    val BITMAPS_ON_JAVA_HEAP = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
  }
}

private val Bitmap.isCoverage: Boolean get() = config == Bitmap.Config.ALPHA_8

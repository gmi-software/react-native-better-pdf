package com.margelo.nitro.betterpdf

import com.margelo.nitro.betterpdf.PdfDocumentLayout.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePlannerTest {
  private val layout = PdfDocumentLayout(List(10) { PageSize(595f, 842f) }, viewportWidth = 1000f, spacing = 0f)
  private val zoomed = TileViewport(offsetX = 400f, offsetY = 600f, scale = 2.5f, width = 1000, height = 1800)

  private fun plan(viewport: TileViewport = zoomed, drawn: RenderRequest.Tile? = null, generation: Int = 1, page: Int = 0) =
    TilePlanner.plan(layout, page, baseWidth = 1000, viewport, generation, drawn, threshold = 1.15f)

  private fun wanted(viewport: TileViewport = zoomed, generation: Int = 1): RenderRequest.Tile =
    (plan(viewport, generation = generation) as TilePlanner.Plan.Render).request

  @Test
  fun `a zoomed page without a tile gets one`() {
    val request = wanted()
    assertEquals(0, request.page)
    assertEquals(1000, request.usedWidth)
    assertEquals(160f, request.contentLeft)
    assertEquals(240f, request.contentTop)
  }

  @Test
  fun `the tile on screen for the same viewport is not rendered again`() {
    assertEquals(TilePlanner.Plan.None, plan(drawn = wanted()))
  }

  @Test
  fun `a tile rendered in an earlier generation still counts when nothing moved`() {
    assertEquals(TilePlanner.Plan.None, plan(drawn = wanted(generation = 1), generation = 2))
  }

  @Test
  fun `a changed zoom, position or view size renders the tile again`() {
    val drawn = wanted()
    val changes = listOf(
      zoomed.copy(scale = 3f),
      zoomed.copy(offsetX = 500f),
      zoomed.copy(offsetY = 700f),
      zoomed.copy(width = 900),
      // A height-only resize keeps the layout, so it keeps the drawn tile too.
      zoomed.copy(height = 1600),
    )
    for (viewport in changes) {
      val next = plan(viewport, drawn = drawn)
      assertTrue("$viewport: $next", next is TilePlanner.Plan.Render)
      assertNotEquals(drawn, (next as TilePlanner.Plan.Render).request.copy(generation = drawn.generation))
    }
  }

  @Test
  fun `zooming again replaces the tile`() {
    val first = wanted()
    val deeper = zoomed.copy(scale = 4f, offsetX = 640f, offsetY = 960f)
    val second = (plan(deeper, drawn = first) as TilePlanner.Plan.Render).request
    assertEquals(4f, second.viewScale)
    assertEquals(TilePlanner.Plan.None, plan(deeper, drawn = second))
    // Back to the first zoom: the tile on screen is the deeper one.
    assertTrue(plan(zoomed, drawn = second) is TilePlanner.Plan.Render)
  }

  @Test
  fun `at a zoom the base bitmap covers the drawn tile is dropped`() {
    assertEquals(TilePlanner.Plan.Drop, plan(zoomed.copy(scale = 1.1f, offsetX = 0f, offsetY = 0f), drawn = wanted()))
  }

  @Test
  fun `a page without a base bitmap gets no tile yet`() {
    assertEquals(
      TilePlanner.Plan.None,
      TilePlanner.plan(layout, 0, baseWidth = null, zoomed, 1, drawn = null, threshold = 1.15f),
    )
  }

  @Test
  fun `a page outside the viewport gets no tile`() {
    assertEquals(TilePlanner.Plan.None, plan(page = 5))
  }

  /**
   * The view's loop around the real [RenderQueue], as in
   * `PdfDocumentView.requestRenders`: after every finished render it plans
   * again (the tile, then the look-ahead base pages not rendered yet) and
   * submits when the list changed.
   */
  @Test
  fun `look-ahead pages finishing after the tile do not render it again`() {
    val queue = RenderQueue<RenderRequest>()
    val lookAhead = (1..4).map { RenderRequest.Base(it, bitmapWidth = 1000, bitmapHeight = 1415, maxPixels = 2_000_000) }
    val renderedBases = HashSet<Int>()
    var drawn: RenderRequest.Tile? = null
    var lastSubmitted: List<RenderRequest>? = null
    var tileRenders = 0

    fun requestRenders() {
      val requests = ArrayList<RenderRequest>()
      (plan(drawn = drawn) as? TilePlanner.Plan.Render)?.let { requests += it.request }
      requests += lookAhead.filter { it.page !in renderedBases }
      if (requests == lastSubmitted) return
      lastSubmitted = requests
      queue.submit(requests)
    }

    requestRenders()
    while (true) {
      val request = queue.poll() ?: break
      queue.finish()
      when (request) {
        is RenderRequest.Tile -> {
          tileRenders++
          drawn = request
        }
        is RenderRequest.Base -> renderedBases += request.page
      }
      requestRenders()
    }
    assertEquals(setOf(1, 2, 3, 4), renderedBases)
    assertEquals(1, tileRenders)
  }
}

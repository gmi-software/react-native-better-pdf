package com.margelo.nitro.betterpdf

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What the view shows: the top-left corner in zoomed pixels, the zoom and the view's size. */
data class TileViewport(val offsetX: Float, val offsetY: Float, val scale: Float, val width: Int, val height: Int)

/**
 * Decides the zoom tile of one visible page.
 *
 * Pure Kotlin so its guarantees are unit tested (`TilePlannerTest`).
 */
object TilePlanner {
  sealed class Plan {
    /** Nothing to render; a drawn tile stays. */
    object None : Plan()

    /** The base bitmap is sharp enough at this zoom: drop the drawn tile. */
    object Drop : Plan()

    data class Render(val request: RenderRequest.Tile) : Plan()
  }

  /**
   * [baseWidth] is the width of the page's base bitmap, `null` while it has
   * none. [drawn] is the request of the tile on screen: one with the wanted
   * geometry is not rendered again.
   */
  fun plan(
    layout: PdfDocumentLayout,
    page: Int,
    baseWidth: Int?,
    viewport: TileViewport,
    generation: Int,
    drawn: RenderRequest.Tile?,
    threshold: Float,
  ): Plan {
    // A page without a base bitmap may still have an estimated size; its tile
    // geometry would be wrong. Tiles follow once the base layer is in.
    if (baseWidth == null) return Plan.None
    val scale = viewport.scale
    // Pixels of base bitmap per unzoomed content pixel.
    val baseResolution = baseWidth / layout.pageWidth(page)
    if (scale <= baseResolution * threshold) return Plan.Drop
    val viewLeft = viewport.offsetX / scale
    val viewTop = viewport.offsetY / scale
    val left = max(layout.pageLeft(page), viewLeft)
    val top = max(layout.pageTop(page), viewTop)
    val right = min(layout.pageRight(page), viewLeft + viewport.width / scale)
    val bottom = min(layout.pageBottom(page), viewTop + viewport.height / scale)
    if (right <= left || bottom <= top) return Plan.None
    val request = RenderRequest.Tile(
      page = page,
      generation = generation,
      bitmapWidth = viewport.width,
      bitmapHeight = viewport.height,
      usedWidth = min(viewport.width, ceil((right - left) * scale).toInt()),
      usedHeight = min(viewport.height, ceil((bottom - top) * scale).toInt()),
      scale = layout.pointsToPixels(page) * scale,
      dx = (layout.pageLeft(page) - left) * scale,
      dy = (layout.pageTop(page) - top) * scale,
      contentLeft = left,
      contentTop = top,
      viewScale = scale,
    )
    // The generation only drops tiles requested before a relayout or a move,
    // so a drawn tile with the same geometry is the wanted one.
    if (drawn != null && drawn.copy(generation = generation) == request) return Plan.None
    return Plan.Render(request)
  }
}

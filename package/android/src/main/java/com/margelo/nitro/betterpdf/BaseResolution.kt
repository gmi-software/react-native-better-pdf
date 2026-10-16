package com.margelo.nitro.betterpdf

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Size of a page's base bitmap: the whole page, drawn under the zoom tiles.
 *
 * At zoom 1 and above it is the fitted page; tiles supply the detail beyond.
 * Zoomed out, the fitted size is halved while the zoom is at most half of
 * it, down to [MIN_LEVEL]: a bitmap never has fewer pixels than the screen
 * shows, and the visible pages' memory stays a few viewports instead of
 * growing with 1 / zoom² as more of them come into view. Halving, rather
 * than following the zoom, keeps a pinch from asking for a new size every
 * frame and leaves [BitmapPool] few sizes to reuse. Further out pages stay
 * at a quarter, so zooming back in starts from a usable picture.
 */
object BaseResolution {
  data class Size(val width: Int, val height: Int, val maxPixels: Long)

  /**
   * The base bitmap of a [pageWidth] x [pageHeight] page (unzoomed layout
   * pixels) in a [viewWidth] x [viewHeight] view at [scale].
   */
  fun size(pageWidth: Float, pageHeight: Float, viewWidth: Int, viewHeight: Int, scale: Float): Size {
    val level = level(scale)
    var width = pageWidth * level
    var height = pageHeight * level
    // Very tall pages (receipts, posters) would otherwise allocate enormous
    // bitmaps; cap the base layer and let tiles supply detail.
    val maxPixels = max(viewWidth.toFloat() * viewHeight * MAX_VIEWPORTS, 1f)
    val pixels = width * height
    if (pixels > maxPixels) {
      val factor = sqrt(maxPixels / pixels)
      width *= factor
      height *= factor
    }
    return Size(max(1, width.roundToInt()), max(1, height.roundToInt()), maxPixels.toLong())
  }

  /** The fraction of the fitted size rendered at [scale]: 1, 1/2 or 1/4. */
  private fun level(scale: Float): Float {
    var level = 1f
    while (level > MIN_LEVEL && scale <= level / 2) level /= 2
    return level
  }

  private const val MIN_LEVEL = 0.25f

  /** Upper bound for a page's base bitmap, in viewports worth of pixels. */
  private const val MAX_VIEWPORTS = 2f
}

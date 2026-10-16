package com.margelo.nitro.betterpdf

/**
 * Work for a document's render thread, produced by [PrefetchPlanner] on the
 * UI thread and consumed through [RenderQueue].
 */
sealed class RenderRequest : DocumentWork {
  abstract val page: Int
  abstract val bitmapWidth: Int
  abstract val bitmapHeight: Int

  /**
   * The whole page at fit-width resolution, or less when zoomed out
   * ([BaseResolution]). [bitmapHeight] follows the layout's (possibly
   * estimated) page size; the render thread corrects it to the page's real
   * aspect ratio, capped at [maxPixels].
   */
  data class Base(
    override val page: Int,
    override val bitmapWidth: Int,
    override val bitmapHeight: Int,
    val maxPixels: Long,
  ) : RenderRequest()

  /**
   * A viewport-sized, full-resolution crop of a zoomed page. Rendered into a
   * bitmap of viewport size (so tile bitmaps are poolable) of which only
   * `usedWidth` x `usedHeight` is drawn. The transform maps PDF points to
   * tile pixels: `tile = point * scale + (dx, dy)`. [contentLeft]/[contentTop]
   * and [viewScale] place the tile in unzoomed content space, so it lands in
   * the right spot even if the user zoomed while it was rendering.
   */
  data class Tile(
    override val page: Int,
    val generation: Int,
    override val bitmapWidth: Int,
    override val bitmapHeight: Int,
    val usedWidth: Int,
    val usedHeight: Int,
    val scale: Float,
    val dx: Float,
    val dy: Float,
    val contentLeft: Float,
    val contentTop: Float,
    val viewScale: Float,
  ) : RenderRequest()
}

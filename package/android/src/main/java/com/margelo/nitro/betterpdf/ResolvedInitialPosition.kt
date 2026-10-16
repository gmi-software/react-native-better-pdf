package com.margelo.nitro.betterpdf

/**
 * The `initialPosition` prop for a loaded document: [page] within it, the
 * zoom (not yet clamped to the scale limits), and the page [point] to put at
 * the top-left corner instead of the page's top.
 */
internal data class ResolvedInitialPosition(val page: Int, val scale: Float, val point: PagePoint?) {
  companion object {
    /**
     * Resolves [position] (null: the first page, fitted) for a document of
     * [pageCount] pages, at least one. A page outside the document is
     * clamped and shown from its top, as by goToViewport and on iOS.
     */
    fun of(position: PdfInitialPosition?, pageCount: Int): ResolvedInitialPosition {
      val requested = position?.page?.toPageIndex() ?: 0
      val page = requested.coerceIn(0, pageCount - 1)
      val scale = position?.scale?.takeIf { it.isFinite() && it > 0 }?.toFloat() ?: 1f
      val x = position?.x?.takeIf { it.isFinite() }
      val y = position?.y?.takeIf { it.isFinite() }
      val point = if (x != null && y != null && page == requested) PagePoint(page, x.toFloat(), y.toFloat()) else null
      return ResolvedInitialPosition(page, scale, point)
    }
  }
}

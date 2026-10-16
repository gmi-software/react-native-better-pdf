package com.margelo.nitro.betterpdf

/** Byte budgets for bitmaps the viewer keeps without drawing them. */
object MemoryBudgets {
  const val MIN_POOL_BYTES = 8L * 1024 * 1024

  /**
   * Free bitmaps [BitmapPool] keeps: a viewport-sized tile or one base page of
   * [baseBitmapBytes], whichever is larger; both when [tilesAtFit], so a page
   * and the tile drawn over it do not push each other out.
   */
  fun pool(viewWidth: Int, viewHeight: Int, baseBitmapBytes: Long, tilesAtFit: Boolean): Long {
    val tileBytes = viewWidth.toLong() * viewHeight * BYTES_PER_PIXEL
    val inUse = if (tilesAtFit) baseBitmapBytes + tileBytes else maxOf(baseBitmapBytes, tileBytes)
    return maxOf(MIN_POOL_BYTES, inUse)
  }

  private const val BYTES_PER_PIXEL = 4
}

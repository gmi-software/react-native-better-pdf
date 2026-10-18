package com.margelo.nitro.betterpdf

/** Byte budgets for bitmaps the viewer keeps without drawing them. */
object MemoryBudgets {
  const val MIN_POOL_BYTES = 8L * 1024 * 1024

  /** The ARGB band grey pages render through ([renderPixels]). */
  const val BAND_BYTES = 1L shl 20

  /**
   * Free bitmaps [BitmapPool] keeps: a viewport-sized tile or one base page of
   * [baseBitmapBytes] (counted at 4 bytes per pixel), whichever is larger;
   * both when [tilesAtFit], so a page and the tile drawn over it do not push
   * each other out. For [greyPages] the same bitmaps as coverage, a byte per
   * pixel, plus the band they render through.
   */
  fun pool(viewWidth: Int, viewHeight: Int, baseBitmapBytes: Long, tilesAtFit: Boolean, greyPages: Boolean = false): Long {
    val tileBytes = viewWidth.toLong() * viewHeight * BYTES_PER_PIXEL
    val inUse = if (tilesAtFit) baseBitmapBytes + tileBytes else maxOf(baseBitmapBytes, tileBytes)
    val argb = maxOf(MIN_POOL_BYTES, inUse)
    return if (greyPages) argb / BYTES_PER_PIXEL + BAND_BYTES else argb
  }

  data class HeapCaps(val cacheBytes: Long, val poolBytes: Long)

  /**
   * Before API 26 bitmap pixels are on the Java heap, so the viewer's
   * bitmaps get a third of [maxHeapBytes]: at most a quarter of that for the
   * pool, one viewport for a tile or the page being rendered, the rest for
   * the cache (which keeps the visible pages regardless).
   */
  fun javaHeapCaps(maxHeapBytes: Long, viewportBytes: Long, poolBytes: Long, greyPages: Boolean = false): HeapCaps {
    val share = maxHeapBytes / JAVA_HEAP_SHARE
    val pool = minOf(poolBytes, share / 4)
    val rendering = if (greyPages) viewportBytes / BYTES_PER_PIXEL + BAND_BYTES else viewportBytes
    return HeapCaps(cacheBytes = maxOf(0L, share - pool - rendering), poolBytes = pool)
  }

  private const val BYTES_PER_PIXEL = 4
  private const val JAVA_HEAP_SHARE = 3
}

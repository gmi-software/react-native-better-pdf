package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetsTest {
  // Base bitmaps below are US Letter at fit width, capped at two viewports
  // of pixels like PdfDocumentView.baseRequest. A capped page narrower than
  // the view / 1.15 gets tiles at zoom 1 (tilesAtFit).

  @Test
  fun `a landscape phone's pool holds a page and the tile drawn over it`() {
    val page = bytes(2001, 2590)
    val tile = bytes(2400, 1080)
    assertTrue(MemoryBudgets.pool(2400, 1080, page, tilesAtFit = true) >= page + tile)
  }

  @Test
  fun `a landscape tablet's pool holds a page`() {
    val page = bytes(2516, 3256)
    assertTrue(MemoryBudgets.pool(2560, 1600, page, tilesAtFit = false) >= page)
  }

  @Test
  fun `an unfolded foldable's pool holds one page, without a tile on top`() {
    assertEquals(bytes(1812, 2345), MemoryBudgets.pool(1812, 2176, bytes(1812, 2345), tilesAtFit = false))
  }

  @Test
  fun `in portrait the pool stays one viewport`() {
    assertEquals(bytes(1080, 2400), MemoryBudgets.pool(1080, 2400, bytes(1080, 1398), tilesAtFit = false))
    assertEquals(bytes(1600, 2560), MemoryBudgets.pool(1600, 2560, bytes(1600, 2071), tilesAtFit = false))
  }

  @Test
  fun `the pool always holds a viewport-sized tile`() {
    assertEquals(bytes(2400, 1080), MemoryBudgets.pool(2400, 1080, baseBitmapBytes = 0L, tilesAtFit = false))
  }

  @Test
  fun `a grey document's pool holds the same bitmaps as coverage, and a band`() {
    val page = bytes(1080, 1398)
    assertEquals(
      MemoryBudgets.pool(1080, 2400, page, tilesAtFit = false) / 4 + MemoryBudgets.BAND_BYTES,
      MemoryBudgets.pool(1080, 2400, page, tilesAtFit = false, greyPages = true),
    )
    assertEquals(MemoryBudgets.MIN_POOL_BYTES / 4 + MemoryBudgets.BAND_BYTES, MemoryBudgets.pool(400, 300, bytes(400, 518), tilesAtFit = false, greyPages = true))
  }

  @Test
  fun `small views keep the minimum`() {
    assertEquals(MemoryBudgets.MIN_POOL_BYTES, MemoryBudgets.pool(400, 300, bytes(400, 518), tilesAtFit = false))
  }

  // Before API 26 the pixels are on the Java heap; the bench viewer is 1080 x 1800.

  @Test
  fun `on a 48 MB Java heap the bitmaps stay within a third of it`() {
    val caps = MemoryBudgets.javaHeapCaps(maxHeapBytes = MB * 48, viewportBytes = bytes(1080, 1800), poolBytes = MemoryBudgets.MIN_POOL_BYTES)
    assertTrue(caps.cacheBytes >= 0)
    assertTrue(caps.cacheBytes + caps.poolBytes + bytes(1080, 1800) <= MB * 16)
  }

  @Test
  fun `a 192 MB Java heap keeps the whole plan of colour pages`() {
    val caps = MemoryBudgets.javaHeapCaps(maxHeapBytes = MB * 192, viewportBytes = bytes(1080, 1800), poolBytes = MemoryBudgets.MIN_POOL_BYTES)
    // Two visible pages, two ahead and two behind (PrefetchPlanner).
    assertTrue(caps.cacheBytes >= 6 * bytes(1080, 1528))
    assertEquals(MemoryBudgets.MIN_POOL_BYTES, caps.poolBytes)
  }

  @Test
  fun `a grey document on a 48 MB Java heap keeps its whole plan as coverage`() {
    val pool = MemoryBudgets.pool(1080, 1800, bytes(1080, 1528), tilesAtFit = false, greyPages = true)
    val caps = MemoryBudgets.javaHeapCaps(maxHeapBytes = MB * 48, viewportBytes = bytes(1080, 1800), poolBytes = pool, greyPages = true)
    assertTrue(caps.cacheBytes >= 6 * bytes(1080, 1528) / 4)
    assertTrue(caps.cacheBytes + caps.poolBytes + bytes(1080, 1800) / 4 + MemoryBudgets.BAND_BYTES <= MB * 16)
  }

  @Test
  fun `a heap too small for a viewport keeps nothing beyond the visible pages`() {
    val caps = MemoryBudgets.javaHeapCaps(maxHeapBytes = MB * 16, viewportBytes = bytes(1080, 1800), poolBytes = MemoryBudgets.MIN_POOL_BYTES)
    assertEquals(0L, caps.cacheBytes)
    assertTrue(caps.poolBytes <= MB * 16 / 3)
  }

  private fun bytes(width: Int, height: Int): Long = width.toLong() * height * 4

  private companion object {
    const val MB = 1024L * 1024
  }
}

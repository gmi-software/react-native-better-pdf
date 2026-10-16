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
  fun `small views keep the minimum`() {
    assertEquals(MemoryBudgets.MIN_POOL_BYTES, MemoryBudgets.pool(400, 300, bytes(400, 518), tilesAtFit = false))
  }

  private fun bytes(width: Int, height: Int): Long = width.toLong() * height * 4
}

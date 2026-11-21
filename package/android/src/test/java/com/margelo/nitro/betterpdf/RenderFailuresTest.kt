package com.margelo.nitro.betterpdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderFailuresTest {
  private val failures = RenderFailures()

  private fun base(page: Int) = RenderRequest.Base(page, bitmapWidth = 100, bitmapHeight = 140, maxPixels = 14_000)

  private fun tile(page: Int) = RenderRequest.Tile(
    page = page,
    generation = 1,
    bitmapWidth = 100,
    bitmapHeight = 100,
    usedWidth = 100,
    usedHeight = 100,
    scale = 2f,
    dx = 0f,
    dy = 0f,
    contentLeft = 0f,
    contentTop = 0f,
    viewScale = 2f,
  )

  @Test
  fun `a page whose render failed is rendered once more before it is reported`() {
    assertFalse(failures.onFailed(base(3)))
    assertTrue(failures.allowsBase(3))
    assertTrue(failures.onFailed(base(3)))
    assertFalse(failures.allowsBase(3))
    assertFalse(failures.allowsTile(3))
  }

  @Test
  fun `a render that works after a failure starts the count again`() {
    failures.onFailed(base(3))
    failures.onRendered(base(3))
    assertFalse(failures.onFailed(base(3)))
    assertTrue(failures.allowsBase(3))
  }

  @Test
  fun `a page whose tiles fail keeps its base bitmap`() {
    assertFalse(failures.onFailed(tile(3)))
    assertTrue(failures.allowsTile(3))
    assertTrue(failures.onFailed(tile(3)))
    assertFalse(failures.allowsTile(3))
    assertTrue(failures.allowsBase(3))
  }

  @Test
  fun `a tile that works after a failure starts the count again`() {
    failures.onFailed(tile(3))
    failures.onRendered(tile(3))
    assertFalse(failures.onFailed(tile(3)))
    assertTrue(failures.allowsTile(3))
  }

  @Test
  fun `a page is reported once`() {
    failures.onFailed(tile(3))
    assertTrue(failures.onFailed(tile(3)))
    failures.onFailed(base(3))
    assertFalse(failures.onFailed(base(3)))
    assertFalse(failures.allowsBase(3))
  }

  @Test
  fun `failures of one page leave the others alone`() {
    failures.onFailed(base(3))
    failures.onFailed(base(3))
    assertTrue(failures.allowsBase(4))
    assertTrue(failures.allowsTile(4))
    assertFalse(failures.onFailed(base(4)))
  }

  @Test
  fun `clear forgets every failure`() {
    failures.onFailed(base(3))
    failures.onFailed(base(3))
    failures.clear()
    assertTrue(failures.allowsBase(3))
    assertFalse(failures.onFailed(base(3)))
  }

  @Test
  fun `a page that ran out of memory is rendered once more`() {
    assertTrue(failures.onOutOfMemory(base(3)))
    assertFalse(failures.onOutOfMemory(base(3)))
    assertTrue(failures.allowsBase(3))
  }

  @Test
  fun `out-of-memory retries count base bitmaps and tiles apart`() {
    assertTrue(failures.onOutOfMemory(base(3)))
    assertTrue(failures.onOutOfMemory(tile(3)))
    assertTrue(failures.onOutOfMemory(base(4)))
    assertTrue(failures.allowsTile(3))
  }

  @Test
  fun `a render that works allows another out-of-memory retry`() {
    failures.onOutOfMemory(base(3))
    failures.onRendered(base(3))
    assertTrue(failures.onOutOfMemory(base(3)))
  }

  @Test
  fun `clear forgets out-of-memory retries`() {
    failures.onOutOfMemory(tile(3))
    failures.clear()
    assertTrue(failures.onOutOfMemory(tile(3)))
  }

  @Test
  fun `a retry that submitted nothing is available again`() {
    failures.onOutOfMemory(base(3))
    failures.onOutOfMemory(tile(4))
    failures.onOutOfMemoryRetryDone()
    assertTrue(failures.onOutOfMemory(base(3)))
    assertTrue(failures.onOutOfMemory(tile(4)))
  }

  @Test
  fun `a retry is used only by the pages and kinds it submitted`() {
    failures.onOutOfMemory(base(3))
    failures.onOutOfMemory(tile(4))
    // While the viewport moves, tiles are not planned.
    failures.onSubmitted(listOf(base(3), base(4), PageTextQuery(4)))
    failures.onOutOfMemoryRetryDone()
    assertFalse(failures.onOutOfMemory(base(3)))
    assertTrue(failures.onOutOfMemory(tile(4)))
  }

  @Test
  fun `a page whose retry ran keeps its marker when another retry submits nothing`() {
    failures.onOutOfMemory(base(3))
    failures.onSubmitted(listOf(base(3)))
    failures.onOutOfMemoryRetryDone()
    failures.onOutOfMemory(base(4))
    failures.onOutOfMemoryRetryDone()
    assertFalse(failures.onOutOfMemory(base(3)))
    assertTrue(failures.onOutOfMemory(base(4)))
  }
}

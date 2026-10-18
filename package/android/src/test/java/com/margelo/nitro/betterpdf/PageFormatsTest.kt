package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFormatsTest {
  private val formats = PageFormats()

  @Test
  fun `pages not rendered yet follow the last page rendered`() {
    assertFalse(formats.isGrey(5))
    formats.onRendered(page = 0, isGrey = true)
    assertTrue(formats.isGrey(5))
    formats.onRendered(page = 1, isGrey = false)
    assertFalse(formats.isGrey(5))
  }

  @Test
  fun `a page rendered in colour stays colour after a grey page`() {
    formats.onRendered(page = 1, isGrey = false)
    formats.onRendered(page = 2, isGrey = true)
    assertFalse(formats.isGrey(1))
    assertTrue(formats.isGrey(2))
  }

  @Test
  fun `a page rendered again takes its new format`() {
    formats.onRendered(page = 3, isGrey = true)
    formats.onRendered(page = 3, isGrey = false)
    formats.onRendered(page = 4, isGrey = true)
    assertFalse(formats.isGrey(3))
  }

  @Test
  fun `clear forgets every page`() {
    formats.onRendered(page = 0, isGrey = true)
    formats.clear()
    formats.onRendered(page = 1, isGrey = false)
    assertFalse(formats.isGrey(0))
  }

  @Test
  fun `cache bytes follow each page's format`() {
    formats.onRendered(page = 0, isGrey = false)
    formats.onRendered(page = 1, isGrey = true)
    // Page 2 is not rendered yet and follows page 1.
    assertEquals(listOf(400L, 100L, 100L), formats.cacheBytes(listOf(0, 1, 2), listOf(400L, 400L, 400L)))
  }
}

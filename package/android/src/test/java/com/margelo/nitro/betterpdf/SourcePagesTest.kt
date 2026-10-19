package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourcePagesTest {
  private val pages = SourcePages(listOf(3, 12, 5))

  @Test
  fun `sources follow one another`() {
    assertEquals(20, pages.pageCount)
    assertEquals(listOf(0, 3, 15), (0 until 3).map(pages::firstPage))
    assertEquals(listOf(3, 12, 5), (0 until 3).map(pages::pageCount))
  }

  @Test
  fun `every page belongs to one source, first and last pages included`() {
    val expected = List(3) { 0 } + List(12) { 1 } + List(5) { 2 }
    assertEquals(expected, (0 until 20).map(pages::sourceOf))
  }

  @Test
  fun `pages map to their source and back`() {
    assertEquals(SourcePage(source = 0, page = 2), pages.locate(2))
    assertEquals(SourcePage(source = 1, page = 0), pages.locate(3))
    assertEquals(SourcePage(source = 2, page = 4), pages.locate(19))
    for (page in 0 until 20) {
      val at = pages.locate(page)
      assertEquals(page, pages.documentPage(at.source, at.page))
    }
    assertEquals(listOf(0 until 3, 3 until 15, 15 until 20), (0 until 3).map(pages::pagesOf))
  }

  @Test
  fun `one source is the whole document`() {
    val single = SourcePages(listOf(7))
    assertEquals(7, single.pageCount)
    assertEquals(List(7) { 0 }, (0 until 7).map(single::sourceOf))
    assertEquals(0, single.firstPage(0))
  }

  @Test
  fun `pages outside the document and sources without pages are rejected`() {
    assertThrows(IllegalArgumentException::class.java) { pages.sourceOf(20) }
    assertThrows(IllegalArgumentException::class.java) { pages.sourceOf(-1) }
    assertThrows(IllegalArgumentException::class.java) { SourcePages(emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { SourcePages(listOf(2, 0)) }
  }

  @Test
  fun `many single-page sources`() {
    val many = SourcePages(List(1000) { 1 })
    assertEquals(1000, many.pageCount)
    assertEquals(637, many.sourceOf(637))
    assertEquals(637, many.firstPage(637))
  }
}

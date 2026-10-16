package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedInitialPositionTest {
  private fun resolve(page: Double, scale: Double = 1.0, x: Double? = 10.0, y: Double? = 20.0, pageCount: Int = 3) =
    ResolvedInitialPosition.of(PdfInitialPosition(page, scale, x, y), pageCount)

  @Test
  fun `a page in the document keeps its point`() {
    assertEquals(ResolvedInitialPosition(1, 2f, PagePoint(1, 10f, 20f)), resolve(1.0, scale = 2.0))
  }

  @Test
  fun `without a position the first page opens fitted`() {
    assertEquals(ResolvedInitialPosition(0, 1f, null), ResolvedInitialPosition.of(null, 3))
  }

  @Test
  fun `a page before the document opens the first page from its top`() {
    assertEquals(ResolvedInitialPosition(0, 1f, null), resolve(-1.0))
    assertEquals(ResolvedInitialPosition(0, 1f, null), resolve(-1e20))
  }

  @Test
  fun `a page past the document opens the last page from its top`() {
    assertEquals(ResolvedInitialPosition(2, 1f, null), resolve(3.0))
    assertEquals(ResolvedInitialPosition(2, 1f, null), resolve(1e20))
  }

  @Test
  fun `fractional pages are rounded, as on iOS`() {
    assertEquals(ResolvedInitialPosition(2, 1f, PagePoint(2, 10f, 20f)), resolve(1.6))
    assertEquals(ResolvedInitialPosition(1, 1f, PagePoint(1, 10f, 20f)), resolve(1.4))
    assertEquals(ResolvedInitialPosition(1, 1f, PagePoint(1, 10f, 20f)), resolve(0.5))
    assertEquals(ResolvedInitialPosition(0, 1f, PagePoint(0, 10f, 20f)), resolve(-0.4))
    // -0.6 is page -1: clamped, so shown from the top.
    assertEquals(ResolvedInitialPosition(0, 1f, null), resolve(-0.6))
  }

  @Test
  fun `values that are not finite fall back to the defaults`() {
    // A page that is not a number is page 0, as on iOS.
    assertEquals(ResolvedInitialPosition(0, 1f, PagePoint(0, 10f, 20f)), resolve(Double.NaN))
    assertEquals(ResolvedInitialPosition(0, 1f, PagePoint(0, 10f, 20f)), resolve(Double.POSITIVE_INFINITY))
    assertEquals(ResolvedInitialPosition(1, 1f, PagePoint(1, 10f, 20f)), resolve(1.0, scale = Double.NaN))
    assertEquals(ResolvedInitialPosition(1, 1f, PagePoint(1, 10f, 20f)), resolve(1.0, scale = 0.0))
    assertEquals(ResolvedInitialPosition(1, 1f, null), resolve(1.0, x = Double.NaN))
    assertEquals(ResolvedInitialPosition(1, 1f, null), resolve(1.0, y = Double.NEGATIVE_INFINITY))
  }

  @Test
  fun `a point needs both coordinates`() {
    assertEquals(ResolvedInitialPosition(1, 1f, null), resolve(1.0, x = null))
    assertEquals(ResolvedInitialPosition(1, 1f, null), resolve(1.0, y = null))
  }
}

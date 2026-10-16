package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoubleToPageIndexTest {
  @Test
  fun `whole pages are unchanged`() {
    assertEquals(0, 0.0.toPageIndex())
    assertEquals(7, 7.0.toPageIndex())
  }

  @Test
  fun `fractional pages round to the nearest page`() {
    assertEquals(1, 1.4.toPageIndex())
    assertEquals(2, 1.6.toPageIndex())
    assertEquals(0, (-0.4).toPageIndex())
    assertEquals(-1, (-0.6).toPageIndex())
  }

  @Test
  fun `halves round away from zero, as Swift's rounded()`() {
    assertEquals(1, 0.5.toPageIndex())
    assertEquals(3, 2.5.toPageIndex())
    assertEquals(-1, (-0.5).toPageIndex())
    assertEquals(-3, (-2.5).toPageIndex())
  }

  @Test
  fun `huge values saturate`() {
    assertEquals(Int.MAX_VALUE, 1e20.toPageIndex())
    assertEquals(-Int.MAX_VALUE, (-1e20).toPageIndex())
  }

  @Test
  fun `values that are not finite have no page`() {
    assertNull(Double.NaN.toPageIndex())
    assertNull(Double.POSITIVE_INFINITY.toPageIndex())
    assertNull(Double.NEGATIVE_INFINITY.toPageIndex())
  }
}

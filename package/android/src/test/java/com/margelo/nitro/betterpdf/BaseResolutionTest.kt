package com.margelo.nitro.betterpdf

import com.margelo.nitro.betterpdf.PdfDocumentLayout.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseResolutionTest {
  private val a4 = PageSize(595f, 842f)

  // A 1080 x 2400 phone: A4 at fit width is 1080 x 1528 px (6.3 MB).
  private val phone = PdfDocumentLayout(List(1000) { a4 }, viewportWidth = 1080f, spacing = 21f)

  @Test
  fun `at zoom 1 and above the base layer is the fitted page`() {
    for (scale in listOf(1f, 1.5f, 2.5f, 4f)) {
      assertEquals(BaseResolution.Size(1080, 1528, 5_184_000), size(phone, 0, scale))
    }
  }

  @Test
  fun `a very tall page is capped at two viewports of pixels at every zoom`() {
    // 200 x 2000 pt at 1080 px wide: 1080 x 10800 px, 2.25x the cap.
    val receipt = PdfDocumentLayout(listOf(PageSize(200f, 2000f)), viewportWidth = 1080f, spacing = 0f)
    assertEquals(BaseResolution.Size(720, 7200, 5_184_000), size(receipt, 0, 1f))
    assertEquals(BaseResolution.Size(720, 7200, 5_184_000), size(receipt, 0, 4f))
    // Half the fitted size is under the cap.
    assertEquals(BaseResolution.Size(540, 5400, 5_184_000), size(receipt, 0, 0.5f))
  }

  @Test
  fun `zoomed out, the base layer is the fitted page halved while the zoom allows`() {
    assertEquals(1080, size(phone, 0, 0.51f).width)
    assertEquals(540, size(phone, 0, 0.5f).width)
    assertEquals(540, size(phone, 0, 0.26f).width)
    assertEquals(270, size(phone, 0, 0.25f).width)
  }

  @Test
  fun `below a quarter the base layer stays a quarter of the fitted page`() {
    for (scale in listOf(0.2499f, 0.1f, 0.05f, 0.001f)) {
      assertEquals(BaseResolution.Size(270, 382, 5_184_000), size(phone, 0, scale))
    }
  }

  @Test
  fun `the base layer has at least the pixels on screen, and less than twice as many`() {
    var scale = 0.25f
    while (scale <= 1f) {
      val width = size(phone, 0, scale).width
      assertTrue("zoom $scale: $width px", width >= phone.pageWidth(0) * scale - 1)
      assertTrue("zoom $scale: $width px", width < phone.pageWidth(0) * scale * 2)
      scale += 0.01f
    }
  }

  @Test
  fun `the visible pages fit the cache down to zoom 0_01`() {
    // Further out the quarter-size pages add up: at 0.005 a tablet shows
    // 225 of them (194 MB), a phone 310 (122 MB).
    val tablet = PdfDocumentLayout(List(1000) { a4 }, viewportWidth = 1600f, spacing = 16f)
    val landscape = PdfDocumentLayout(List(1000) { a4 }, viewportWidth = 2400f, spacing = 21f)
    for (scale in listOf(1f, 0.75f, 0.5f, 0.25f, 0.1f, 0.05f, 0.01f)) {
      assertFits(phone, 1080, 2400, scale)
      assertFits(tablet, 1600, 2560, scale)
      assertFits(landscape, 2400, 1080, scale)
    }
  }

  /** The pages from the top of [layout] in a [viewWidth] x [viewHeight] view at [scale], as `PdfDocumentView` keeps them. */
  private fun assertFits(layout: PdfDocumentLayout, viewWidth: Int, viewHeight: Int, scale: Float) {
    val visible = layout.visibleRange(0f, viewHeight / scale)
    val bytes = visible.sumOf { page ->
      val size = BaseResolution.size(layout.pageWidth(page), layout.pageHeight(page), viewWidth, viewHeight, scale)
      size.width.toLong() * size.height * 4
    }
    assertTrue(
      "${viewWidth}x$viewHeight at zoom $scale: ${visible.count()} pages, ${bytes / 1024 / 1024} MB",
      bytes <= MAX_CACHE_BYTES,
    )
  }

  private fun size(layout: PdfDocumentLayout, page: Int, scale: Float) =
    BaseResolution.size(layout.pageWidth(page), layout.pageHeight(page), viewWidth = 1080, viewHeight = 2400, scale = scale)

  private companion object {
    /** `PdfDocumentView.MAX_CACHE_BYTES`. */
    const val MAX_CACHE_BYTES = 128L * 1024 * 1024
  }
}

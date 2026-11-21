package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitZoomTest {
  @Test
  fun `the fitted page is not zoomed in when minScale is below 1`() {
    assertFalse(FitZoom.isZoomedIn(scale = 1f, minScale = 0.5f, maxScale = 4f))
    assertFalse(FitZoom.isZoomedIn(scale = 0.5f, minScale = 0.5f, maxScale = 4f))
    assertTrue(FitZoom.isZoomedIn(scale = 1.1f, minScale = 0.5f, maxScale = 4f))
  }

  @Test
  fun `zoomed in is measured from minScale when it is 1 or above`() {
    assertFalse(FitZoom.isZoomedIn(scale = 1f, minScale = 1f, maxScale = 4f))
    assertFalse(FitZoom.isZoomedIn(scale = 1.005f, minScale = 1f, maxScale = 4f))
    assertTrue(FitZoom.isZoomedIn(scale = 1.1f, minScale = 1f, maxScale = 4f))
    assertFalse(FitZoom.isZoomedIn(scale = 1.5f, minScale = 1.5f, maxScale = 4f))
    assertTrue(FitZoom.isZoomedIn(scale = 1.6f, minScale = 1.5f, maxScale = 4f))
  }

  @Test
  fun `the first double tap on the fitted page zooms in when minScale is below 1`() {
    assertEquals(2f, FitZoom.doubleTapTarget(scale = 1f, minScale = 0.5f, maxScale = 4f))
  }

  @Test
  fun `a double tap below the fitted page returns to it`() {
    assertEquals(1f, FitZoom.doubleTapTarget(scale = 0.5f, minScale = 0.5f, maxScale = 4f))
    assertEquals(1f, FitZoom.doubleTapTarget(scale = 0.9f, minScale = 0.5f, maxScale = 4f))
  }

  @Test
  fun `a double tap when zoomed in returns to the fitted page`() {
    assertEquals(1f, FitZoom.doubleTapTarget(scale = 2f, minScale = 0.5f, maxScale = 4f))
    assertEquals(1f, FitZoom.doubleTapTarget(scale = 3f, minScale = 1f, maxScale = 4f))
  }

  @Test
  fun `double tap targets are unchanged for minScale 1 and above`() {
    // The rule before: scale > minScale * 1.05 → minScale, else min(maxScale, max(minScale * 2, 2)).
    for ((minScale, maxScale) in listOf(1f to 4f, 1f to 1.5f, 1.5f to 4f, 2.5f to 4f, 2f to 2f)) {
      for (scale in listOf(minScale, minScale * 1.04f, minScale * 1.06f, maxScale)) {
        val before = if (scale > minScale * 1.05f) minScale else minOf(maxScale, maxOf(minScale * 2f, 2f))
        assertEquals("min $minScale max $maxScale at $scale", before, FitZoom.doubleTapTarget(scale, minScale, maxScale))
      }
    }
  }

  @Test
  fun `with maxScale below 1 the resting zoom is maxScale`() {
    assertEquals(0.8f, FitZoom.restScale(minScale = 0.3f, maxScale = 0.8f))
    assertFalse(FitZoom.isZoomedIn(scale = 0.8f, minScale = 0.3f, maxScale = 0.8f))
    assertEquals(0.8f, FitZoom.doubleTapTarget(scale = 0.8f, minScale = 0.3f, maxScale = 0.8f))
    assertEquals(0.8f, FitZoom.doubleTapTarget(scale = 0.3f, minScale = 0.3f, maxScale = 0.8f))
  }
}

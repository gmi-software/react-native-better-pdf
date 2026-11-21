package com.margelo.nitro.betterpdf

import kotlin.math.min

/**
 * Zoom rules measured from the fitted page (scale 1), not from `minScale`:
 * with `minScale` below 1 the fitted page is still the resting zoom.
 *
 * Pure Kotlin so the rules are unit tested (`FitZoomTest`).
 */
internal object FitZoom {
  /** The fitted page, within the scale limits. */
  fun restScale(minScale: Float, maxScale: Float): Float = 1f.coerceIn(minScale, maxScale)

  /** Whether paging gives way to free scrolling. */
  fun isZoomedIn(scale: Float, minScale: Float, maxScale: Float): Boolean =
    scale > restScale(minScale, maxScale) * 1.01f

  /** Away from the resting zoom (in or out), back to it; at it, 2× of it. */
  fun doubleTapTarget(scale: Float, minScale: Float, maxScale: Float): Float {
    val rest = restScale(minScale, maxScale)
    val isAtRest = scale <= rest * 1.05f && scale >= rest / 1.05f
    return if (isAtRest) min(maxScale, rest * 2f) else rest
  }
}

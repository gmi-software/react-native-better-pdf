package com.margelo.nitro.betterpdf

import kotlin.math.roundToInt

/** A page number from JS as an index, rounded as on iOS; null when not finite. */
internal fun Double.toPageIndex(): Int? {
  if (!isFinite()) return null
  // Halves away from zero, like Swift's rounded(); roundToInt() rounds -0.5 to 0.
  return if (this < 0) -(-this).roundToInt() else roundToInt()
}

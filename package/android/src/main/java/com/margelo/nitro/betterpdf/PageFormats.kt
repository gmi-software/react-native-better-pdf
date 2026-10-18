package com.margelo.nitro.betterpdf

import java.util.BitSet

/**
 * Which pages rendered as grey coverage ([renderPixels]) and which in
 * colour; a page not rendered yet is guessed from the last one that was.
 * UI thread only.
 */
class PageFormats {
  private val grey = BitSet()
  private val colour = BitSet()
  private var lastWasGrey = false

  fun onRendered(page: Int, isGrey: Boolean) {
    grey[page] = isGrey
    colour[page] = !isGrey
    lastWasGrey = isGrey
  }

  fun isGrey(page: Int): Boolean = when {
    colour[page] -> false
    grey[page] -> true
    else -> lastWasGrey
  }

  /** What [pages] of [argbBytes] each take as cached: a quarter for grey ones. */
  fun cacheBytes(pages: List<Int>, argbBytes: List<Long>): List<Long> =
    pages.zip(argbBytes) { page, bytes -> if (isGrey(page)) bytes / 4 else bytes }

  fun clear() {
    grey.clear()
    colour.clear()
    lastWasGrey = false
  }
}

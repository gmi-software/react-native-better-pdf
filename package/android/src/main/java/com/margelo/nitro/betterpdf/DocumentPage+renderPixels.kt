package com.margelo.nitro.betterpdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import kotlin.math.min

/** How [renderPixels] renders a page. */
internal enum class PixelGuess {
  /** Rendered with colour before: one pass, not checked. */
  COLOUR,

  /** The document's last new page had colour: one pass, converted if grey. */
  PROBABLY_COLOUR,

  /** In bands, until one has colour. */
  PROBABLY_GREY,
}

/**
 * Renders [usedWidth] x [usedHeight] pixels of the page through [matrix]
 * into a [width] x [height] bitmap from [pool]: ALPHA_8 coverage when every
 * pixel is grey, otherwise ARGB_8888.
 *
 * `PdfRenderer` draws into ARGB only, so a grey page renders in bands that
 * are converted as they are drawn and never needs a page-sized ARGB bitmap.
 * A page found to have colour mid-way costs up to twice a pass (results.md
 * §20), hence [guess].
 */
internal fun DocumentPage.renderPixels(
  pool: BitmapPool,
  width: Int,
  height: Int,
  usedWidth: Int,
  usedHeight: Int,
  matrix: Matrix,
  guess: PixelGuess,
): Bitmap = when (guess) {
  PixelGuess.COLOUR -> renderArgb(pool, width, height, usedWidth, usedHeight, matrix, coverage = null, fromRow = 0)
  PixelGuess.PROBABLY_COLOUR -> {
    val rgba = renderArgb(pool, width, height, usedWidth, usedHeight, matrix, coverage = null, fromRow = 0)
    convertIfGrey(pool, rgba, usedWidth, usedHeight)
  }
  PixelGuess.PROBABLY_GREY -> renderBands(pool, width, height, usedWidth, usedHeight, matrix)
}

private fun DocumentPage.renderBands(pool: BitmapPool, width: Int, height: Int, usedWidth: Int, usedHeight: Int, matrix: Matrix): Bitmap {
  val coverage = pool.acquire(width, height, Bitmap.Config.ALPHA_8)
  var result: Bitmap? = null
  var band: Bitmap? = null
  try {
    val scratch = pool.acquire(width, bandRows(width, height)).also { band = it }
    val bandMatrix = Matrix()
    val clip = Rect()
    var top = 0
    while (top < usedHeight) {
      val rows = min(scratch.height, usedHeight - top)
      scratch.eraseColor(Color.WHITE)
      bandMatrix.set(matrix)
      bandMatrix.postTranslate(0f, -top.toFloat())
      clip.set(0, 0, usedWidth, rows)
      renderForDisplay(scratch, clip, bandMatrix)
      if (!NativePageCoverage.fromRgba(scratch, usedWidth, rows, coverage, top)) {
        return renderArgb(pool, width, height, usedWidth, usedHeight, matrix, coverage, fromRow = top).also { result = it }
      }
      top += rows
    }
    result = coverage
    return coverage
  } finally {
    band?.let(pool::release)
    if (result !== coverage) pool.release(coverage)
  }
}

/** [rgba], or its coverage when every used pixel is grey. */
private fun convertIfGrey(pool: BitmapPool, rgba: Bitmap, usedWidth: Int, usedHeight: Int): Bitmap {
  var coverage: Bitmap? = null
  var result: Bitmap? = null
  try {
    val candidate = pool.acquire(rgba.width, rgba.height, Bitmap.Config.ALPHA_8).also { coverage = it }
    result = if (NativePageCoverage.fromRgba(rgba, usedWidth, usedHeight, candidate, 0)) candidate else rgba
    return result
  } finally {
    if (result !== rgba) pool.release(rgba)
    coverage?.takeIf { it !== result }?.let(pool::release)
  }
}

private fun bandRows(width: Int, height: Int): Int = (MemoryBudgets.BAND_BYTES / (width.toLong() * 4)).toInt().coerceIn(1, height)

/** Renders from row [fromRow] down; the rows above come from [coverage]. */
private fun DocumentPage.renderArgb(
  pool: BitmapPool,
  width: Int,
  height: Int,
  usedWidth: Int,
  usedHeight: Int,
  matrix: Matrix,
  coverage: Bitmap?,
  fromRow: Int,
): Bitmap {
  val target = pool.acquire(width, height)
  try {
    target.eraseColor(Color.WHITE)
    if (coverage != null && fromRow > 0) NativePageCoverage.toRgba(coverage, usedWidth, fromRow, target)
    renderForDisplay(target, Rect(0, fromRow, usedWidth, usedHeight), matrix)
    return target
  } catch (error: Throwable) {
    pool.release(target)
    throw error
  }
}

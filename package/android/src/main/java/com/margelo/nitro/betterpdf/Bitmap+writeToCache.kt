package com.margelo.nitro.betterpdf

import android.content.Context
import android.graphics.Bitmap
import com.margelo.nitro.betterpdf.PdfMethodException.Code
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Writes this bitmap as a uniquely named PNG or JPEG ([quality] 0–1) into
 * `cacheDir/BetterPdf/renders` and returns the file. Blocking.
 */
internal fun Bitmap.writeToCache(context: Context, format: PdfImageFormat, quality: Double): File {
  val directory = File(context.cacheDir, "BetterPdf/renders").apply { mkdirs() }
  val extension = if (format == PdfImageFormat.JPEG) "jpg" else "png"
  val file = File(directory, "${UUID.randomUUID()}.$extension")
  val compressFormat = if (format == PdfImageFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
  val written = file.outputStream().use { compress(compressFormat, (quality * 100).roundToInt(), it) }
  if (!written) {
    file.delete()
    throw PdfMethodException(Code.IO_ERROR, "The page image could not be encoded")
  }
  return file
}

package com.margelo.nitro.betterpdf

import android.content.Context
import android.net.Uri

/** A validated `renderPage` call, shared by the view and `PdfDocument`. */
internal class PageImageRequest private constructor(
  val page: Int,
  private val width: Int,
  private val format: PdfImageFormat,
  private val quality: Double,
) {
  /**
   * Renders the page with [reads] and writes the image file; [pageInFile]:
   * the page in the file [reads] reads ([page] of a document of one
   * source). Blocking.
   */
  fun render(reads: DocumentReads, context: Context, pageInFile: Int, formEdits: List<FormEdit> = emptyList()): PdfPageImage {
    val bitmap = reads.renderPageImage(pageInFile, width, formEdits)
    try {
      val file = bitmap.writeToCache(context, format, quality)
      return PdfPageImage(Uri.fromFile(file).toString(), bitmap.width.toDouble(), bitmap.height.toDouble())
    } finally {
      bitmap.recycle()
    }
  }

  companion object {
    private const val MAX_WIDTH = 4096.0
    private const val DEFAULT_JPEG_QUALITY = 0.9

    /**
     * @throws IllegalArgumentException for a page outside the document or a
     *   width outside 1–4096 pixels (rejected rather than clamped).
     */
    fun of(page: Double, pageCount: Int, options: PdfRenderPageOptions): PageImageRequest {
      require(page.isFinite() && page == Math.floor(page) && page >= 0 && page < pageCount) {
        "Page $page is outside the document (0–${pageCount - 1})"
      }
      val width = options.width
      require(width.isFinite() && width >= 1 && width <= MAX_WIDTH) {
        "width must be 1–$MAX_WIDTH pixels, got $width"
      }
      return PageImageRequest(
        page = page.toInt(),
        width = Math.round(width).toInt(),
        format = options.format ?: PdfImageFormat.PNG,
        quality = options.quality?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: DEFAULT_JPEG_QUALITY,
      )
    }
  }
}

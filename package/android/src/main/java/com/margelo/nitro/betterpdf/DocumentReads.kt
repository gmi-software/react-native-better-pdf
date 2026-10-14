package com.margelo.nitro.betterpdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.ParcelFileDescriptor
import com.margelo.nitro.betterpdf.outline.FileChannelByteSource
import com.margelo.nitro.betterpdf.outline.FormFieldEntry
import com.margelo.nitro.betterpdf.outline.OutlineEntry
import com.margelo.nitro.betterpdf.outline.PdfDocumentReader
import com.margelo.nitro.betterpdf.outline.PdfFormReader
import com.margelo.nitro.betterpdf.outline.PdfFormatException
import com.margelo.nitro.betterpdf.outline.PdfInfo
import com.margelo.nitro.betterpdf.outline.PdfInfoReader
import com.margelo.nitro.betterpdf.outline.PdfLinkReader
import com.margelo.nitro.betterpdf.outline.PdfOutlineReader
import com.margelo.nitro.betterpdf.outline.PdfPageSizeReader
import java.io.File
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reads of an open document besides displaying it — outline, metadata,
 * page sizes, page images — shared by the view's [PdfDocumentSession] and
 * `PdfDocument`. Each read works on its own duplicate of the document's
 * descriptor ([duplicateDescriptor]), with positional reads or a renderer
 * that is closed afterwards, so it never blocks rendering and PDFium keeps
 * nothing once it returns. Blocking: call off the main thread. The outline,
 * metadata and page sizes are cached.
 *
 * @param duplicateDescriptor a duplicate of the open document's descriptor,
 *   the caller's to close; throws [IllegalStateException] once the document
 *   is closed.
 */
internal class DocumentReads(
  private val password: String?,
  private val duplicateDescriptor: () -> ParcelFileDescriptor,
) {
  @Volatile private var outline: List<OutlineEntry>? = null
  @Volatile private var info: PdfInfo? = null
  @Volatile private var pageSizes: List<PdfDocumentLayout.PageSize>? = null

  /**
   * The document outline, parsed from the file.
   *
   * @throws PdfFormatException when the file cannot be parsed (or is encrypted).
   */
  fun readOutline(): List<OutlineEntry> {
    outline?.let { return it }
    val entries = parse { PdfOutlineReader(it).read() }
    outline = entries
    return entries
  }

  /**
   * Every page's links, parsed from the file: any Android version and every
   * form of link (the renderer reports links from Android 15 only, and not
   * all forms). Pages without links are absent.
   *
   * @throws PdfFormatException when the file cannot be parsed (or is
   *   encrypted), or its page tree does not have the renderer's [pageCount]
   *   pages (a damaged file the renderer repaired).
   */
  fun readLinks(pageCount: Int): Map<Int, List<PageLink>> = parse { document ->
    val reader = PdfLinkReader(document)
    if (reader.pageCount != pageCount) {
      throw PdfFormatException("The page tree has ${reader.pageCount} pages, the renderer $pageCount")
    }
    reader.read()
  }

  /**
   * The information dictionary, parsed from the file.
   *
   * @throws PdfFormatException when the file cannot be parsed (or is encrypted).
   */
  fun readInfo(): PdfInfo {
    info?.let { return it }
    val read = parse { PdfInfoReader(it).read() }
    info = read
    return read
  }

  /**
   * The document's form fields with the values the file has. An encrypted
   * document is read from a copy without its protection, written to
   * [cacheDirectory] by a renderer and deleted afterwards (the parser does
   * not decrypt); devices that cannot write one reject it, like the
   * outline.
   *
   * @throws PdfFormatException when the file cannot be parsed.
   */
  fun readFormFields(cacheDirectory: File): List<FormFieldEntry> =
    try {
      parse { PdfFormReader(it).read() }
    } catch (error: PdfFormatException) {
      if (password.isNullOrEmpty() || !DocumentRenderer.supportsPasswords) throw error
      val copy = File.createTempFile("form", ".pdf", cacheDirectory)
      try {
        ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { target ->
          DocumentRenderer.open(duplicateDescriptor(), password).use { it.writeUnprotected(target) }
        }
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)).channel.use { channel ->
          parse(channel) { PdfFormReader(it).read() }
        }
      } finally {
        copy.delete()
      }
    }

  /**
   * Every page's displayed size (see [PdfPageSizeReader]), [pageCount] of
   * them. Read from the page tree, so no page is opened and PDFium caches
   * nothing; documents the parser cannot read (encrypted, or a page tree
   * that disagrees with the renderer) are measured by opening each page on
   * a separate renderer, recycled like the render thread's.
   */
  fun readPageSizes(pageCount: Int): List<PdfDocumentLayout.PageSize> {
    pageSizes?.let { return it }
    val sizes = readPageSizesFromTree()?.takeIf { it.size == pageCount } ?: measurePageSizes(pageCount)
    pageSizes = sizes
    return sizes
  }

  /**
   * Renders [page] [width] pixels wide, the height following the page's
   * aspect ratio, on white like the view, with a renderer of its own —
   * with the form as filled in ([formEdits]).
   *
   * @throws IllegalArgumentException when the image would exceed
   *   [MAX_IMAGE_PIXELS] (e.g. a very tall page at a large width).
   */
  fun renderPageImage(page: Int, width: Int, formEdits: List<FormEdit> = emptyList()): Bitmap {
    // The renderer owns (and closes) the duplicate.
    DocumentRenderer.open(duplicateDescriptor(), password).use { renderer ->
      renderer.applyEdits(formEdits)
      renderer.openPage(page).use { pdfPage ->
        val height = max(1, (width.toDouble() * pdfPage.height / pdfPage.width).roundToInt())
        require(width.toLong() * height <= MAX_IMAGE_PIXELS) {
          "A ${width}×$height image exceeds the limit of $MAX_IMAGE_PIXELS pixels; use a smaller width"
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val matrix = Matrix().apply { setScale(width / pdfPage.width.toFloat(), height / pdfPage.height.toFloat()) }
        pdfPage.renderForDisplay(bitmap, null, matrix)
        return bitmap
      }
    }
  }

  private fun <T> parse(read: (PdfDocumentReader) -> T): T =
    ParcelFileDescriptor.AutoCloseInputStream(duplicateDescriptor()).channel.use { parse(it, read) }

  /**
   * Reads [channel]'s file with [read]. The readers bound their recursion,
   * but not the stack of the thread they run on: a file that still exhausts
   * it fails like any unreadable file instead of crashing the app (the
   * links thread and the render thread catch exceptions, not errors).
   */
  private fun <T> parse(channel: FileChannel, read: (PdfDocumentReader) -> T): T =
    try {
      read(PdfDocumentReader(FileChannelByteSource(channel)))
    } catch (_: StackOverflowError) {
      throw PdfFormatException("The PDF's structure is nested too deeply")
    }

  private fun readPageSizesFromTree(): List<PdfDocumentLayout.PageSize>? =
    try {
      parse { PdfPageSizeReader(it).read() }
    } catch (_: PdfFormatException) {
      // Encrypted, or not readable as PDF syntax.
      null
    } catch (_: RuntimeException) {
      // A structure the parser does not expect; the renderer decides.
      null
    }

  private fun measurePageSizes(pageCount: Int): List<PdfDocumentLayout.PageSize> {
    val descriptor = duplicateDescriptor()
    try {
      // Renderers own (and close) the descriptor they get; keep ours for recycling.
      var renderer: DocumentRenderer? = DocumentRenderer.open(descriptor.dup(), password)
      try {
        return List(pageCount) { index ->
          if (index > 0 && index % PdfDocumentSession.RECYCLE_AFTER_PAGES == 0) {
            renderer?.close()
            // A failed reopen must not close the closed renderer again in
            // `finally`: "Document already closed" would hide its error.
            renderer = null
            renderer = DocumentRenderer.open(descriptor.dup(), password)
          }
          renderer!!.openPage(index).use { PdfDocumentLayout.PageSize(it.width.toFloat(), it.height.toFloat()) }
        }
      } finally {
        renderer?.close()
      }
    } finally {
      descriptor.closeQuietly()
    }
  }

  companion object {
    /** 4096 × 4096: 64 MB as ARGB, the most [renderPageImage] allocates. */
    const val MAX_IMAGE_PIXELS = 4096L * 4096L
  }
}

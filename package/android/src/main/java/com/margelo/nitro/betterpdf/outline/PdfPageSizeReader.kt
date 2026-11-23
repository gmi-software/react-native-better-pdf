package com.margelo.nitro.betterpdf.outline

import com.margelo.nitro.betterpdf.PdfDocumentLayout.PageSize

/**
 * Every page's displayed size, read from the page tree without opening a
 * page: the crop box clipped to the media box (both inheritable, ISO
 * 32000-1 §7.7.3.3), width and height swapped for a /Rotate of 90° or 270°.
 * The same rules as PDFium (and so `PdfRenderer`): a missing media box is US
 * Letter, a crop box that does not overlap the media box is ignored, and
 * /Rotate counts in whole quarter turns.
 *
 * Cycles are broken and the walk is limited to [MAX_TREE_DEPTH] levels,
 * [MAX_PAGES] pages and what a read may keep ([ScanBudget]).
 */
class PdfPageSizeReader(private val document: PdfDocumentReader) {
  private val catalog = document.resolve(document.trailer["Root"]) as? PdfObject.Dictionary
    ?: throw PdfFormatException("No document catalog")

  fun read(): List<PageSize> {
    val sizes = ArrayList<PageSize>()
    val visited = HashSet<Int>()
    fun visit(node: PdfObject?, mediaBox: FloatArray?, cropBox: FloatArray?, rotate: Int, depth: Int) {
      if (depth > MAX_TREE_DEPTH || sizes.size >= MAX_PAGES) return
      val reference = node as? PdfObject.Reference
      if (reference != null && !visited.add(reference.number)) return
      document.keep(NODE_BYTES)
      // A node's objects are let go once its pages' sizes are listed.
      document.releasing {
        val dictionary = document.resolve(node) as? PdfObject.Dictionary ?: return
        val ownMediaBox = rectangleOf(dictionary["MediaBox"]) ?: mediaBox
        val ownCropBox = rectangleOf(dictionary["CropBox"]) ?: cropBox
        val ownRotate = (document.resolve(dictionary["Rotate"]) as? PdfObject.Number)?.value?.toInt() ?: rotate
        val kids = document.resolve(dictionary["Kids"]) as? PdfObject.Array
        if (kids != null && dictionary["Type"] != PdfObject.Name("Page")) {
          kids.items.forEach { visit(it, ownMediaBox, ownCropBox, ownRotate, depth + 1) }
        } else {
          sizes += displayedSize(ownMediaBox, ownCropBox, ownRotate)
        }
      }
    }
    visit(catalog["Pages"], mediaBox = null, cropBox = null, rotate = 0, depth = 0)
    if (sizes.isEmpty()) throw PdfFormatException("No pages in the page tree")
    return sizes
  }

  /** `[llx lly urx ury]` normalized to left ≤ right, bottom ≤ top; null unless four numbers. */
  private fun rectangleOf(value: PdfObject?): FloatArray? {
    val items = (document.resolve(value) as? PdfObject.Array)?.items ?: return null
    if (items.size != 4) return null
    val numbers = items.map { (document.resolve(it) as? PdfObject.Number)?.value?.toFloat() ?: return null }
    return floatArrayOf(
      minOf(numbers[0], numbers[2]),
      minOf(numbers[1], numbers[3]),
      maxOf(numbers[0], numbers[2]),
      maxOf(numbers[1], numbers[3]),
    )
  }

  private fun displayedSize(mediaBox: FloatArray?, cropBox: FloatArray?, rotate: Int): PageSize {
    val media = mediaBox?.takeIf { it[2] > it[0] && it[3] > it[1] } ?: LETTER
    val box = cropBox?.let { crop ->
      val clipped = floatArrayOf(
        maxOf(crop[0], media[0]),
        maxOf(crop[1], media[1]),
        minOf(crop[2], media[2]),
        minOf(crop[3], media[3]),
      )
      clipped.takeIf { it[2] > it[0] && it[3] > it[1] }
    } ?: media
    val width = box[2] - box[0]
    val height = box[3] - box[1]
    // PDFium: (Rotate / 90) % 4, negative turns counted backwards.
    val quarterTurns = Math.floorMod(rotate / 90, 4)
    return if (quarterTurns % 2 == 1) PageSize(height, width) else PageSize(width, height)
  }

  private companion object {
    const val MAX_TREE_DEPTH = 64
    const val MAX_PAGES = 1_000_000
    val LETTER = floatArrayOf(0f, 0f, 612f, 792f)

    /** What a read keeps ([ScanBudget.keep]) for a node it has visited and its size. */
    const val NODE_BYTES = 96L
  }
}

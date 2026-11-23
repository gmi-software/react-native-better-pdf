package com.margelo.nitro.betterpdf.outline

/**
 * A page as the page tree describes it: its object number, the box it is
 * displayed with (the crop box clipped to the media box, inheritable, as
 * PDFium and `PdfPageSizeReader`), its clockwise quarter turns, and its
 * annotations: [annotationItems] as `/Annots` lists them (references or,
 * rarely, dictionaries), [annotations] the referenced objects' numbers.
 */
class PdfPageGeometry(val number: Int, val box: FloatArray, val quarterTurns: Int, val annotationItems: List<PdfObject>) {
  val annotations: List<Int> get() = annotationItems.mapNotNull { (it as? PdfObject.Reference)?.number }

  /** A point in PDF page space, as PDF points from the displayed page's top-left corner. */
  fun topLeft(x: Float, y: Float): Pair<Float, Float> {
    val u = x - box[0]
    val v = box[3] - y
    val width = box[2] - box[0]
    val height = box[3] - box[1]
    return when (quarterTurns) {
      1 -> (height - v) to u
      2 -> (width - u) to (height - v)
      3 -> v to (width - u)
      else -> u to v
    }
  }

  /** `[left, top, right, bottom]` from the displayed page's top-left corner, of a page-space rectangle. */
  fun topLeftRect(rect: FloatArray): FloatArray {
    val (x1, y1) = topLeft(rect[0], rect[3])
    val (x2, y2) = topLeft(rect[2], rect[1])
    return floatArrayOf(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
  }
}

/**
 * Every page of the document in order, read from the page tree without
 * opening a page. Cycles are broken; the walk is limited to [MAX_DEPTH]
 * levels, and the pages to what a read may keep ([ScanBudget]).
 */
class PdfPageTree(private val document: PdfDocumentReader, catalog: PdfObject.Dictionary) {
  val pages: List<PdfPageGeometry> = readPages(catalog["Pages"])

  /** Page object number → zero-based index. */
  val indexByObject: Map<Int, Int> by lazy {
    document.keep(INDEX_BYTES * pages.size)
    pages.withIndex().associate { (index, page) -> page.number to index }
  }

  private fun readPages(root: PdfObject?): List<PdfPageGeometry> {
    val pages = ArrayList<PdfPageGeometry>()
    val visited = HashSet<Int>()
    fun visit(node: PdfObject?, mediaBox: FloatArray?, cropBox: FloatArray?, rotate: Int, depth: Int) {
      if (depth > MAX_DEPTH) return
      val reference = node as? PdfObject.Reference ?: return
      if (!visited.add(reference.number)) return
      document.keep(NODE_BYTES)
      // A node's objects are let go once its pages are listed; a page keeps its annotations.
      document.releasing {
        val dictionary = document.resolve(node) as? PdfObject.Dictionary ?: return
        val ownMediaBox = rectangleOf(document, dictionary["MediaBox"]) ?: mediaBox
        val ownCropBox = rectangleOf(document, dictionary["CropBox"]) ?: cropBox
        val ownRotate = (document.resolve(dictionary["Rotate"]) as? PdfObject.Number)?.value?.toInt() ?: rotate
        val kids = document.resolve(dictionary["Kids"]) as? PdfObject.Array
        if (kids != null && dictionary["Type"] != PdfObject.Name("Page")) {
          kids.items.forEach { visit(it, ownMediaBox, ownCropBox, ownRotate, depth + 1) }
          return
        }
        val annotations = document.resolve(dictionary["Annots"]) as? PdfObject.Array
        document.keep(PAGE_BYTES + (annotations?.heldBytes() ?: 0))
        pages += PdfPageGeometry(
          reference.number,
          displayedBox(ownMediaBox, ownCropBox),
          Math.floorMod(ownRotate / 90, 4),
          annotations?.items.orEmpty(),
        )
      }
    }
    visit(root, mediaBox = null, cropBox = null, rotate = 0, depth = 0)
    return pages
  }

  /** The crop box clipped to the media box, as `PdfPageSizeReader` and PDFium. */
  private fun displayedBox(mediaBox: FloatArray?, cropBox: FloatArray?): FloatArray {
    val media = mediaBox?.takeIf { it[2] > it[0] && it[3] > it[1] } ?: LETTER
    return cropBox?.let { crop ->
      floatArrayOf(maxOf(crop[0], media[0]), maxOf(crop[1], media[1]), minOf(crop[2], media[2]), minOf(crop[3], media[3]))
        .takeIf { it[2] > it[0] && it[3] > it[1] }
    } ?: media
  }

  companion object {
    const val MAX_DEPTH = 64
    private val LETTER = floatArrayOf(0f, 0f, 612f, 792f)

    /** What a read keeps ([ScanBudget.keep]) for a node it has visited, a page, and a page in [indexByObject]. */
    private const val NODE_BYTES = 64L
    private const val PAGE_BYTES = 96L
    private const val INDEX_BYTES = 80L

    /** `[llx lly urx ury]` normalized; null unless four numbers. */
    fun rectangleOf(document: PdfDocumentReader, value: PdfObject?): FloatArray? {
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
  }
}

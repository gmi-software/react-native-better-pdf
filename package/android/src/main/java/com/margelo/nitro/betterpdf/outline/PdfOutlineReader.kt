package com.margelo.nitro.betterpdf.outline

/**
 * Reads the document outline (ISO 32000-1 §12.3.3) and resolves every
 * entry to a page index: explicit destinations, GoTo actions, and named
 * destinations from the catalog's `/Dests` dictionary (PDF 1.1) or the
 * `/Names` → `/Dests` name tree.
 *
 * Cycles are broken and the walk is limited to [MAX_DEPTH] levels and
 * [MAX_ENTRIES] entries — the same limits as the iOS implementation — and
 * to what a read may keep ([ScanBudget]).
 */
class PdfOutlineReader(private val document: PdfDocumentReader) {
  private val catalog = document.resolve(document.trailer["Root"]) as? PdfObject.Dictionary
    ?: throw PdfFormatException("No document catalog")
  private val destinations = PdfDestinations(document, catalog, ::indexPages)

  fun read(): List<OutlineEntry> {
    val outlines = document.resolve(catalog["Outlines"]) as? PdfObject.Dictionary ?: return emptyList()
    val entries = ArrayList<OutlineEntry>()
    walk(outlines["First"], depth = 0, entries, visited = HashSet())
    return entries
  }

  private fun walk(first: PdfObject?, depth: Int, entries: MutableList<OutlineEntry>, visited: MutableSet<Int>) {
    var current = first
    while (current != null && entries.size < MAX_ENTRIES) {
      val reference = current as? PdfObject.Reference
      if (reference != null && !visited.add(reference.number)) return
      // An item's objects are let go once it and its children are listed.
      current = document.releasing {
        val item = document.resolve(current) as? PdfObject.Dictionary ?: return
        val title = titleOf(item)
        document.keep(ENTRY_BYTES + 2L * title.length)
        entries += OutlineEntry(title, pageOf(item), depth)
        if (depth + 1 < MAX_DEPTH) walk(item["First"], depth + 1, entries, visited)
        // The next item is read after this one is let go: one written here, not referred to, is kept.
        item["Next"]?.also { if (it !is PdfObject.Reference) document.keep(it.heldBytes()) }
      }
    }
  }

  private fun titleOf(item: PdfObject.Dictionary): String {
    val title = (document.resolve(item["Title"]) as? PdfObject.Str)?.also { document.spend(it.bytes.size) }?.toText() ?: return ""
    // Line breaks and tabs would break a single-line list row.
    return title.replace(CONTROL_CHARACTERS, " ").trim().take(MAX_TITLE_LENGTH)
  }

  private fun pageOf(item: PdfObject.Dictionary): Int? {
    item["Dest"]?.let { return destinations.pageOf(it) }
    val action = document.resolve(item["A"]) as? PdfObject.Dictionary ?: return null
    return destinations.pageOfAction(action)
  }

  /** Page object number → zero-based index, walking the page tree in order. */
  private fun indexPages(): Map<Int, Int> {
    val indices = HashMap<Int, Int>()
    val visited = HashSet<Int>()
    fun visit(node: PdfObject?, depth: Int) {
      if (depth > MAX_TREE_DEPTH || indices.size >= MAX_PAGES) return
      val reference = node as? PdfObject.Reference
      if (reference != null && !visited.add(reference.number)) return
      document.keep(PAGE_BYTES)
      // A node's objects are let go once its pages are numbered.
      document.releasing {
        val dictionary = document.resolve(node) as? PdfObject.Dictionary ?: return
        val kids = document.resolve(dictionary["Kids"]) as? PdfObject.Array
        if (kids != null && dictionary["Type"] != PdfObject.Name("Page")) {
          kids.items.forEach { visit(it, depth + 1) }
        } else if (reference != null) {
          indices[reference.number] = indices.size
        }
      }
    }
    visit(catalog["Pages"], depth = 0)
    return indices
  }

  companion object {
    /** Shared with iOS (`PdfOutlineReader.swift`). */
    const val MAX_DEPTH = 64
    const val MAX_ENTRIES = 10_000
    private const val MAX_TITLE_LENGTH = 512
    private const val MAX_TREE_DEPTH = 64
    private const val MAX_PAGES = 1_000_000

    /** What a read keeps ([ScanBudget.keep]) for an entry besides its title, and for a node of the page tree. */
    private const val ENTRY_BYTES = 96L
    private const val PAGE_BYTES = 160L
    private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]+")
  }
}

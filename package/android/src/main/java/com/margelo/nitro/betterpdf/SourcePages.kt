package com.margelo.nitro.betterpdf

/**
 * Where each of a document's sources has its pages: the sources' pages
 * follow one another in source order (see "Several sources" in
 * docs/api-design.md). Pure; a lookup is a binary search.
 */
internal class SourcePages(pageCounts: List<Int>) {
  init {
    require(pageCounts.isNotEmpty() && pageCounts.all { it > 0 }) { "Every source needs pages" }
  }

  private val firstPages = IntArray(pageCounts.size).also { firsts ->
    var next = 0
    pageCounts.forEachIndexed { index, count ->
      firsts[index] = next
      next += count
    }
  }
  private val pageCounts = pageCounts.toIntArray()

  val pageCount: Int = pageCounts.sum()

  fun firstPage(source: Int): Int = firstPages[source]

  fun pageCount(source: Int): Int = pageCounts[source]

  /** The pages of the document that are [source]'s. */
  fun pagesOf(source: Int): IntRange = firstPages[source] until firstPages[source] + pageCounts[source]

  /** [page] of [source] as a page of the document. */
  fun documentPage(source: Int, page: Int): Int = firstPages[source] + page

  /** [page] of the document as a page of its source. */
  fun locate(page: Int): SourcePage = sourceOf(page).let { SourcePage(it, page - firstPages[it]) }

  /** The source showing [page] of the document. */
  fun sourceOf(page: Int): Int {
    require(page in 0 until pageCount) { "Page $page is outside the document (0–${pageCount - 1})" }
    var low = 0
    var high = firstPages.size - 1
    while (low < high) {
      val middle = (low + high + 1) / 2
      if (firstPages[middle] <= page) low = middle else high = middle - 1
    }
    return low
  }
}

/** A page of one of a document's sources: [page] counts in [source]'s file. */
internal data class SourcePage(val source: Int, val page: Int)

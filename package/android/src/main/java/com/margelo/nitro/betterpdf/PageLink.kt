package com.margelo.nitro.betterpdf

/**
 * A tappable link area on a page: read from the file
 * (`outline.PdfLinkReader`), or from PdfRenderer when the page is first
 * rendered where the file cannot be parsed (encrypted).
 */
sealed interface PageLink {
  /** Areas the link covers; a link spanning several lines has several. */
  val bounds: List<PageRect>

  fun contains(x: Float, y: Float): Boolean = bounds.any { it.contains(x, y) }

  /** Points outside the document; reported to JS as `onLinkPress`. */
  data class Url(override val bounds: List<PageRect>, val url: String) : PageLink

  /**
   * Points to another page of the document; followed natively. [end]: the
   * Named action FirstPage or LastPage, which means the whole document when
   * the document is made of several sources. As read from a file, [page] of
   * NextPage or PrevPage can be one past the file's ends: a neighbouring
   * source's page, as PDFKit has it.
   */
  data class Goto(override val bounds: List<PageRect>, val page: Int, val end: DocumentEnd? = null) : PageLink

  enum class DocumentEnd { FIRST, LAST }
}

/**
 * A link of [source] as a link of the document it is part of, whose pages
 * [pages] places: its page counted across the document, the first and last
 * pages being the document's; null when it leads past the document's ends.
 */
internal fun PageLink.inDocument(pages: SourcePages, source: Int): PageLink? =
  when (this) {
    is PageLink.Url -> this
    is PageLink.Goto -> when (end) {
      PageLink.DocumentEnd.FIRST -> copy(page = 0)
      PageLink.DocumentEnd.LAST -> copy(page = pages.pageCount - 1)
      null -> copy(page = pages.documentPage(source, page)).takeIf { it.page in 0 until pages.pageCount }
    }
  }

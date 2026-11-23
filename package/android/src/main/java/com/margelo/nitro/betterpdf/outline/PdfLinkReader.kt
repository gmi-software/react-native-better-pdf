package com.margelo.nitro.betterpdf.outline

import com.margelo.nitro.betterpdf.PageLink
import com.margelo.nitro.betterpdf.PageRect

/**
 * Every page's link annotations (ISO 32000-1 §12.5.6.5), read from the file:
 * on any Android version, and every form of link — URI actions; GoTo
 * actions and `/Dest` entries with explicit, named (the catalog's `/Dests`
 * or the name tree) and dictionary destinations, `/Fit` and the rest alike;
 * and the Named actions NextPage, PrevPage, FirstPage and LastPage. Areas
 * are the `/QuadPoints` when present (a link across lines), else the
 * `/Rect`, from the displayed page's top-left. Other actions (another file,
 * launching an app, JavaScript) are left out. Limited to [MAX_LINKS] links
 * and to what a read may keep ([ScanBudget]).
 */
class PdfLinkReader(private val document: PdfDocumentReader) {
  private val catalog = document.resolve(document.trailer["Root"]) as? PdfObject.Dictionary
    ?: throw PdfFormatException("No document catalog")
  private val tree = PdfPageTree(document, catalog)

  /** Pages in the page tree: links of a tree the renderer disagrees with land on other pages. */
  val pageCount: Int get() = tree.pages.size

  /** Links by zero-based page; pages without any are absent. */
  fun read(): Map<Int, List<PageLink>> {
    val destinations = PdfDestinations(document, catalog) { tree.indexByObject }
    val result = HashMap<Int, List<PageLink>>()
    var total = 0
    tree.pages.forEachIndexed { index, page ->
      if (total >= MAX_LINKS) return result
      val links = page.annotationItems.mapNotNull { item ->
        // An annotation's objects are let go once its link is made.
        document.releasing {
          val annotation = document.resolve(item) as? PdfObject.Dictionary ?: return@releasing null
          if (annotation["Subtype"] != PdfObject.Name("Link")) return@releasing null
          link(annotation, index, page, pageCount, destinations)?.also { document.keep(heldBytes(it)) }
        }
      }
      if (links.isNotEmpty()) {
        result[index] = links
        total += links.size
      }
    }
    return result
  }

  private fun link(
    annotation: PdfObject.Dictionary,
    pageIndex: Int,
    page: PdfPageGeometry,
    pageCount: Int,
    destinations: PdfDestinations,
  ): PageLink? {
    val bounds = areas(annotation, page).takeIf { it.isNotEmpty() } ?: return null
    val action = document.resolve(annotation["A"]) as? PdfObject.Dictionary
    if (action == null) {
      return destinations.pageOf(annotation["Dest"])?.let { PageLink.Goto(bounds, it) }
    }
    return when ((action["S"] as? PdfObject.Name)?.value) {
      "URI" -> (document.resolve(action["URI"]) as? PdfObject.Str)
        ?.also { document.spend(it.bytes.size) }
        // URIs are 7-bit ASCII (§12.6.4.7).
        ?.bytes?.toString(Charsets.ISO_8859_1)?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { PageLink.Url(bounds, it) }
      "GoTo" -> destinations.pageOf(action["D"])?.let { PageLink.Goto(bounds, it) }
      "Named" -> when ((action["N"] as? PdfObject.Name)?.value) {
        "NextPage" -> PageLink.Goto(bounds, pageIndex + 1)
        "PrevPage" -> PageLink.Goto(bounds, pageIndex - 1)
        "FirstPage" -> PageLink.Goto(bounds, 0, PageLink.DocumentEnd.FIRST)
        "LastPage" -> PageLink.Goto(bounds, pageCount - 1, PageLink.DocumentEnd.LAST)
        else -> null
      }
      else -> null
    }
  }

  private fun heldBytes(link: PageLink): Long =
    LINK_BYTES + AREA_BYTES * link.bounds.size + ((link as? PageLink.Url)?.url?.length ?: 0)

  /** The link's areas: each quadrilateral's bounds, up to [MAX_AREAS] of them, or the rectangle. */
  private fun areas(annotation: PdfObject.Dictionary, page: PdfPageGeometry): List<PageRect> {
    val quads = (document.resolve(annotation["QuadPoints"]) as? PdfObject.Array)?.items
      ?.takeIf { it.size <= 8 * MAX_AREAS }
      ?.map { (document.resolve(it) as? PdfObject.Number)?.value?.toFloat() }
    if (quads != null && quads.size >= 8 && quads.size % 8 == 0 && quads.all { it != null }) {
      return quads.filterNotNull().chunked(8).map { quad ->
        val xs = quad.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = quad.filterIndexed { i, _ -> i % 2 == 1 }
        page.topLeftRect(floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())).toPageRect()
      }
    }
    val rect = PdfPageTree.rectangleOf(document, annotation["Rect"]) ?: return emptyList()
    return listOf(page.topLeftRect(rect).toPageRect())
  }

  private fun FloatArray.toPageRect() = PageRect(this[0], this[1], this[2], this[3])

  private companion object {
    const val MAX_LINKS = 100_000
    const val MAX_AREAS = 256

    /** What a read keeps ([ScanBudget.keep]) for a link besides its address, and for each of its areas. */
    const val LINK_BYTES = 96L
    const val AREA_BYTES = 40L
  }
}

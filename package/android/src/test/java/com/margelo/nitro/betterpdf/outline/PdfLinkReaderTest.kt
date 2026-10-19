package com.margelo.nitro.betterpdf.outline

import com.margelo.nitro.betterpdf.PageLink
import com.margelo.nitro.betterpdf.PageRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fixtures: benchmarks/generate-fixtures.mjs `links` and `links-all`. Areas
 * are in points from the displayed page's top-left (A4: 842 pt tall).
 */
class PdfLinkReaderTest {
  private fun reader(name: String): PdfLinkReader {
    val bytes = javaClass.getResourceAsStream("/outline/$name")!!.use { it.readBytes() }
    return PdfLinkReader(PdfDocumentReader(ByteArraySource(bytes)))
  }

  private fun read(name: String) = reader(name).read()

  private fun area(left: Int, top: Int, right: Int, bottom: Int) =
    PageRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

  @Test
  fun `a URL action and a GoTo action with an explicit destination`() {
    assertEquals(
      mapOf(
        0 to listOf(
          PageLink.Url(listOf(area(0, 0, 357, 421)), "https://example.com/better-pdf"),
          PageLink.Goto(listOf(area(0, 421, 357, 842)), page = 2),
        ),
      ),
      read("links.pdf"),
    )
  }

  /** Page 1's JavaScript action is left out: taps on it are page taps. */
  @Test
  fun `a destination without an action, and a string of the name tree`() {
    assertEquals(
      listOf(
        PageLink.Goto(listOf(area(0, 0, 357, 421)), page = 2),
        PageLink.Goto(listOf(area(0, 421, 357, 842)), page = 3),
      ),
      read("links-all.pdf")[0],
    )
  }

  @Test
  fun `a link across two lines, named destinations, Named actions and a page number`() {
    assertEquals(
      listOf(
        PageLink.Url(listOf(area(40, 72, 400, 97), area(40, 117, 200, 142)), "https://example.com/two-lines"),
        // The catalog's /Dests.
        PageLink.Goto(listOf(area(40, 182, 300, 222)), page = 1),
        // A dictionary in the name tree.
        PageLink.Goto(listOf(area(40, 242, 300, 282)), page = 2),
        // NextPage, PrevPage, FirstPage, LastPage.
        PageLink.Goto(listOf(area(40, 302, 300, 342)), page = 2),
        PageLink.Goto(listOf(area(40, 362, 300, 402)), page = 0),
        PageLink.Goto(listOf(area(40, 422, 300, 462)), page = 0, end = PageLink.DocumentEnd.FIRST),
        PageLink.Goto(listOf(area(40, 482, 300, 522)), page = 4, end = PageLink.DocumentEnd.LAST),
        // The JavaScript action is left out; [4 /Fit] is the fifth page.
        PageLink.Goto(listOf(area(40, 602, 300, 642)), page = 4),
      ),
      read("links-all.pdf")[1],
    )
  }

  @Test
  fun `a rotated page, an annotation in Annots directly, and pages without links`() {
    val links = read("links-all.pdf")
    // Rotate 90: [100 700 300 730] is displayed 30 pt wide and 200 pt tall.
    assertEquals(listOf(PageLink.Url(listOf(area(700, 100, 730, 300)), "https://example.com/rotated")), links[2])
    assertEquals(listOf(PageLink.Goto(listOf(area(40, 102, 300, 142)), page = 2)), links[3])
    assertEquals(setOf(0, 1, 2, 3), links.keys)
    assertEquals(5, reader("links-all.pdf").pageCount)
  }

  @Test
  fun `NextPage and PrevPage at the file's ends lead just past them`() {
    val pdf = TestPdfWriter()
    val (catalog, pages, page, previous, next) = List(5) { pdf.reserve() }
    pdf.add(catalog, "<< /Type /Catalog /Pages $pages 0 R >>")
    pdf.add(pages, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
    pdf.add(page, "<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 595 842] /Annots [$previous 0 R $next 0 R] >>")
    pdf.add(previous, "<< /Type /Annot /Subtype /Link /Rect [0 0 595 421] /A << /S /Named /N /PrevPage >> >>")
    pdf.add(next, "<< /Type /Annot /Subtype /Link /Rect [0 421 595 842] /A << /S /Named /N /NextPage >> >>")
    val links = PdfLinkReader(PdfDocumentReader(ByteArraySource(pdf.build("/Root $catalog 0 R")))).read()
    assertEquals(
      mapOf(0 to listOf(PageLink.Goto(listOf(area(0, 421, 595, 842)), -1), PageLink.Goto(listOf(area(0, 0, 595, 421)), 1))),
      links,
    )
  }

  @Test
  fun `a document without links`() {
    assertEquals(emptyMap<Int, List<PageLink>>(), read("forms.pdf"))
  }

  @Test
  fun `encrypted documents are rejected`() {
    assertThrows(PdfFormatException::class.java) { read("encrypted.pdf") }
  }
}

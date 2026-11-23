package com.margelo.nitro.betterpdf.outline

import com.margelo.nitro.betterpdf.PageLink
import com.margelo.nitro.betterpdf.PdfDocumentLayout.PageSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Large ordinary documents are read fully: what a read may hold
 * ([ScanBudget]) and the readers' own limits leave room for them.
 */
class PdfLargeDocumentTest {
  private fun references(numbers: List<Int>) = numbers.joinToString(" ") { "$it 0 R" }

  /** A name tree of `page.0`, `page.1`, … for [pages], 100 names a leaf; returns its root. */
  private fun destinations(pdf: TestPdfWriter, pages: List<Int>): Int {
    val root = pdf.reserve()
    val leaves = pages.withIndex().chunked(100).map { names ->
      pdf.reserve().also { leaf ->
        pdf.add(leaf, "<< /Names [${names.joinToString(" ") { (index, page) -> "(page.$index) [$page 0 R /Fit]" }}] >>")
      }
    }
    pdf.add(root, "<< /Kids [${references(leaves)}] >>")
    return root
  }

  /** As many links as the link reader lists, in object streams: to an address, or to a page by a named destination. */
  @Test
  fun `100,000 links on 4,000 pages`() {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val tree = pdf.reserve()
    val pages = List(4_000) { pdf.reserve() }
    pages.chunked(1_000).forEachIndexed { part, chunk ->
      val stream = pdf.reserve().also { pdf.objectStream(it, "", deflate = true) }
      chunk.forEachIndexed { offset, page ->
        val index = part * 1_000 + offset
        val links = List(25) { link ->
          val action = if (link % 2 == 0) "/A << /S /URI /URI (https://example.com/$index/$link) >>" else "/Dest (page.${(index + link) % 4_000})"
          pdf.reserve().also { pdf.addCompressed(it, stream, "<< /Type /Annot /Subtype /Link /Rect [0 ${link * 10} 100 ${link * 10 + 10}] $action >>") }
        }
        pdf.add(page, "<< /Type /Page /Parent $tree 0 R /MediaBox [0 0 200 300] /Annots [${references(links)}] >>")
      }
    }
    pdf.add(tree, "<< /Type /Pages /Kids [${references(pages)}] /Count 4000 >>")
    pdf.add(catalog, "<< /Type /Catalog /Pages $tree 0 R /Names << /Dests ${destinations(pdf, pages)} 0 R >> >>")
    val links = PdfLinkReader(PdfDocumentReader(ByteArraySource(pdf.build("/Root $catalog 0 R")))).read()
    assertEquals(4_000, links.size)
    assertEquals(100_000, links.values.sumOf { it.size })
    assertEquals("https://example.com/3999/24", (links.getValue(3999).last() as PageLink.Url).url)
    assertEquals(List(12) { (1000 + 2 * it + 1) % 4_000 }, links.getValue(1000).filterIsInstance<PageLink.Goto>().map { it.page })
  }

  @Test
  fun `100,000 pages`() {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val tree = pdf.reserve()
    val pages = List(100_000) { pdf.reserve() }
    for ((index, page) in pages.withIndex()) {
      pdf.add(page, "<< /Type /Page /Parent $tree 0 R /MediaBox [0 0 ${200 + index % 7} 300] >>")
    }
    pdf.add(tree, "<< /Type /Pages /Kids [${references(pages)}] /Count 100000 >>")
    pdf.add(catalog, "<< /Type /Catalog /Pages $tree 0 R >>")
    val bytes = pdf.build("/Root $catalog 0 R")
    assertEquals(List(100_000) { PageSize(200f + it % 7, 300f) }, PdfPageSizeReader(PdfDocumentReader(ByteArraySource(bytes))).read())
    assertEquals(100_000, PdfLinkReader(PdfDocumentReader(ByteArraySource(bytes))).pageCount)
  }

  /** As many entries as the outline reader lists, each to a named destination. */
  @Test
  fun `10,000 outline entries`() {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val tree = pdf.reserve()
    val outline = pdf.reserve()
    val pages = List(1_000) { pdf.reserve() }
    for (page in pages) pdf.add(page, "<< /Type /Page /Parent $tree 0 R /MediaBox [0 0 200 300] >>")
    val items = List(PdfOutlineReader.MAX_ENTRIES) { pdf.reserve() }
    items.forEachIndexed { index, item ->
      val next = items.getOrNull(index + 1)?.let { "/Next $it 0 R" }.orEmpty()
      pdf.add(item, "<< /Title (Section $index) /Dest (page.${index % 1_000}) $next >>")
    }
    pdf.add(outline, "<< /Type /Outlines /First ${items.first()} 0 R >>")
    pdf.add(tree, "<< /Type /Pages /Kids [${references(pages)}] /Count 1000 >>")
    pdf.add(catalog, "<< /Type /Catalog /Pages $tree 0 R /Outlines $outline 0 R /Names << /Dests ${destinations(pdf, pages)} 0 R >> >>")
    val entries = PdfOutlineReader(PdfDocumentReader(ByteArraySource(pdf.build("/Root $catalog 0 R")))).read()
    assertEquals(List(PdfOutlineReader.MAX_ENTRIES) { OutlineEntry("Section $it", page = it % 1_000, depth = 0) }, entries)
  }

  /** As many fields as the form reader lists, the kids of one field, each its own widget, 100 on a page. */
  @Test
  fun `10,000 form fields`() {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val tree = pdf.reserve()
    val form = pdf.reserve()
    val pages = List(100) { pdf.reserve() }
    val fields = List(10_000) { pdf.reserve() }
    fields.forEachIndexed { index, field ->
      pdf.add(
        field,
        "<< /T (field$index) /Parent $form 0 R /FT /Tx /V (value $index) /DA (/Helv 10 Tf 0 g) /MK << /BC [0 0 0] >> " +
          "/Type /Annot /Subtype /Widget /F 4 /Rect [0 0 100 20] >>",
      )
    }
    pages.forEachIndexed { index, page ->
      pdf.add(page, "<< /Type /Page /Parent $tree 0 R /MediaBox [0 0 200 300] /Annots [${references(fields.subList(index * 100, index * 100 + 100))}] >>")
    }
    pdf.add(form, "<< /T (form) /Kids [${references(fields)}] >>")
    pdf.add(tree, "<< /Type /Pages /Kids [${references(pages)}] /Count 100 >>")
    pdf.add(catalog, "<< /Type /Catalog /Pages $tree 0 R /AcroForm << /Fields [$form 0 R] >> >>")
    val read = PdfFormReader(PdfDocumentReader(ByteArraySource(pdf.build("/Root $catalog 0 R")))).read()
    assertEquals(List(10_000) { "form.field$it" to "value $it" }, read.map { it.name to it.text })
    assertEquals(List(10_000) { it / 100 }, read.map { it.widgets.single().page })
  }

  /** Every field keeps its own copy of its options. */
  @Test
  fun `2,000 choice fields of 100 options`() {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val tree = pdf.reserve()
    val page = pdf.reserve()
    val options = (0 until 100).joinToString(" ") { "[(v$it) (Option number $it)]" }
    val fields = List(2_000) { pdf.reserve() }
    fields.forEachIndexed { index, field ->
      pdf.add(field, "<< /T (choice$index) /FT /Ch /Ff 131072 /Opt [$options] /V (v${index % 100}) /Type /Annot /Subtype /Widget /Rect [0 0 100 20] >>")
    }
    pdf.add(page, "<< /Type /Page /Parent $tree 0 R /MediaBox [0 0 200 300] /Annots [${references(fields)}] >>")
    pdf.add(tree, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
    pdf.add(catalog, "<< /Type /Catalog /Pages $tree 0 R /AcroForm << /Fields [${references(fields)}] >> >>")
    val read = PdfFormReader(PdfDocumentReader(ByteArraySource(pdf.build("/Root $catalog 0 R")))).read()
    assertEquals(List(2_000) { "Option number ${it % 100}" }, read.map { it.text })
    assertEquals(List(100) { "Option number $it" }, read.last().options)
  }
}

package com.margelo.nitro.betterpdf.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The rebuild of broken cross-references: which objects it finds, and which trailer and catalog it picks. */
class PdfCrossReferencesTest {
  private val readers = listOf<Pair<String, (PdfDocumentReader) -> Any>>(
    "links" to { PdfLinkReader(it).read() },
    "outline" to { PdfOutlineReader(it).read() },
    "forms" to { PdfFormReader(it).read() },
    "information" to { PdfInfoReader(it).read() },
    "page sizes" to { PdfPageSizeReader(it).read() },
  )

  private fun resource(name: String) = javaClass.getResourceAsStream("/outline/$name")!!.use { it.readBytes() }

  /** [bytes] with every `startxref` pointing past the end: read by the rebuild. */
  private fun damaged(bytes: ByteArray) =
    bytes.toString(Charsets.ISO_8859_1)
      .replace(Regex("startxref\n\\d+"), "startxref\n999999999")
      .toByteArray(Charsets.ISO_8859_1)

  private fun outline(bytes: ByteArray) = PdfOutlineReader(PdfDocumentReader(ByteArraySource(bytes))).read()

  @Test
  fun `the rebuild finds objects however their headers are spaced`() {
    val file = listOf(
      "%PDF-1.7",
      "1 0 obj (plain) endobj",
      "2\r\n0\r\nobj(lines)endobj",
      "x3\t 0 \u0000obj (after a letter, PDF whitespace) endobj",
      "5 0 obj%comment\n(after a comment) endobj",
      "7 8 9 0 obj (the last two numbers) endobj",
      "6 234567 0 obj (too long for a generation) endobj",
      "91234567890 0 obj (eleven digits) endobj",
      "4 0 objx (not the keyword) endobj",
      "10 0 obj << /Type /Catalog >> endobj",
    ).joinToString("\n")
    // No cross-references and no trailer: the catalog is found among the objects.
    val document = PdfDocumentReader(ByteArraySource(file.toByteArray(Charsets.ISO_8859_1)))
    fun text(number: Int) = (document.resolve(PdfObject.Reference(number, 0)) as? PdfObject.Str)?.toText()
    assertEquals(PdfObject.Reference(10, 0), document.trailer["Root"])
    assertEquals("plain", text(1))
    assertEquals("lines", text(2))
    assertEquals("after a letter, PDF whitespace", text(3))
    assertEquals("after a comment", text(5))
    assertEquals("the last two numbers", text(9))
    assertEquals("too long for a generation", text(234567))
    assertNull(text(1234567890))
    assertNull(text(4))
  }

  /**
   * Files with cross-reference streams have no `trailer`: its entries are in
   * the stream's dictionary, and the catalog is in an object stream.
   */
  @Test
  fun `damaged files with cross-reference streams read like intact ones`() {
    for (name in listOf("outline-compressed.pdf", "forms-compressed.pdf", "metadata-compressed.pdf", "page-boxes-compressed.pdf")) {
      val intact = resource(name)
      for ((reader, read) in readers) {
        assertEquals(
          "$name, $reader",
          read(PdfDocumentReader(ByteArraySource(intact))),
          read(PdfDocumentReader(ByteArraySource(damaged(intact)))),
        )
      }
    }
  }

  /** An incremental update with a cross-reference stream replaces the catalog of a file with a classic table. */
  @Test
  fun `the newest of a trailer and a cross-reference stream names the catalog`() {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val pages = pdf.reserve()
    val page = pdf.reserve()
    val outlines = pdf.reserve()
    val item = pdf.reserve()
    pdf.add(catalog, "<< /Type /Catalog /Pages $pages 0 R /Outlines $outlines 0 R >>")
    pdf.add(pages, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
    pdf.add(page, "<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 200 300] >>")
    pdf.add(outlines, "<< /Type /Outlines /First $item 0 R >>")
    pdf.add(item, "<< /Title (Before the update) /Dest [$page 0 R /Fit] >>")
    val original = pdf.build("/Root $catalog 0 R")
    val updated = appendUpdate(
      original,
      mapOf(
        6 to "<< /Type /Catalog /Pages $pages 0 R /Outlines 7 0 R >>",
        7 to "<< /Type /Outlines /First 8 0 R >>",
        8 to "<< /Title (After the update) /Dest [$page 0 R /Fit] >>",
      ),
      root = 6,
    )
    val expected = listOf(OutlineEntry("After the update", page = 0, depth = 0))
    assertEquals(expected, outline(updated))
    assertEquals(expected, outline(damaged(updated)))
  }

  /** When no trailer names the catalog, it is looked for among the objects, those in object streams too. */
  @Test
  fun `a catalog in an object stream is found without a trailer`() {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val pages = pdf.reserve()
    val page = pdf.reserve()
    val outlines = pdf.reserve()
    val item = pdf.reserve()
    val stream = pdf.reserve()
    pdf.objectStream(stream, "")
    pdf.addCompressed(catalog, stream, "<< /Type /Catalog /Pages $pages 0 R /Outlines $outlines 0 R >>")
    pdf.addCompressed(pages, stream, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
    pdf.addCompressed(page, stream, "<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 200 300] >>")
    pdf.addCompressed(outlines, stream, "<< /Type /Outlines /First $item 0 R >>")
    pdf.addCompressed(item, stream, "<< /Title (Found) /Dest [$page 0 R /Fit] >>")
    // The cross-reference stream's dictionary does not name it either.
    val bytes = damaged(pdf.build("/Root $catalog 0 R")).toString(Charsets.ISO_8859_1)
      .replace("/Root", "/Roof")
      .toByteArray(Charsets.ISO_8859_1)
    assertEquals(listOf(OutlineEntry("Found", page = 0, depth = 0)), outline(bytes))
  }

  /**
   * [original] with an incremental update: [objects] in the file and a
   * cross-reference stream (`/W [1 4 2]`) listing them, naming [root].
   */
  private fun appendUpdate(original: ByteArray, objects: Map<Int, String>, root: Int): ByteArray {
    val previous = Regex("startxref\n(\\d+)").findAll(original.toString(Charsets.ISO_8859_1)).last().groupValues[1]
    val out = ByteArrayOutputStream()
    out.write(original)
    val offsets = objects.mapValues { (number, body) ->
      out.size().also { out.write("$number 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1)) }
    }
    val first = objects.keys.min()
    val stream = objects.keys.max() + 1
    val start = out.size()
    val rows = ByteArrayOutputStream()
    for (number in first..stream) {
      val offset = offsets[number] ?: start
      rows.write(1)
      for (shift in intArrayOf(24, 16, 8, 0)) rows.write(offset ushr shift and 0xff)
      rows.write(0)
      rows.write(0)
    }
    out.write(
      ("$stream 0 obj\n<< /Type /XRef /Size ${stream + 1} /Index [$first ${stream - first + 1}] /W [1 4 2] " +
        "/Length ${rows.size()} /Prev $previous /Root $root 0 R >>\nstream\n").toByteArray(Charsets.ISO_8859_1),
    )
    out.write(rows.toByteArray())
    out.write("\nendstream\nendobj\nstartxref\n$start\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
    return out.toByteArray()
  }
}

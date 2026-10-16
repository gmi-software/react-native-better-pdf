package com.margelo.nitro.betterpdf.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

  /**
   * A one-page document with a classic table and one outline item, [title]:
   * catalog 1, pages 2, page 3, outlines 4, item 5; [add] writes more.
   */
  private fun outlinedDocument(title: String, add: (TestPdfWriter) -> Unit = {}): ByteArray {
    val pdf = TestPdfWriter()
    repeat(5) { pdf.reserve() }
    pdf.add(1, "<< /Type /Catalog /Pages 2 0 R /Outlines 4 0 R >>")
    pdf.add(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    pdf.add(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] >>")
    pdf.add(4, "<< /Type /Outlines /First 5 0 R >>")
    pdf.add(5, "<< /Title ($title) /Dest [3 0 R /Fit] >>")
    add(pdf)
    return pdf.build("/Root 1 0 R")
  }

  /** [bytes] with the classic table's row for object [number] replaced by [row] (20 bytes). */
  private fun withRow(bytes: ByteArray, number: Int, row: String): ByteArray {
    val text = bytes.toString(Charsets.ISO_8859_1)
    val table = text.indexOf("xref\n0 ")
    val rows = text.indexOf('\n', text.indexOf('\n', table) + 1) + 1
    val at = rows + 20 * number
    return (text.substring(0, at) + row + text.substring(at + 20)).toByteArray(Charsets.ISO_8859_1)
  }

  private fun offsetOf(bytes: ByteArray, number: Int) = bytes.toString(Charsets.ISO_8859_1).indexOf("\n$number 0 obj") + 1

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

  /** The table and trailer read, but the catalog's entry is lost: the objects are where the scan finds them. */
  @Test
  fun `a table that does not lead to the catalog is rebuilt`() {
    val intact = outlinedDocument("Found")
    val expected = listOf(OutlineEntry("Found", page = 0, depth = 0))
    assertEquals(expected, outline(withRow(intact, 1, "0000000000 65535 f\r\n")))
    // The catalog's row points at the page tree.
    assertEquals(expected, outline(withRow(intact, 1, "%010d 00000 n\r\n".format(offsetOf(intact, 2)))))
  }

  /** An incremental update adds a catalog, and no trailer names one: the newest is used. */
  @Test
  fun `without a trailer the newest catalog is used`() {
    val updated = appendUpdate(
      outlinedDocument("Before the update"),
      mapOf(
        6 to "<< /Type /Catalog /Pages 2 0 R /Outlines 7 0 R >>",
        7 to "<< /Type /Outlines /First 8 0 R >>",
        8 to "<< /Title (After the update) /Dest [3 0 R /Fit] >>",
      ),
      root = 6,
    )
    val bytes = damaged(updated).toString(Charsets.ISO_8859_1).replace("/Root", "/Roof").toByteArray(Charsets.ISO_8859_1)
    assertEquals(listOf(OutlineEntry("After the update", page = 0, depth = 0)), outline(bytes))
  }

  /** Its header is a token longer than the parser reads. */
  @Test
  fun `an object stream that cannot be listed does not stop the rebuild`() {
    val bytes = outlinedDocument("Found") { pdf ->
      pdf.add(pdf.reserve(), "<< /Type /ObjStm /N 1 /First 0 /Length 1500 >>\nstream\n${"1".repeat(1500)}\nendstream")
    }
    assertEquals(listOf(OutlineEntry("Found", page = 0, depth = 0)), outline(damaged(bytes)))
  }

  /** Each object found is kept until the rebuild ends: millions would exhaust the memory. */
  @Test
  fun `a rebuild of more than a million objects is rejected`() {
    val text = StringBuilder(damaged(outlinedDocument("Found")).toString(Charsets.ISO_8859_1))
    for (number in 6..1_100_000) text.append(number).append(" 0 obj null endobj\n")
    val bytes = text.toString().toByteArray(Charsets.ISO_8859_1)
    assertThrows(PdfFormatException::class.java) { PdfCrossReferences.read(ByteArraySource(bytes)) }
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

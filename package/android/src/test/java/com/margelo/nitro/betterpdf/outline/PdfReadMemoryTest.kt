package com.margelo.nitro.betterpdf.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * What one read holds at once is bounded, whatever the file: the objects
 * its parsers build, what the readers keep of them, the streams it decodes
 * and the cross-references. Each file here is read in a JVM of its own
 * with a heap of [HEAP_MEGABYTES] MB ([readInHeapOf]) and must end in a
 * result or a [PdfFormatException].
 */
class PdfReadMemoryTest {
  /** No read runs out of memory; [failing] end in a [PdfFormatException]. */
  private fun assertBounded(bytes: ByteArray, readers: Collection<String> = READERS.keys, failing: List<String> = emptyList()) {
    val ends = readInHeapOf(HEAP_MEGABYTES, bytes, readers)
    assertEquals(readers.toSet(), ends.keys)
    assertFalse("$ends", ReadEnd.OUT_OF_MEMORY in ends.values)
    for (reader in failing) assertEquals("$ends", ReadEnd.FORMAT_EXCEPTION, ends[reader])
  }

  private val largeArray = "[" + "[]".repeat(1_000_000) + "]"

  @Test
  fun `large arrays in an object stream, under many numbers`() =
    assertBounded(
      aliasedDocument(200) { pdf ->
        val stream = pdf.reserve().also { pdf.objectStream(it, "", deflate = true) }
        List(15) { pdf.reserve().also { number -> pdf.addCompressed(number, stream, largeArray) } }
      },
      failing = listOf("links", "outline", "forms"),
    )

  @Test
  fun `a large array in the file, under many numbers`() =
    assertBounded(
      aliasedDocument(50) { pdf -> listOf(pdf.reserve().also { pdf.add(it, largeArray) }) },
      failing = listOf("links", "outline", "forms"),
    )

  /**
   * A valid one-page document: catalog 1, pages 2, page 3 with [page]'s
   * entries, and what [add] writes.
   */
  private fun document(page: String = "", catalog: String = "", add: (TestPdfWriter) -> Unit = {}): TestPdfWriter {
    val pdf = TestPdfWriter()
    repeat(3) { pdf.reserve() }
    add(pdf)
    pdf.add(1, "<< /Type /Catalog /Pages 2 0 R $catalog >>")
    pdf.add(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    pdf.add(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] $page >>")
    return pdf
  }

  @Test
  fun `one link with a long address, listed 2,000 times`() {
    val pdf = document(page = "/Annots [${"4 0 R ".repeat(2_000)}]") {
      val address = "a".repeat(1_000_000)
      it.add(it.reserve(), "<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] /A << /S /URI /URI ($address) >> >>")
    }
    assertBounded(pdf.build("/Root 1 0 R"), failing = listOf("links"))
  }

  @Test
  fun `2,000 form fields with one long value`() {
    val pdf = TestPdfWriter()
    repeat(4) { pdf.reserve() }
    val fields = List(2_000) { pdf.reserve().also { number -> pdf.add(number, "<< /T (f$number) /FT /Tx /V 4 0 R >>") } }
    pdf.add(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [${fields.joinToString(" ") { "$it 0 R" }}] >> >>")
    pdf.add(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    pdf.add(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] >>")
    pdf.add(4, "(" + "a".repeat(1_000_000) + ")")
    assertBounded(pdf.build("/Root 1 0 R"), failing = listOf("forms"))
  }

  /** The page tree's nodes, an outline destination and a form field make every reader walk it. */
  @Test
  fun `a page tree 60 levels deep whose nodes are large`() {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val page = pdf.reserve()
    val outline = pdf.reserve()
    val item = pdf.reserve()
    val field = pdf.reserve()
    val stream = pdf.reserve().also { pdf.objectStream(it, "", deflate = true) }
    val nodes = List(60) { pdf.reserve() }
    val filler = "[" + "[]".repeat(100_000) + "]"
    nodes.forEachIndexed { level, node ->
      val kid = nodes.getOrNull(level + 1) ?: page
      pdf.addCompressed(node, stream, "<< /Type /Pages /Kids [$kid 0 R] /Count 1 /Filler $filler >>")
    }
    pdf.add(page, "<< /Type /Page /Parent ${nodes.last()} 0 R /MediaBox [0 0 200 300] /Annots [$field 0 R] >>")
    pdf.add(outline, "<< /Type /Outlines /First $item 0 R >>")
    pdf.add(item, "<< /Title (Page) /Dest [$page 0 R /Fit] >>")
    pdf.add(field, "<< /T (f) /FT /Tx /Type /Annot /Subtype /Widget /Rect [0 0 10 10] >>")
    pdf.add(
      catalog,
      "<< /Type /Catalog /Pages ${nodes.first()} 0 R /Outlines $outline 0 R /AcroForm << /Fields [$field 0 R] >> >>",
    )
    assertBounded(pdf.build("/Root $catalog 0 R"), failing = listOf("links", "outline", "forms", "page sizes"))
  }

  /** The object stream's padding gives the read the budget to parse the page 300 times. */
  @Test
  fun `one page with 100,000 annotations, under 300 numbers`() {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val pages = pdf.reserve()
    val link = pdf.reserve()
    val stream = pdf.reserve().also { pdf.objectStream(it, "", padding = 24 * 1024 * 1024, deflate = true) }
    val page = pdf.reserve()
    pdf.addCompressed(
      page,
      stream,
      "<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 200 300] /Annots [${"$link 0 R ".repeat(100_000)}] >>",
    )
    val kids = List(300) { pdf.reserve().also { number -> pdf.alias(number, page) } }
    pdf.add(catalog, "<< /Type /Catalog /Pages $pages 0 R >>")
    pdf.add(pages, "<< /Type /Pages /Kids [${kids.joinToString(" ") { "$it 0 R" }}] /Count 300 >>")
    pdf.add(link, "<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] /A << /S /URI /URI (https://example.com) >> >>")
    assertBounded(pdf.build("/Root $catalog 0 R"), failing = listOf("links"))
  }

  /** Each number is a reference of its own to the reader, whatever it reads there. */
  @Test
  fun `one null object under 990,000 numbers`() {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val tree = pdf.reserve()
    val nothing = pdf.reserve().also { pdf.add(it, "null") }
    val pages = List(18) { pdf.reserve() }
    for (page in pages) {
      val annotations = List(55_000) { pdf.reserve().also { number -> pdf.alias(number, nothing) } }
      pdf.add(page, "<< /Type /Page /Parent $tree 0 R /MediaBox [0 0 200 300] /Annots [${annotations.joinToString(" ") { "$it 0 R" }}] >>")
    }
    pdf.add(tree, "<< /Type /Pages /Kids [${pages.joinToString(" ") { "$it 0 R" }}] /Count 18 >>")
    pdf.add(catalog, "<< /Type /Catalog /Pages $tree 0 R >>")
    assertBounded(pdf.build("/Root $catalog 0 R"), readers = listOf("links"))
  }

  /**
   * What a read holds, its two caches and the cross-references, all near
   * their limits: 900,000 numbers for five links, on 18 pages in an object
   * stream of 30 MB.
   */
  @Test
  fun `a file at every limit at once`() {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val tree = pdf.reserve()
    val links = List(5) { index ->
      pdf.reserve().also {
        pdf.add(it, "<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] /A << /S /URI /URI (https://example.com/$index) >> >>")
      }
    }
    val stream = pdf.reserve().also { pdf.objectStream(it, "", padding = 22 * 1024 * 1024, deflate = true) }
    val pages = List(18) { pdf.reserve() }
    for (page in pages) {
      val annotations = List(50_000) { index -> pdf.reserve().also { number -> pdf.alias(number, links[index % 5]) } }
      pdf.addCompressed(page, stream, "<< /Type /Page /Parent $tree 0 R /MediaBox [0 0 200 300] /Annots [${annotations.joinToString(" ") { "$it 0 R" }}] >>")
    }
    pdf.add(tree, "<< /Type /Pages /Kids [${pages.joinToString(" ") { "$it 0 R" }}] /Count 18 >>")
    pdf.add(catalog, "<< /Type /Catalog /Pages $tree 0 R >>")
    assertBounded(pdf.build("/Root $catalog 0 R"), readers = listOf("links"))
  }

  /** Rows of a cross-reference stream, `/W [1 2 1]`: [objects] in the file at [offsets], then entries at offset 1. */
  private fun crossReferenceRows(offsets: List<Int>, objects: Int): ByteArray {
    val rows = ByteArray(4 * objects)
    for (number in 1 until objects) {
      val offset = offsets.getOrNull(number - 1) ?: 1
      rows[4 * number] = 1
      rows[4 * number + 1] = (offset ushr 8).toByte()
      rows[4 * number + 2] = offset.toByte()
    }
    return rows
  }

  @Test
  fun `a cross-reference stream that lists 4,000,000 objects`() {
    val out = ByteArrayOutputStream()
    fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
    write("%PDF-1.7\n")
    val offsets = listOf(
      "<< /Type /Catalog /Pages 2 0 R >>",
      "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
      "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] >>",
    ).mapIndexed { index, body -> out.size().also { write("${index + 1} 0 obj\n$body\nendobj\n") } }
    val start = out.size()
    val objects = 4_000_000
    val data = deflate(crossReferenceRows(offsets + start, objects))
    write("4 0 obj\n<< /Type /XRef /Size $objects /W [1 2 1] /Filter /FlateDecode /Length ${data.size} /Root 1 0 R >>\nstream\n")
    out.write(data)
    write("\nendstream\nendobj\nstartxref\n$start\n%%EOF\n")
    assertBounded(out.toByteArray())
  }

  @Test
  fun `a cross-reference table that lists 2,000,000 objects`() {
    val out = ByteArrayOutputStream()
    fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
    write("%PDF-1.7\n")
    val offsets = listOf(
      "<< /Type /Catalog /Pages 2 0 R >>",
      "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
      "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] >>",
    ).mapIndexed { index, body -> out.size().also { write("${index + 1} 0 obj\n$body\nendobj\n") } }
    val start = out.size()
    val objects = 2_000_000
    write("xref\n0 $objects\n0000000000 65535 f\r\n")
    for (offset in offsets) write("%010d 00000 n\r\n".format(offset))
    val row = "0000000009 00000 n\r\n".toByteArray(Charsets.ISO_8859_1)
    repeat(objects - 1 - offsets.size) { out.write(row) }
    write("trailer\n<< /Size $objects /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n")
    // 40 MB: one reader, which reads the cross-references like the others.
    assertBounded(out.toByteArray(), readers = listOf("links"))
  }

  /**
   * 30 object streams over the same 8 MB of the file, each read to resolve
   * the `/Filter` of the one before: the page's crop box is in the first.
   */
  @Test
  fun `object streams that share their data, each the filter of the one before`() {
    val levels = 30
    val firstStream = 4
    val firstFilter = firstStream + levels
    val cropBox = firstFilter + levels
    val xrefStream = cropBox + 1
    val out = ByteArrayOutputStream()
    fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
    val offsets = HashMap<Int, Int>()
    fun add(number: Int, body: String) {
      offsets[number] = out.size()
      write("$number 0 obj\n$body\nendobj\n")
    }
    write("%PDF-1.7\n")
    add(1, "<< /Type /Catalog /Pages 2 0 R >>")
    add(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    add(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] /CropBox $cropBox 0 R >>")
    val shared = 8 * 1024 * 1024
    // Every stream's dictionary has the same length, so each /Length is known before it is written.
    fun header(level: Int, length: Int) =
      "${firstStream + level} 0 obj\n<< /Type /ObjStm /N 1 /First 0 /Length %010d /Filter ${firstFilter + level} 0 R >>\nstream\n"
        .format(length)
    val headerLength = header(0, 0).length
    for (level in 0 until levels) {
      offsets[firstStream + level] = out.size()
      write(header(level, (levels - 1 - level) * headerLength + shared))
    }
    out.write(ByteArray(shared) { ' '.code.toByte() })
    write("\nendstream\nendobj\n")
    val start = out.size()
    offsets[xrefStream] = start
    val rows = ByteArrayOutputStream()
    for (number in 0..xrefStream) {
      val offset = offsets[number]
      val (type, field) = when {
        offset != null -> 1 to offset
        number == cropBox -> 2 to firstStream
        // The filter of stream `level` is in stream `level + 1`.
        number >= firstFilter -> 2 to number - firstFilter + firstStream + 1
        else -> 0 to 0
      }
      rows.write(type)
      for (shift in intArrayOf(24, 16, 8, 0)) rows.write(field ushr shift and 0xff)
      rows.write(0)
      rows.write(0)
    }
    write("$xrefStream 0 obj\n<< /Type /XRef /Size ${xrefStream + 1} /W [1 4 2] /Length ${rows.size()} /Root 1 0 R >>\nstream\n")
    out.write(rows.toByteArray())
    write("\nendstream\nendobj\nstartxref\n$start\n%%EOF\n")
    assertBounded(out.toByteArray(), readers = listOf("links", "page sizes"))
  }

  private companion object {
    const val HEAP_MEGABYTES = 128
  }
}

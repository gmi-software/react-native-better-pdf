package com.margelo.nitro.betterpdf.outline

import com.margelo.nitro.betterpdf.PageLink
import com.margelo.nitro.betterpdf.PageRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The streams the readers decode — object streams and cross-reference
 * streams — had no limit on a read's total: an object stream that could not
 * be decoded was read and inflated again for every object needed from it,
 * and every decoded object stream was kept. A few MB of small compressed
 * streams expanding to 31 MB each took gigabytes. A read now decodes a
 * bounded amount, linear in the file's size, and keeps a bounded amount;
 * an object stream that cannot be decoded costs only its own objects.
 */
class PdfDecodeTimeTest {
  private val readers = listOf<Pair<String, (PdfDocumentReader) -> Unit>>(
    "links" to { PdfLinkReader(it).read() },
    "outline" to { PdfOutlineReader(it).read() },
    "forms" to { PdfFormReader(it).read() },
    "information" to { PdfInfoReader(it).read() },
    "page sizes" to { PdfPageSizeReader(it).read() },
  )

  private val urlLink = PageLink.Url(listOf(PageRect(0f, 290f, 10f, 300f)), "https://example.com")

  private fun <T> quickly(name: String, read: () -> T): T? {
    val started = System.nanoTime()
    val result = try {
      read()
    } catch (expected: PdfFormatException) {
      null
    }
    val millis = (System.nanoTime() - started) / 1_000_000
    assertTrue("$name took $millis ms", millis < 1_000)
    return result
  }

  private fun assertEveryReaderEndsQuickly(bytes: ByteArray) {
    for ((name, read) in readers) quickly(name) { read(PdfDocumentReader(ByteArraySource(bytes))) }
  }

  /** [bytes] with every `startxref` pointing past the end: read by the rebuild. */
  private fun damaged(bytes: ByteArray) =
    bytes.toString(Charsets.ISO_8859_1)
      .replace(Regex("startxref\n\\d+"), "startxref\n999999999")
      .toByteArray(Charsets.ISO_8859_1)

  /**
   * A valid one-page document with a URL link in the file and [streams]
   * object streams, all with the same data: [objects] link annotations
   * ([body]) and [padding] spaces, deflated when [deflate], else with
   * [filter]. The page lists the URL link and [references] links from the
   * object streams, a link from each stream in turn.
   */
  private fun objectStreamDocument(
    streams: Int,
    references: Int,
    objects: Int = 1,
    body: String = "<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] >>",
    padding: Int = 0,
    deflate: Boolean = false,
    filter: String = "",
  ): ByteArray {
    val header = (0 until objects).joinToString(" ", postfix = " ") { "0 ${it * (body.length + 1)}" }
    val plain = (header + "$body\n".repeat(objects)).toByteArray(Charsets.ISO_8859_1) + ByteArray(padding) { ' '.code.toByte() }
    val data = if (deflate) deflate(plain) else plain
    val filterEntry = if (deflate) "/Filter /FlateDecode" else filter

    val out = ByteArrayOutputStream()
    fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
    val firstStream = 5
    val firstAnnotation = firstStream + streams
    val xrefStream = firstAnnotation + streams * objects
    val offsets = HashMap<Int, Int>()
    fun add(number: Int, body: String) {
      offsets[number] = out.size()
      write("$number 0 obj\n$body\nendobj\n")
    }
    write("%PDF-1.7\n")
    add(1, "<< /Type /Catalog /Pages 2 0 R >>")
    add(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    val annotations = List(references) { "${firstAnnotation + it % streams * objects + it / streams % objects} 0 R" }
    add(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] /Annots [4 0 R ${annotations.joinToString(" ")}] >>")
    add(4, "<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] /A << /S /URI /URI (https://example.com) >> >>")
    for (number in firstStream until firstAnnotation) {
      offsets[number] = out.size()
      write("$number 0 obj\n<< /Type /ObjStm /N $objects /First ${header.length} /Length ${data.size} $filterEntry >>\nstream\n")
      out.write(data)
      write("\nendstream\nendobj\n")
    }
    val start = out.size()
    offsets[xrefStream] = start
    val rows = ByteArrayOutputStream()
    for (number in 0..xrefStream) {
      val offset = offsets[number]
      val compressed = number - firstAnnotation
      val (type, field, index) = when {
        offset != null -> Triple(1, offset, 0)
        compressed >= 0 -> Triple(2, firstStream + compressed / objects, compressed % objects)
        else -> Triple(0, 0, 0)
      }
      rows.write(type)
      for (shift in intArrayOf(24, 16, 8, 0)) rows.write(field ushr shift and 0xff)
      rows.write(index ushr 8 and 0xff)
      rows.write(index and 0xff)
    }
    write("$xrefStream 0 obj\n<< /Type /XRef /Size ${xrefStream + 1} /W [1 4 2] /Length ${rows.size()} /Root 1 0 R >>\nstream\n")
    out.write(rows.toByteArray())
    write("\nendstream\nendobj\nstartxref\n$start\n%%EOF\n")
    return out.toByteArray()
  }

  /** The links of [bytes], read within a second, and how many bytes the read read. */
  private fun links(bytes: ByteArray): Pair<Map<Int, List<PageLink>>?, Long> {
    val source = CountingSource(bytes)
    return quickly("links") { PdfLinkReader(PdfDocumentReader(source)).read() } to source.bytesRead
  }

  /** Inflating it fails at the decoder's limit, 32 MB: each object needed from it used to inflate it again. */
  @Test
  fun `an object stream that expands beyond the limit, needed 100 times`() {
    val bytes = objectStreamDocument(1, references = 100, padding = 33 * 1024 * 1024, deflate = true)
    val (links, bytesRead) = links(bytes)
    assertEquals(mapOf(0 to listOf(urlLink)), links)
    assertTrue("read $bytesRead bytes of ${bytes.size}", bytesRead < 4L * bytes.size)
    assertEveryReaderEndsQuickly(bytes)
  }

  /** Each object needed from it used to read its 4 MB again. */
  @Test
  fun `an object stream with an unsupported filter, needed 5,000 times`() {
    val bytes = objectStreamDocument(1, references = 5_000, padding = 4 * 1024 * 1024, filter = "/Filter /LZWDecode")
    val (links, bytesRead) = links(bytes)
    assertEquals(mapOf(0 to listOf(urlLink)), links)
    assertTrue("read $bytesRead bytes of ${bytes.size}", bytesRead < 4L * bytes.size)
    assertEveryReaderEndsQuickly(bytes)
  }

  /** 36 MB in all, more than a read keeps: needed in turn, each is decoded again. */
  @Test
  fun `three object streams of 12 MB, needed in turn`() {
    val bytes = objectStreamDocument(
      3,
      references = 9,
      objects = 3,
      body = "<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] /A << /S /URI /URI (https://example.com) >> >>",
      padding = 12 * 1024 * 1024,
    )
    assertEquals(mapOf(0 to List(10) { urlLink }), links(bytes).first)
  }

  /** Data that is not compressed has no limit of its own: 40 MB would be kept. */
  @Test
  fun `an object stream of 40 MB is not read`() {
    val bytes = objectStreamDocument(
      1,
      references = 1,
      body = "<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] /A << /S /URI /URI (https://example.com) >> >>",
      padding = 40 * 1024 * 1024,
    )
    val (links, bytesRead) = links(bytes)
    assertEquals(mapOf(0 to listOf(urlLink)), links)
    assertTrue("read $bytesRead bytes of ${bytes.size}", bytesRead < 1024 * 1024)
  }

  /** 3 MB of file, 3 GB decoded and kept. */
  @Test
  fun `100 object streams that expand to 31 MB each`() {
    val bytes = objectStreamDocument(100, references = 100, padding = 31 * 1024 * 1024, deflate = true)
    assertEveryReaderEndsQuickly(bytes)
    // The rebuild decodes every object stream in the file.
    assertEveryReaderEndsQuickly(damaged(bytes))
  }

  /** Every section of a `/Prev` chain is decoded: 1.9 GB from a 2 MB file. */
  @Test
  fun `60 cross-reference streams that expand to 31 MB each`() {
    val out = ByteArrayOutputStream()
    fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
    write("%PDF-1.7\n")
    val offsets = listOf(
      "<< /Type /Catalog /Pages 2 0 R >>",
      "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
      "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] >>",
    ).mapIndexed { index, body ->
      out.size().also { write("${index + 1} 0 obj\n$body\nendobj\n") }
    }
    // The older sections list nothing: the same data each.
    val empty = deflate(ByteArray(31 * 1024 * 1024))
    var previous: Int? = null
    repeat(60) { section ->
      val start = out.size()
      val newest = section == 59
      val data = if (newest) {
        val rows = ByteArrayOutputStream()
        for (offset in listOf(0) + offsets) {
          rows.write(if (offset == 0) 0 else 1)
          for (shift in intArrayOf(24, 16, 8, 0)) rows.write(offset ushr shift and 0xff)
          rows.write(0)
          rows.write(0)
        }
        deflate(rows.toByteArray() + ByteArray(31 * 1024 * 1024))
      } else {
        empty
      }
      val index = if (newest) "/Index [0 4]" else "/Index []"
      write(
        "${4 + section} 0 obj\n<< /Type /XRef /Size ${5 + section} /W [1 4 2] $index /Filter /FlateDecode " +
          "/Length ${data.size} ${previous?.let { "/Prev $it" }.orEmpty()} /Root 1 0 R >>\nstream\n",
      )
      out.write(data)
      write("\nendstream\nendobj\n")
      previous = start
    }
    write("startxref\n$previous\n%%EOF\n")
    val bytes = out.toByteArray()
    quickly("cross-references") {
      assertThrows(PdfFormatException::class.java) { PdfCrossReferences.read(ByteArraySource(bytes)) }
    }
  }
}

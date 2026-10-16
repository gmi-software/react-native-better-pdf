package com.margelo.nitro.betterpdf.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objects whose parse runs on to the end of the file — a stream without
 * `endstream`, an unterminated string — used to be parsed once per object,
 * or once per reference to them, in time growing with the square of the
 * file's size: seconds for a few hundred KB, minutes for a few MB, on every
 * open. Every reader now ends within a second, in a result or a
 * [PdfFormatException], and reads the file a bounded number of times.
 */
class PdfReadTimeTest {
  private val readers = listOf<Pair<String, (PdfDocumentReader) -> Unit>>(
    "links" to { PdfLinkReader(it).read() },
    "outline" to { PdfOutlineReader(it).read() },
    "forms" to { PdfFormReader(it).read() },
    "information" to { PdfInfoReader(it).read() },
    "page sizes" to { PdfPageSizeReader(it).read() },
  )

  private fun assertEveryReaderEndsQuickly(bytes: ByteArray) {
    for ((name, read) in readers) {
      val started = System.nanoTime()
      try {
        read(PdfDocumentReader(ByteArraySource(bytes)))
      } catch (expected: PdfFormatException) {
        // Fine.
      }
      val millis = (System.nanoTime() - started) / 1_000_000
      assertTrue("$name took $millis ms", millis < 1_000)
    }
  }

  /** `n 0 obj` and [body] for n = 1, 2, … up to [size] bytes; no cross-reference data, no trailer. */
  private fun repeatedObjects(body: String, size: Int): ByteArray {
    val text = StringBuilder("%PDF-1.7\n")
    var number = 1
    while (text.length < size) text.append(number++).append(" 0 obj").append(body)
    return text.toString().toByteArray(Charsets.ISO_8859_1)
  }

  /**
   * A valid one-page document whose page lists [count] link annotations;
   * [add] writes the objects and returns the page's `/Annots` entries.
   */
  private fun linkDocument(count: Int, add: (TestPdfWriter, annotations: List<Int>) -> List<Int>): ByteArray {
    val pdf = TestPdfWriter()
    val catalog = pdf.reserve()
    val pages = pdf.reserve()
    val page = pdf.reserve()
    val annotations = add(pdf, List(count) { pdf.reserve() })
    pdf.add(catalog, "<< /Type /Catalog /Pages $pages 0 R >>")
    pdf.add(pages, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
    pdf.add(
      page,
      "<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 200 300] /Annots [${annotations.joinToString(" ") { "$it 0 R" }}] >>",
    )
    return pdf.build("/Root $catalog 0 R")
  }

  /** The issue's file: each object's stream is scanned for an `endstream` until the end of the file. */
  @Test
  fun `359 KB of streams without endstream or cross-references`() =
    assertEveryReaderEndsQuickly(repeatedObjects("<<>>stream\n", 359 * 1024))

  @Test
  fun `359 KB of unterminated strings without cross-references`() =
    assertEveryReaderEndsQuickly(repeatedObjects("(", 359 * 1024))

  /** Read through valid cross-references, on every open. */
  @Test
  fun `links that are streams without endstream`() =
    assertEveryReaderEndsQuickly(
      linkDocument(8_000) { pdf, annotations ->
        annotations.forEach { pdf.add(it, "<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] >>\nstream") }
        annotations
      },
    )

  @Test
  fun `links with unterminated strings`() =
    assertEveryReaderEndsQuickly(
      linkDocument(4_000) { pdf, annotations ->
        annotations.forEach { pdf.add(it, "<< /Type /Annot /Subtype /Link /Contents (") }
        annotations
      },
    )

  @Test
  fun `links in an object stream with unterminated strings`() =
    assertEveryReaderEndsQuickly(
      linkDocument(10_000) { pdf, annotations ->
        val stream = pdf.reserve()
        pdf.objectStream(stream, "")
        annotations.forEach { pdf.addCompressed(it, stream, "<< /Subtype /Link /Contents (") }
        annotations
      },
    )

  /** An object that cannot be read is parsed again for each reference to it. */
  @Test
  fun `one unterminated object behind every link`() =
    assertEveryReaderEndsQuickly(
      linkDocument(1) { pdf, (annotation) ->
        pdf.add(annotation, "<< /Subtype /Link /Contents (" + " ".repeat(200_000))
        List(4_000) { annotation }
      },
    )

  /**
   * More than 2 KB after `%%EOF` hid `startxref` and made every read
   * rebuild the cross-references by scanning the whole file.
   */
  @Test
  fun `a file with 4 KB after %%EOF is read through its own cross-references`() {
    val document = linkDocument(1) { pdf, (annotation) ->
      // 1 MB the readers never look at.
      val padding = "x".repeat(1024 * 1024)
      pdf.add(pdf.reserve(), "<< /Length ${padding.length} >>\nstream\n$padding\nendstream")
      pdf.add(annotation, "<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] /A << /S /URI /URI (https://example.com) >> >>")
      listOf(annotation)
    }
    val bytes = document + ByteArray(4 * 1024)
    val source = CountingSource(bytes)
    assertEquals(
      PdfLinkReader(PdfDocumentReader(ByteArraySource(document))).read(),
      PdfLinkReader(PdfDocumentReader(source)).read(),
    )
    // A rebuild reads every byte.
    assertTrue("read ${source.bytesRead} of ${bytes.size} bytes", source.bytesRead < bytes.size / 4)
  }

  /** A parser used to read 64 KB ahead for every object it parsed. */
  @Test
  fun `a file of small objects is read a few times over, not once per object`() {
    val bytes = repeatedObjects("<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] >>\nendobj\n", 1024 * 1024)
    for ((name, read) in readers) {
      val source = CountingSource(bytes)
      try {
        read(PdfDocumentReader(source))
      } catch (expected: PdfFormatException) {
        // No catalog.
      }
      assertTrue("$name read ${source.bytesRead} bytes of ${bytes.size}", source.bytesRead < 16L * bytes.size)
    }
  }
}

package com.margelo.nitro.betterpdf.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each read of a document (links, outline, page sizes, forms, metadata)
 * used to read its cross-references again, and to rebuild a damaged file's
 * by scanning all of it. Each read has its own byte source, as each of
 * `DocumentReads` duplicates the file descriptor.
 */
class CrossReferenceCacheTest {
  private val readers = listOf<(PdfDocumentReader) -> Unit>(
    { PdfLinkReader(it).read() },
    { PdfOutlineReader(it).read() },
    { PdfFormReader(it).read() },
    { PdfInfoReader(it).read() },
    { PdfPageSizeReader(it).read() },
  )

  /** A 1 MB stream no reader looks at: rebuilding reads it, reading objects does not. */
  private val padding = "<< /Length 1048576 >>\nstream\n${"x".repeat(1024 * 1024)}\nendstream"

  @Test
  fun `a damaged file is rebuilt once for all of its readers`() {
    val bytes = deepChainDocument(List(8) { ChainStep.LENGTH }, padding = padding).toString(Charsets.ISO_8859_1)
      .replace(Regex("startxref\n\\d+"), "startxref\n999999999")
      .toByteArray(Charsets.ISO_8859_1)
    val cache = CrossReferenceCache()
    val bytesRead = readers.map { read ->
      val source = CountingSource(bytes)
      read(PdfDocumentReader(source, cache.get(source)))
      source.bytesRead
    }
    assertTrue("the first read read ${bytesRead[0]} of ${bytes.size} bytes", bytesRead[0] >= bytes.size)
    for (count in bytesRead.drop(1)) assertTrue("a later read read $count of ${bytes.size} bytes", count < bytes.size / 4)
  }

  @Test
  fun `a file that cannot be rebuilt fails every reader without being read again`() {
    val bytes = "%PDF-1.7\n1 0 obj\n$padding\nendobj\n".toByteArray(Charsets.ISO_8859_1)
    val cache = CrossReferenceCache()
    val bytesRead = List(3) {
      val source = CountingSource(bytes)
      assertThrows(PdfFormatException::class.java) { cache.get(source) }
      source.bytesRead
    }
    assertTrue(bytesRead[0] >= bytes.size)
    assertEquals(listOf(0L, 0L), bytesRead.drop(1))
  }
}

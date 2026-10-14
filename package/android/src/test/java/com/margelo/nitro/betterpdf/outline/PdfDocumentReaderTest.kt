package com.margelo.nitro.betterpdf.outline

import com.margelo.nitro.betterpdf.PageLink
import com.margelo.nitro.betterpdf.PageRect
import com.margelo.nitro.betterpdf.PdfDocumentLayout.PageSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A crafted chain of objects, each needed to read the one before, used to
 * recurse once per object until the stack overflowed — on the thread that
 * reads links, which crashed the app. Reading such a chain stops at a
 * nesting limit, and every reader still reads the rest of the document.
 */
class PdfDocumentReaderTest {
  private fun <T> read(bytes: ByteArray, reader: (PdfDocumentReader) -> T): T =
    onSmallStack { reader(PdfDocumentReader(ByteArraySource(bytes))) }

  private fun assertEveryReaderEnds(bytes: ByteArray) {
    assertEquals(
      mapOf(0 to listOf(PageLink.Url(listOf(PageRect(0f, 100f, 100f, 200f)), "https://example.com/deep"))),
      read(bytes) { PdfLinkReader(it).read() },
    )
    assertEquals(listOf(OutlineEntry("Level 0", page = null, depth = 0)), read(bytes) { PdfOutlineReader(it).read() })
    val field = read(bytes) { PdfFormReader(it).read() }.single()
    assertEquals("f0" to FormFieldKind.TEXT, field.name to field.kind)
    assertEquals("", field.text)
    assertEquals(PdfInfo(title = "Deep"), read(bytes) { PdfInfoReader(it).read() })
    // The crop box is unreadable: the media box applies.
    assertEquals(listOf(PageSize(200f, 300f)), read(bytes) { PdfPageSizeReader(it).read() })
  }

  @Test
  fun `a chain of 2,000 streams with an indirect Length`() =
    assertEveryReaderEnds(deepChainDocument(List(2_000) { ChainStep.LENGTH }))

  @Test
  fun `a chain of 2,000 object streams`() =
    assertEveryReaderEnds(deepChainDocument(List(2_000) { ChainStep.OBJECT_STREAM }))

  @Test
  fun `a chain of 2,000 object streams through their Filter`() =
    assertEveryReaderEnds(deepChainDocument(List(2_000) { ChainStep.OBJECT_STREAM_FILTER }))

  /** Within the limit: the results above are the document's, not the limit's. */
  @Test
  fun `a short chain reads the same`() {
    val bytes = deepChainDocument(List(8) { ChainStep.LENGTH })
    val document = PdfDocumentReader(ByteArraySource(bytes))
    // The first stream's /Length is the second stream, not a number: its end is found by scanning.
    val head = document.resolve(PdfObject.Reference(1, 0)) as PdfObject.Stream
    assertEquals("chain", document.streamData(head).toString(Charsets.ISO_8859_1))
    assertEveryReaderEnds(bytes)
  }
}

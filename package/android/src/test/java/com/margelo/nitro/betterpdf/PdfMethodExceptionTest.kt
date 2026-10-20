package com.margelo.nitro.betterpdf

import com.margelo.nitro.betterpdf.PdfMethodException.Code
import com.margelo.nitro.betterpdf.outline.EncryptedPdfException
import com.margelo.nitro.betterpdf.outline.PdfFormatException
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PdfMethodExceptionTest {
  @Test
  fun `the message starts with the code`() {
    val error = PdfMethodException(Code.NOT_LOADED, "No document is loaded")
    assertEquals("[not-loaded] No document is loaded", error.message)
    // fbjni hands JS the stack trace as printStackTrace writes it; PdfMethodError.ts reads its first line.
    val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    assertEquals(
      "com.margelo.nitro.betterpdf.PdfMethodException: [not-loaded] No document is loaded",
      trace.lineSequence().first(),
    )
  }

  @Test
  fun `codes are PdfMethodErrorCode's`() {
    assertEquals(
      listOf(
        "not-loaded", "closed", "invalid-argument", "not-supported", "not-permitted",
        "busy", "io-error", "invalid-pdf", "out-of-memory", "unknown",
      ),
      Code.entries.map { it.value },
    )
  }

  @Test
  fun `other failures get the code their type implies`() {
    val closed = PdfMethodException(Code.CLOSED, "The document was closed")
    assertSame(closed, closed.toMethodException())
    assertEquals(Code.OUT_OF_MEMORY, OutOfMemoryError().toMethodException().code)
    assertEquals("[io-error] Could not write x", IOException("Could not write x").toMethodException().message)
    assertEquals(Code.INVALID_PDF, PdfFormatException("No trailer").toMethodException().code)
    assertEquals(Code.NOT_SUPPORTED, EncryptedPdfException().toMethodException().code)
    val unknown = IllegalStateException("boom").toMethodException()
    assertEquals("[unknown] boom", unknown.message)
    assertEquals(Code.UNKNOWN, NullPointerException().toMethodException().code)
    assertEquals(
      "[out-of-memory] Out of memory while reading the form",
      OutOfMemoryError().toMethodException("Out of memory while reading the form").message,
    )
  }
}

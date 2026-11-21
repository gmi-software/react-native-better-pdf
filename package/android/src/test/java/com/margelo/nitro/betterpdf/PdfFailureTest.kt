package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfFailureTest {
  @Test
  fun `the source URI is redacted in the cause's text too`() {
    val uri = "content://com.app.provider/doc/1?token=t"
    val cause = SecurityException("Permission Denial: reading com.app.Provider uri $uri from pid=1")
    val failure = PdfFailure(PdfErrorCode.FILE_NOT_FOUND, "No permission to read", cause = cause, uri = uri)
    val expected = "SecurityException: Permission Denial: reading com.app.Provider uri content://com.app.provider/doc/1?… from pid=1"
    assertEquals(expected, failure.toPdfError().nativeDescription)
    assertEquals(expected, failure.ofSource(1).toPdfError().nativeDescription)
  }
}

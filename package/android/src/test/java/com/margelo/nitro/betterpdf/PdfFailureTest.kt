package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfFailureTest {
  private fun nativeDescription(causeMessage: String): String? {
    val failure = PdfFailure(PdfErrorCode.FILE_NOT_FOUND, "No permission to read", cause = SecurityException(causeMessage))
    assertEquals(failure.toPdfError().nativeDescription, failure.ofSource(1).toPdfError().nativeDescription)
    return failure.toPdfError().nativeDescription
  }

  @Test
  fun `URIs in the cause's text are redacted, whatever their spelling`() {
    // The source was content://com.app.provider//doc/1?token=t; the platform
    // may quote it normalized.
    assertEquals(
      "SecurityException: Permission Denial: reading com.app.Provider uri content://com.app.provider/doc/1?… from pid=1",
      nativeDescription("Permission Denial: reading com.app.Provider uri content://com.app.provider/doc/1?token=t from pid=1"),
    )
    assertEquals(
      "SecurityException: No content at \"https://host/x.pdf?…\", then (http://other/a)",
      nativeDescription("No content at \"https://u:p@host/x.pdf?sig=s\", then (http://other/a)"),
    )
  }

  @Test
  fun `text without a URI is kept`() {
    assertEquals("SecurityException: Permission denied: a:b", nativeDescription("Permission denied: a:b"))
  }
}

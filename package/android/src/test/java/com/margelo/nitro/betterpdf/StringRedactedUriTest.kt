package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Test

class StringRedactedUriTest {
  /** The same table as iOS's, in test/loader/loader.test.ts. */
  private val cases = listOf(
    "https://u:p@host/x.pdf?sig=s#part" to "https://host/x.pdf?…",
    "https://u:p@ss@host:8443/a/b.pdf?x=1" to "https://host:8443/a/b.pdf?…",
    "http://u:p@[::1]:8080/x.pdf?sig=s" to "http://[::1]:8080/x.pdf?…",
    "https://host?sig=s" to "https://host?…",
    "https://u:p@host" to "https://host",
    "https://host/x.pdf#part?sig=s" to "https://host/x.pdf",
    "https://host/x.pdf?" to "https://host/x.pdf?…",
    "https://host/token-in-path/x.pdf" to "https://host/token-in-path/x.pdf",
    "https://host/%C3%BC/ü.pdf?x" to "https://host/%C3%BC/ü.pdf?…",
    "HTTPS://Host/X.pdf" to "HTTPS://Host/X.pdf",
    "content://com.app.provider/doc/1?token=t" to "content://com.app.provider/doc/1?…",
    "ftp://u:p@127.0.0.1/x.pdf?sig=s#part" to "ftp://127.0.0.1/x.pdf?…",
    "document.pdf" to "<not a URI>",
    "data:application/pdf;base64,JVBERi0=" to "<not a URI>",
    "http:host/x.pdf?sig=s" to "<not a URI>",
    "1http://host/x.pdf" to "<not a URI>",
    "u:p@host/x.pdf?sig=s" to "<not a URI>",
    "" to "<not a URI>",
  )

  @Test
  fun `keeps scheme, host, port and path, and hides the rest`() {
    for ((uri, expected) in cases) assertEquals(uri, expected, uri.redactedUri())
  }
}

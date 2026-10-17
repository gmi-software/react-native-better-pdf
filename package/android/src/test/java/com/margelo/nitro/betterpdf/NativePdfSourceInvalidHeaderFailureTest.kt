package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NativePdfSourceInvalidHeaderFailureTest {
  private fun failure(headers: Map<String, String>?) =
    NativePdfSource("https://x.test/a.pdf", base64 = null, headers, cache = null, maxAgeMs = null, cacheKey = null).invalidHeaderFailure()

  @Test
  fun `token names and values without control characters are valid`() {
    assertNull(failure(null))
    assertNull(
      failure(
        mapOf(
          "Authorization" to "Bearer abc",
          "X-Api-Key" to "a b\tc",
          "X-Name" to "zażółć",
          "!#$%&'*+-.^_`|~09azAZ" to "v",
          "X-Empty" to "",
        ),
      ),
    )
  }

  @Test
  fun `a value with a line break or another control character is invalid`() {
    for (value in listOf("Bearer a\nb", "Bearer a\r\n", "a\r", "a\u0000b", "a\u000bb", "a\u007f")) {
      val failure = failure(mapOf("Authorization" to value))
      assertEquals(value, PdfErrorCode.INVALID_SOURCE, failure?.code)
      assertEquals("Invalid value for request header \"Authorization\"", failure?.message)
    }
  }

  @Test
  fun `a name that is not a token is invalid`() {
    for (name in listOf("", "X Test", "X:Test", "X-Tëst", "X\nTest", "X(1)", "X\u007f")) {
      val failure = failure(mapOf(name to "v"))
      assertEquals(name, PdfErrorCode.INVALID_SOURCE, failure?.code)
      assertEquals("Invalid request header name: \"$name\"", failure?.message)
    }
  }

  @Test
  fun `the message names the first invalid header by name and never shows a value`() {
    val failure = failure(mapOf("B" to "secret\n1", "A" to "ok", "C" to "secret\n2"))
    assertEquals("Invalid value for request header \"B\"", failure?.message)
    assertFalse(failure!!.message.contains("secret"))
  }
}

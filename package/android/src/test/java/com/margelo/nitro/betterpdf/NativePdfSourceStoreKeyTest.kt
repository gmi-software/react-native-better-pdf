package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePdfSourceStoreKeyTest {
  private fun source(
    uri: String = "https://x.test/a.pdf",
    headers: Map<String, String>? = null,
    cache: PdfCacheMode? = null,
    maxAgeMs: Double? = null,
    cacheKey: String? = null,
  ) = NativePdfSource(uri, base64 = null, headers, cache, maxAgeMs, cacheKey)

  @Test
  fun `the key ignores header order and cache options`() {
    assertEquals(
      source(headers = linkedMapOf("A" to "1", "B" to "2")).storeKey(),
      source(headers = linkedMapOf("B" to "2", "A" to "1"), cache = PdfCacheMode.PERSISTENT, maxAgeMs = 0.0).storeKey(),
    )
  }

  @Test
  fun `the key depends on the uri and every header`() {
    val base = source(headers = mapOf("A" to "1")).storeKey()
    assertNotEquals(base, source(uri = "https://x.test/b.pdf", headers = mapOf("A" to "1")).storeKey())
    assertNotEquals(base, source(headers = mapOf("A" to "2")).storeKey())
    assertNotEquals(base, source().storeKey())
  }

  @Test
  fun `a cacheKey replaces the uri and headers`() {
    assertEquals(
      source(uri = "https://x.test/a.pdf?token=1", cacheKey = "doc").storeKey(),
      source(uri = "https://x.test/a.pdf?token=2", headers = mapOf("A" to "1"), cacheKey = "doc").storeKey(),
    )
    assertNotEquals(source(uri = "doc").storeKey(), source(cacheKey = "doc").storeKey())
  }

  @Test
  fun `sources with the same cacheKey are the same document`() {
    assertTrue(source(uri = "https://x.test/a?t=1", cacheKey = "doc").isSameSource(source(uri = "https://x.test/a?t=2", cacheKey = "doc")))
    assertFalse(source(cacheKey = "doc").isSameSource(source(cacheKey = "other")))
    assertFalse(source(cacheKey = "doc").isSameSource(source()))
  }

  @Test
  fun `cache options alone never reload`() {
    assertTrue(source().isSameSource(source(cache = PdfCacheMode.PERSISTENT, maxAgeMs = 0.0)))
    assertFalse(source().isSameSource(source(headers = mapOf("A" to "1"))))
  }
}

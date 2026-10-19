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
    password: String? = null,
  ) = NativePdfSource(uri, base64 = null, headers, cache, maxAgeMs, cacheKey, password)

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

  @Test
  fun `a changed password reloads, but is the same downloaded copy`() {
    assertFalse(source(password = "a").isSameSource(source(password = "b")))
    assertFalse(source(cacheKey = "doc").isSameSource(source(cacheKey = "doc", password = "a")))
    assertTrue(source(password = "").isSameSource(source()))
    assertEquals(source().storeKey(), source(password = "a").storeKey())
  }

  @Test
  fun `lists of sources are the same when every source is, in order`() {
    val a = source(uri = "https://x.test/a.pdf")
    val b = source(uri = "https://x.test/b.pdf")
    assertTrue(arrayOf(a, b).isSameSources(arrayOf(a, b.copy(maxAgeMs = 0.0))))
    assertFalse(arrayOf(a, b).isSameSources(arrayOf(b, a)))
    assertFalse(arrayOf(a, b).isSameSources(arrayOf(a)))
    assertFalse(arrayOf(a).isSameSources(null))
    assertTrue((null as Array<NativePdfSource>?).isSameSources(null))
  }
}

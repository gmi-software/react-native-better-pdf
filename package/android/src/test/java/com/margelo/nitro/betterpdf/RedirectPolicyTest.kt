package com.margelo.nitro.betterpdf

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectPolicyTest {
  private val start = URL("http://example.com/a/doc.pdf")

  private fun RedirectPolicy.follow(from: URL, location: String): URL =
    (next(from, location) as? RedirectPolicy.Next.Follow)?.url ?: throw AssertionError("Expected $location to be followed")

  @Test
  fun `resolves relative locations against the redirecting URL`() {
    val policy = RedirectPolicy(start)
    assertEquals(URL("http://example.com/a/b.pdf"), policy.follow(start, "b.pdf"))
    assertEquals(URL("http://example.com/b.pdf"), policy.follow(start, "/b.pdf"))
    assertEquals(URL("http://cdn.example.com/b.pdf"), policy.follow(start, "//cdn.example.com/b.pdf"))
  }

  @Test
  fun `keeps the headers while the origin stays the same`() {
    val policy = RedirectPolicy(start)
    policy.follow(start, "http://EXAMPLE.com:80/b.pdf")
    policy.follow(URL("http://example.com/b.pdf"), "/c.pdf")
    assertTrue(policy.sendsHeaders)

    val https = URL("https://example.com/doc.pdf")
    val secure = RedirectPolicy(https)
    secure.follow(https, "https://example.com:443/b.pdf")
    assertTrue(secure.sendsHeaders)
  }

  @Test
  fun `drops the headers for the rest of the chain after another host, port or scheme`() {
    for (location in listOf("http://cdn.example.com/b.pdf", "http://example.com:8080/b.pdf", "https://example.com/b.pdf")) {
      val policy = RedirectPolicy(start)
      policy.follow(start, location)
      assertFalse(location, policy.sendsHeaders)
    }
    val policy = RedirectPolicy(start)
    val away = policy.follow(start, "http://cdn.example.com/b.pdf")
    policy.follow(away, start.toString())
    assertFalse(policy.sendsHeaders)
  }

  @Test
  fun `refuses https to http, other schemes and unusable locations`() {
    val https = URL("https://example.com/doc.pdf")
    assertEquals(RedirectPolicy.Next.Refuse, RedirectPolicy(https).next(https, "http://example.com/doc.pdf"))
    for (location in listOf(null, "ftp://example.com/doc.pdf", "file:///doc.pdf", "unknown:doc", "javascript:alert(1)")) {
      assertEquals(location, RedirectPolicy.Next.Refuse, RedirectPolicy(start).next(start, location))
    }
  }

  @Test
  fun `follows ten redirects, not eleven`() {
    val policy = RedirectPolicy(start)
    repeat(RedirectPolicy.MAX_REDIRECTS) { policy.follow(start, "/doc.pdf") }
    assertEquals(RedirectPolicy.Next.TooMany, policy.next(start, "/doc.pdf"))
  }
}

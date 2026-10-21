package com.margelo.nitro.betterpdf

import java.net.MalformedURLException
import java.net.URL

/**
 * The redirects one download follows, the same as iOS's `RedirectPolicy`: at
 * most [MAX_REDIRECTS], to `http(s)` only, never from https to http, and the
 * caller's headers only until the first hop to another origin.
 */
internal class RedirectPolicy(first: URL) {
  sealed interface Next {
    data class Follow(val url: URL) : Next

    /** The redirect response is the answer. */
    data object Refuse : Next

    data object TooMany : Next
  }

  private val origin = Origin(first)
  private var followed = 0

  var sendsHeaders = true
    private set

  fun next(from: URL, location: String?): Next {
    val to = try {
      URL(from, location ?: return Next.Refuse)
    } catch (_: MalformedURLException) {
      return Next.Refuse
    }
    if (to.protocol != "http" && to.protocol != "https") return Next.Refuse
    if (from.protocol == "https" && to.protocol == "http") return Next.Refuse
    if (followed == MAX_REDIRECTS) return Next.TooMany
    followed++
    if (Origin(to) != origin) sendsHeaders = false
    return Next.Follow(to)
  }

  private data class Origin(val scheme: String, val host: String, val port: Int) {
    constructor(url: URL) : this(url.protocol, url.host.lowercase(), if (url.port == -1) url.defaultPort else url.port)
  }

  companion object {
    const val MAX_REDIRECTS = 10

    /** The statuses that redirect, as in URLSession. */
    val STATUSES = setOf(301, 302, 303, 307, 308)
  }
}

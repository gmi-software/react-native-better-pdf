package com.margelo.nitro.betterpdf

/**
 * This URI as error messages show it: `scheme://host[:port]/path`, `?…` in
 * place of a query, no user-info or fragment. Apps log messages, and those
 * parts often hold a token. Anything but a `scheme://` URI is not shown.
 * Split by hand, the same way as on iOS, so both platforms show the same text.
 */
internal fun String.redactedUri(): String {
  val scheme = SCHEME.find(this) ?: return NOT_A_URI
  val authorityEnd = indexOfAny(charArrayOf('/', '?', '#'), scheme.range.last + 1).takeIf { it >= 0 } ?: length
  val hostStart = lastIndexOf('@', authorityEnd - 1).takeIf { it > scheme.range.last }?.plus(1) ?: (scheme.range.last + 1)
  val pathEnd = indexOfAny(charArrayOf('?', '#'), authorityEnd).takeIf { it >= 0 } ?: length
  val query = if (getOrNull(pathEnd) == '?') "?…" else ""
  return scheme.value + substring(hostStart, pathEnd) + query
}

/** This text with every `scheme://` URI in it replaced by its [redactedUri]. */
internal fun String.redactingUris(): String = URI.replace(this) { it.value.redactedUri() }

private val URI = Regex("[A-Za-z][A-Za-z0-9+.-]*://[^\\s\"'<>]+")

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

private const val NOT_A_URI = "<not a URI>"

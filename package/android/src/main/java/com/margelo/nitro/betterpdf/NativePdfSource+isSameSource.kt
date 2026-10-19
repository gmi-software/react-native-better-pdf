package com.margelo.nitro.betterpdf

/**
 * Whether two sources show the same document, so a re-sent source never
 * reloads. Sources with a `cacheKey` are the same document when their keys
 * are equal, whatever their `uri` and headers (signed URLs, rotating tokens).
 * Cache options alone never reload; a changed password does. Base64 sources
 * compare by buffer.
 */
internal fun NativePdfSource.isSameSource(other: NativePdfSource): Boolean {
  if (base64 !== other.base64) return false
  if (password.orEmpty() != other.password.orEmpty()) return false
  if (cacheKey != null || other.cacheKey != null) return cacheKey == other.cacheKey
  return uri == other.uri && headers.orEmpty() == other.headers.orEmpty()
}

/** Whether two lists of sources show the same documents, in order. */
internal fun Array<NativePdfSource>?.isSameSources(other: Array<NativePdfSource>?): Boolean {
  if (this == null || other == null) return this == null && other == null
  return size == other.size && indices.all { this[it].isSameSource(other[it]) }
}

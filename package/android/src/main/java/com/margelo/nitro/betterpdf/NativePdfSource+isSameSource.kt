package com.margelo.nitro.betterpdf

import com.margelo.nitro.core.ArrayBuffer

/**
 * Whether two sources show the same document, so a re-sent source never
 * reloads. Sources with a `cacheKey` are the same document when their keys
 * are equal, whatever their `uri` and headers (signed URLs, rotating tokens).
 * Cache options alone never reload; a changed password does. Base64 sources
 * compare by buffer memory: each prop conversion wraps the same buffer in a
 * new [ArrayBuffer].
 */
internal fun NativePdfSource.isSameSource(other: NativePdfSource): Boolean {
  if (!isSameBuffer(base64, other.base64)) return false
  if (password.orEmpty() != other.password.orEmpty()) return false
  if (cacheKey != null || other.cacheKey != null) return cacheKey == other.cacheKey
  return uri == other.uri && headers.orEmpty() == other.headers.orEmpty()
}

/** Whether two lists of sources show the same documents, in order. */
internal fun Array<NativePdfSource>?.isSameSources(other: Array<NativePdfSource>?): Boolean {
  if (this == null || other == null) return this == null && other == null
  return size == other.size && indices.all { this[it].isSameSource(other[it]) }
}

private fun isSameBuffer(lhs: ArrayBuffer?, rhs: ArrayBuffer?): Boolean =
  isSameBuffer(lhs, rhs, { it.isOwner }, { it.size }) {
    NativeBase64Document.bufferAddress(it.getBuffer(false))
  }

/**
 * Whether two buffers are the same memory. Only owning buffers compare: a
 * JS-owned one can be freed and its address reused. [size] and [address]
 * are read only for owning buffers (a JS-owned one throws off the JS thread),
 * and [address] only when the sizes match and are not zero.
 */
internal fun <T : Any> isSameBuffer(
  lhs: T?,
  rhs: T?,
  isOwner: (T) -> Boolean,
  size: (T) -> Int,
  address: (T) -> Long,
): Boolean {
  if (lhs == null || rhs == null) return lhs == null && rhs == null
  if (!isOwner(lhs) || !isOwner(rhs)) return false
  val lhsSize = size(lhs)
  if (lhsSize != size(rhs)) return false
  return lhsSize == 0 || address(lhs) == address(rhs)
}

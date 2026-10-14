package com.margelo.nitro.betterpdf

/**
 * Why this source's headers cannot be sent, or null: every name must be an
 * HTTP token (RFC 9110) and no value may contain a control character other
 * than tab, e.g. a line break. Checked before any request because the
 * platforms disagree on the rest: Android throws from `setRequestProperty`,
 * iOS drops the header or sends it as is. Values never appear in the
 * message: they may hold a token.
 */
internal fun NativePdfSource.invalidHeaderFailure(): PdfFailure? {
  for ((name, value) in headers.orEmpty().toSortedMap()) {
    if (name.isEmpty() || name.any { it !in TOKEN_CHARACTERS }) {
      return PdfFailure(PdfErrorCode.INVALID_SOURCE, "Invalid request header name: \"$name\"")
    }
    if (value.any { (it < ' ' && it != '\t') || it == '\u007f' }) {
      return PdfFailure(PdfErrorCode.INVALID_SOURCE, "Invalid value for request header \"$name\"")
    }
  }
  return null
}

private val TOKEN_CHARACTERS = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "!#$%&'*+-.^_`|~".toList()).toSet()

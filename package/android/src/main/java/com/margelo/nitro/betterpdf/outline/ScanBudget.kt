package com.margelo.nitro.betterpdf.outline

/**
 * How many bytes the parsers of one read may examine: [UNITS_PER_BYTE] for
 * each byte of the file ([bytes]) and of the object streams it decodes, and
 * [BASE_UNITS] more. Objects whose parse runs on to the end of the file — a
 * stream without `endstream`, an unterminated string — let each of N
 * objects, or each of N references to one, scan the rest of the file, in
 * time growing with the square of its size; the limits on nesting and
 * sizes do not bound that. Within the budget the time grows linearly.
 *
 * Beyond it every read fails with [Exceeded], which the readers do not
 * swallow like an unreadable object: the whole read fails.
 */
internal class ScanBudget(bytes: Long) {
  private var remaining = BASE_UNITS + UNITS_PER_BYTE * bytes

  /** Room for parsing [bytes] more: a decoded object stream. */
  fun extend(bytes: Int) {
    remaining += UNITS_PER_BYTE * bytes
  }

  /** One byte examined. */
  fun spend() {
    if (--remaining < 0) throw Exceeded()
  }

  class Exceeded : PdfFormatException("The PDF's structure takes too long to read")

  private companion object {
    const val UNITS_PER_BYTE = 16L
    const val BASE_UNITS = 1L shl 20
  }
}

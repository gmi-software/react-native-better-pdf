package com.margelo.nitro.betterpdf.outline

/**
 * How much work one read may do, linear in the size of its file ([bytes]).
 *
 * - The bytes its parsers examine: [UNITS_PER_BYTE] for each byte of the
 *   file and of the object streams it decodes, and [BASE_UNITS] more.
 *   Objects whose parse runs on to the end of the file — a stream without
 *   `endstream`, an unterminated string — let each of N objects, or each of
 *   N references to one, scan the rest of the file, in time growing with
 *   the square of its size; the limits on nesting and sizes do not bound
 *   that.
 * - The bytes of streams it reads and decodes: [STREAM_BYTES_PER_BYTE] for
 *   each byte of the file, and [BASE_STREAM_BYTES] more. Small compressed
 *   streams expand up to 32 MB each, and a file can have many.
 *
 * Beyond either every read fails with [Exceeded], which the readers do not
 * swallow like an unreadable object: the whole read fails.
 */
internal class ScanBudget(bytes: Long) {
  private var remaining = BASE_UNITS + UNITS_PER_BYTE * bytes
  private var streamBytes = BASE_STREAM_BYTES + STREAM_BYTES_PER_BYTE * bytes

  /** Room for parsing [bytes] more: a decoded object stream. */
  fun extend(bytes: Int) {
    remaining += UNITS_PER_BYTE * bytes
  }

  /** One byte examined. */
  fun spend() {
    if (--remaining < 0) throw Exceeded("The PDF's structure takes too long to read")
  }

  /** [count] bytes of a stream read from the file, or decoded. */
  fun spendOnStream(count: Int) {
    streamBytes -= count
    if (streamBytes < 0) throw Exceeded("The PDF's streams take too long to decode")
  }

  class Exceeded(message: String) : PdfFormatException(message)

  private companion object {
    const val UNITS_PER_BYTE = 16L
    const val BASE_UNITS = 1L shl 20
    const val STREAM_BYTES_PER_BYTE = 16L
    const val BASE_STREAM_BYTES = 64L shl 20
  }
}

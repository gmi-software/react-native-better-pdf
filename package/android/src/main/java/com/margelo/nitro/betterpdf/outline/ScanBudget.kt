package com.margelo.nitro.betterpdf.outline

/**
 * How much one read may do and hold. Its work is linear in the size of its
 * file ([bytes]); what it holds at once has a fixed limit.
 *
 * - The bytes its parsers examine: [UNITS_PER_BYTE] for each byte of the
 *   file and of the object streams it decodes, and [BASE_UNITS] more.
 *   Objects whose parse runs on to the end of the file — a stream without
 *   `endstream`, an unterminated string — let each of N objects, or each of
 *   N references to one, scan the rest of the file, in time growing with
 *   the square of its size; the limits on nesting and sizes do not bound
 *   that. Object streams add at most [EXTENSION_BYTES_PER_BYTE] times the
 *   file and [BASE_EXTENSION_BYTES]: they decode to far more than the file.
 *   An object read before is not examined again, but it is gone through
 *   again: it and the strings a reader decodes count too ([spend]).
 * - The bytes of streams it reads and decodes: [STREAM_BYTES_PER_BYTE] for
 *   each byte of the file, and [BASE_STREAM_BYTES] more. Small compressed
 *   streams expand up to 32 MB each, and a file can have many.
 * - What it holds ([hold]): [MAX_HELD_BYTES] of the objects its parsers
 *   build, of the stream data it reads and decodes, and of what its readers
 *   make of them, as [HeldBytes] estimates them. Work is no measure of it:
 *   a few bytes parse into an object of twenty times their size, an object
 *   can be read under many numbers, and a reader can copy one it was given
 *   for nothing.
 *
 * Beyond any of them every read fails with [Exceeded], which the readers do
 * not swallow like an unreadable object: the whole read fails.
 */
internal class ScanBudget(bytes: Long) {
  private var remaining = BASE_UNITS + UNITS_PER_BYTE * bytes
  private var streamBytes = BASE_STREAM_BYTES + STREAM_BYTES_PER_BYTE * bytes
  private var extension = BASE_EXTENSION_BYTES + EXTENSION_BYTES_PER_BYTE * bytes

  /** What the read holds now, in bytes. */
  var held = 0L
    private set

  /** The part of [held] that stays for the rest of the read. */
  private var kept = 0L
  private var keeping = false

  /** Room for parsing [bytes] more: a decoded object stream. */
  fun extend(bytes: Int) {
    val granted = minOf(bytes.toLong(), extension)
    extension -= granted
    remaining += UNITS_PER_BYTE * granted
  }

  /** One byte examined. */
  fun spend() {
    if (--remaining < 0) throw Exceeded("The PDF's structure takes too long to read")
  }

  /** [units] of other work: an object taken from the cache, a string decoded. */
  fun spend(units: Long) {
    remaining -= units
    if (remaining < 0) throw Exceeded("The PDF's structure takes too long to read")
  }

  /** [count] bytes of a stream read from the file, or decoded. */
  fun spendOnStream(count: Int) {
    streamBytes -= count
    if (streamBytes < 0) throw Exceeded("The PDF's streams take too long to decode")
  }

  /** [bytes] more held, until the [releasing] block around it ends. */
  fun hold(bytes: Long) {
    held += bytes
    if (keeping) kept += bytes
    if (held > MAX_HELD_BYTES) throw Exceeded("The PDF's structure takes too much memory to read")
  }

  fun hold(bytes: Int) = hold(bytes.toLong())

  /** [bytes] more held for the rest of the read: what a reader returns or keeps. */
  fun keep(bytes: Long) {
    kept += bytes
    held += bytes
    if (held > MAX_HELD_BYTES) throw Exceeded("The PDF's structure takes too much memory to read")
  }

  /**
   * Runs [block], then counts what it held as let go, apart from what was
   * kept. Nothing else it read or built may be reachable once it returns,
   * except through the reader's caches, which have limits of their own.
   */
  inline fun <T> releasing(block: () -> T): T {
    val heldBefore = held
    val keptBefore = kept
    val keepingBefore = keeping
    keeping = false
    try {
      return block()
    } finally {
      keeping = keepingBefore
      held = heldBefore + (kept - keptBefore)
    }
  }

  /** Runs [block]; what it holds is kept ([keep]), a [releasing] block inside it apart. */
  inline fun <T> keeping(block: () -> T): T {
    val keepingBefore = keeping
    keeping = true
    try {
      return block()
    } finally {
      keeping = keepingBefore
    }
  }

  class Exceeded(message: String) : PdfFormatException(message)

  companion object {
    /** The most a read holds at once ([hold]). */
    const val MAX_HELD_BYTES = 32L shl 20
    private const val UNITS_PER_BYTE = 16L
    private const val BASE_UNITS = 1L shl 20
    private const val STREAM_BYTES_PER_BYTE = 16L
    private const val BASE_STREAM_BYTES = 64L shl 20
    private const val EXTENSION_BYTES_PER_BYTE = 16L
    private const val BASE_EXTENSION_BYTES = 4L shl 20
  }
}

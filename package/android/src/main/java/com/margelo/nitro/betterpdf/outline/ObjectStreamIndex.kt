package com.margelo.nitro.betterpdf.outline

/** A decoded object stream: object `i` starts at `first + offsets[i]` in [data]. */
internal class ObjectStreamIndex(val data: ByteArray, val first: Int, val offsets: IntArray) {
  /** What it takes on the heap, kept by a reader: an empty one takes [ENTRY_BYTES]. */
  val bytes: Long get() = ENTRY_BYTES + data.size + Int.SIZE_BYTES.toLong() * offsets.size

  companion object {
    const val MAX_OBJECTS = 100_000
    private const val ENTRY_BYTES = 128L
  }
}

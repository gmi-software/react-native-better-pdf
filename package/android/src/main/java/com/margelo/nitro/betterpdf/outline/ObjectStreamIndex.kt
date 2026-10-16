package com.margelo.nitro.betterpdf.outline

/** A decoded object stream: object `i` starts at `first + offsets[i]` in [data]. */
internal class ObjectStreamIndex(val data: ByteArray, val first: Int, val offsets: IntArray) {
  companion object {
    const val MAX_OBJECTS = 100_000
  }
}

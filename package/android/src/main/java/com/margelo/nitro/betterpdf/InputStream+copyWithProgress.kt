package com.margelo.nitro.betterpdf

import java.io.InputStream
import java.io.OutputStream

/**
 * Copies the rest of this stream to [output] in chunks, reporting the bytes
 * copied after each and once more on completion; a total smaller than that
 * is reported as unknown. Throws [InterruptedException] once [isCancelled].
 */
internal fun InputStream.copyWithProgress(
  output: OutputStream,
  totalBytes: Long?,
  isCancelled: () -> Boolean,
  onProgress: (loadedBytes: Long, totalBytes: Long?, isComplete: Boolean) -> Unit,
) {
  val buffer = ByteArray(COPY_CHUNK_BYTES)
  var loaded = 0L
  fun report(isComplete: Boolean) = onProgress(loaded, totalBytes?.takeIf { it >= loaded }, isComplete)
  while (true) {
    if (isCancelled()) throw InterruptedException()
    val count = read(buffer)
    if (count < 0) break
    output.write(buffer, 0, count)
    loaded += count
    report(isComplete = false)
  }
  report(isComplete = true)
}

private const val COPY_CHUNK_BYTES = 64 * 1024

package com.margelo.nitro.betterpdf.outline

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Positional reads (`pread`) over a file channel, so the channel's shared
 * position is never moved: safe while PdfRenderer reads the same file.
 */
class FileChannelByteSource(private val channel: FileChannel) : PdfByteSource {
  override val size: Long = channel.size()

  override fun read(position: Long, length: Int): ByteArray {
    val count = minOf(length.toLong(), size - position).coerceAtLeast(0).toInt()
    val buffer = ByteBuffer.allocate(count)
    var at = position
    while (buffer.hasRemaining()) {
      val read = channel.read(buffer, at)
      if (read <= 0) break
      at += read
    }
    return if (buffer.hasRemaining()) buffer.array().copyOf(buffer.position()) else buffer.array()
  }
}

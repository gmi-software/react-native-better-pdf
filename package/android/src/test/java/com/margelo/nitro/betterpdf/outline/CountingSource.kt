package com.margelo.nitro.betterpdf.outline

/** In-memory bytes that count how many bytes are read. */
internal class CountingSource(bytes: ByteArray) : PdfByteSource {
  private val bytes = ByteArraySource(bytes)
  var bytesRead = 0L
    private set

  override val size: Long get() = bytes.size

  override fun read(position: Long, length: Int): ByteArray = bytes.read(position, length).also { bytesRead += it.size }
}

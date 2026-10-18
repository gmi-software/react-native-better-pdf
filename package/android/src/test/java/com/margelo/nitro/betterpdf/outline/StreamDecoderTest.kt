package com.margelo.nitro.betterpdf.outline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Deflater

class StreamDecoderTest {
  @Test
  fun `a predictor stream shorter than one row decodes to nothing, without allocating the row`() {
    // 32 colours × 16 bits × 2^20 columns: a 64 MiB row, declared by a stream of a few bytes.
    val stream = flateStream(ByteArray(8), predictor(colors = 32, bitsPerComponent = 16, columns = 1 shl 20))
    var decoded: ByteArray? = null
    val allocated = allocatedBytes { decoded = stream.decode() }
    assertArrayEquals(ByteArray(0), decoded)
    assertTrue("Decoding allocated $allocated bytes", allocated < 1 shl 20)
  }

  @Test
  fun `a predictor stream of exactly one row decodes that row`() {
    val stream = flateStream(byteArrayOf(2, 5, 6, 7), predictor(colors = 1, bitsPerComponent = 8, columns = 3))
    assertArrayEquals(byteArrayOf(5, 6, 7), stream.decode())
  }

  @Test
  fun `predictor rows add the row above`() {
    val stream = flateStream(byteArrayOf(2, 1, 2, 3, 2, 1, 1, 1), predictor(colors = 1, bitsPerComponent = 8, columns = 3))
    assertArrayEquals(byteArrayOf(1, 2, 3, 2, 3, 4), stream.decode())
  }

  private class EncodedStream(val bytes: ByteArray, val stream: PdfObject.Stream) {
    fun decode(): ByteArray = StreamDecoder.read(ByteArraySource(bytes), stream, ScanBudget(bytes.size.toLong())) { it }
  }

  private fun predictor(colors: Int, bitsPerComponent: Int, columns: Int) = PdfObject.Dictionary(
    mapOf(
      "Predictor" to PdfObject.Number(12.0),
      "Colors" to PdfObject.Number(colors.toDouble()),
      "BitsPerComponent" to PdfObject.Number(bitsPerComponent.toDouble()),
      "Columns" to PdfObject.Number(columns.toDouble()),
    ),
  )

  private fun flateStream(data: ByteArray, parameters: PdfObject.Dictionary): EncodedStream {
    val deflater = Deflater()
    deflater.setInput(data)
    deflater.finish()
    val buffer = ByteArray(256)
    val length = deflater.deflate(buffer)
    deflater.end()
    val dictionary = PdfObject.Dictionary(mapOf("Filter" to PdfObject.Name("FlateDecode"), "DecodeParms" to parameters))
    return EncodedStream(buffer.copyOf(length), PdfObject.Stream(dictionary, dataOffset = 0, dataLength = length))
  }

  /** Through reflection: unit tests compile against android.jar, which has no `java.lang.management`. */
  private fun allocatedBytes(block: () -> Unit): Long {
    val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    val allocated = Class.forName("com.sun.management.ThreadMXBean")
      .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
    val id = Thread.currentThread().id
    val before = allocated.invoke(threads, id) as Long
    block()
    return allocated.invoke(threads, id) as Long - before
  }
}

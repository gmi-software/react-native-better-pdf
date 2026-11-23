package com.margelo.nitro.betterpdf.outline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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

  /** Above 4 MB the data is inflated twice, the second time into an array of its size. */
  @Test
  fun `a stream of 6 MB decodes to its data`() {
    val data = ByteArray(6 * 1024 * 1024) { (it * 31 + it / 977).toByte() }
    assertArrayEquals(data, flateStream(data).decode())
  }

  @Test
  fun `a stream that ends on a block's edge decodes to its data`() {
    for (size in intArrayOf(64 * 1024, 4 * 1024 * 1024, 4 * 1024 * 1024 + 64 * 1024)) {
      val data = ByteArray(size) { (it / 3).toByte() }
      assertArrayEquals(data, flateStream(data).decode())
    }
  }

  /** What decoded before the damage is kept, as viewers do, however long it is. */
  @Test
  fun `a damaged stream of 6 MB decodes to what precedes the damage`() {
    val data = ByteArray(6 * 1024 * 1024) { (it * 31 + it / 977).toByte() }
    val whole = flateStream(data)
    val damaged = whole.bytes.copyOf()
    for (index in damaged.size - 1000 until damaged.size - 900) damaged[index] = 0x55
    val decoded = EncodedStream(damaged, whole.stream).decode()
    assertTrue("decoded ${decoded.size} bytes", decoded.size in 5 * 1024 * 1024 until data.size)
    assertArrayEquals(data.copyOf(decoded.size), decoded)
  }

  @Test
  fun `more than a read may hold fails the read`() {
    val data = ByteArray(8 * 1024 * 1024)
    val stream = flateStream(data)
    val budget = ScanBudget(stream.bytes.size.toLong())
    budget.hold(ScanBudget.MAX_HELD_BYTES - 4 * 1024 * 1024)
    assertThrows(ScanBudget.Exceeded::class.java) { StreamDecoder.read(ByteArraySource(stream.bytes), stream.stream, budget) { it } }
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

  private fun flateStream(data: ByteArray, parameters: PdfObject.Dictionary? = null): EncodedStream {
    val bytes = deflate(data)
    val dictionary = PdfObject.Dictionary(
      listOfNotNull("Filter" to PdfObject.Name("FlateDecode"), parameters?.let { "DecodeParms" to it }).toMap(),
    )
    return EncodedStream(bytes, PdfObject.Stream(dictionary, dataOffset = 0, dataLength = bytes.size))
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

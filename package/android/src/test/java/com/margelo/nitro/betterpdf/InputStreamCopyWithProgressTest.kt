package com.margelo.nitro.betterpdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class InputStreamCopyWithProgressTest {
  private val bytes = ByteArray(200_000) { it.toByte() }
  private val progress = mutableListOf<Triple<Long, Long?, Boolean>>()
  private val output = ByteArrayOutputStream()

  @Test
  fun `copies every byte, reporting each chunk and then completion`() {
    ByteArrayInputStream(bytes).copyWithProgress(output, bytes.size.toLong(), { false }, ::record)

    assertArrayEquals(bytes, output.toByteArray())
    assertEquals(
      listOf(65_536L, 131_072L, 196_608L, 200_000L).map { Triple(it, 200_000L, false) } + Triple(200_000L, 200_000L, true),
      progress,
    )
  }

  @Test
  fun `a total smaller than the bytes copied is reported as unknown`() {
    ByteArrayInputStream(bytes).copyWithProgress(output, 100_000, { false }, ::record)

    assertEquals(listOf(100_000L, null, null, null, null), progress.map { it.second })
  }

  @Test
  fun `cancelling stops the copy before the next chunk`() {
    var cancelled = false
    val input = object : ByteArrayInputStream(bytes) {
      override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { cancelled = true }
    }

    assertThrows(InterruptedException::class.java) {
      input.copyWithProgress(output, null, { cancelled }, ::record)
    }
    assertEquals(65_536, output.size())
    assertEquals(listOf(Triple(65_536L, null, false)), progress)
  }

  private fun record(loadedBytes: Long, totalBytes: Long?, isComplete: Boolean) {
    progress += Triple(loadedBytes, totalBytes, isComplete)
  }
}

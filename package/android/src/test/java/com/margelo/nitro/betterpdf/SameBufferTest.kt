package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameBufferTest {
  /** A wrapper of [address]'s memory, like each prop conversion's new `ArrayBuffer`. */
  private class Buffer(val address: Long, val size: Int, val isOwner: Boolean = true)

  private val reads = mutableListOf<String>()

  private fun same(lhs: Buffer?, rhs: Buffer?) =
    isSameBuffer(
      lhs,
      rhs,
      { it.isOwner },
      { reads += "size"; it.size },
      { reads += "address"; it.address },
    )

  @Test
  fun `new wrappers of the same memory are the same buffer`() {
    assertTrue(same(Buffer(0x1000, 12), Buffer(0x1000, 12)))
  }

  @Test
  fun `other memory or another size is another buffer`() {
    assertFalse(same(Buffer(0x1000, 12), Buffer(0x2000, 12)))
    assertFalse(same(Buffer(0x1000, 12), Buffer(0x1000, 8)))
  }

  @Test
  fun `a buffer JS owns is never the same, and is not read`() {
    assertFalse(same(Buffer(0x1000, 12, isOwner = false), Buffer(0x1000, 12)))
    assertFalse(same(Buffer(0x1000, 12), Buffer(0x1000, 12, isOwner = false)))
    assertEquals(emptyList<String>(), reads)
  }

  @Test
  fun `empty buffers are the same without reading an address`() {
    assertTrue(same(Buffer(0, 0), Buffer(0x2000, 0)))
    assertEquals(listOf("size", "size"), reads)
  }

  @Test
  fun `no buffer is only the same as no buffer`() {
    assertTrue(same(null, null))
    assertFalse(same(Buffer(0x1000, 12), null))
    assertFalse(same(null, Buffer(0x1000, 12)))
  }
}

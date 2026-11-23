package com.margelo.nitro.betterpdf.outline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class XrefTableTest {
  @Test
  fun `entries are found by their numbers`() {
    val table = XrefTable()
    table[7] = XrefEntry.InFile(1234)
    table[9] = XrefEntry.Compressed(streamNumber = 7, index = 3)
    table[0] = XrefEntry.InFile(0)
    assertEquals(XrefEntry.InFile(1234), table[7])
    assertEquals(XrefEntry.Compressed(7, 3), table[9])
    assertEquals(XrefEntry.InFile(0), table[0])
    assertNull(table[8])
    assertEquals(3, table.size)
  }

  @Test
  fun `the first entry stays, unless it is set again`() {
    val table = XrefTable()
    table.putIfAbsent(5, XrefEntry.InFile(10))
    table.putIfAbsent(5, XrefEntry.InFile(20))
    assertEquals(XrefEntry.InFile(10), table[5])
    table[5] = XrefEntry.Compressed(2, 0)
    assertEquals(XrefEntry.Compressed(2, 0), table[5])
    assertEquals(1, table.size)
  }

  @Test
  fun `100,000 entries, the largest number and offset among them`() {
    val table = XrefTable()
    for (number in 0 until 100_000) table.putIfAbsent(number * 7, XrefEntry.InFile(number + 1L))
    table[Int.MAX_VALUE] = XrefEntry.InFile(Long.MAX_VALUE)
    for (number in 0 until 100_000) assertEquals(XrefEntry.InFile(number + 1L), table[number * 7])
    assertEquals(XrefEntry.InFile(Long.MAX_VALUE), table[Int.MAX_VALUE])
    assertNull(table[1])
    assertEquals(100_001, table.size)
  }

  @Test
  fun `the numbers come in order`() {
    val table = XrefTable()
    for (number in intArrayOf(30, 2, Int.MAX_VALUE, 0, 11)) table[number] = XrefEntry.InFile(1)
    assertArrayEquals(intArrayOf(0, 2, 11, 30, Int.MAX_VALUE), table.sortedNumbers())
  }

  /** No reference has a negative number, and no object stream a negative number or index. */
  @Test
  fun `negative numbers are not listed, and negative places read as no object`() {
    val table = XrefTable()
    table[-1] = XrefEntry.InFile(10)
    table.putIfAbsent(-5, XrefEntry.InFile(10))
    assertNull(table[-1])
    assertEquals(0, table.size)
    table[1] = XrefEntry.Compressed(-3, 2)
    table[2] = XrefEntry.Compressed(3, -2)
    assertEquals(XrefEntry.Compressed(Int.MAX_VALUE, 2), table[1])
    assertEquals(XrefEntry.Compressed(3, Int.MAX_VALUE), table[2])
  }

  @Test
  fun `more than a million objects fail`() {
    val table = XrefTable()
    for (number in 0 until XrefTable.MAX_OBJECTS) table[number] = XrefEntry.InFile(1)
    assertThrows(PdfFormatException::class.java) { table[XrefTable.MAX_OBJECTS] = XrefEntry.InFile(1) }
    assertThrows(PdfFormatException::class.java) { table.putIfAbsent(XrefTable.MAX_OBJECTS, XrefEntry.InFile(1)) }
    // One that is listed can still be set.
    table[5] = XrefEntry.InFile(2)
    assertEquals(XrefEntry.InFile(2), table[5])
  }
}

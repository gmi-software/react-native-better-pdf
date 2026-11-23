package com.margelo.nitro.betterpdf.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScanBudgetTest {
  private val megabyte = 1L shl 20

  @Test
  fun `holding more than the limit fails`() {
    val budget = ScanBudget(0)
    budget.hold(ScanBudget.MAX_HELD_BYTES)
    assertThrows(ScanBudget.Exceeded::class.java) { budget.hold(1) }
  }

  @Test
  fun `what a block held is let go when it ends, apart from what it kept`() {
    val budget = ScanBudget(0)
    budget.hold(megabyte)
    budget.releasing {
      budget.hold(10 * megabyte)
      budget.keep(2 * megabyte)
      assertEquals(13 * megabyte, budget.held)
    }
    assertEquals(3 * megabyte, budget.held)
  }

  @Test
  fun `a block that fails lets go as well`() {
    val budget = ScanBudget(0)
    assertThrows(PdfFormatException::class.java) {
      budget.releasing {
        budget.hold(10 * megabyte)
        throw PdfFormatException("unreadable")
      }
    }
    assertEquals(0, budget.held)
  }

  @Test
  fun `blocks nest`() {
    val budget = ScanBudget(0)
    budget.releasing {
      budget.hold(megabyte)
      budget.releasing {
        budget.hold(4 * megabyte)
        budget.keep(megabyte)
      }
      // The outer block still holds its own, and what the inner one kept.
      assertEquals(2 * megabyte, budget.held)
    }
    assertEquals(megabyte, budget.held)
  }

  @Test
  fun `what is held while keeping is kept, a block inside it apart`() {
    val budget = ScanBudget(0)
    budget.releasing {
      budget.keeping {
        budget.hold(megabyte)
        budget.releasing {
          budget.hold(8 * megabyte)
          budget.keep(megabyte)
        }
        budget.hold(megabyte)
      }
      budget.hold(megabyte)
    }
    assertEquals(3 * megabyte, budget.held)
  }

  /** 16 units for each byte of the file, 1 Mi more, and 16 for each byte object streams add, up to 16 times the file and 4 MB. */
  @Test
  fun `object streams add a limited number of units`() {
    fun unitsOf(budget: ScanBudget): Long {
      var units = 0L
      try {
        while (true) {
          budget.spend()
          units++
        }
      } catch (expected: ScanBudget.Exceeded) {
        return units
      }
    }
    assertEquals(megabyte + 16 * 1000, unitsOf(ScanBudget(1000)))
    assertEquals(megabyte + 16 * 1000 + 16 * 5000, unitsOf(ScanBudget(1000).apply { extend(5000) }))
    val most = megabyte + 16 * 1000 + 16 * (4 * megabyte + 16 * 1000)
    assertEquals(most, unitsOf(ScanBudget(1000).apply { repeat(3) { extend(32 * 1024 * 1024) } }))
  }
}

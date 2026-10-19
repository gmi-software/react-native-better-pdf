package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcesProgressTest {
  private val emitted = mutableListOf<Pair<Long, Long?>>()
  private var nowMs = 0L
  private val progress = SourcesProgress(count = 3, emit = { loaded, total -> emitted += loaded to total }, now = { nowMs })

  @Test
  fun `sums the sources downloading, with a total only while each knows its size`() {
    progress.report(source = 0, loadedBytes = 10, totalBytes = 100, isComplete = false)
    nowMs += 200
    progress.report(source = 2, loadedBytes = 5, totalBytes = null, isComplete = false)
    nowMs += 200
    progress.report(source = 2, loadedBytes = 50, totalBytes = 50, isComplete = true)

    assertEquals(listOf(10L to 100L, 15L to null, 60L to 150L), emitted)
  }

  @Test
  fun `throttles updates but always reports a source completing`() {
    progress.report(source = 0, loadedBytes = 10, totalBytes = 100, isComplete = false)
    progress.report(source = 0, loadedBytes = 20, totalBytes = 100, isComplete = false)
    progress.report(source = 0, loadedBytes = 100, totalBytes = 100, isComplete = true)

    assertEquals(listOf(10L to 100L, 100L to 100L), emitted)
  }
}

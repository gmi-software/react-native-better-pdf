package com.margelo.nitro.betterpdf

import android.os.SystemClock

/**
 * Download progress of a document's sources together, reported from their
 * download threads: the sum of the sources downloading, `totalBytes` only
 * while each of them knows its size, throttled by [ProgressThrottle]. A
 * source completing is always reported.
 */
internal class SourcesProgress(
  count: Int,
  private val emit: (loadedBytes: Long, totalBytes: Long?) -> Unit,
  private val now: () -> Long = SystemClock::uptimeMillis,
) {
  private val downloads = arrayOfNulls<Pair<Long, Long?>>(count)
  private val throttle = ProgressThrottle()

  @Synchronized
  fun report(source: Int, loadedBytes: Long, totalBytes: Long?, isComplete: Boolean) {
    downloads[source] = loadedBytes to totalBytes
    val active = downloads.filterNotNull()
    val loaded = active.sumOf { it.first }
    val totals = active.mapNotNull { it.second }
    val total = if (totals.size == active.size) totals.sum() else null
    if (throttle.shouldEmit(now(), loaded, isComplete)) emit(loaded, total)
  }
}

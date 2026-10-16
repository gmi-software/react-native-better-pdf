package com.margelo.nitro.betterpdf

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Runs a document's blocking work one task at a time, in call order, on a
 * thread of its own that exits while idle. Once [close]d, queued and later
 * work fails instead of running; work in progress finishes.
 */
internal class SerialWorker(threadName: String) {
  private val executor = ThreadPoolExecutor(0, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
    Thread(task, threadName).apply { isDaemon = true }
  }

  @Volatile private var closed = false

  /** Runs [work]; [done] gets its result, or what it threw, on the worker thread (on the caller's once closed). */
  fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
    try {
      executor.execute {
        done(if (closed) Result.failure(IllegalStateException("The document was closed")) else runCatching(work))
      }
    } catch (_: RejectedExecutionException) {
      done(Result.failure(IllegalStateException("The document is closed")))
    }
  }

  fun close() {
    closed = true
    executor.shutdown()
  }

  private companion object {
    /** The thread exits after this long without work. */
    const val IDLE_SECONDS = 5L
  }
}

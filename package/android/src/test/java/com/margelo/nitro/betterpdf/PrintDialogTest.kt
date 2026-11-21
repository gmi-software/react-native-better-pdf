package com.margelo.nitro.betterpdf

import android.print.PrintDocumentAdapter
import android.print.PrintJobInfo
import com.margelo.nitro.betterpdf.PdfMethodException.Code
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tests play the print framework: they finish the adapter, or destroy the Activity. */
class PrintDialogTest {
  private class Host : PrintHost {
    override var canPrint = true
    override var isResumed = true
    var startsJob = true
    var failure: Exception? = null
    var jobState = PrintJobInfo.STATE_CREATED
    val adapters = ArrayList<PrintDocumentAdapter>()
    private var onDestroyed: (() -> Unit)? = null
    val isWatched: Boolean get() = onDestroyed != null

    override fun print(jobName: String, adapter: PrintDocumentAdapter): (() -> Int)? {
      failure?.let { throw it }
      adapters += adapter
      return if (startsJob) ({ jobState }) else null
    }

    override fun onDestroyed(callback: () -> Unit): () -> Unit {
      onDestroyed = callback
      return { onDestroyed = null }
    }

    fun destroyActivity() {
      onDestroyed?.invoke()
    }
  }

  private val host = Host()
  private val delayed = ArrayDeque<() -> Unit>()
  private var waitedMs = 0L
  private val dialog = PrintDialog { delayMs, task ->
    waitedMs += delayMs
    delayed += task
  }
  private val results = ArrayList<Result<PdfPrintResult>>()

  private fun print() = dialog.open(host, "Invoice 7", pageCount = 3, write = {}, done = { results += it })

  private fun runDelayed() {
    while (delayed.isNotEmpty()) delayed.removeFirst()()
  }

  private fun assertResolved(isSubmitted: Boolean) = assertEquals(listOf(Result.success(PdfPrintResult(isSubmitted))), results)

  private fun assertRejected(code: Code, message: String) {
    assertEquals(1, results.size)
    val error = results.single().exceptionOrNull() as PdfMethodException
    assertEquals(code, error.code)
    assertEquals("[${code.value}] $message", error.message)
  }

  @Test
  fun `printing or saving resolves submitted`() {
    print()
    host.jobState = PrintJobInfo.STATE_QUEUED
    host.adapters.single().onFinish()

    assertResolved(isSubmitted = true)
    assertFalse(host.isWatched)
  }

  @Test
  fun `closing the dialog resolves not submitted once the job turns cancelled`() {
    print()
    host.adapters.single().onFinish()
    assertTrue(results.isEmpty())

    host.jobState = PrintJobInfo.STATE_CANCELED
    runDelayed()
    assertResolved(isSubmitted = false)
  }

  @Test
  fun `a job that stays created resolves not submitted after 2 s`() {
    print()
    host.adapters.single().onFinish()
    runDelayed()

    assertResolved(isSubmitted = false)
    assertEquals(2000L, waitedMs)
  }

  @Test
  fun `the result waits for the app to return to the foreground`() {
    print()
    // The dialog closes a moment before the Activity under it resumes.
    host.isResumed = false
    host.jobState = PrintJobInfo.STATE_COMPLETED
    host.adapters.single().onFinish()
    assertTrue(results.isEmpty())

    host.isResumed = true
    runDelayed()
    assertResolved(isSubmitted = true)
  }

  @Test
  fun `an app left in the background gets the result after 2 s`() {
    print()
    host.isResumed = false
    host.jobState = PrintJobInfo.STATE_COMPLETED
    host.adapters.single().onFinish()
    runDelayed()

    assertResolved(isSubmitted = true)
    assertEquals(2000L, waitedMs)
  }

  @Test
  fun `a job cancelled in the print queue afterwards was still submitted`() {
    print()
    host.isResumed = false
    host.jobState = PrintJobInfo.STATE_QUEUED
    host.adapters.single().onFinish()
    host.jobState = PrintJobInfo.STATE_CANCELED
    host.isResumed = true
    runDelayed()

    assertResolved(isSubmitted = true)
  }

  @Test
  fun `a job that fails in the print queue afterwards was still submitted`() {
    print()
    host.isResumed = false
    host.jobState = PrintJobInfo.STATE_STARTED
    host.adapters.single().onFinish()
    host.jobState = PrintJobInfo.STATE_FAILED
    runDelayed()

    assertResolved(isSubmitted = true)
    assertEquals(2000L, waitedMs)
  }

  @Test
  fun `a cancelled dialog and the wait for the app share the 2 s`() {
    print()
    host.isResumed = false
    host.adapters.single().onFinish()
    repeat(10) { delayed.removeFirst()() }
    host.jobState = PrintJobInfo.STATE_CANCELED
    runDelayed()

    assertResolved(isSubmitted = false)
    assertEquals(2000L, waitedMs)
  }

  @Test
  fun `a failed job rejects`() {
    print()
    host.jobState = PrintJobInfo.STATE_FAILED
    host.adapters.single().onFinish()

    assertRejected(Code.IO_ERROR, "The print job failed")
  }

  @Test
  fun `a second dialog waits for the first to close`() {
    print()
    print()
    assertRejected(Code.BUSY, "A print dialog is already open")
    assertEquals(1, host.adapters.size)

    host.jobState = PrintJobInfo.STATE_COMPLETED
    host.adapters.single().onFinish()
    print()
    assertEquals(2, host.adapters.size)
    assertEquals(2, results.size)
  }

  @Test
  fun `a failure to start the job rejects with it`() {
    val failure = IllegalStateException("Cannot start printing for finishing activity")
    host.failure = failure
    print()
    assertSame(failure, results.single().exceptionOrNull())

    host.failure = null
    print()
    assertEquals(1, host.adapters.size)
    assertEquals(1, results.size)
  }

  @Test
  fun `a device without printing rejects`() {
    host.canPrint = false
    // PrintManager.print returns null there and calls nothing.
    host.startsJob = false
    print()

    assertRejected(Code.NOT_SUPPORTED, "Printing is not available on this device")
    assertTrue(host.adapters.isEmpty())
  }

  @Test
  fun `no job from the print system rejects`() {
    host.startsJob = false
    print()
    assertRejected(Code.NOT_SUPPORTED, "The system did not open the print dialog")

    host.startsJob = true
    print()
    assertEquals(2, host.adapters.size)
    assertEquals(1, results.size)
  }

  @Test
  fun `no job and a finished adapter settle once`() {
    host.startsJob = false
    print()
    // Printing disabled by device policy: the system finishes the adapter.
    host.adapters.single().onFinish()
    runDelayed()

    assertRejected(Code.NOT_SUPPORTED, "The system did not open the print dialog")
  }

  @Test
  fun `an app that is not in the foreground rejects`() {
    host.isResumed = false
    print()

    assertRejected(Code.BUSY, "Printing needs the app in the foreground")
    assertTrue(host.adapters.isEmpty())
  }

  @Test
  fun `the Activity destroyed under the dialog resolves not submitted`() {
    print()
    // The framework drops the adapter's onFinish; the spooler cancels the job.
    host.destroyActivity()
    host.jobState = PrintJobInfo.STATE_CANCELED
    runDelayed()

    assertResolved(isSubmitted = false)
    assertFalse(host.isWatched)
  }

  @Test
  fun `a job submitted before the Activity was destroyed resolves submitted`() {
    print()
    host.jobState = PrintJobInfo.STATE_QUEUED
    host.destroyActivity()

    assertResolved(isSubmitted = true)
  }

  @Test
  fun `a dialog opens again after the Activity was destroyed`() {
    print()
    host.destroyActivity()
    runDelayed()
    print()

    assertEquals(2, host.adapters.size)
    assertResolved(isSubmitted = false)
  }
}

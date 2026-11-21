package com.margelo.nitro.betterpdf

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.print.PrintDocumentAdapter
import android.print.PrintJob
import android.print.PrintJobInfo
import android.print.PrintManager
import com.facebook.react.common.LifecycleState
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.betterpdf.PdfMethodException.Code

/** What [PrintDialog] needs of the app and the print framework. */
internal interface PrintHost {
  /**
   * Whether the device has `android.software.print`. Without it
   * `PrintManager.print` returns null and never calls the adapter.
   */
  val canPrint: Boolean

  /** Whether an Activity to print from is in the foreground. */
  val isResumed: Boolean

  /**
   * `PrintManager.print`: a reader of the new job's `PrintJobInfo` state, or
   * null when the framework started no job.
   */
  fun print(jobName: String, adapter: PrintDocumentAdapter): (() -> Int)?

  /**
   * Calls [callback] when the Activity printed from is destroyed, until the
   * returned function is called.
   */
  fun onDestroyed(callback: () -> Unit): () -> Unit
}

/**
 * A view's print dialog, one at a time. [open]'s `done` runs once whatever
 * the print framework does. Main thread.
 */
internal class PrintDialog(private val later: (delayMs: Long, task: () -> Unit) -> Unit) {
  private var isOpen = false

  fun open(
    host: PrintHost,
    jobName: String,
    pageCount: Int,
    write: (ParcelFileDescriptor) -> Unit,
    done: (Result<PdfPrintResult>) -> Unit,
  ) {
    val refusal = when {
      !host.canPrint -> PdfMethodException(Code.NOT_SUPPORTED, "Printing is not available on this device")
      isOpen -> PdfMethodException(Code.BUSY, "A print dialog is already open")
      !host.isResumed -> PdfMethodException(Code.BUSY, "Printing needs the app in the foreground")
      else -> null
    }
    if (refusal != null) return done(Result.failure(refusal))

    var job: (() -> Int)? = null
    var stopWatching = {}
    // The adapter's onFinish or, as the framework drops that when its
    // Activity is destroyed, the destruction: whichever comes first. Without
    // a job there is nothing to close; a system that refuses to print
    // (device policy) finishes the adapter all the same.
    val closed = {
      val opened = job
      if (opened != null) {
        job = null
        isOpen = false
        stopWatching()
        settle(host, opened, done, SETTLE_POLLS)
      }
    }
    val started = try {
      host.print(jobName, PdfPrintAdapter(fileName(jobName), pageCount, write, closed))
    } catch (error: Exception) {
      return done(Result.failure(error))
    }
    if (started == null) {
      return done(Result.failure(PdfMethodException(Code.NOT_SUPPORTED, "The system did not open the print dialog")))
    }
    job = started
    isOpen = true
    stopWatching = host.onDestroyed(closed)
  }

  /**
   * Settles once the job has left the state it is created in and the app is
   * in the foreground again. When the dialog closes, a saved or printed job
   * is already queued or completed, but a cancelled one is still "created"
   * and turns cancelled a moment later (measured: under 500 ms on API 37).
   * The Activity resumes after the dialog closes (measured: 50 ms later), and
   * a `print()` called from this result needs it resumed.
   */
  private fun settle(host: PrintHost, state: () -> Int, done: (Result<PdfPrintResult>) -> Unit, polls: Int) {
    val current = state()
    if (polls > 0 && (current == PrintJobInfo.STATE_CREATED || !host.isResumed)) {
      return later(SETTLE_INTERVAL_MS) { settle(host, state, done, polls - 1) }
    }
    done(
      when (current) {
        PrintJobInfo.STATE_CREATED, PrintJobInfo.STATE_CANCELED -> Result.success(PdfPrintResult(isSubmitted = false))
        PrintJobInfo.STATE_FAILED -> Result.failure(PdfMethodException(Code.IO_ERROR, "The print job failed"))
        else -> Result.success(PdfPrintResult(isSubmitted = true))
      },
    )
  }

  /** Saving as PDF names the file after the document; it needs the extension. */
  private fun fileName(jobName: String): String =
    if (jobName.endsWith(".pdf", ignoreCase = true)) jobName else "$jobName.pdf"

  private companion object {
    const val SETTLE_INTERVAL_MS = 50L

    /** 2 s in all. */
    const val SETTLE_POLLS = 40
  }
}

/** Prints from the React host's current Activity. */
internal class ReactPrintHost(private val context: ThemedReactContext) : PrintHost {
  private val activity: Activity? = context.currentActivity

  override val canPrint: Boolean
    get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING)

  // React Native keeps its current Activity while the app is paused or stopped.
  override val isResumed: Boolean
    get() = activity != null && context.reactApplicationContext.lifecycleState == LifecycleState.RESUMED

  override fun print(jobName: String, adapter: PrintDocumentAdapter): (() -> Int)? {
    // Declared non-null, yet null when the framework starts no job.
    val job: PrintJob? = checkNotNull(activity).getSystemService(PrintManager::class.java).print(jobName, adapter, null)
    return job?.let { { it.info.state } }
  }

  override fun onDestroyed(callback: () -> Unit): () -> Unit {
    val application = checkNotNull(activity).application
    val callbacks = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityDestroyed(destroyed: Activity) {
        if (destroyed === activity) callback()
      }

      override fun onActivityCreated(created: Activity, savedInstanceState: Bundle?) = Unit

      override fun onActivityStarted(started: Activity) = Unit

      override fun onActivityResumed(resumed: Activity) = Unit

      override fun onActivityPaused(paused: Activity) = Unit

      override fun onActivityStopped(stopped: Activity) = Unit

      override fun onActivitySaveInstanceState(saved: Activity, outState: Bundle) = Unit
    }
    application.registerActivityLifecycleCallbacks(callbacks)
    return { application.unregisterActivityLifecycleCallbacks(callbacks) }
  }
}

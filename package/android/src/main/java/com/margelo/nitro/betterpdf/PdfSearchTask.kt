package com.margelo.nitro.betterpdf

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor

/**
 * Searches a document page by page on its own thread, with its own renderer
 * on a duplicate of the file descriptor (of each of its [sources] in turn),
 * so rendering and scrolling are not blocked (renderers share PDFium's
 * process-wide lock, so a render waits at most for one page's search).
 * Starts at [startPage] and wraps, so the reader's nearest matches come
 * first.
 *
 * Results are posted to the main thread; nothing is posted once [cancel]led.
 */
internal class PdfSearchTask(
  private val sources: List<Source>,
  private val sourcePages: SourcePages,
  private val startPage: Int,
  private val pages: Int,
  private val query: String,
  private val listener: Listener,
) {
  /** A source of the document: a duplicate of its descriptor, the task's to close. */
  class Source(val descriptor: ParcelFileDescriptor, val password: String?)

  interface Listener {
    /** [page] was searched; [matches] may be empty. */
    fun onSearchedPage(task: PdfSearchTask, page: Int, matches: List<List<PageRect>>)

    /** Every page was searched, or ([isSupported] false) the device cannot search text. */
    fun onSearchFinished(task: PdfSearchTask, isSupported: Boolean)
  }

  @Volatile var isCancelled = false
    private set
  private val mainHandler = Handler(Looper.getMainLooper())
  private val thread = Thread(::run, "BetterPdf-search").apply { isDaemon = true }

  fun start() = thread.start()

  fun cancel() {
    isCancelled = true
  }

  private fun run() {
    var renderer: DocumentRenderer? = null
    var rendererSource = -1
    var supported = true
    try {
      var pagesOpened = 0
      for (offset in 0 until pages) {
        if (isCancelled) return
        val page = (startPage + offset) % sourcePages.pageCount
        val at = sourcePages.locate(page)
        // Recycled for the same reason as the render thread's (release what
        // PDFium cached), and replaced where the next source starts.
        if (at.source != rendererSource || pagesOpened >= RECYCLE_AFTER_PAGES) {
          renderer?.close()
          renderer = null
          // Renderers own (and close) the descriptor they get; keep ours for recycling.
          renderer = DocumentRenderer.open(sources[at.source].descriptor.dup(), sources[at.source].password)
          rendererSource = at.source
          pagesOpened = 0
        }
        val matches = checkNotNull(renderer).openPage(at.page).use { it.searchText(query) }
        pagesOpened++
        if (matches == null) {
          supported = false
          break
        }
        post { listener.onSearchedPage(this, page, matches) }
      }
    } catch (_: Exception) {
      // An unreadable page or document ends the search with what was found.
    } catch (_: OutOfMemoryError) {
      // As does running out of memory: this thread must not end the app.
    } finally {
      try {
        renderer?.close()
      } catch (_: Exception) {
      }
      sources.forEach { it.descriptor.closeQuietly() }
    }
    post { listener.onSearchFinished(this, supported) }
  }

  private inline fun post(crossinline block: () -> Unit) {
    mainHandler.post { if (!isCancelled) block() }
  }

  private companion object {
    const val RECYCLE_AFTER_PAGES = 48
  }
}

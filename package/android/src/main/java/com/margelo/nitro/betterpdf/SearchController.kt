package com.margelo.nitro.betterpdf

import android.os.SystemClock

internal typealias SearchStart =
  (query: String, pageCount: Int, startPage: Int, pages: Int, listener: PdfSearchTask.Listener) -> PdfSearchTask?

/**
 * Runs `search()` for a view: one [PdfSearchTask] at a time, its
 * [SearchResults] shown by [view], navigation, and `onSearchChange` events —
 * at most one per [EMIT_INTERVAL_MS] while pages are searched, immediately
 * for the first match, navigation, completion and clearing. Main thread.
 */
internal class SearchController(
  private val view: Host,
  private val emit: (PdfSearchChangeEvent) -> Unit,
) : PdfSearchTask.Listener {
  interface Host {
    val pageCount: Int
    val currentPageIndex: Int
    var searchResults: SearchResults?

    fun revealSearchMatch(match: SearchMatch)

    fun invalidate()
  }

  private var startSearch: SearchStart? = null
  private var task: PdfSearchTask? = null
  private var results: SearchResults? = null
  private var query = ""
  private var startPage = 0
  private var searchedPages = 0
  private var isComplete = true
  private var isSupported = true
  private var isViewAttached = false
  private var lastEmitMs = 0L

  /** A document was shown or closed (null); ends any search on the previous one. */
  fun onDocumentChanged(startSearch: SearchStart?) {
    clear()
    this.startSearch = startSearch
  }

  fun onViewAttached() {
    isViewAttached = true
    if (isComplete) return
    runTask()
    if (isComplete) emitNow()
  }

  fun onViewDetached() {
    isViewAttached = false
    cancelTask()
  }

  fun search(query: String) {
    cancelTask()
    if (query.isEmpty()) {
      clear()
      return
    }
    if (startSearch == null) return
    val results = SearchResults()
    this.query = query
    this.results = results
    view.searchResults = results
    startPage = view.currentPageIndex
    searchedPages = 0
    isSupported = true
    isComplete = false
    runTask()
    emitNow()
  }

  fun next() = show(results?.next())

  fun previous() = show(results?.previous())

  fun clear() {
    cancelTask()
    val hadSearch = results != null
    query = ""
    results = null
    view.searchResults = null
    searchedPages = 0
    isComplete = true
    if (hadSearch) emitNow()
  }

  override fun onSearchedPage(task: PdfSearchTask, page: Int, matches: List<List<PageRect>>) {
    if (task !== this.task) return
    val results = results ?: return
    searchedPages++
    results.add(page, matches)
    if (matches.isNotEmpty()) view.invalidate()
    if (results.currentMatch == null && results.count > 0) {
      // Pages arrive from the reader's page onwards, so this is the nearest
      // match at or after it (or, after wrapping, the first one).
      show(results.selectFirstAtOrAfter(startPage))
      return
    }
    if (SystemClock.uptimeMillis() - lastEmitMs >= EMIT_INTERVAL_MS) emitNow()
  }

  override fun onSearchFinished(task: PdfSearchTask, isSupported: Boolean) {
    if (task !== this.task) return
    this.task = null
    isComplete = true
    this.isSupported = isSupported
    emitNow()
  }

  private fun show(match: SearchMatch?) {
    match ?: return
    view.revealSearchMatch(match)
    emitNow()
  }

  private fun runTask() {
    if (task != null || isComplete || !isViewAttached) return
    val startSearch = startSearch ?: return
    val pageCount = view.pageCount
    if (searchedPages >= pageCount) {
      isComplete = true
      return
    }
    task = startSearch(query, pageCount, (startPage + searchedPages) % pageCount, pageCount - searchedPages, this)
    if (task == null) isComplete = true
  }

  private fun cancelTask() {
    task?.cancel()
    task = null
  }

  private fun emitNow() {
    lastEmitMs = SystemClock.uptimeMillis()
    val results = results
    val current = results?.currentMatch
    emit(
      PdfSearchChangeEvent(
        query = query,
        matchCount = (results?.count ?: 0).toDouble(),
        currentMatch = results?.currentIndex?.toDouble(),
        currentPage = current?.page?.toDouble(),
        isComplete = isComplete,
        isSupported = isSupported,
      ),
    )
  }

  private companion object {
    const val EMIT_INTERVAL_MS = 100L
  }
}

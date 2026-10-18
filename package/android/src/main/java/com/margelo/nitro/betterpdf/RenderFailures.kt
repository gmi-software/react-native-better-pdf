package com.margelo.nitro.betterpdf

/**
 * Failed renders of a view's pages: each is retried once, base bitmaps and
 * tiles counted apart, and a page is reported once, when it gives up.
 */
internal class RenderFailures {
  private val baseFailures = HashMap<Int, Int>()
  private val tileFailures = HashMap<Int, Int>()
  private val reportedPages = HashSet<Int>()

  fun allowsBase(page: Int): Boolean = (baseFailures[page] ?: 0) < ATTEMPTS

  fun allowsTile(page: Int): Boolean = allowsBase(page) && (tileFailures[page] ?: 0) < ATTEMPTS

  /** Returns whether to report the failure. */
  fun onFailed(request: RenderRequest): Boolean {
    val failures = failuresOf(request)
    val count = (failures[request.page] ?: 0) + 1
    failures[request.page] = count
    return count >= ATTEMPTS && reportedPages.add(request.page)
  }

  fun onRendered(request: RenderRequest) {
    failuresOf(request).remove(request.page)
  }

  fun clear() {
    baseFailures.clear()
    tileFailures.clear()
    reportedPages.clear()
  }

  private fun failuresOf(request: RenderRequest): HashMap<Int, Int> = when (request) {
    is RenderRequest.Base -> baseFailures
    is RenderRequest.Tile -> tileFailures
  }

  private companion object {
    const val ATTEMPTS = 2
  }
}

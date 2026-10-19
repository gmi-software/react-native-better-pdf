package com.margelo.nitro.betterpdf

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tasks are never started: the tests call the listener methods instead. */
class SearchControllerTest {
  private class Host(override val pageCount: Int, override val currentPageIndex: Int) : SearchController.Host {
    override var searchResults: SearchResults? = null
    val revealed = ArrayList<SearchMatch>()

    override fun revealSearchMatch(match: SearchMatch) {
      revealed += match
    }

    override fun invalidate() = Unit
  }

  private class Started(val task: PdfSearchTask, val startPage: Int, val pages: Int)

  private val host = Host(pageCount = 10, currentPageIndex = 4)
  private val events = ArrayList<PdfSearchChangeEvent>()
  private val controller = SearchController(host) { events += it }
  private val started = ArrayList<Started>()
  private var canStart = true
  private val startSearch: SearchStart = { query, pageCount, startPage, pages, listener ->
    if (canStart) {
      PdfSearchTask(listOf(PdfSearchTask.Source(ParcelFileDescriptor(null), null)), SourcePages(listOf(pageCount)), startPage, pages, query, listener)
        .also { started += Started(it, startPage, pages) }
    } else {
      null
    }
  }

  private fun match(page: Int) = listOf(listOf(PageRect(page.toFloat(), 0f, page + 1f, 1f)))

  private fun search(index: Int, pages: Iterable<Int>, matchesOn: Set<Int> = emptySet()) {
    val task = started[index].task
    for (page in pages) controller.onSearchedPage(task, page, if (page in matchesOn) match(page) else emptyList())
  }

  @Test
  fun `a search before the document loads reports nothing and is not kept`() {
    controller.onViewAttached()
    controller.search("lorem")
    controller.onDocumentChanged(startSearch)

    assertEquals(emptyList<PdfSearchChangeEvent>(), events)
    assertTrue(started.isEmpty())
    assertNull(host.searchResults)
  }

  @Test
  fun `a search starts at the reader's page and covers every page`() {
    controller.onDocumentChanged(startSearch)
    controller.onViewAttached()
    controller.search("lorem")

    assertEquals(1, started.size)
    assertEquals(4, started[0].startPage)
    assertEquals(10, started[0].pages)
    assertFalse(events.last().isComplete)
  }

  @Test
  fun `a detached view's search stops, and goes on from the next page once attached`() {
    controller.onDocumentChanged(startSearch)
    controller.onViewAttached()
    controller.search("lorem")
    search(0, 4..6)
    val eventsBefore = events.size

    controller.onViewDetached()
    assertTrue(started[0].task.isCancelled)
    assertEquals(eventsBefore, events.size)
    search(0, listOf(7), matchesOn = setOf(7))
    assertEquals(0, host.searchResults?.count)

    controller.onViewAttached()
    assertEquals(2, started.size)
    assertEquals(7, started[1].startPage)
    assertEquals(7, started[1].pages)
    search(1, listOf(7, 8, 9, 0, 1, 2, 3), matchesOn = setOf(1, 8))
    controller.onSearchFinished(started[1].task, isSupported = true)

    val last = events.last()
    assertTrue(last.isComplete)
    assertEquals(2.0, last.matchCount, 0.0)
    assertEquals(8.0, last.currentPage)
  }

  @Test
  fun `a search started while the view is detached starts once it is attached`() {
    controller.onDocumentChanged(startSearch)
    controller.search("lorem")
    assertTrue(started.isEmpty())
    assertEquals("lorem", events.last().query)
    assertFalse(events.last().isComplete)

    controller.onViewAttached()
    assertEquals(1, started.size)
    assertEquals(4, started[0].startPage)
    assertEquals(10, started[0].pages)
  }

  @Test
  fun `a search whose pages were all searched ends when the view is attached`() {
    controller.onDocumentChanged(startSearch)
    controller.onViewAttached()
    controller.search("lorem")
    search(0, (4..9) + (0..3))
    controller.onViewDetached()

    controller.onViewAttached()
    assertEquals(1, started.size)
    assertTrue(events.last().isComplete)
    assertTrue(events.last().isSupported)
  }

  @Test
  fun `a search that cannot go on when the view is attached ends`() {
    controller.onDocumentChanged(startSearch)
    controller.onViewAttached()
    controller.search("lorem")
    search(0, 4..5)
    controller.onViewDetached()

    canStart = false
    controller.onViewAttached()
    assertTrue(events.last().isComplete)
  }

  @Test
  fun `a document change while the view is detached ends the search`() {
    controller.onDocumentChanged(startSearch)
    controller.onViewAttached()
    controller.search("lorem")
    controller.onViewDetached()

    controller.onDocumentChanged(null)
    assertEquals("", events.last().query)
    controller.onViewAttached()
    assertEquals(1, started.size)
  }

  @Test
  fun `clearing ends the search`() {
    controller.onDocumentChanged(startSearch)
    controller.onViewAttached()
    controller.search("lorem")

    controller.clear()
    assertTrue(started[0].task.isCancelled)
    assertEquals("", events.last().query)
    assertNull(host.searchResults)
    controller.onViewDetached()
    controller.onViewAttached()
    assertEquals(1, started.size)
  }
}

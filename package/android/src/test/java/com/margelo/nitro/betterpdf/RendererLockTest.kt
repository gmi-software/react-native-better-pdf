package com.margelo.nitro.betterpdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererLockTest {
  @Test
  fun `the lock is on for Android 7, both API levels`() {
    assertTrue(RendererLock.isNeeded(24))
    assertTrue(RendererLock.isNeeded(25))
    assertFalse(RendererLock.isNeeded(26))
    assertFalse(RendererLock.isNeeded(37))
  }

  @Test
  fun `when on, every call into the renderer and its pages holds the lock`() {
    val lock = RendererLock(isEnabled = true)
    val calls = Calls(lock)

    callEverything(lock.open { calls.record("open"); FakeRenderer(calls) })

    assertEquals(expectedCalls(), calls.held.keys)
    assertEquals(emptyList<String>(), calls.held.filterValues { !it }.keys.toList())
  }

  @Test
  fun `when off, the renderer is returned as is and nothing holds the lock`() {
    val lock = RendererLock(isEnabled = false)
    val calls = Calls(lock)
    val raw = FakeRenderer(calls)

    val opened = lock.open { calls.record("open"); raw }
    callEverything(opened)

    assertSame(raw, opened)
    assertEquals(expectedCalls(), calls.held.keys)
    assertEquals(emptyList<String>(), calls.held.filterValues { it }.keys.toList())
  }

  private fun callEverything(renderer: DocumentRenderer) {
    val descriptor = ParcelFileDescriptor(null)
    renderer.pageCount
    renderer.canFillForm
    renderer.writeUnprotected(descriptor)
    renderer.writeProtected(descriptor)
    renderer.openPage(0).use { page ->
      page.width
      page.height
      page.renderForDisplay(bitmap(), null, Matrix())
      page.readLinks()
      page.searchText("a")
      page.readText()
      page.selectText(TextSelectionQuery(0, 0, TextSelectionQuery.Boundary.Index(0), TextSelectionQuery.Boundary.Index(1)))
      page.formWidgets()
      page.applyFormEdit(FormEdit.SetText(0, 0, "a"))
    }
    renderer.close()
  }

  /**
   * "open" plus every member of both interfaces and `close` (from
   * [AutoCloseable]), so a member added later must be locked too.
   */
  private fun expectedCalls(): Set<String> =
    setOf("open") +
      (members(DocumentRenderer::class.java) + "close").map { "renderer.$it" } +
      (members(DocumentPage::class.java) + "close").map { "page.$it" }

  private fun members(type: Class<*>): List<String> =
    type.declaredMethods.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }

  /** On the JVM `Bitmap.createBitmap` returns null; the fake page never reads it. */
  private fun bitmap(): Bitmap {
    val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Bitmap::class.java) as Bitmap
  }

  private class Calls(private val lock: RendererLock) {
    val held = mutableMapOf<String, Boolean>()

    fun record(call: String) {
      held[call] = Thread.holdsLock(lock)
    }
  }

  private class FakeRenderer(private val calls: Calls) : DocumentRenderer {
    override val pageCount: Int get() = 1.also { calls.record("renderer.getPageCount") }

    override fun openPage(index: Int): DocumentPage = FakePage(calls).also { calls.record("renderer.openPage") }

    override fun writeUnprotected(destination: ParcelFileDescriptor) = calls.record("renderer.writeUnprotected")

    override val canFillForm: Boolean get() = false.also { calls.record("renderer.getCanFillForm") }

    override fun writeProtected(destination: ParcelFileDescriptor) = calls.record("renderer.writeProtected")

    override fun close() = calls.record("renderer.close")
  }

  private class FakePage(private val calls: Calls) : DocumentPage {
    override val width: Int get() = 612.also { calls.record("page.getWidth") }
    override val height: Int get() = 792.also { calls.record("page.getHeight") }

    override fun renderForDisplay(target: Bitmap, clip: Rect?, matrix: Matrix) = calls.record("page.renderForDisplay")

    override fun readLinks(): List<PageLink> = emptyList<PageLink>().also { calls.record("page.readLinks") }

    override fun searchText(query: String): List<List<PageRect>>? = null.also { calls.record("page.searchText") }

    override fun readText(): String? = null.also { calls.record("page.readText") }

    override fun selectText(query: TextSelectionQuery): PageTextSelection? = null.also { calls.record("page.selectText") }

    override fun formWidgets(): List<FormWidget> = emptyList<FormWidget>().also { calls.record("page.formWidgets") }

    override fun applyFormEdit(edit: FormEdit) = calls.record("page.applyFormEdit")

    override fun close() = calls.record("page.close")
  }
}

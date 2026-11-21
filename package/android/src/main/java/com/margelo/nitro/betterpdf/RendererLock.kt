package com.margelo.nitro.betterpdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor

/**
 * One process-wide lock around every call into the platform renderer where
 * the platform takes none: PDFium is not thread-safe, and `PdfRenderer`
 * serializes its calls only from Android 7.1.1. API 25 is both 7.1.0 and
 * 7.1.1, so the whole level is locked. On 7.1.1 this lock is taken before
 * the platform's own, and only the platform call runs while it is held.
 */
internal class RendererLock(private val isEnabled: Boolean) {
  /** Opens a renderer with [factory]; when enabled, under the lock and returning one that locks every call. */
  fun open(factory: () -> DocumentRenderer): DocumentRenderer =
    if (isEnabled) LockedRenderer(locked(factory)) else factory()

  private inline fun <T> locked(block: () -> T): T = synchronized(this) { block() }

  // Written out member by member: delegation with `by` would let a member
  // added later skip the lock.
  private inner class LockedRenderer(private val renderer: DocumentRenderer) : DocumentRenderer {
    override val pageCount: Int get() = locked { renderer.pageCount }

    override fun openPage(index: Int): DocumentPage = LockedPage(locked { renderer.openPage(index) })

    override fun writeUnprotected(destination: ParcelFileDescriptor) = locked { renderer.writeUnprotected(destination) }

    override val canFillForm: Boolean get() = locked { renderer.canFillForm }

    override fun writeProtected(destination: ParcelFileDescriptor) = locked { renderer.writeProtected(destination) }

    override fun close() = locked { renderer.close() }
  }

  private inner class LockedPage(private val page: DocumentPage) : DocumentPage {
    override val width: Int get() = locked { page.width }
    override val height: Int get() = locked { page.height }

    override fun renderForDisplay(target: Bitmap, clip: Rect?, matrix: Matrix) =
      locked { page.renderForDisplay(target, clip, matrix) }

    override fun readLinks(): List<PageLink> = locked { page.readLinks() }

    override fun searchText(query: String): List<List<PageRect>>? = locked { page.searchText(query) }

    override fun readText(): String? = locked { page.readText() }

    override fun selectText(query: TextSelectionQuery): PageTextSelection? = locked { page.selectText(query) }

    override fun formWidgets(): List<FormWidget> = locked { page.formWidgets() }

    override fun applyFormEdit(edit: FormEdit) = locked { page.applyFormEdit(edit) }

    override fun close() = locked { page.close() }
  }

  companion object {
    fun isNeeded(sdk: Int): Boolean = sdk < Build.VERSION_CODES.O

    val platform = RendererLock(isNeeded(Build.VERSION.SDK_INT))
  }
}

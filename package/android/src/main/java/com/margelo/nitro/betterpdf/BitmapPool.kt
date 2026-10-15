package com.margelo.nitro.betterpdf

import android.graphics.Bitmap

/**
 * Reuses ARGB_8888 bitmaps of identical dimensions and frees the rest eagerly.
 *
 * Pages of one document are usually the same size, so a page bitmap evicted
 * from the cache can be handed straight to the renderer for the next page
 * instead of allocating ~6 MB of fresh native memory.
 *
 * Bitmaps that are not pooled are `recycle()`d immediately. Leaving them to
 * the GC is not an option: pixel memory is native while the Java heap of a
 * PDF viewer stays tiny, so the GC rarely runs and evicted pixels pile up
 * (measured: ~600 MB of native heap while scrolling a 1000-page scan).
 * Recycling is safe because callers only release a bitmap after removing it
 * from everything that draws; a frame already recorded keeps its own
 * reference to the pixels inside HWUI.
 *
 * Acquired on the render thread, released on the UI thread, hence the lock.
 */
class BitmapPool(private var maxBytes: Long) {
  private val lock = Any()
  private val free = ArrayDeque<Bitmap>()
  private var freeBytes = 0L

  @Volatile
  var allocations = 0L
    private set

  /** Returns a bitmap of exactly [width] x [height]; contents are undefined. */
  fun acquire(width: Int, height: Int): Bitmap {
    synchronized(lock) {
      val iterator = free.iterator()
      while (iterator.hasNext()) {
        val bitmap = iterator.next()
        if (bitmap.width == width && bitmap.height == height) {
          iterator.remove()
          freeBytes -= bitmap.allocationByteCount
          return bitmap
        }
      }
    }
    allocations++
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  }

  fun release(bitmap: Bitmap) {
    if (bitmap.isRecycled) return
    val evicted = ArrayList<Bitmap>(1)
    synchronized(lock) {
      val bytes = bitmap.allocationByteCount.toLong()
      if (!bitmap.isMutable || bytes > maxBytes) {
        evicted += bitmap
      } else {
        free.addLast(bitmap)
        freeBytes += bytes
        trimLocked(evicted)
      }
    }
    evicted.forEach(Bitmap::recycle)
  }

  fun setMaxBytes(bytes: Long) {
    val evicted = ArrayList<Bitmap>()
    synchronized(lock) {
      maxBytes = bytes
      trimLocked(evicted)
    }
    evicted.forEach(Bitmap::recycle)
  }

  fun clear() = setMaxBytes(0)

  val pooledBytes: Long get() = synchronized(lock) { freeBytes }

  val budgetBytes: Long get() = synchronized(lock) { maxBytes }

  private fun trimLocked(into: MutableList<Bitmap>) {
    while (freeBytes > maxBytes && free.isNotEmpty()) {
      val bitmap = free.removeFirst()
      freeBytes -= bitmap.allocationByteCount
      into += bitmap
    }
  }
}

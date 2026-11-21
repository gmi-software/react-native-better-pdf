package com.margelo.nitro.betterpdf

/**
 * What is moving the viewport: a finger, the scroller (a fling or an
 * animated scroll) and the zoom animation.
 *
 * The scroller counts in zoomed pixels, so it never runs together with a
 * zoom animation: whichever starts ends the other first. An animation ended
 * for another one jumps to its target, so both requests are kept; a fling
 * just stops.
 *
 * Whenever a call leaves nothing moving, [Host.scheduleSettle] follows, so
 * every motion and every viewport change at rest ends in a settle.
 *
 * Pure Kotlin so its guarantees are unit tested (`ViewportMotionTest`).
 */
class ViewportMotion(private val host: Host) {
  /** The scroller and the zoom animation. They may report back before a call returns. */
  interface Host {
    /** Stops the scroller where it is. */
    fun stopScroll()

    /** Moves the viewport to the scroll animation's target and stops the scroller. */
    fun finishScroll()

    /** Stops the zoom animation where it is. */
    fun cancelZoom()

    /** Moves the viewport to the zoom animation's target and stops it. */
    fun finishZoom()

    /** Starts the wait for the viewport to count as settled, over again if it was running. */
    fun scheduleSettle()
  }

  private enum class Scroll { NONE, FLING, ANIMATION }

  private var isTouching = false
  private var scroll = Scroll.NONE
  private var isZooming = false

  val isMoving: Boolean get() = isTouching || scroll != Scroll.NONE || isZooming

  /** A finger takes the viewport as it is. */
  fun touchDown() {
    isTouching = true
    endScroll(atTarget = false)
    endZoom(atTarget = false)
  }

  /** The finger lifted, or its gesture was taken away. */
  fun touchUp() {
    isTouching = false
    settleIfIdle()
  }

  /**
   * Call before computing where a scroll (a jump, an animation or a fling)
   * goes: afterwards the zoom is final and the scroller has stopped.
   */
  fun beforeScroll() {
    endZoom(atTarget = true)
    endScroll(atTarget = false)
    settleIfIdle()
  }

  fun scrollStarted(isFling: Boolean) {
    scroll = if (isFling) Scroll.FLING else Scroll.ANIMATION
  }

  /** The scroller reached its end by itself. */
  fun scrollEnded() {
    scroll = Scroll.NONE
    settleIfIdle()
  }

  /**
   * Call before the zoom changes (a jump or an animation): afterwards an
   * animated scroll has reached its target, a fling has stopped, and an
   * earlier zoom animation has stopped where it was.
   */
  fun beforeZoom() {
    endScroll(atTarget = true)
    endZoom(atTarget = false)
    settleIfIdle()
  }

  fun zoomStarted() {
    isZooming = true
  }

  /** The zoom animation ended, by itself or not. */
  fun zoomEnded() {
    isZooming = false
    settleIfIdle()
  }

  /** Ends the scroller and the zoom animation: where they are, or animations [atTarget]. */
  fun end(atTarget: Boolean) {
    endScroll(atTarget)
    endZoom(atTarget)
    settleIfIdle()
  }

  /** The offsets or the zoom were written. */
  fun viewportChanged() = settleIfIdle()

  // The state changes before the host is called: the host reports back (a
  // viewport change, the end of the zoom) from inside the call.
  private fun endScroll(atTarget: Boolean) {
    val ended = scroll
    if (ended == Scroll.NONE) return
    scroll = Scroll.NONE
    if (atTarget && ended == Scroll.ANIMATION) host.finishScroll() else host.stopScroll()
  }

  private fun endZoom(atTarget: Boolean) {
    if (!isZooming) return
    isZooming = false
    if (atTarget) host.finishZoom() else host.cancelZoom()
  }

  private fun settleIfIdle() {
    if (!isMoving) host.scheduleSettle()
  }
}

package com.margelo.nitro.betterpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportMotionTest {
  /**
   * Stands in for the view: a scroller, a zoom animator and the settle
   * timer. It reports back from inside the host calls, as the platform's
   * animator and the view's offset setter do.
   */
  private class FakeView : ViewportMotion.Host {
    val motion = ViewportMotion(this)
    val log = ArrayList<String>()
    var isTouching = false
    var isScrolling = false
    var isZooming = false
    var isSettleArmed = false
    var settles = 0

    override fun stopScroll() {
      log += "stop scroll"
      isScrolling = false
    }

    override fun finishScroll() {
      log += "finish scroll"
      isScrolling = false
      motion.viewportChanged()
    }

    override fun cancelZoom() {
      log += "cancel zoom"
      isZooming = false
      motion.zoomEnded()
    }

    override fun finishZoom() {
      log += "finish zoom"
      isZooming = false
      motion.viewportChanged()
      motion.zoomEnded()
    }

    override fun scheduleSettle() {
      isSettleArmed = true
    }

    fun touchDown() {
      isTouching = true
      motion.touchDown()
    }

    fun touchUp() {
      isTouching = false
      motion.touchUp()
    }

    fun fling() = startScroll(isFling = true)

    fun animateScroll() = startScroll(isFling = false)

    private fun startScroll(isFling: Boolean) {
      motion.beforeScroll()
      log += "start scroll"
      isScrolling = true
      motion.scrollStarted(isFling)
    }

    fun jumpScroll() {
      motion.beforeScroll()
      log += "write"
      motion.viewportChanged()
    }

    fun scrollerReachesEnd() {
      isScrolling = false
      motion.viewportChanged()
      motion.scrollEnded()
    }

    fun animateZoom() {
      motion.beforeZoom()
      log += "start zoom"
      isZooming = true
      motion.zoomStarted()
      motion.viewportChanged()
    }

    fun jumpZoom() {
      motion.beforeZoom()
      log += "write"
      motion.viewportChanged()
    }

    fun zoomReachesEnd() {
      isZooming = false
      motion.viewportChanged()
      motion.zoomEnded()
    }

    fun write() = motion.viewportChanged()

    /** The settle timer runs out; the view drops it while something moves. */
    fun timerFires() {
      if (!isSettleArmed) return
      isSettleArmed = false
      if (!motion.isMoving) settles++
    }
  }

  @Test
  fun `a scroll during a zoom animation lands the zoom on its target first`() {
    val view = FakeView()
    view.animateZoom()
    view.log.clear()
    view.animateScroll()
    // The scroll's target is computed at the final zoom.
    assertEquals(listOf("finish zoom", "start scroll"), view.log)
  }

  @Test
  fun `a jump during a zoom animation lands the zoom on its target first`() {
    val view = FakeView()
    view.animateZoom()
    view.log.clear()
    view.jumpScroll()
    assertEquals(listOf("finish zoom", "write"), view.log)
  }

  @Test
  fun `a fling during a zoom animation lands the zoom on its target first`() {
    val view = FakeView()
    view.touchDown()
    view.animateZoom() // The second tap of a double tap, then a drag.
    view.touchUp()
    view.log.clear()
    view.fling()
    assertEquals(listOf("finish zoom", "start scroll"), view.log)
  }

  @Test
  fun `a zoom during an animated scroll lands the scroll on its target first`() {
    val view = FakeView()
    view.animateScroll()
    view.log.clear()
    view.animateZoom()
    assertEquals(listOf("finish scroll", "start zoom"), view.log)
  }

  @Test
  fun `a zoom jump during an animated scroll lands the scroll on its target first`() {
    val view = FakeView()
    view.animateScroll()
    view.log.clear()
    view.jumpZoom()
    assertEquals(listOf("finish scroll", "write"), view.log)
  }

  @Test
  fun `a zoom during a fling stops the fling where it is`() {
    val view = FakeView()
    view.fling()
    view.log.clear()
    view.animateZoom()
    assertEquals(listOf("stop scroll", "start zoom"), view.log)
  }

  @Test
  fun `a new scroll or zoom replaces the running one where it is`() {
    val view = FakeView()
    view.animateScroll()
    view.log.clear()
    view.animateScroll()
    assertEquals(listOf("stop scroll", "start scroll"), view.log)

    view.motion.end(atTarget = false)
    view.animateZoom()
    view.log.clear()
    view.animateZoom()
    assertEquals(listOf("cancel zoom", "start zoom"), view.log)
  }

  @Test
  fun `a finger takes the viewport where the animations are`() {
    val view = FakeView()
    view.animateScroll()
    view.log.clear()
    view.touchDown()
    assertEquals(listOf("stop scroll"), view.log)
    view.touchUp()

    view.animateZoom()
    view.log.clear()
    view.touchDown()
    assertEquals(listOf("cancel zoom"), view.log)
  }

  @Test
  fun `ending at the targets lands the animations and stops a fling`() {
    val view = FakeView()
    view.animateScroll()
    view.log.clear()
    view.motion.end(atTarget = true)
    assertEquals(listOf("finish scroll"), view.log)

    view.animateZoom()
    view.log.clear()
    view.motion.end(atTarget = true)
    assertEquals(listOf("finish zoom"), view.log)

    view.fling()
    view.log.clear()
    view.motion.end(atTarget = true)
    assertEquals(listOf("stop scroll"), view.log)
  }

  @Test
  fun `ending in place lands nothing`() {
    val view = FakeView()
    view.animateScroll()
    view.log.clear()
    view.motion.end(atTarget = false)
    assertEquals(listOf("stop scroll"), view.log)

    view.animateZoom()
    view.log.clear()
    view.motion.end(atTarget = false)
    assertEquals(listOf("cancel zoom"), view.log)
  }

  @Test
  fun `the viewport moves until the finger, the scroller and the zoom animation have all ended`() {
    val view = FakeView()
    view.touchDown()
    view.animateZoom()
    view.touchUp()
    assertTrue(view.motion.isMoving)
    view.zoomReachesEnd()
    assertFalse(view.motion.isMoving)

    view.touchDown()
    view.touchUp()
    view.fling()
    assertTrue(view.motion.isMoving)
    view.scrollerReachesEnd()
    assertFalse(view.motion.isMoving)
  }

  @Test
  fun `a motion ends in one settle, after its last frame`() {
    val view = FakeView()
    view.touchDown()
    repeat(10) { view.write() }
    view.touchUp()
    view.fling()
    repeat(10) { view.write() }
    // A wait started earlier runs out during the fling.
    view.timerFires()
    assertEquals(0, view.settles)
    view.scrollerReachesEnd()
    view.timerFires()
    view.timerFires()
    assertEquals(1, view.settles)
  }

  @Test
  fun `a viewport change at rest ends in a settle`() {
    val view = FakeView()
    view.write()
    assertTrue(view.isSettleArmed)
    view.timerFires()
    assertEquals(1, view.settles)
  }

  @Test
  fun `a touch that is given up no longer counts`() {
    val view = FakeView()
    view.touchDown()
    // The document went away under the finger.
    view.motion.end(atTarget = false)
    view.touchUp()
    assertFalse(view.motion.isMoving)
    view.timerFires()
    assertEquals(1, view.settles)
  }

  /**
   * Every order of up to five events: the scroller and the zoom animation
   * never run together, the motion state matches what actually runs (no
   * flag left set), and whenever an event leaves the viewport at rest a
   * settle is waiting.
   */
  @Test
  fun `the invariants hold for every short sequence of events`() {
    val events = listOf<Pair<String, (FakeView) -> Boolean>>(
      "touch down" to { it.touchDown().let { true } },
      "touch up" to { it.touchUp().let { true } },
      "fling" to { it.fling().let { true } },
      "animate scroll" to { it.animateScroll().let { true } },
      "jump scroll" to { it.jumpScroll().let { true } },
      "scroller reaches end" to { view -> view.isScrolling.also { if (it) view.scrollerReachesEnd() } },
      "animate zoom" to { it.animateZoom().let { true } },
      "jump zoom" to { it.jumpZoom().let { true } },
      "zoom reaches end" to { view -> view.isZooming.also { if (it) view.zoomReachesEnd() } },
      "end in place" to { it.motion.end(atTarget = false).let { true } },
      "end at targets" to { it.motion.end(atTarget = true).let { true } },
      "write" to { it.write().let { true } },
      "timer fires" to { it.timerFires().let { false } },
    )
    val length = 5
    val sequence = IntArray(length)
    var checked = 0
    while (true) {
      val view = FakeView()
      for (step in 0 until length) {
        val (_, run) = events[sequence[step]]
        val happened = run(view)
        val trace = sequence.take(step + 1).joinToString(", ") { events[it].first }
        assertFalse("scroller and zoom animation both run after: $trace", view.isScrolling && view.isZooming)
        assertEquals(
          "motion state after: $trace",
          view.isTouching || view.isScrolling || view.isZooming,
          view.motion.isMoving,
        )
        if (happened && !view.motion.isMoving) assertTrue("no settle waiting after: $trace", view.isSettleArmed)
      }
      checked++
      var digit = length - 1
      while (digit >= 0 && sequence[digit] == events.size - 1) sequence[digit--] = 0
      if (digit < 0) break
      sequence[digit]++
    }
    assertEquals(371_293, checked)
  }
}

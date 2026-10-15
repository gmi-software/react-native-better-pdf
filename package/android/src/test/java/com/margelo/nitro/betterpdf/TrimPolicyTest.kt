@file:Suppress("DEPRECATION")

package com.margelo.nitro.betterpdf

import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import com.margelo.nitro.betterpdf.TrimPolicy.Response.NONE
import com.margelo.nitro.betterpdf.TrimPolicy.Response.RELEASE
import com.margelo.nitro.betterpdf.TrimPolicy.Response.RELEASE_AND_BACK_OFF
import org.junit.Assert.assertEquals
import org.junit.Test

class TrimPolicyTest {
  @Test
  fun `hiding the UI releases memory without backing off`() {
    assertEquals(RELEASE, TrimPolicy.of(TRIM_MEMORY_UI_HIDDEN))
  }

  @Test
  fun `background levels release memory without backing off`() {
    assertEquals(RELEASE, TrimPolicy.of(TRIM_MEMORY_BACKGROUND))
    assertEquals(RELEASE, TrimPolicy.of(TRIM_MEMORY_MODERATE))
    assertEquals(RELEASE, TrimPolicy.of(TRIM_MEMORY_COMPLETE))
  }

  @Test
  fun `pressure while running releases memory and backs off`() {
    assertEquals(RELEASE_AND_BACK_OFF, TrimPolicy.of(TRIM_MEMORY_RUNNING_LOW))
    assertEquals(RELEASE_AND_BACK_OFF, TrimPolicy.of(TRIM_MEMORY_RUNNING_CRITICAL))
  }

  @Test
  fun `moderate pressure while running keeps everything`() {
    assertEquals(NONE, TrimPolicy.of(TRIM_MEMORY_RUNNING_MODERATE))
  }
}

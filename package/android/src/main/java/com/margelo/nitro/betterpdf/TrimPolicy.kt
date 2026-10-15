package com.margelo.nitro.betterpdf

import android.content.ComponentCallbacks2

/** How the viewer answers [ComponentCallbacks2.onTrimMemory]. */
object TrimPolicy {
  enum class Response { NONE, RELEASE, RELEASE_AND_BACK_OFF }

  /**
   * `UI_HIDDEN` and the background levels above it come on every trip to the
   * background, not only under memory pressure. The `RUNNING_*` levels mean
   * pressure while the app is in use; since API 34 they are no longer
   * delivered, so one mapping covers every API level.
   */
  @Suppress("DEPRECATION")
  fun of(level: Int): Response = when {
    level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Response.RELEASE
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> Response.RELEASE_AND_BACK_OFF
    else -> Response.NONE
  }
}

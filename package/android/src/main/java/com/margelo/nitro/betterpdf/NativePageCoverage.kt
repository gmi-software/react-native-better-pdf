package com.margelo.nitro.betterpdf

import android.graphics.Bitmap
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip

/** `PageCoverage.cpp` on Bitmap pixels; registered in `JNI_OnLoad`. */
@Keep
@DoNotStrip
internal object NativePageCoverage {
  /** Coverage of the top [rows] rows of [rgba] into [coverage] from row [top]; false at a pixel that is not opaque grey. */
  @JvmStatic external fun fromRgba(rgba: Bitmap, width: Int, rows: Int, coverage: Bitmap, top: Int): Boolean

  /** The top [rows] rows of [coverage] into [rgba] as opaque greys. */
  @JvmStatic external fun toRgba(coverage: Bitmap, width: Int, rows: Int, rgba: Bitmap)
}

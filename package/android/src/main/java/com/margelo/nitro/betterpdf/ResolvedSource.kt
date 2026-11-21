package com.margelo.nitro.betterpdf

import android.os.ParcelFileDescriptor
import java.io.File

/**
 * An opened source: its descriptor, the file behind it when there is one (not
 * for `content://`), and whether it is a stale copy of a remote document.
 */
data class ResolvedSource(
  val descriptor: ParcelFileDescriptor,
  val file: File?,
  val isStale: Boolean = false,
  val remote: RemoteCopy? = null,
)

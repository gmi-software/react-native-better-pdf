package com.margelo.nitro.betterpdf

import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import java.nio.ByteBuffer

/** Decodes a `{ base64 }` text in C++ (`Base64Document.cpp`); registered in `JNI_OnLoad`. */
@Keep
@DoNotStrip
internal object NativeBase64Document {
  /** Returns why [text] is not a document, or null once it is written to [fd]. */
  @JvmStatic external fun writeDecoded(text: ByteBuffer, fd: Int): String?
}

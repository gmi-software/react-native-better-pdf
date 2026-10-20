package com.margelo.nitro.betterpdf

import com.margelo.nitro.betterpdf.PdfMethodException.Code
import com.margelo.nitro.betterpdf.outline.EncryptedPdfException
import com.margelo.nitro.betterpdf.outline.PdfFormatException
import com.margelo.nitro.core.Promise
import java.io.IOException

/**
 * Why a view or `PdfDocument` method rejected. Nitro rejections carry only
 * a message, so JS reads [code] from its `[code] ` prefix
 * (`PdfMethodError.ts`).
 */
internal class PdfMethodException(val code: Code, message: String, cause: Throwable? = null) :
  Exception("[${code.value}] $message", cause) {
  enum class Code(val value: String) {
    NOT_LOADED("not-loaded"),
    CLOSED("closed"),
    INVALID_ARGUMENT("invalid-argument"),
    NOT_SUPPORTED("not-supported"),
    NOT_PERMITTED("not-permitted"),
    BUSY("busy"),
    IO_ERROR("io-error"),
    INVALID_PDF("invalid-pdf"),
    OUT_OF_MEMORY("out-of-memory"),
    UNKNOWN("unknown"),
  }

  companion object {
    fun notLoaded() = PdfMethodException(Code.NOT_LOADED, "No document is loaded")

    fun closed() = PdfMethodException(Code.CLOSED, "The document was closed")
  }
}

/**
 * This failure as a [PdfMethodException], coded by its type unless it is
 * one; [message] replaces the failure's own.
 */
internal fun Throwable.toMethodException(message: String? = null): PdfMethodException {
  if (this is PdfMethodException) return this
  val code = when (this) {
    is OutOfMemoryError -> Code.OUT_OF_MEMORY
    is IOException -> Code.IO_ERROR
    is EncryptedPdfException -> Code.NOT_SUPPORTED
    is PdfFormatException -> Code.INVALID_PDF
    else -> Code.UNKNOWN
  }
  val fallback = if (this is OutOfMemoryError) "Out of memory" else javaClass.name
  return PdfMethodException(code, message ?: this.message ?: fallback, this)
}

/** Like [require], for a method's arguments: an `invalid-argument` [PdfMethodException]. */
internal inline fun requireArgument(value: Boolean, message: () -> String) {
  if (!value) throw PdfMethodException(Code.INVALID_ARGUMENT, message())
}

/** Rejects with [error] as a [PdfMethodException]. */
internal fun <T> Promise<T>.fail(error: Throwable) = reject(error.toMethodException())

package com.margelo.nitro.betterpdf

/** A load/render failure, convertible to the JS-facing [PdfError]. */
class PdfFailure(
  val code: PdfErrorCode,
  override val message: String,
  val page: Int? = null,
  val httpStatus: Int? = null,
  cause: Throwable? = null,
  /** The view's source that could not be loaded. */
  val sourceIndex: Int? = null,
  /** A source URI that [cause]'s message may quote; it is redacted there too. */
  private val uri: String? = null,
) : Exception(message, cause) {
  /** This failure, of the view's source [index]. */
  fun ofSource(index: Int): PdfFailure = PdfFailure(code, message, page, httpStatus, cause, sourceIndex = index, uri = uri)

  fun toPdfError(): PdfError =
    PdfError(
      code = code,
      message = message,
      page = page?.toDouble(),
      sourceIndex = sourceIndex?.toDouble(),
      httpStatus = httpStatus?.toDouble(),
      nativeDescription = cause?.let {
        val description = "${it.javaClass.simpleName}: ${it.message}"
        if (uri == null) description else description.replace(uri, uri.redactedUri())
      },
    )
}

package com.margelo.nitro.betterpdf

/**
 * Runs [read]; a remote copy it rejects as `invalid-pdf` is removed from the
 * cache and, unless this open just downloaded it, fetched once more.
 */
internal inline fun <T> ResolvedSource.readOrRefetch(reopen: () -> ResolvedSource, read: (ResolvedSource) -> T): T {
  try {
    return read(this)
  } catch (failure: PdfFailure) {
    if (failure.code != PdfErrorCode.INVALID_PDF || remote == null) throw failure
    remote.discard()
    if (remote.isDownloaded) throw failure
  }
  val fresh = reopen()
  try {
    return read(fresh)
  } catch (failure: PdfFailure) {
    if (failure.code == PdfErrorCode.INVALID_PDF) fresh.remote?.discard()
    throw failure
  }
}

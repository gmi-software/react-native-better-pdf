package com.margelo.nitro.betterpdf

/**
 * Runs [read]; a remote copy it rejects as `invalid-pdf` is removed from the
 * cache and, unless this open just downloaded it, fetched once more.
 */
internal inline fun <T> ResolvedSource.readOrRefetch(reopen: (mode: PdfCacheMode) -> ResolvedSource, read: (ResolvedSource) -> T): T {
  val discarded = try {
    return read(this)
  } catch (failure: PdfFailure) {
    if (failure.code != PdfErrorCode.INVALID_PDF || remote == null) throw failure
    remote.discard()
    if (remote.isDownloaded) throw failure
    remote
  }
  val fresh = reopen(discarded.mode)
  try {
    return read(fresh)
  } catch (failure: PdfFailure) {
    if (failure.code == PdfErrorCode.INVALID_PDF) fresh.remote?.discard()
    throw failure
  }
}

/** This source, asking for a persistent copy when the copy it replaces was one. */
internal fun NativePdfSource.refetching(mode: PdfCacheMode): NativePdfSource =
  if (mode == PdfCacheMode.PERSISTENT) copy(cache = mode) else this

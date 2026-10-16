package com.margelo.nitro.betterpdf.outline

/**
 * One file's [PdfCrossReferences] for all of its readers: read — or rebuilt
 * by scanning the whole file — once, by the first; when they cannot be
 * read, every later reader fails the same way without reading them again.
 * Thread-safe: a reader on another thread waits for the first.
 */
class CrossReferenceCache {
  private var crossReferences: Result<PdfCrossReferences>? = null

  /** The cross-references, read from [source] on the first call; every call's [source] must read the same file. */
  @Synchronized
  fun get(source: PdfByteSource): PdfCrossReferences {
    val known = crossReferences ?: try {
      Result.success(PdfCrossReferences.read(source))
    } catch (error: PdfFormatException) {
      Result.failure(error)
    }
    crossReferences = known
    return known.getOrThrow()
  }
}

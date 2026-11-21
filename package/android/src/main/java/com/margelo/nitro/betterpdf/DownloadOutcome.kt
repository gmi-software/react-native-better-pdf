package com.margelo.nitro.betterpdf

/** How a [SharedDownload] ended (a cancelled one ends without an outcome). */
internal sealed interface DownloadOutcome {
  /** Downloaded, or confirmed unchanged by the server (`304`). */
  data class Stored(val copy: CachedCopy, val isDownloaded: Boolean) : DownloadOutcome

  /**
   * [isServerUnreachable]: no connection, a timeout or a 5xx response — the
   * cases in which an existing copy may still be shown.
   */
  data class Failed(val failure: PdfFailure, val isServerUnreachable: Boolean) : DownloadOutcome
}

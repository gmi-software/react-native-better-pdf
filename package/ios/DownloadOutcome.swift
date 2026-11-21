/// How a `SharedDownload` ended (a cancelled one ends without an outcome).
enum DownloadOutcome {
  /// Downloaded, or confirmed unchanged by the server (`304`).
  case stored(CachedCopy, isDownloaded: Bool)
  /// `isServerUnreachable`: no connection, a timeout or a 5xx response — the
  /// cases in which an existing copy may still be shown.
  case failed(PdfLoadFailure, isServerUnreachable: Bool)
}

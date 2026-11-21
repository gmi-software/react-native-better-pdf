import Foundation

/// A source resolved to a local file, before it is opened.
struct ResolvedFile {
  /// The file to open: the source's own file, or a pin of a downloaded copy.
  let url: URL
  /// The path reported to JS (`onLoad`'s `filePath`).
  let filePath: String?
  /// A remote document's outdated copy, shown because the server could not
  /// be reached.
  let isStale: Bool
  /// To be unpinned once the document is no longer displayed.
  let pin: URL?
  var remote: RemoteCopy? = nil

  struct RemoteCopy {
    let key: String
    let mode: PdfCacheMode
    let isDownloaded: Bool
    /// Taken before the file is opened; nil when it could not be read.
    let file: DocumentCacheStore.FileIdentity?
  }
}

import Foundation
import PDFKit

/// An opened document and what the view reports and releases with it.
struct LoadedDocument {
  let document: PDFDocument
  /// The file opened: the source's own file, or a pin of a downloaded
  /// copy. Separate reads (outline, page images) open it again.
  let url: URL
  /// `onLoad`'s `filePath`.
  let filePath: String?
  /// `onLoad`'s `isStale`.
  let isStale: Bool
  /// Unpinned (`DocumentCacheStore.unpin`) when the view stops displaying it.
  let pin: URL?
}

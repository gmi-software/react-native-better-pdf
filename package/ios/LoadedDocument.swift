import Foundation
import PDFKit

/// An opened document and what the view reports and releases with it.
struct LoadedDocument {
  let document: PDFDocument
  /// `onLoad`'s `filePath`.
  let filePath: String?
  /// `onLoad`'s `isStale`.
  let isStale: Bool
  /// Unpinned (`DocumentCacheStore.unpin`) when the view stops displaying it.
  let pin: URL?
}

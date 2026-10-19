import PDFKit

extension PDFPage {
  /// Reads the page's link destinations while it is still in its own
  /// document. PDFKit resolves a destination lazily, against the document
  /// the page is in when it is first read: one read after the page was
  /// inserted into another document has no page (checked with the `links`
  /// and `links-all` fixtures), one read before keeps pointing at its page,
  /// which is then found at its index in the other document. Call on the
  /// thread that owns the document.
  func resolveLinkDestinations() {
    for annotation in annotations {
      _ = annotation.destination?.page
      _ = (annotation.action as? PDFActionGoTo)?.destination.page
    }
  }
}

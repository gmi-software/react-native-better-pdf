import PDFKit

extension PDFDocument {
  /// The outline flattened depth-first, with the same limits as Android's
  /// `PdfOutlineReader` (64 levels, 10,000 entries).
  func outlineEntries(maxDepth: Int = 64, maxEntries: Int = 10_000) -> [PdfOutlineEntry] {
    guard let root = outlineRoot else { return [] }
    var entries: [PdfOutlineEntry] = []
    func walk(_ node: PDFOutline, depth: Int) {
      for index in 0..<node.numberOfChildren {
        guard entries.count < maxEntries, let child = node.child(at: index) else { return }
        let page = child.targetPage.map { self.index(for: $0) }.flatMap { $0 == NSNotFound ? nil : Double($0) }
        entries.append(PdfOutlineEntry(title: child.normalizedLabel, page: page, depth: Double(depth)))
        if depth + 1 < maxDepth { walk(child, depth: depth + 1) }
      }
    }
    walk(root, depth: 0)
    return entries
  }
}

extension PdfOutlineEntry {
  /// The entry of a source whose first page is `firstPage` of the view's
  /// document.
  func movedTo(firstPage: Int) -> PdfOutlineEntry {
    guard firstPage != 0 else { return self }
    return PdfOutlineEntry(title: title, page: page.map { $0 + Double(firstPage) }, depth: depth)
  }
}

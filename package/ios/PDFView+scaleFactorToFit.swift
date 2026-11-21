import PDFKit

extension PDFView {
  /// The scale at which page `index` (the first by default) fits the view by
  /// `policy`, within the page break margins; 0 before layout. PDFKit applies
  /// one scale to every page. `columns`: pages side by side (2 with facing
  /// pages), each fitting its share of the width. `clearance`: height kept
  /// clear above and below the pages (content insets).
  func scaleFactorToFit(_ policy: PdfFitPolicy, page index: Int = 0, columns: Int = 1, clearance: CGFloat = 0) -> CGFloat {
    guard let page = document?.page(at: index) else { return 0 }
    var size = page.bounds(for: displayBox).size
    if page.rotation % 180 != 0 { swap(&size.width, &size.height) }
    let margins = displaysPageBreaks ? pageBreakMargins : .zero
    let width = (bounds.width - margins.left - margins.right) / CGFloat(max(columns, 1))
    let height = bounds.height - clearance - margins.top - margins.bottom
    guard size.width > 0, size.height > 0, width > 0, height > 0 else { return 0 }
    switch policy {
    case .width: return width / size.width
    case .height: return height / size.height
    case .page: return min(width / size.width, height / size.height)
    }
  }
}

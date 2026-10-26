import PDFKit

extension PDFPage {
  /// Converts a point in PDF page space (origin bottom-left of the box,
  /// unrotated) to PDF points from the top-left corner of the page as
  /// displayed — `box` with the page's rotation applied, as Android's
  /// renderer draws it. The convention of `onPageTap`, positions and
  /// overlays on every platform.
  func topLeftPoint(from pagePoint: CGPoint, box: PDFDisplayBox) -> CGPoint {
    let bounds = bounds(for: box)
    // From the unrotated box's top-left, y down.
    let u = pagePoint.x - bounds.minX
    let v = bounds.maxY - pagePoint.y
    // The rotation is clockwise.
    switch quarterTurns {
    case 1: return CGPoint(x: bounds.height - v, y: u)
    case 2: return CGPoint(x: bounds.width - u, y: bounds.height - v)
    case 3: return CGPoint(x: v, y: bounds.width - u)
    default: return CGPoint(x: u, y: v)
    }
  }

  /// Inverse of `topLeftPoint(from:box:)`.
  func pagePoint(fromTopLeft point: CGPoint, box: PDFDisplayBox) -> CGPoint {
    let bounds = bounds(for: box)
    let u: CGFloat
    let v: CGFloat
    switch quarterTurns {
    case 1: (u, v) = (point.y, bounds.height - point.x)
    case 2: (u, v) = (bounds.width - point.x, bounds.height - point.y)
    case 3: (u, v) = (bounds.width - point.y, point.x)
    default: (u, v) = (point.x, point.y)
    }
    return CGPoint(x: bounds.minX + u, y: bounds.maxY - v)
  }

  /// A rectangle in PDF points from the displayed page's top-left corner,
  /// in PDF page space.
  func pageRect(fromTopLeft rect: CGRect, box: PDFDisplayBox) -> CGRect {
    let a = pagePoint(fromTopLeft: CGPoint(x: rect.minX, y: rect.minY), box: box)
    let b = pagePoint(fromTopLeft: CGPoint(x: rect.maxX, y: rect.maxY), box: box)
    return CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: Swift.abs(a.x - b.x), height: Swift.abs(a.y - b.y))
  }

  /// Inverse of `pageRect(fromTopLeft:box:)`.
  func topLeftRect(from pageRect: CGRect, box: PDFDisplayBox) -> CGRect {
    let a = topLeftPoint(from: CGPoint(x: pageRect.minX, y: pageRect.minY), box: box)
    let b = topLeftPoint(from: CGPoint(x: pageRect.maxX, y: pageRect.maxY), box: box)
    return CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: Swift.abs(a.x - b.x), height: Swift.abs(a.y - b.y))
  }

  /// `rotation` in clockwise quarter turns, 0–3 (/Rotate may be negative).
  private var quarterTurns: Int {
    ((rotation / 90) % 4 + 4) % 4
  }
}

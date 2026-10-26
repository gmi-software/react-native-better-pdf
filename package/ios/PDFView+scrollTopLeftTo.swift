import PDFKit
import UIKit

extension PDFView {
  /// Scrolls `scrollView` (this view's own, in continuous layouts) so
  /// `point` — PDF points from `page`'s top-left corner — is at the top-left
  /// corner of the area below `topClearance`, clamped to the scrollable
  /// area. Inverse of `topLeftAnchor(topClearance:)`.
  func scroll(_ scrollView: UIScrollView, topLeftTo point: CGPoint, on page: PDFPage, topClearance: CGFloat = 0) {
    let viewPoint = convert(page.pagePoint(fromTopLeft: point, box: displayBox), from: page)
    let inset = scrollView.adjustedContentInset
    let maxX = max(-inset.left, scrollView.contentSize.width + inset.right - scrollView.bounds.width)
    let maxY = max(-inset.top, scrollView.contentSize.height + inset.bottom - scrollView.bounds.height)
    let target = CGPoint(
      x: min(max(scrollView.contentOffset.x + viewPoint.x - bounds.minX, -inset.left), maxX),
      y: min(max(scrollView.contentOffset.y + viewPoint.y - bounds.minY - topClearance, -inset.top), maxY)
    )
    scrollView.setContentOffset(target, animated: false)
  }

  /// After `go(to:)` — which ignores content insets, measured: the page's
  /// top lands at the view's top, or past it under a tall bottom inset —
  /// moves `page`'s top below `topClearance`, as far as the scrollable area
  /// allows (a short last page stays at the end). Vertical continuous
  /// layouts.
  func alignTop(of page: PDFPage, in scrollView: UIScrollView, topClearance: CGFloat) {
    let pageTop = convert(page.bounds(for: displayBox), from: page).minY
    let inset = scrollView.adjustedContentInset
    let maxY = max(-inset.top, scrollView.contentSize.height + inset.bottom - scrollView.bounds.height)
    let target = min(max(scrollView.contentOffset.y + pageTop - bounds.minY - topClearance, -inset.top), maxY)
    if Swift.abs(target - scrollView.contentOffset.y) > 0.5 { scrollView.contentOffset.y = target }
  }
}

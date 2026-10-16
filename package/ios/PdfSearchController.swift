import PDFKit
import UIKit

/// Runs `search()` for a view with PDFKit's asynchronous find: matches
/// arrive in document order, are highlighted as they come, and the first one
/// at or after the page being read becomes current. `onSearchChange` is
/// emitted at most every 100 ms while searching, immediately for the first
/// match, navigation, completion and clearing. Main thread.
final class PdfSearchController {
  private weak var pdfView: PDFView?
  private let emit: (PdfSearchChangeEvent) -> Void
  private var document: PDFDocument?
  private var observers: [NSObjectProtocol] = []
  private var matches: [PDFSelection] = []
  /// Ascending: matches arrive in page order.
  private var matchPages: [Int] = []
  private var currentIndex: Int?
  private var query = ""
  private var startPage = 0
  private var isComplete = true
  private var lastEmit = Date.distantPast
  private var highlightedPages: ClosedRange<Int>?
  private var pageHighlights: [Int: PDFSelection] = [:]
  private var needsHighlightRefresh = false

  /// Highlight of every match; set by `searchHighlightColor`.
  var matchColor = UIColor(red: 1, green: 214 / 255, blue: 0, alpha: 0.4) {
    didSet { recolorVisibleMatches() }
  }
  /// Highlight of the current match; set by `searchCurrentHighlightColor`.
  var currentColor = UIColor(red: 1, green: 140 / 255, blue: 0, alpha: 0.6) {
    didSet { recolorVisibleMatches() }
  }
  private static let emitInterval: TimeInterval = 0.1
  /// Pages highlighted either side of the visible ones.
  private static let pageMargin = 1
  /// Height kept clear at the top and bottom of the view (`contentInset`).
  var clearance: () -> (top: CGFloat, bottom: CGFloat) = { (0, 0) }

  init(pdfView: PDFView?, emit: @escaping (PdfSearchChangeEvent) -> Void) {
    self.pdfView = pdfView
    self.emit = emit
  }

  func search(_ query: String, startPage: Int) {
    stopFinding()
    guard !query.isEmpty else {
      clear()
      return
    }
    guard let pdfView, let document = pdfView.document else { return }
    self.query = query
    self.startPage = startPage
    self.document = document
    matches = []
    matchPages = []
    currentIndex = nil
    isComplete = false
    highlightedPages = nil
    pageHighlights = [:]
    needsHighlightRefresh = false
    pdfView.highlightedSelections = nil
    let center = NotificationCenter.default
    observers = [
      center.addObserver(forName: .PDFDocumentDidFindMatch, object: document, queue: .main) { [weak self] note in
        guard let selection = note.userInfo?["PDFDocumentFoundSelection"] as? PDFSelection else { return }
        self?.found(selection)
      },
      center.addObserver(forName: .PDFDocumentDidEndFind, object: document, queue: .main) { [weak self] _ in
        self?.finished()
      },
    ]
    document.beginFindString(query, withOptions: [.caseInsensitive])
    emitNow()
  }

  func next() {
    guard !matches.isEmpty else { return }
    select(currentIndex.map { ($0 + 1) % matches.count } ?? 0)
  }

  func previous() {
    guard !matches.isEmpty else { return }
    select(currentIndex.map { ($0 - 1 + matches.count) % matches.count } ?? matches.count - 1)
  }

  func clear() {
    stopFinding()
    // Not kept for a closed view or a document being replaced.
    document = nil
    let hadSearch = !query.isEmpty
    query = ""
    matches = []
    matchPages = []
    currentIndex = nil
    isComplete = true
    highlightedPages = nil
    pageHighlights = [:]
    needsHighlightRefresh = false
    pdfView?.highlightedSelections = nil
    if hadSearch { emitNow() }
  }

  /// Every scroll frame: the pages at the corners take ~2 µs, `visiblePages` ~0.1 ms.
  func viewportDidChange() {
    guard !query.isEmpty, let pdfView, let document else { return }
    if let highlightedPages {
      let bounds = pdfView.bounds
      let corners = [CGPoint(x: bounds.minX, y: bounds.minY), CGPoint(x: bounds.maxX, y: bounds.maxY)]
      let isShown = corners.allSatisfy { corner in
        pdfView.page(for: corner, nearest: true).map { highlightedPages.contains(document.index(for: $0)) } ?? false
      }
      if isShown { return }
    }
    viewportDidSettle()
  }

  /// The corners can miss a page at the edge of a horizontal layout.
  func viewportDidSettle() {
    guard !query.isEmpty, let visible = visiblePageRange() else { return }
    if let highlightedPages, highlightedPages.contains(visible.lowerBound), highlightedPages.contains(visible.upperBound) {
      return
    }
    refreshHighlights()
  }

  private func found(_ selection: PDFSelection) {
    let page = pageIndex(of: selection) ?? matchPages.last ?? 0
    matches.append(selection)
    matchPages.append(page)
    if currentIndex == nil, page >= startPage {
      select(matches.count - 1)
      return
    }
    pageHighlights[page] = nil
    if highlightedPages?.contains(page) ?? true { needsHighlightRefresh = true }
    if Date().timeIntervalSince(lastEmit) >= Self.emitInterval {
      if needsHighlightRefresh { refreshHighlights() }
      emitNow()
    }
  }

  private func finished() {
    stopFinding()
    isComplete = true
    // Nothing at or after the reader's page: wrap to the first match.
    if currentIndex == nil, !matches.isEmpty {
      select(0)
      return
    }
    refreshHighlights()
    emitNow()
  }

  private func select(_ index: Int) {
    if let currentIndex { pageHighlights[matchPages[currentIndex]] = nil }
    pageHighlights[matchPages[index]] = nil
    currentIndex = index
    let selection = matches[index]
    if let pdfView, let page = selection.pages.first {
      let rect = selection.bounds(for: page)
      pdfView.go(to: rect, on: page)
      keepClear(pdfView.convert(rect, from: page), in: pdfView)
    }
    // After `go(to:)`: the visible pages changed.
    refreshHighlights(isRecolored: true)
    emitNow()
  }

  /// Scrolls a match PDFKit brought on screen out from under the clearance.
  private func keepClear(_ rect: CGRect, in pdfView: PDFView) {
    let clearance = clearance()
    guard clearance.top > 0 || clearance.bottom > 0, let scrollView = pdfView.firstDescendantScrollView() else { return }
    let top = pdfView.bounds.minY + clearance.top
    let bottom = pdfView.bounds.maxY - clearance.bottom
    let delta: CGFloat = rect.minY < top ? rect.minY - top : (rect.maxY > bottom ? min(rect.maxY - bottom, rect.minY - top) : 0)
    guard delta != 0 else { return }
    let inset = scrollView.adjustedContentInset
    let maxY = max(-inset.top, scrollView.contentSize.height + inset.bottom - scrollView.bounds.height)
    scrollView.contentOffset.y = min(max(scrollView.contentOffset.y + delta, -inset.top), maxY)
  }

  /// PDFKit's setter costs time per selection on the visible pages (1,602
  /// matches: 900 ms as separate selections, 5 ms as one per page). It keeps
  /// drawn pages when only a colour changes, so recolouring resets the list.
  private func refreshHighlights(isRecolored: Bool = false) {
    needsHighlightRefresh = false
    guard let pdfView, let document else { return }
    highlightedPages = visiblePageRange().map { ($0.lowerBound - Self.pageMargin)...($0.upperBound + Self.pageMargin) }
    var selections: [PDFSelection] = []
    var highlights: [Int: PDFSelection] = [:]
    if let highlightedPages {
      for page in highlightedPages {
        guard let highlight = pageHighlights[page] ?? pageHighlight(on: page, in: document) else { continue }
        highlight.color = matchColor
        highlights[page] = highlight
        selections.append(highlight)
      }
    }
    pageHighlights = highlights
    if let currentIndex {
      matches[currentIndex].color = currentColor
      selections.append(matches[currentIndex])
    }
    if isRecolored { pdfView.highlightedSelections = nil }
    pdfView.highlightedSelections = selections
  }

  /// The page's matches but the current one, as one selection.
  private func pageHighlight(on page: Int, in document: PDFDocument) -> PDFSelection? {
    let others = (firstMatch(onOrAfter: page)..<firstMatch(onOrAfter: page + 1))
      .filter { $0 != currentIndex }
      .map { matches[$0] }
    guard !others.isEmpty else { return nil }
    let highlight = PDFSelection(document: document)
    highlight.add(others)
    return highlight
  }

  private func recolorVisibleMatches() {
    guard !matches.isEmpty else { return }
    refreshHighlights(isRecolored: true)
  }

  private func visiblePageRange() -> ClosedRange<Int>? {
    guard let pdfView, let document else { return nil }
    let indices = pdfView.visiblePages.map { document.index(for: $0) }.filter { $0 != NSNotFound }
    guard let first = indices.min(), let last = indices.max() else { return nil }
    return first...last
  }

  private func firstMatch(onOrAfter page: Int) -> Int {
    var low = 0
    var high = matchPages.count
    while low < high {
      let mid = (low + high) / 2
      if matchPages[mid] < page { low = mid + 1 } else { high = mid }
    }
    return low
  }

  private func pageIndex(of selection: PDFSelection) -> Int? {
    guard let page = selection.pages.first, let document else { return nil }
    let index = document.index(for: page)
    return index == NSNotFound ? nil : index
  }

  /// Observers first: `cancelFindString()` posts `PDFDocumentDidEndFind` before it returns.
  private func stopFinding() {
    observers.forEach { NotificationCenter.default.removeObserver($0) }
    observers = []
    if let document, document.isFinding { document.cancelFindString() }
  }

  private func emitNow() {
    lastEmit = Date()
    let currentPage = currentIndex.flatMap { pageIndex(of: matches[$0]) }
    emit(PdfSearchChangeEvent(
      query: query,
      matchCount: Double(matches.count),
      currentMatch: currentIndex.map(Double.init),
      currentPage: currentPage.map(Double.init),
      isComplete: isComplete,
      isSupported: true
    ))
  }
}

// bench <pdf> <query>; see README.md in this folder.
import PDFKit
import UIKit

setvbuf(stdout, nil, _IONBF, 0)

/// Stand-in for the Nitro-generated event.
struct PdfSearchChangeEvent {
  let query: String
  let matchCount: Double
  let currentMatch: Double?
  let currentPage: Double?
  let isComplete: Bool
  let isSupported: Bool
}

let arguments = CommandLine.arguments
guard arguments.count == 3, let document = PDFDocument(url: URL(fileURLWithPath: arguments[1])) else {
  print("usage: bench <pdf> <query>")
  exit(2)
}
let query = arguments[2]

func time(_ body: () -> Void) -> Double {
  let start = CACurrentMediaTime()
  body()
  return (CACurrentMediaTime() - start) * 1000
}

func range(_ values: [Double]) -> String {
  String(format: "%.1f–%.1f", values.min() ?? 0, values.max() ?? 0)
}

func runLoop(for seconds: TimeInterval) {
  RunLoop.main.run(until: Date(timeIntervalSinceNow: seconds))
}

/// How long each main-queue ping from another thread waited (ms).
final class StallProbe {
  private let lock = NSLock()
  private var waits: [Double] = []
  private var isRunning = true

  func start() {
    Thread.detachNewThread { [self] in
      while running() {
        let sent = CACurrentMediaTime()
        let done = DispatchSemaphore(value: 0)
        DispatchQueue.main.async { [self] in
          let waited = (CACurrentMediaTime() - sent) * 1000
          lock.lock()
          waits.append(waited)
          lock.unlock()
          done.signal()
        }
        done.wait()
        usleep(1000)
      }
    }
  }

  func stop() -> [Double] {
    lock.lock()
    defer { lock.unlock() }
    isRunning = false
    return waits
  }

  private func running() -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return isRunning
  }
}

let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
let pdfView = PDFView(frame: window.bounds)
pdfView.displayMode = .singlePageContinuous
pdfView.displayDirection = .vertical
window.addSubview(pdfView)
window.isHidden = false
pdfView.document = document
pdfView.autoScales = true
pdfView.layoutIfNeeded()
guard let scrollView = pdfView.firstDescendantScrollView() else {
  print("PDFView has no scroll view")
  exit(2)
}

var last: PdfSearchChangeEvent?
let controller = PdfSearchController(pdfView: pdfView) { last = $0 }
// As HybridBetterPdfView: viewportDidChange per scroll frame, viewportDidSettle once still.
#if VIEWPORT_HOOK
let offsetObservation = scrollView.observe(\.contentOffset, options: []) { _, _ in controller.viewportDidChange() }
#endif

func settle() {
  runLoop(for: 0.3)
  #if VIEWPORT_HOOK
  controller.viewportDidSettle()
  #endif
}

var failures: [String] = []

func visiblePages() -> [Int] { pdfView.visiblePages.map { document.index(for: $0) } }

func expectedCharacters(on page: PDFPage) -> Set<Int> {
  let text = (page.string ?? "") as NSString
  var result = Set<Int>()
  var searched = NSRange(location: 0, length: text.length)
  while true {
    let found = text.range(of: query, options: .caseInsensitive, range: searched)
    if found.location == NSNotFound { break }
    result.formUnion(found.location..<found.upperBound)
    searched = NSRange(location: found.upperBound, length: text.length - found.upperBound)
  }
  return result
}

func characters(of selection: PDFSelection, on page: PDFPage) -> Set<Int> {
  var result = Set<Int>()
  for index in 0..<selection.numberOfTextRanges(on: page) {
    let range = selection.range(at: index, on: page)
    result.formUnion(range.location..<range.upperBound)
  }
  return result
}

func selectionLayers(_ layer: CALayer) -> [CALayer] {
  let own = String(describing: type(of: layer)) == "PDFSelectionLayer" ? [layer] : []
  return own + (layer.sublayers ?? []).flatMap(selectionLayers)
}

func check(_ phase: String) {
  runLoop(for: 0.3)
  let assigned = pdfView.highlightedSelections ?? []
  var problems: [String] = []
  let current = assigned.filter { $0.color == controller.currentColor }
  if (last?.matchCount ?? 0) > 0, current.count != 1 {
    problems.append("\(current.count) selections in the current colour")
  }
  let otherColour = assigned.filter { $0.color != controller.currentColor && $0.color != controller.matchColor }
  if !otherColour.isEmpty { problems.append("\(otherColour.count) selections in another colour") }
  let visible = Set(pdfView.visiblePages.map(ObjectIdentifier.init))
  var onPage: [ObjectIdentifier: [PDFSelection]] = [:]
  for selection in assigned {
    for page in selection.pages where visible.contains(ObjectIdentifier(page)) {
      onPage[ObjectIdentifier(page), default: []].append(selection)
    }
  }
  for page in pdfView.visiblePages {
    let text = (page.string ?? "") as NSString
    var covered = Set<Int>()
    for selection in onPage[ObjectIdentifier(page)] ?? [] {
      let selected = characters(of: selection, on: page)
      if !covered.isDisjoint(with: selected) { problems.append("page \(document.index(for: page)): overlapping highlights") }
      covered.formUnion(selected)
    }
    let expected = expectedCharacters(on: page)
    // PDFKit joins matches either side of a line break into one range.
    let extra = covered.subtracting(expected).filter { text.character(at: $0) != 10 }
    let missing = expected.subtracting(covered)
    if !missing.isEmpty || !extra.isEmpty {
      problems.append("page \(document.index(for: page)): \(missing.count) characters unhighlighted, \(extra.count) extra")
    }
  }
  if let currentPage = last?.currentPage.map({ Int($0) }), visiblePages().contains(currentPage) {
    let drawn = selectionLayers(pdfView.layer).filter { $0.backgroundColor.map(UIColor.init(cgColor:)) == controller.currentColor }
    if drawn.isEmpty { problems.append("current match not drawn") }
  }
  print("check \(phase): visible pages \(visiblePages()), \(assigned.count) selections:",
        problems.isEmpty ? "OK" : "FAILED \(problems)")
  failures += problems.map { "\(phase): \($0)" }
}

let probe = StallProbe()
probe.start()
let searchTime = time {
  controller.search(query, startPage: 0)
  while last?.isComplete != true { runLoop(for: 0.05) }
}
let stalls = probe.stop()
let matches = Int(last?.matchCount ?? 0)
print(String(format: "search \"%@\": %d matches in %.2f s; longest main-thread stall %.1f ms, %d over 16 ms",
             query, matches, searchTime / 1000, stalls.max() ?? 0, stalls.filter { $0 > 16 }.count))
check("search")

let colourTimes = [UIColor.blue, .green, .yellow].map { colour in
  time { controller.matchColor = colour.withAlphaComponent(0.4) }
}
print("colour change: \(range(colourTimes)) ms")
check("colour change")

let stepTimes = (0..<5).map { _ in time { controller.next() } } + (0..<3).map { _ in time { controller.previous() } }
print("next / previous: \(range(stepTimes)) ms")
check("next / previous")

var dragTimes: [Double] = []
for _ in 0..<60 {
  dragTimes.append(time { scrollView.contentOffset.y += 200 })
  runLoop(for: 0.005)
}
settle()
let dragMean = dragTimes.reduce(0, +) / Double(dragTimes.count)
print(String(format: "drag: mean %.1f ms, longest step %.1f ms", dragMean, dragTimes.max() ?? 0))
check("drag")

let jumpPage = document.pageCount * 7 / 10
let jumpTime = time {
  if let page = document.page(at: jumpPage) { pdfView.go(to: page) }
}
settle()
print(String(format: "jump to page %d: %.1f ms", jumpPage + 1, jumpTime))
check("jump")

#if VIEWPORT_HOOK
let microsecondsPerCall = time { for _ in 0..<1000 { controller.viewportDidChange() } }
print(String(format: "viewportDidChange with nothing new on screen: %.1f µs per call", microsecondsPerCall))
#endif

controller.next()
check("next from far away")

print(String(format: "| %@ | %d | %@ | %@ | %.1f | %d | %.1f | %.1f / %.1f | %.1f | %@ |",
             query, matches, range(colourTimes), range(stepTimes), stalls.max() ?? 0, stalls.filter { $0 > 16 }.count,
             searchTime / 1000, dragMean, dragTimes.max() ?? 0, jumpTime, failures.isEmpty ? "OK" : "FAILED"))
exit(failures.isEmpty ? 0 : 1)

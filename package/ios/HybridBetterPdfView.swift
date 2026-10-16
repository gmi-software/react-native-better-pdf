import NitroModules
import PDFKit
import UIKit

/// iOS implementation of `BetterPdfView`.
///
/// PDFKit's `PDFView` owns scrolling, zooming, page virtualization and tiled
/// background rendering. This class owns everything around it: loading
/// (including remote download), scale mapping, initial page, events, and
/// resource lifecycle.
///
/// Threading: props, `afterUpdate` and `onDropView` arrive on the main thread.
/// Methods called through `hybridRef` arrive on the JS thread and hop to main.
final class HybridBetterPdfView: HybridBetterPdfViewSpec {
  private let container = PdfContainerView()
  private let pageOverlays = PageOverlays()
  private lazy var formInteraction = container.pdfView.map(FormInteraction.init(pdfView:))
  private lazy var searchController = PdfSearchController(pdfView: container.pdfView) { [weak self] event in
    self?.onSearchChange?(event)
  }
  var view: UIView { container }
  private var pdfView: PDFView? { container.pdfView }

  // MARK: Props

  var source: NativePdfSource? {
    didSet { if !source.isSameSource(as: oldValue) { needsReload = true } }
  }
  var password: String? {
    didSet { if password != oldValue { needsReload = true } }
  }
  var initialPosition: PdfInitialPosition?
  var minScale: Double? {
    didSet { needsScaleUpdate = true }
  }
  var maxScale: Double? {
    didSet { needsScaleUpdate = true }
  }
  var pageSpacing: Double? {
    didSet {
      applyPageSpacing()
      // The page view controller takes its spacing only when it is set up.
      if isPagedLayout && Self.resolvedSpacing(oldValue) != resolvedPageSpacing { needsLayoutModeUpdate = true }
    }
  }
  var horizontal: Bool? {
    didSet { if horizontal != oldValue { needsLayoutModeUpdate = true } }
  }
  var enablePaging: Bool? {
    didSet { if enablePaging != oldValue { needsLayoutModeUpdate = true } }
  }
  var enableRTL: Bool? {
    didSet { if enableRTL != oldValue { needsLayoutModeUpdate = true } }
  }
  var singlePage: Bool? {
    didSet { if singlePage != oldValue { needsLayoutModeUpdate = true } }
  }
  var fitPolicy: PdfFitPolicy? {
    didSet { if fitPolicy != oldValue { needsLayoutModeUpdate = true } }
  }
  var spreadMode: PdfSpreadMode? {
    didSet { if spreadMode != oldValue { needsLayoutModeUpdate = true } }
  }
  var colorScheme: PdfColorScheme? {
    didSet { if colorScheme != oldValue { applyColorScheme() } }
  }
  var contentInset: NativeContentInset? {
    didSet {
      guard contentInset?.top != oldValue?.top || contentInset?.bottom != oldValue?.bottom else { return }
      applyScrollInsets()
      // Fitting a page's height depends on the space between the insets.
      container.setNeedsLayout()
    }
  }
  var overlayLayerId: String? {
    didSet {
      pageOverlays.layerId = overlayLayerId
      layoutOverlaysSoon()
    }
  }
  var overlays: [PdfOverlayPlacement]? {
    didSet {
      pageOverlays.placements = overlays ?? []
      observeOverlayScrollViews()
      layoutOverlaysSoon()
    }
  }
  var enableDoubleTapZoom: Bool? {
    didSet { container.isDoubleTapZoomEnabled = enableDoubleTapZoom ?? true }
  }
  var pageAccessibilityLabel: String?
  var scrollEnabled: Bool? {
    didSet { applyScrollEnabled() }
  }
  var showsVerticalScrollIndicator: Bool? {
    didSet { applyScrollIndicators() }
  }
  var showsHorizontalScrollIndicator: Bool? {
    didSet { applyScrollIndicators() }
  }
  var enableTextSelection: Bool? {
    didSet { container.isTextSelectionEnabled = enableTextSelection ?? true }
  }
  var enableFormFilling: Bool? {
    didSet { applyFormFilling() }
  }
  var searchHighlightColor: Double? {
    didSet {
      guard let searchHighlightColor else { return }
      searchController.matchColor = UIColor(argb: searchHighlightColor)
    }
  }
  var searchCurrentHighlightColor: Double? {
    didSet {
      guard let searchCurrentHighlightColor else { return }
      searchController.currentColor = UIColor(argb: searchCurrentHighlightColor)
    }
  }
  var onLoad: ((PdfLoadEvent) -> Void)?
  var onLoadProgress: ((PdfLoadProgressEvent) -> Void)?
  var onError: ((PdfError) -> Void)?
  var onPageChange: ((PdfPageChangeEvent) -> Void)?
  var onLinkPress: ((PdfLinkPressEvent) -> Void)?
  var onPageTap: ((PdfPageTapEvent) -> Void)?
  var onScaleChange: ((PdfScaleChangeEvent) -> Void)?
  var onViewportChange: ((PdfViewportChangeEvent) -> Void)?
  var onSearchChange: ((PdfSearchChangeEvent) -> Void)?
  var onTextSelectionChange: ((PdfTextSelectionChangeEvent) -> Void)?
  var onFormChange: ((NativePdfFormChangeEvent) -> Void)?

  // MARK: State (main thread)

  private var needsReload = false
  private var needsScaleUpdate = false
  private var needsLayoutModeUpdate = false
  private var pageChangeObserver: NSObjectProtocol?
  private var loadTask: PdfLoadTask?
  /// The pin of the displayed downloaded copy, released with the document.
  private var displayedPin: URL?
  private var pendingInitialPage: Int?
  /// With `pendingInitialPage`: the page point to put at the top-left corner.
  private var pendingInitialPoint: CGPoint?
  /// `initialScale`, applied with the document's first fit.
  private var pendingInitialScale: Double?
  private var fitScale: CGFloat = 0
  /// Relative zoom last sent through `onScaleChange`; nil until the
  /// document's first fit, which is the baseline rather than a change.
  private var lastReportedScale: Double?
  private var scaleObserver: NSObjectProtocol?
  /// Scale and viewport events wait for the viewport to stop moving.
  private lazy var settle = SettleDebouncer(delay: Self.settleDelay) { [weak self] in
    self?.reportIfSettled()
  }
  /// Last `onViewportChange` event; nil until the document's first.
  private var lastReportedViewport: PdfViewportChangeEvent?
  /// Paged layouts: the scale PDFKit showed the current page at on arrival.
  private var pagedFitScale: CGFloat?
  private var lastReportedPage = -1
  /// The page a layout switch or resize keeps: the reported page, except
  /// that with facing pages a page the reader was on stays while it is on
  /// screen — spreads report the first page of the row under the centre,
  /// which on a tall view can be the row below the one navigated to.
  /// Switching to spreads and back returns to the reader's page.
  private var readingPage = -1
  /// While a layout mode switch or resize is applied: PDFKit passes through
  /// other pages (often page 1) on the way, which must not count as the
  /// reader's page or reach `onPageChange`.
  private var isRelayingOut = false
  /// The container size of the last layout; a change is a resize.
  private var lastLayoutSize: CGSize = .zero
  /// `getOutline()` result for the displayed file (main thread).
  private var outlineCache: (url: URL, entries: [PdfOutlineEntry])?
  /// `getPageSizes()` result for the displayed file (main thread).
  private var pageSizesCache: (url: URL, sizes: [PdfPageSize])?
  /// The displayed document's form, read on first use (main thread).
  private var documentForm: DocumentForm?
  /// Reads separate instances of the displayed file (outline, page sizes).
  private static let readQueue = DispatchQueue(label: "com.betterpdf.read", qos: .userInitiated)
  /// Writes exports: seconds for long documents, so not on `readQueue`.
  private static let writeQueue = DispatchQueue(label: "com.betterpdf.write", qos: .userInitiated)
  /// Scroll-offset observation of PDFView's internal scroll view; drives
  /// `onPageChange` with the same rule as Android (see `reportCurrentPage`).
  private var offsetObservation: NSKeyValueObservation?
  /// Content-size observation of the same scroll view; keeps a short
  /// document at the top while it is zoomed (see `applyScrollInsets`).
  private var contentSizeObservation: NSKeyValueObservation?
  /// The other scroll views in the PDF view: in paged layouts every page of
  /// the page view controller zooms and pans in its own. Overlays follow
  /// them too; re-collected as pages show (the controller creates them).
  private var overlayScrollObservations: [NSKeyValueObservation] = []
  private weak var scrollView: UIScrollView?

  /// Read from the JS thread by `getStats()`.
  private let statsLock = NSLock()
  private var statsPageCount = 0

  override init() {
    super.init()
    applyPageSpacing()
    container.onLayout = { [weak self] in self?.layoutDidChange() }
    container.linkPressed = { [weak self] url in
      self?.onLinkPress?(PdfLinkPressEvent(url: url.absoluteString))
    }
    container.pageTapped = { [weak self] page, point in
      self?.reportPageTap(page: page, point: point)
    }
    container.selectionSettled = { [weak self] text in
      self?.onTextSelectionChange?(PdfTextSelectionChangeEvent(text: text))
    }
    _ = formInteraction
    searchController.clearance = { [weak self] in self?.clearance ?? (0, 0) }
    // Posted for every frame of a pinch; reported once the zoom settles.
    scaleObserver = NotificationCenter.default.addObserver(
      forName: .PDFViewScaleChanged, object: container.pdfView, queue: .main
    ) { [weak self] _ in
      self?.layoutOverlays()
      self?.settle.noteMotion()
    }
  }

  func afterUpdate() {
    if needsLayoutModeUpdate {
      needsLayoutModeUpdate = false
      applyLayoutMode()
    }
    if needsReload {
      needsReload = false
      reload()
    }
    if needsScaleUpdate {
      needsScaleUpdate = false
      applyScaleLimits(preservingRelativeScale: true)
    }
  }

  func onDropView() {
    releaseCallbacks()
    searchController.clear()
    documentForm = nil
    offsetObservation = nil
    contentSizeObservation = nil
    overlayScrollObservations = []
    if let scaleObserver { NotificationCenter.default.removeObserver(scaleObserver) }
    scaleObserver = nil
    settle.cancel()
    if let pageChangeObserver { NotificationCenter.default.removeObserver(pageChangeObserver) }
    pageChangeObserver = nil
    loadTask?.cancel()
    loadTask = nil
    // Release PDFKit's page views, tiles and the document now rather than
    // whenever JS garbage-collects its reference to this hybrid object.
    container.releasePdfView()
    releaseDisplayedPin()
    scrollView = nil
    setStatsPageCount(0)
  }

  /// The callbacks hold JS functions, which hold the JS wrapper's state and
  /// with it this hybrid object (`hybridRef`): a cycle through a JS GC root
  /// that kept every closed viewer alive (measured, see
  /// docs/performance/results.md). Also, no events into JS for a dropped view.
  private func releaseCallbacks() {
    onLoad = nil
    onLoadProgress = nil
    onError = nil
    onPageChange = nil
    onLinkPress = nil
    onPageTap = nil
    onScaleChange = nil
    onViewportChange = nil
    onSearchChange = nil
    onTextSelectionChange = nil
    onFormChange = nil
  }

  // MARK: Methods (JS thread)

  func goToPage(page: Double, animated: Bool) throws {
    guard let index = page.pageIndex else { return }
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.keepReadingPage(index)
      self.scrollToPage(index, animated: animated)
    }
  }

  /// Navigation names the reader's page, also within a spread whose first
  /// page is reported (see `readingPage`).
  private func keepReadingPage(_ index: Int) {
    guard let count = pdfView?.document?.pageCount, count > 0 else { return }
    readingPage = min(max(index, 0), count - 1)
  }

  func goToViewport(viewport: PdfViewport) throws {
    guard let index = viewport.page.pageIndex,
          [viewport.x, viewport.y, viewport.scale].allSatisfy(\.isFinite), viewport.scale > 0 else { return }
    DispatchQueue.main.async { [weak self] in
      self?.applyViewport(
        page: index,
        point: CGPoint(x: viewport.x, y: viewport.y),
        scale: viewport.scale
      )
    }
  }

  func search(query: String) throws {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.searchController.search(query, startPage: max(self.lastReportedPage, 0))
    }
  }

  func searchNext() throws {
    DispatchQueue.main.async { [weak self] in self?.searchController.next() }
  }

  func searchPrevious() throws {
    DispatchQueue.main.async { [weak self] in self?.searchController.previous() }
  }

  func clearSearch() throws {
    DispatchQueue.main.async { [weak self] in self?.searchController.clear() }
  }

  func getOutline() throws -> Promise<[PdfOutlineEntry]> {
    let promise = Promise<[PdfOutlineEntry]>()
    DispatchQueue.main.async { [weak self] in
      guard let self, let url = self.pdfView?.document?.documentURL else {
        promise.reject(withError: RuntimeError.error(withMessage: "No document is loaded"))
        return
      }
      if let cached = self.outlineCache, cached.url == url {
        promise.resolve(withResult: cached.entries)
        return
      }
      let password = self.password
      // Resolving outline destinations is slow (measured ~130 ms for 1,000
      // entries) and PDFKit objects are not thread-safe, while the view's
      // document is in use by PDFView on the main thread. Read a separate
      // instance of the same file off the main thread instead. Weak: the
      // read must not keep the view alive, nor release it off the main thread.
      Self.readQueue.async { [weak self] in
        guard let document = PDFDocument(url: url) else {
          promise.reject(withError: RuntimeError.error(withMessage: "The outline could not be read"))
          return
        }
        if document.isLocked { _ = document.unlock(withPassword: password ?? "") }
        let entries = document.outlineEntries()
        DispatchQueue.main.async { [weak self] in
          if self?.pdfView?.document?.documentURL == url { self?.outlineCache = (url, entries) }
        }
        promise.resolve(withResult: entries)
      }
    }
    return promise
  }

  func getPageSizes() throws -> Promise<[PdfPageSize]> {
    let promise = Promise<[PdfPageSize]>()
    DispatchQueue.main.async { [weak self] in
      guard let self, let url = self.pdfView?.document?.documentURL else {
        promise.reject(withError: RuntimeError.error(withMessage: "No document is loaded"))
        return
      }
      if let cached = self.pageSizesCache, cached.url == url {
        promise.resolve(withResult: cached.sizes)
        return
      }
      let password = self.password
      // Like getOutline: a separate instance of the same file, off the main
      // thread, so a long document does not stall the view's.
      Self.readQueue.async { [weak self] in
        guard let document = PDFDocument(url: url) else {
          promise.reject(withError: RuntimeError.error(withMessage: "The page sizes could not be read"))
          return
        }
        if document.isLocked { _ = document.unlock(withPassword: password ?? "") }
        let sizes = document.pageSizes()
        DispatchQueue.main.async { [weak self] in
          if self?.pdfView?.document?.documentURL == url { self?.pageSizesCache = (url, sizes) }
        }
        promise.resolve(withResult: sizes)
      }
    }
    return promise
  }

  func renderPage(page: Double, options: PdfRenderPageOptions) throws -> Promise<PdfPageImage> {
    let promise = Promise<PdfPageImage>()
    DispatchQueue.main.async { [weak self] in
      guard let self, let document = self.pdfView?.document, let url = document.documentURL else {
        promise.reject(withError: RuntimeError.error(withMessage: "No document is loaded"))
        return
      }
      let request: PageImageRequest
      do {
        request = try PageImageRequest(page: page, pageCount: document.pageCount, options: options)
      } catch {
        promise.reject(withError: error)
        return
      }
      let password = self.password
      // Commits text being typed, so the image shows it.
      self.container.endEditing(true)
      let filledForm = self.filledFormSnapshot(of: document)
      // A separate instance of the file, like getOutline: PDFKit objects are
      // not thread-safe and the view draws its own document on main. A
      // filled-in form is copied over to it.
      Self.readQueue.async {
        do {
          guard let renderDocument = Self.openCopy(of: url, password: password, filledIn: filledForm)?.document else {
            throw RuntimeError.error(withMessage: "The document could not be opened for rendering")
          }
          promise.resolve(withResult: try request.render(from: renderDocument))
        } catch {
          promise.reject(withError: error)
        }
      }
    }
    return promise
  }

  func print(jobName: String) throws -> Promise<PdfPrintResult> {
    let promise = Promise<PdfPrintResult>()
    DispatchQueue.main.async { [weak self] in
      guard let self, let document = self.pdfView?.document, let url = document.documentURL else {
        promise.reject(withError: RuntimeError.error(withMessage: "No document is loaded"))
        return
      }
      guard UIPrintInteractionController.isPrintingAvailable else {
        promise.reject(withError: RuntimeError.error(withMessage: "Printing is not available on this device"))
        return
      }
      guard document.allowsPrinting else {
        promise.reject(withError: RuntimeError.error(withMessage: "The document does not permit printing"))
        return
      }
      guard !self.isPrinting else {
        promise.reject(withError: RuntimeError.error(withMessage: "A print dialog is already open"))
        return
      }
      self.isPrinting = true
      // A filled-in form prints as shown, text being typed included.
      self.container.endEditing(true)
      let filledForm = self.filledFormSnapshot(of: document)
      guard document.isEncrypted || filledForm != nil else {
        self.presentPrintDialog(printing: url, jobName: jobName, promise: promise)
        return
      }
      // The print system opens files without a password: print an unlocked
      // copy, filled in like the displayed document, kept in memory.
      // Serialized off the main thread on a separate instance of the file,
      // like renderPage.
      let password = self.password
      Self.writeQueue.async { [weak self] in
        let copy = Self.openCopy(of: url, password: password, filledIn: filledForm)?.document
        // Without options PDFKit keeps some ciphers' encryption (AES-128).
        let data = copy.flatMap { $0.dataRepresentation(options: $0.protectedWriteOptions(unlockPassword: nil)) }
        DispatchQueue.main.async { [weak self] in
          // Closed or replaced meanwhile: no dialog over another screen.
          guard let self, self.pdfView?.document === document else {
            self?.isPrinting = false
            promise.reject(withError: RuntimeError.error(withMessage: "The document was closed"))
            return
          }
          guard let data else {
            self.isPrinting = false
            promise.reject(withError: RuntimeError.error(withMessage: "The document could not be prepared for printing"))
            return
          }
          self.presentPrintDialog(printing: data, jobName: jobName, promise: promise)
        }
      }
    }
    return promise
  }

  func getFormFields() throws -> Promise<[NativePdfFormField]> {
    let promise = Promise<[NativePdfFormField]>()
    DispatchQueue.main.async { [weak self] in
      guard let self, let pdfView = self.pdfView, let document = pdfView.document, let form = self.form() else {
        promise.reject(withError: RuntimeError.error(withMessage: "No document is loaded"))
        return
      }
      // Commits text being typed, so it is read too.
      self.container.endEditing(true)
      let box = pdfView.displayBox
      promise.resolve(withResult: form.fields().compactMap { field in
        NativePdfFormField(name: field.name, widgets: field.widgets, document: document, box: box)
      })
    }
    return promise
  }

  func setFormFieldValues(values: [NativePdfFormValue]) throws -> Promise<Void> {
    let promise = Promise<Void>()
    DispatchQueue.main.async { [weak self] in
      guard let self, let document = self.pdfView?.document as? ColorSchemeDocument, let form = self.form() else {
        promise.reject(withError: RuntimeError.error(withMessage: "No document is loaded"))
        return
      }
      // Commits text being typed first, so it does not overwrite the values.
      self.container.endEditing(true)
      do {
        try document.withoutReportingChanges(countingAsChange: true) { try form.setValues(values) }
        promise.resolve(withResult: ())
      } catch {
        promise.reject(withError: error)
      }
    }
    return promise
  }

  func exportDocument(filePath: String) throws -> Promise<PdfExportResult> {
    let promise = Promise<PdfExportResult>()
    DispatchQueue.main.async { [weak self] in
      guard let self, let document = self.pdfView?.document, let url = document.documentURL else {
        promise.reject(withError: RuntimeError.error(withMessage: "No document is loaded"))
        return
      }
      // Ends text editing, so a field being typed in is written too.
      self.container.endEditing(true)
      let destination: URL
      do {
        destination = try filePath.isEmpty ? Self.newExportFile(named: url) : Self.exportFile(at: filePath)
      } catch {
        promise.reject(withError: error)
        return
      }
      // The displayed file is read while the copy is written, and is never
      // changed (an export over it would replace it).
      if destination.standardizedFileURL.resolvingSymlinksInPath() == url.standardizedFileURL.resolvingSymlinksInPath() {
        promise.reject(withError: RuntimeError.error(withMessage: "The export cannot replace the displayed document's file"))
        return
      }
      let password = self.password
      let filledForm = self.filledFormSnapshot(of: document)
      // Off the main thread: PDFKit writes a long document in seconds.
      Self.writeQueue.async {
        do {
          if let filledForm {
            guard let copy = Self.openCopy(of: url, password: password, filledIn: filledForm) else {
              throw RuntimeError.error(withMessage: "The document could not be opened for writing")
            }
            let options = copy.document.protectedWriteOptions(unlockPassword: copy.unlockPassword)
            guard let data = copy.document.dataRepresentation(options: options) else {
              throw RuntimeError.error(withMessage: "The document could not be written")
            }
            try data.write(to: destination, options: .atomic)
          } else {
            // Nothing changed: the file as it is, its own protection included.
            try Self.copyReplacing(url, to: destination)
          }
          promise.resolve(withResult: PdfExportResult(filePath: destination.path))
        } catch {
          promise.reject(withError: error)
        }
      }
    }
    return promise
  }

  /// The file `filePath` names. A directory is rejected: replacing it would
  /// delete everything in it.
  private static func exportFile(at filePath: String) throws -> URL {
    var isDirectory: ObjCBool = false
    if filePath.hasSuffix("/")
      || (FileManager.default.fileExists(atPath: filePath, isDirectory: &isDirectory) && isDirectory.boolValue) {
      throw RuntimeError.error(withMessage: "The export path is a directory, not a file: \(filePath)")
    }
    return URL(fileURLWithPath: filePath, isDirectory: false)
  }

  /// Copies `source` over `destination` in one step, as Android does: the
  /// copy is written beside it and renamed over it, so a failed copy leaves
  /// an existing file as it was, and rename(2) never replaces a directory.
  /// The copy's name does not repeat the target's, which may already be
  /// close to the 255-byte limit.
  private static func copyReplacing(_ source: URL, to destination: URL) throws {
    let partial = destination.deletingLastPathComponent()
      .appendingPathComponent(".\(UUID().uuidString).partial")
    defer { try? FileManager.default.removeItem(at: partial) }
    try FileManager.default.copyItem(at: source, to: partial)
    if rename(partial.path, destination.path) != 0 {
      throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
    }
  }

  /// A new file in the caches directory with the document's file name.
  private static func newExportFile(named source: URL) throws -> URL {
    let directory = try FileManager.default
      .url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
      .appendingPathComponent("BetterPdfExports", isDirectory: true)
      .appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let name = source.deletingPathExtension().lastPathComponent
    return directory.appendingPathComponent(name.isEmpty ? "document" : name).appendingPathExtension("pdf")
  }

  /// The displayed document's form, read once per document.
  private func form() -> DocumentForm? {
    guard let document = pdfView?.document else { return nil }
    if let documentForm { return documentForm }
    let form = DocumentForm(document: document)
    documentForm = form
    return form
  }

  /// The form's values once it was filled in, to copy to separate
  /// instances of the file (exports, page images, printing); nil while it
  /// is as loaded. Main thread.
  private func filledFormSnapshot(of document: PDFDocument) -> FormSnapshot? {
    guard let document = document as? ColorSchemeDocument, document.formChangeCount > 0, let form = form() else { return nil }
    return FormSnapshot(form: form)
  }

  /// A separate instance of the displayed file, unlocked and filled in like
  /// the displayed document; for the calling (background) thread only.
  private static func openCopy(
    of url: URL,
    password: String?,
    filledIn form: FormSnapshot?
  ) -> (document: PDFDocument, unlockPassword: String?)? {
    guard let copy = PDFDocument(url: url) else { return nil }
    let unlockPassword = copy.isLocked ? password ?? "" : nil
    if let unlockPassword { _ = copy.unlock(withPassword: unlockPassword) }
    guard !copy.isLocked else { return nil }
    form?.apply(to: copy)
    return (copy, unlockPassword)
  }

  private func reportFormChange(_ widget: FormWidgetAnnotation) {
    guard formInteraction?.isActive == true else { return }
    (pdfView?.document as? ColorSchemeDocument)?.noteFormChanged()
    guard onFormChange != nil, let name = widget.fieldName else { return }
    // After PDFKit's setter returns, with the whole radio group updated.
    DispatchQueue.main.async { [weak self] in
      guard let self, let onFormChange = self.onFormChange, let pdfView = self.pdfView,
            let document = pdfView.document, let form = self.form(),
            let field = NativePdfFormField(
              name: name, widgets: form.widgets(named: name), document: document, box: pdfView.displayBox
            ) else { return }
      onFormChange(NativePdfFormChangeEvent(field: field))
    }
  }

  private func applyFormFilling() {
    let isEnabled = enableFormFilling ?? true
    (pdfView?.document as? ColorSchemeDocument)?.isFormFillingEnabled = isEnabled
    if !isEnabled { container.endEditing(true) }
  }

  /// Whether this view's print dialog is open (main thread).
  private var isPrinting = false

  /// `item`: a file URL or PDF data. Main thread.
  private func presentPrintDialog(printing item: Any, jobName: String, promise: Promise<PdfPrintResult>) {
    let controller = UIPrintInteractionController.shared
    let info = UIPrintInfo(dictionary: nil)
    info.jobName = jobName
    info.outputType = .general
    controller.printInfo = info
    controller.printingItem = item
    // For a locked document `present` returns false and the completion still runs later.
    var isSettled = false
    let completion: UIPrintInteractionController.CompletionHandler = { [weak self] _, completed, error in
      guard !isSettled else { return }
      isSettled = true
      self?.isPrinting = false
      if let error {
        promise.reject(withError: error)
      } else {
        promise.resolve(withResult: PdfPrintResult(isSubmitted: completed))
      }
    }
    // iPad shows the dialog as a popover, which needs an anchor.
    let presented = UIDevice.current.userInterfaceIdiom == .pad
      ? controller.present(from: container.bounds, in: container, animated: true, completionHandler: completion)
      : controller.present(animated: true, completionHandler: completion)
    if !presented, !isSettled {
      isSettled = true
      isPrinting = false
      promise.reject(withError: RuntimeError.error(withMessage: "The print dialog could not be opened (another one may be open)"))
    }
  }

  func zoomTo(scale: Double, animated: Bool) throws {
    DispatchQueue.main.async { [weak self] in
      guard let self, let pdfView = self.pdfView, self.fitScale > 0 else { return }
      let target = self.fitScale * CGFloat(self.clampScale(scale))
      if animated {
        UIView.animate(withDuration: 0.25) { pdfView.scaleFactor = target }
      } else {
        pdfView.scaleFactor = target
      }
    }
  }

  func getStats() throws -> PdfViewStats {
    let pageCount = statsLock.withLock { statsPageCount }
    // PDFKit manages page views and tiles internally and does not expose
    // cache metrics; -1 marks "not tracked".
    return PdfViewStats(
      pageCount: Double(pageCount),
      residentPages: -1,
      cacheBytes: -1,
      cacheBudgetBytes: -1,
      cacheHits: -1,
      cacheMisses: -1,
      bitmapAllocations: -1,
      pagesRendered: -1,
      rendersCancelled: -1,
      meanRenderMs: -1
    )
  }

  // MARK: Loading

  private func reload() {
    loadTask?.cancel()
    loadTask = nil
    pdfView?.document = nil
    // The search and the form would keep the old document while the next
    // one loads, or for the life of the view if it fails to load.
    searchController.clear()
    documentForm = nil
    layoutOverlays()
    releaseDisplayedPin()
    container.resetSelection()
    fitScale = 0
    lastReportedPage = -1
    readingPage = -1
    setStatsPageCount(0)
    guard let source else { return }

    loadTask = PdfDocumentLoader.load(
      source: source,
      password: password,
      progress: { [weak self] loadedBytes, totalBytes in
        self?.onLoadProgress?(PdfLoadProgressEvent(
          loadedBytes: Double(loadedBytes),
          totalBytes: totalBytes.map(Double.init)
        ))
      }
    ) { [weak self] result in
      guard let self else { return }
      self.loadTask = nil
      switch result {
      case .success(let loaded):
        self.present(loaded)
      case .failure(let failure):
        self.onError?(failure.toPdfError())
      }
    }
  }

  private func releaseDisplayedPin() {
    if let displayedPin { DocumentCache.shared.store.unpin(displayedPin) }
    displayedPin = nil
  }

  private func present(_ loaded: LoadedDocument) {
    let document = loaded.document
    displayedPin = loaded.pin
    searchController.clear()
    lastReportedScale = nil
    lastReportedViewport = nil
    outlineCache = nil
    pageSizesCache = nil
    documentForm = nil
    guard let pdfView else { return }
    let pageCount = document.pageCount
    let position = initialPosition
    let requestedPage = position?.page.pageIndex ?? 0
    pendingInitialPage = min(max(requestedPage, 0), pageCount - 1)
    pendingInitialScale = position.flatMap { $0.scale.isFinite && $0.scale > 0 ? $0.scale : nil }
    pendingInitialPoint = nil
    if let x = position?.x, let y = position?.y, x.isFinite, y.isFinite, pendingInitialPage == requestedPage {
      pendingInitialPoint = CGPoint(x: x, y: y)
    }
    if let document = document as? ColorSchemeDocument {
      document.isDark = colorScheme == .some(.dark)
      document.isFormFillingEnabled = enableFormFilling ?? true
      document.widgetChanged = { [weak self] widget in self?.reportFormChange(widget) }
    }
    pdfView.document = document
    setStatsPageCount(pageCount)
    // A new document starts at initialPage, not at the previous one's page.
    applyLayoutMode(keepingCurrentPage: false)
    container.applyDoubleTapZoom()
    container.applyTextSelection()

    let firstPage = document.page(at: 0)?.displaySize ?? .zero
    onLoad?(PdfLoadEvent(
      pageCount: Double(pageCount),
      firstPageWidth: Double(firstPage.width),
      firstPageHeight: Double(firstPage.height),
      filePath: loaded.filePath,
      isStale: loaded.isStale
    ))
    layoutDidChange()
  }

  // MARK: Layout & scale

  private func layoutDidChange() {
    let wasRelayingOut = isRelayingOut
    isRelayingOut = true
    defer {
      isRelayingOut = wasRelayingOut
      if !wasRelayingOut { reportCurrentPage() }
    }
    guard let pdfView, pdfView.document != nil, container.bounds.width > 0 else { return }
    // PDFKit does not keep the page across every resize (measured: a
    // horizontal layout returns to page 1), so the reader's page is restored.
    let isResize = lastLayoutSize != .zero && container.bounds.size != lastLayoutSize
    lastLayoutSize = container.bounds.size
    let pageToKeep = isResize && pendingInitialPage == nil ? readingPage : -1
    let newFit = currentFitScale(pdfView)
    if newFit > 0, abs(newFit - fitScale) > 0.0001 {
      applyScaleLimits(preservingRelativeScale: fitScale > 0)
    }
    if let page = pendingInitialPage, fitScale > 0 {
      pendingInitialPage = nil
      let point = pendingInitialPoint
      pendingInitialPoint = nil
      pdfView.layoutIfNeeded()
      if let point, !isPagedLayout, !isSinglePage, let scrollView, let pdfPage = pdfView.document?.page(at: page) {
        pdfView.scroll(scrollView, topLeftTo: point, on: pdfPage, topClearance: clearance.top)
      } else {
        scrollToPage(page, animated: false, centeredHorizontally: true)
      }
      keepReadingPage(page)
      reportCurrentPage()
    }
    // With facing pages the reported page can be another row's (see
    // readingPage): the kept page is lost only when it left the screen.
    let isPageLost = isSpreadLayout ? !isPageVisible(pageToKeep) : currentPageIndex() != pageToKeep
    if pageToKeep >= 0, isPageLost, let page = pdfView.document?.page(at: pageToKeep) {
      pdfView.layoutIfNeeded()
      // PDFKit shows a page of facing pages anywhere on screen; put its
      // spread at the top, like a layout switch.
      if isSpreadLayout {
        scrollToPage(pageToKeep, animated: false)
      } else {
        goToTop(of: page)
      }
    }
    applyScrollInsets()
    layoutOverlays()
    // The first layout and rotations move the viewport without scrolling.
    settle.noteMotion()
  }

  /// `goToViewport`: continuous layouts put the page point at the top-left
  /// corner; paged and single-page layouts (one page view per page, no
  /// scroll view spanning the document) go to the page at the zoom.
  private func applyViewport(page index: Int, point: CGPoint, scale: Double) {
    guard let pdfView, let document = pdfView.document, document.pageCount > 0, fitScale > 0 else { return }
    let clamped = min(max(index, 0), document.pageCount - 1)
    guard let page = document.page(at: clamped) else { return }
    keepReadingPage(clamped)
    if isPagedLayout || isSinglePage {
      // Same placement as goToPage; the zoom is relative to the page view
      // controller's own fit (see relativeScale), applied only if it differs.
      let fit = isPagedLayout ? (pagedFitScale ?? pdfView.scaleFactor) : fitScale
      scrollToPage(clamped, animated: false)
      let target = fit * CGFloat(clampScale(scale))
      if abs(pdfView.scaleFactor - target) > 0.0001 { pdfView.scaleFactor = target }
      settle.noteMotion()
      return
    }
    pdfView.scaleFactor = fitScale * CGFloat(clampScale(scale))
    pdfView.layoutIfNeeded()
    if let scrollView {
      // A page outside the document is shown from its top.
      pdfView.scroll(scrollView, topLeftTo: clamped == index ? point : .zero, on: page, topClearance: clearance.top)
    }
    settle.noteMotion()
  }

  /// Scale props are relative to the fitted page; PDFKit's are absolute. Re-derive
  /// the absolute limits whenever the fit scale or the props change, keeping
  /// the user's relative zoom across rotations.
  private func applyScaleLimits(preservingRelativeScale: Bool) {
    guard let pdfView, pdfView.document != nil else { return }
    let newFit = currentFitScale(pdfView)
    guard newFit > 0 else { return }
    let relative = preservingRelativeScale && fitScale > 0
      ? Double(pdfView.scaleFactor / fitScale)
      : pendingInitialScale ?? resolvedMinScale
    pendingInitialScale = nil
    fitScale = newFit
    // Widen first so the new range never momentarily excludes the current scale.
    pdfView.minScaleFactor = 0.01
    pdfView.maxScaleFactor = 1000
    pdfView.scaleFactor = newFit * CGFloat(clampScale(relative))
    pdfView.minScaleFactor = newFit * CGFloat(resolvedMinScale)
    pdfView.maxScaleFactor = newFit * CGFloat(resolvedMaxScale)
    if lastReportedScale == nil { lastReportedScale = relativeScale(pdfView) }
  }

  /// The viewport has settled when it did not move for `settleDelay` and no
  /// pinch, drag, deceleration or zoom bounce is in progress — the same rule
  /// as Android's settle.
  private func reportIfSettled() {
    // Also wait out page turns: during one, the page view controller updates
    // `currentPage` and `scaleFactor` at different times.
    if let scrollView,
       scrollView.isZooming || scrollView.isZoomBouncing || scrollView.isTracking
        || scrollView.isDragging || scrollView.isDecelerating {
      settle.noteMotion()
      return
    }
    reportScale()
    reportViewport()
    // Page turns and resizes don't move the observed scroll view.
    searchController.viewportDidSettle()
  }

  private func reportScale() {
    guard let pdfView, fitScale > 0, let last = lastReportedScale else { return }
    guard let scale = relativeScale(pdfView) else { return }
    guard abs(scale - last) > 0.001 else { return }
    lastReportedScale = scale
    onScaleChange?(PdfScaleChangeEvent(scale: scale))
  }

  /// The zoom relative to the fitted page. PDFKit's page view controller
  /// fits every page itself, with insets that differ from ours (measured: a
  /// 226×3000 pt page at 0.1939 vs 0.1918 computed), so in paged layouts the
  /// scale PDFKit shows when a page arrives is that page's 1.0. A page change
  /// alone is then not a zoom change.
  private func relativeScale(_ pdfView: PDFView) -> Double? {
    let fit: CGFloat
    if isPagedLayout {
      if pagedFitScale == nil { pagedFitScale = pdfView.scaleFactor }
      fit = pagedFitScale ?? 0
    } else {
      fit = fitScale
    }
    guard fit > 0 else { return nil }
    return Double(pdfView.scaleFactor / fit)
  }

  private func reportViewport() {
    guard let pdfView, let document = pdfView.document, fitScale > 0,
          let scale = relativeScale(pdfView), let anchor = pdfView.topLeftAnchor(topClearance: clearance.top) else { return }
    let page = document.index(for: anchor.page)
    let visible = pdfView.visiblePages.map { document.index(for: $0) }.filter { $0 != NSNotFound }
    guard page != NSNotFound, let first = visible.min(), let last = visible.max() else { return }
    let isAtEnd = isPagedLayout || isSinglePage
      ? last == document.pageCount - 1 && pdfView.isDocumentEndVisible()
      : pdfView.isDocumentEndVisible(topClearance: clearance.top, bottomClearance: clearance.bottom)
    let event = PdfViewportChangeEvent(
      viewport: PdfViewport(page: Double(page), x: Double(anchor.point.x), y: Double(anchor.point.y), scale: scale),
      firstVisiblePage: Double(first),
      lastVisiblePage: Double(last),
      isAtEnd: isAtEnd
    )
    if let lastReportedViewport, lastReportedViewport.isClose(to: event) { return }
    lastReportedViewport = event
    onViewportChange?(event)
  }

  private static let settleDelay: CFTimeInterval = 0.1

  /// PDFKit applies one scale to every page. Fit width in a vertical layout
  /// uses PDFKit's own size-to-fit, which fits the widest page; the other
  /// fits are computed from the first page.
  private func currentFitScale(_ pdfView: PDFView) -> CGFloat {
    let horizontal = isHorizontalLayout
    // Default: fit width when scrolling vertically through pages, else whole pages.
    let policy = fitPolicy ?? (horizontal || isPagedLayout || isSinglePage ? .page : .width)
    if policy == .width && !horizontal {
      return pdfView.scaleFactorForSizeToFit
    }
    return pdfView.scaleFactorToFit(policy, columns: isSpreadLayout ? 2 : 1, clearance: clearance.top + clearance.bottom)
  }

  private var resolvedMinScale: Double {
    let value = minScale ?? 1
    return value.isFinite && value > 0 ? value : 1
  }

  private var resolvedMaxScale: Double {
    let value = maxScale ?? 4
    return max(value.isFinite ? value : 4, resolvedMinScale)
  }

  private func clampScale(_ scale: Double) -> Double {
    guard scale.isFinite else { return resolvedMinScale }
    return min(max(scale, resolvedMinScale), resolvedMaxScale)
  }

  private var resolvedPageSpacing: CGFloat { Self.resolvedSpacing(pageSpacing) }

  private static func resolvedSpacing(_ spacing: Double?) -> CGFloat {
    let value = spacing ?? 8
    return CGFloat(value.isFinite && value >= 0 ? value : 8)
  }

  private func applyPageSpacing() {
    guard let pdfView else { return }
    let spacing = resolvedPageSpacing
    pdfView.pageBreakMargins = UIEdgeInsets(top: spacing / 2, left: 0, bottom: spacing / 2, right: 0)
    pdfView.pageShadowsEnabled = spacing > 0
  }

  // MARK: Navigation & events

  /// `centeredHorizontally`: a page wider than the view (zoomed in) starts
  /// centred instead of at its left edge — as on Android for the initial page.
  private func scrollToPage(_ index: Int, animated: Bool, centeredHorizontally: Bool = false) {
    guard let pdfView, let document = pdfView.document, document.pageCount > 0 else { return }
    let clamped = min(max(index, 0), document.pageCount - 1)
    guard let page = document.page(at: clamped) else { return }
    let bounds = page.bounds(for: pdfView.displayBox)
    // Top-left of the view in page space (PDF y axis points up).
    var x = bounds.minX
    if centeredHorizontally, pdfView.scaleFactor > 0 {
      let visibleWidth = pdfView.bounds.width / pdfView.scaleFactor
      x = max(bounds.minX, bounds.midX - visibleWidth / 2)
    }
    let destination = PDFDestination(page: page, at: CGPoint(x: x, y: bounds.maxY))
    if animated {
      UIView.animate(withDuration: 0.3) {
        pdfView.go(to: destination)
        self.alignTopBelowClearance(page)
      }
    } else {
      pdfView.go(to: destination)
      alignTopBelowClearance(page)
    }
  }

  /// `go(to:)` for a page the reader keeps: its top below the clearance.
  private func goToTop(of page: PDFPage) {
    pdfView?.go(to: page)
    alignTopBelowClearance(page)
  }

  /// PDFKit's `go(to:)` ignores `contentInset`: it puts a page at the view's
  /// top, under a top inset, and with a bottom inset it can scroll past the
  /// page's top (measured: the last page under a 400 pt toolbar landed
  /// 389 pt below its top, near the document's end).
  private func alignTopBelowClearance(_ page: PDFPage) {
    guard let pdfView, let scrollView, clearance.top > 0 || clearance.bottom > 0 else { return }
    pdfView.alignTop(of: page, in: scrollView, topClearance: clearance.top)
  }

  /// `contentInset`, in vertical continuous layouts only: the space pages
  /// scroll under, which positions, the current page and the end refer
  /// past.
  private var clearance: (top: CGFloat, bottom: CGFloat) {
    guard !isHorizontalLayout, !isPagedLayout, !isSinglePage, let contentInset else { return (0, 0) }
    return (CGFloat(max(contentInset.top, 0)), CGFloat(max(contentInset.bottom, 0)))
  }

  /// The page under the viewport's vertical center, or the last page once
  /// scrolled to the end — the same rule as Android. PDFKit's own
  /// `currentPage` never reaches a last page shorter than the viewport.
  private var isSinglePage: Bool { singlePage ?? false }

  /// `singlePage` takes precedence over direction and paging.
  private var isHorizontalLayout: Bool { (horizontal ?? false) && !isSinglePage }

  private var isPagedLayout: Bool { (enablePaging ?? false) && !isSinglePage }

  private var isRightToLeftLayout: Bool { (enableRTL ?? false) && isHorizontalLayout }

  /// Moves the `PdfPageOverlay`s onto their pages for the current position.
  private func layoutOverlays() {
    guard let pdfView else { return }
    pageOverlays.layout(pdfView: pdfView, container: container)
  }

  /// After the props update: the overlay layer is a sibling React mounts
  /// in the same transaction, after this view.
  private func layoutOverlaysSoon() {
    DispatchQueue.main.async { [weak self] in self?.layoutOverlays() }
  }

  /// Draws the displayed document's pages dark or in their own colours.
  /// PDFKit keeps the tiles it drew and redraws none on request (measured:
  /// `setNeedsDisplay` on its layers and `annotationsChanged(on:)` leave
  /// them), but a display box change makes it lay out and draw every page
  /// again: switching away and back ends in the same layout, so the exact
  /// scroll position is put back (an anchor point could land a fraction
  /// off the end of the document and lose "at the end").
  private func applyColorScheme() {
    guard let pdfView, let document = pdfView.document as? ColorSchemeDocument else { return }
    let isDark = colorScheme == .some(.dark)
    guard document.isDark != isDark else { return }
    document.isDark = isDark
    let offset = isPagedLayout || isSinglePage ? nil : scrollView?.contentOffset
    let wasRelayingOut = isRelayingOut
    isRelayingOut = true
    defer {
      isRelayingOut = wasRelayingOut
      if !wasRelayingOut { reportCurrentPage() }
    }
    let box = pdfView.displayBox
    pdfView.displayBox = box == .cropBox ? .mediaBox : .cropBox
    pdfView.displayBox = box
    if let offset, let scrollView {
      pdfView.layoutIfNeeded()
      scrollView.setContentOffset(offset, animated: false)
    }
  }

  /// Facing pages side by side: `spreadMode` in the vertical continuous
  /// layout only. PDFKit lays out two-up pages only there (measured: its
  /// page view controller shows single pages, and horizontal two-up
  /// overlaps them).
  private var isSpreadLayout: Bool {
    guard !isHorizontalLayout, !isPagedLayout, !isSinglePage else { return false }
    switch spreadMode {
    case .some(.pairs), .some(.book): return true
    default: return false
    }
  }

  private func isPageVisible(_ index: Int) -> Bool {
    guard index >= 0, let pdfView, let page = pdfView.document?.page(at: index) else { return false }
    return pdfView.visiblePages.contains(page)
  }

  /// The first page of the spread showing page `index` (itself without spreads).
  private func spreadStart(of index: Int) -> Int {
    guard isSpreadLayout else { return index }
    if spreadMode == .some(.book) { return index == 0 ? 0 : index - (index - 1) % 2 }
    return index - index % 2
  }

  /// Scrolled to the far end where the last page is, even when it is too
  /// short to reach the centre. PDFKit mirrors RTL content without mirroring
  /// the scroll offset, so RTL checks the last page's on-screen frame.
  private func isLastPageEdgeReached(_ scrollView: UIScrollView) -> Bool {
    if isRightToLeftLayout {
      guard let pdfView, let document = pdfView.document, document.pageCount > 1,
            let lastPage = document.page(at: document.pageCount - 1) else { return false }
      return pdfView.isPageFullyVisible(lastPage)
    }
    return scrollView.isScrolledToEnd(horizontal: isHorizontalLayout, bottomClearance: clearance.bottom)
  }

  /// `pageAccessibilityLabel` with `{page}` (1-based) and `{pageCount}` filled in.
  private func accessibilityLabel(forPage index: Int, of pageCount: Int) -> String {
    (pageAccessibilityLabel ?? "Page {page} of {pageCount}")
      .replacingOccurrences(of: "{page}", with: String(index + 1))
      .replacingOccurrences(of: "{pageCount}", with: String(pageCount))
  }

  /// The page under the viewport's centre, or the last page once scrolled
  /// to the end (see `reportCurrentPage`); nil without a document.
  private func currentPageIndex() -> Int? {
    guard let pdfView, let document = pdfView.document, document.pageCount > 0 else { return nil }
    let index: Int
    if isPagedLayout || isSinglePage, let page = pdfView.currentPage {
      index = document.index(for: page)
    } else if let scrollView, isLastPageEdgeReached(scrollView) {
      index = document.pageCount - 1
    } else {
      // The centre of the area between the clearances.
      let clearance = self.clearance
      let center = CGPoint(x: pdfView.bounds.midX, y: (pdfView.bounds.minY + clearance.top + pdfView.bounds.maxY - clearance.bottom) / 2)
      guard let page = pdfView.page(for: center, nearest: true) else { return nil }
      // Facing pages meet at the centre: report the spread's first page.
      index = spreadStart(of: document.index(for: page))
    }
    return index == NSNotFound ? nil : index
  }

  private func reportCurrentPage() {
    guard !isRelayingOut, let document = pdfView?.document, let index = currentPageIndex() else { return }
    if !(isSpreadLayout && isPageVisible(readingPage)) { readingPage = index }
    guard index != lastReportedPage else { return }
    lastReportedPage = index
    onPageChange?(PdfPageChangeEvent(page: Double(index), pageCount: Double(document.pageCount)))
    // PDFKit exposes the page text to VoiceOver but never says which page
    // is showing (its scroll bar counts screens, not pages).
    if UIAccessibility.isVoiceOverRunning {
      UIAccessibility.post(notification: .pageScrolled, argument: accessibilityLabel(forPage: index, of: document.pageCount))
    }
  }

  /// PDFView scrolls with an internal UIScrollView; there is no public
  /// scroll callback, so observe its offset. Runs natively per frame and only
  /// crosses into JS when the page actually changes.
  private func observeScrollOffset() {
    scrollView = pdfView?.firstDescendantScrollView()
    applyScrollIndicators()
    applyScrollEnabled()
    applyContentInsetAdjustment()
    offsetObservation = scrollView?.observe(\.contentOffset, options: []) { [weak self] _, _ in
      self?.layoutOverlays()
      self?.reportCurrentPage()
      self?.searchController.viewportDidChange()
      // Every scroll frame, including animated zooms (PDFKit's double tap
      // posts PDFViewScaleChanged only when it starts).
      self?.settle.noteMotion()
    }
    contentSizeObservation = scrollView?.observe(\.contentSize, options: []) { [weak self] _, _ in
      self?.applyScrollInsets()
    }
    applyScrollInsets()
    observeOverlayScrollViews()
  }

  private func observeOverlayScrollViews() {
    overlayScrollObservations = []
    guard !pageOverlays.placements.isEmpty, let pdfView else { return }
    for other in pdfView.descendantScrollViews() where other !== scrollView {
      overlayScrollObservations.append(other.observe(\.contentOffset, options: []) { [weak self] _, _ in
        self?.layoutOverlays()
      })
      overlayScrollObservations.append(other.observe(\.contentSize, options: []) { [weak self] _, _ in
        self?.layoutOverlays()
      })
    }
  }

  /// No automatic safe-area insets, as on Android and in React Native's
  /// ScrollView: PDFKit's page navigation ignores insets, so a view under
  /// the status bar could show the first page partly behind it. Covers the page
  /// view controller's scroll views like `applyScrollEnabled`.
  private func applyContentInsetAdjustment() {
    pdfView?.descendantScrollViews().forEach { $0.contentInsetAdjustmentBehavior = .never }
  }

  /// PDFKit centres a document shorter than the view; vertical continuous
  /// layouts start at the top instead, as on Android. PDFKit centres within
  /// the content insets, so a bottom inset as tall as the free space leaves
  /// nothing to centre. Horizontal layouts keep pages centred across and a
  /// single page stays centred, as on Android. Paged layouts (the page view
  /// controller's scroll view, not PDFKit's) centre each page in its slot.
  /// The only writer of the scroll view's insets: the app's clearance
  /// (`contentInset`), and below it the free height of a short document,
  /// which keeps it at the top — PDFKit centres a document shorter than its
  /// view within the content insets (measured), so a bottom inset as tall
  /// as the free space leaves nothing to centre.
  private func applyScrollInsets() {
    guard let scrollView, !isPagedLayout else { return }
    let isVerticalContinuous = !isHorizontalLayout && !isSinglePage
    let clearance = self.clearance
    let freeHeight = isVerticalContinuous
      ? max(0, scrollView.bounds.height - clearance.top - clearance.bottom - scrollView.contentSize.height)
      : 0
    let insets = UIEdgeInsets(top: clearance.top, left: 0, bottom: clearance.bottom + freeHeight, right: 0)
    if abs(scrollView.contentInset.top - insets.top) > 0.5 || abs(scrollView.contentInset.bottom - insets.bottom) > 0.5 {
      scrollView.contentInset = insets
    }
    let indicators = UIEdgeInsets(top: clearance.top, left: 0, bottom: clearance.bottom, right: 0)
    if scrollView.verticalScrollIndicatorInsets != indicators {
      scrollView.verticalScrollIndicatorInsets = indicators
    }
    // A document that fits has one valid offset, its top. Going to a page
    // while PDFKit still centred the document left the offset below it,
    // cutting the page off once the document moved up.
    let top = -scrollView.adjustedContentInset.top
    if freeHeight > 0, abs(scrollView.contentOffset.y - top) > 0.5 {
      scrollView.contentOffset.y = top
    }
  }

  /// Direction and paging. PDFKit replaces its internal scroll view when
  /// either changes, so observation and the fit scale are re-derived.
  /// Paging uses PDFKit's page view controller, which pages along
  /// `displayDirection`.
  private func applyLayoutMode(keepingCurrentPage: Bool = true) {
    guard let pdfView, pdfView.document != nil else { return }
    let wasRelayingOut = isRelayingOut
    isRelayingOut = true
    defer {
      isRelayingOut = wasRelayingOut
      if !wasRelayingOut { reportCurrentPage() }
    }
    // The page the app was told about (centre / end rule), not PDFKit's
    // top-of-viewport `currentPage`: they differ on a short last page.
    let currentPage = readingPage >= 0 ? pdfView.document?.page(at: readingPage) : pdfView.currentPage
    let paged = isPagedLayout
    // A single page is static, like react-native-pdf's: PDFKit shows one page
    // and touches go to the React Native parents (e.g. a Pressable thumbnail).
    pdfView.displayMode = isSinglePage ? .singlePage : (isSpreadLayout ? .twoUpContinuous : .singlePageContinuous)
    pdfView.displaysAsBook = isSpreadLayout && spreadMode == .some(.book)
    container.isUserInteractionEnabled = !isSinglePage
    pdfView.displayDirection = isHorizontalLayout ? .horizontal : .vertical
    pdfView.displaysRTL = isRightToLeftLayout
    pdfView.usePageViewController(paged, withViewOptions: paged ? [UIPageViewController.OptionsKey.interPageSpacing: resolvedPageSpacing] : nil)
    container.applyDoubleTapZoom()
    container.applyTextSelection()
    if let pageChangeObserver { NotificationCenter.default.removeObserver(pageChangeObserver) }
    pageChangeObserver = nil
    if paged || isSinglePage {
      // No single scroll view spans the document; PDFKit's current page is exact.
      pageChangeObserver = NotificationCenter.default.addObserver(
        forName: .PDFViewPageChanged, object: pdfView, queue: .main
      ) { [weak self] _ in
        self?.observeOverlayScrollViews()
        self?.layoutOverlays()
        self?.reportCurrentPage()
        // The relative zoom is measured against the page that just arrived.
        self?.pagedFitScale = nil
        self?.settle.noteMotion()
      }
    }
    observeScrollOffset()
    fitScale = 0
    pagedFitScale = nil
    layoutDidChange()
    // Switching modes keeps the reader on the same page.
    if keepingCurrentPage, pendingInitialPage == nil, let currentPage {
      // PDFKit shows a page of facing pages anywhere on screen; put its
      // spread at the top, like goToPage and Android.
      if isSpreadLayout, let document = pdfView.document {
        scrollToPage(document.index(for: currentPage), animated: false)
      } else {
        goToTop(of: currentPage)
      }
    }
  }

  /// Panning only: UIScrollView keeps pinch-zooming with scrolling disabled,
  /// and a disabled pan lets an enclosing scroll view take the drag. Covers
  /// the page view controller's scroll views too; PDFKit replaces them when
  /// the layout mode changes, so this is re-applied then.
  private func applyScrollEnabled() {
    let enabled = scrollEnabled ?? true
    pdfView?.descendantScrollViews().forEach { $0.isScrollEnabled = enabled }
  }

  /// PDFView's scroll view exists once a document is set; also applied then.
  private func applyScrollIndicators() {
    scrollView?.showsVerticalScrollIndicator = showsVerticalScrollIndicator ?? true
    scrollView?.showsHorizontalScrollIndicator = showsHorizontalScrollIndicator ?? true
  }

  private func reportPageTap(page: PDFPage, point: CGPoint) {
    guard let onPageTap, let document = pdfView?.document else { return }
    let index = document.index(for: page)
    guard index != NSNotFound else { return }
    onPageTap(PdfPageTapEvent(page: Double(index), x: Double(point.x), y: Double(point.y)))
  }

  private func setStatsPageCount(_ count: Int) {
    statsLock.withLock { statsPageCount = count }
  }
}

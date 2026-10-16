import Foundation
import NitroModules
import PDFKit

/// iOS implementation of `BetterPdfDocument`: a document opened without a
/// view.
///
/// Opens like the view (`PdfDocumentLoader`) and keeps the `PDFDocument`,
/// confined to `queue`: PDFKit objects are not thread-safe, and nothing
/// else uses this one, so reads need not re-open the file as the view's do.
/// Methods arrive on the JS thread and return at once.
final class HybridBetterPdfDocument: HybridBetterPdfDocumentSpec {
  /// Runs every read, one at a time, in order.
  private let queue = DispatchQueue(label: "com.betterpdf.document", qos: .userInitiated)

  private let lock = NSLock()
  private var openCalled = false
  private var closed = false
  private var loadTask: PdfLoadTask?
  private var loaded: LoadedDocument?

  // Results of the reads (queue only).
  private var metadataCache: PdfDocumentMetadata?
  private var pageSizesCache: [PdfPageSize]?
  private var outlineCache: [PdfOutlineEntry]?

  /// Nominal: Nitro reads it when the object reaches JS, before `open()`.
  var memorySize: Int { return 1 << 20 }

  func open(
    source: NativePdfSource,
    password: String?,
    onProgress: ((PdfLoadProgressEvent) -> Void)?
  ) throws -> Promise<PdfOpenResult> {
    let promise = Promise<PdfOpenResult>()
    let shouldOpen = try lock.withLock { () throws -> Bool in
      guard !openCalled else { throw RuntimeError.error(withMessage: "open() was already called") }
      openCalled = true
      // After close() the promise never resolves.
      return !closed
    }
    guard shouldOpen else { return promise }
    let task = PdfDocumentLoader.load(
      source: source,
      password: password,
      isDisplayed: false,
      progress: { loadedBytes, totalBytes in
        onProgress?(PdfLoadProgressEvent(loadedBytes: Double(loadedBytes), totalBytes: totalBytes.map(Double.init)))
      }
    ) { [weak self] result in
      switch result {
      case .success(let loaded):
        // Read before the document is shared with the queue.
        let opened = PdfOpenedDocument(
          pageCount: Double(loaded.document.pageCount),
          filePath: loaded.filePath,
          isStale: loaded.isStale
        )
        guard let self, self.accept(loaded) else {
          if let pin = loaded.pin { DocumentCache.shared.store.unpin(pin) }
          return
        }
        promise.resolve(withResult: PdfOpenResult(document: opened, error: nil))
      case .failure(let failure):
        guard let self, !self.isClosed else { return }
        promise.resolve(withResult: PdfOpenResult(document: nil, error: failure.toPdfError()))
      }
    }
    let cancelNow = lock.withLock { () -> Bool in
      if closed { return true }
      if loaded == nil { loadTask = task }
      return false
    }
    if cancelNow { task.cancel() }
    return promise
  }

  /// Keeps `loaded` unless closed meanwhile.
  private func accept(_ loaded: LoadedDocument) -> Bool {
    return lock.withLock {
      guard !closed else { return false }
      self.loaded = loaded
      loadTask = nil
      return true
    }
  }

  private var isClosed: Bool { lock.withLock { closed } }

  func getMetadata() throws -> Promise<PdfDocumentMetadata> {
    return read { document in
      if let cached = self.metadataCache { return cached }
      let metadata = document.metadata()
      self.metadataCache = metadata
      return metadata
    }
  }

  func getPageSizes() throws -> Promise<[PdfPageSize]> {
    return read { document in
      if let cached = self.pageSizesCache { return cached }
      let sizes = document.pageSizes()
      self.pageSizesCache = sizes
      return sizes
    }
  }

  func getOutline() throws -> Promise<[PdfOutlineEntry]> {
    return read { document in
      if let cached = self.outlineCache { return cached }
      let entries = document.outlineEntries()
      self.outlineCache = entries
      return entries
    }
  }

  func renderPage(page: Double, options: PdfRenderPageOptions) throws -> Promise<PdfPageImage> {
    guard let pageCount = lock.withLock({ loaded?.document.pageCount }) else {
      return Promise.rejected(withError: notOpen())
    }
    let request: PageImageRequest
    do {
      request = try PageImageRequest(page: page, pageCount: pageCount, options: options)
    } catch {
      return Promise.rejected(withError: error)
    }
    return read { document in try request.render(from: document) }
  }

  func close() throws {
    release()
  }

  func dispose() {
    release()
  }

  deinit {
    release()
  }

  /// Cancels an open in progress and lets go of the document; reads in
  /// progress keep it until they finish. The pin of a downloaded copy is
  /// released now (PDFKit keeps the file open).
  private func release() {
    let (task, loaded) = lock.withLock { () -> (PdfLoadTask?, LoadedDocument?) in
      guard !closed else { return (nil, nil) }
      closed = true
      defer {
        loadTask = nil
        self.loaded = nil
      }
      return (loadTask, self.loaded)
    }
    task?.cancel()
    if let pin = loaded?.pin { DocumentCache.shared.store.unpin(pin) }
  }

  private func read<T>(_ body: @escaping (PDFDocument) throws -> T) -> Promise<T> {
    let promise = Promise<T>()
    guard lock.withLock({ loaded != nil }) else {
      promise.reject(withError: notOpen())
      return promise
    }
    queue.async {
      guard let document = self.lock.withLock({ self.loaded?.document }) else {
        promise.reject(withError: self.notOpen())
        return
      }
      do {
        promise.resolve(withResult: try body(document))
      } catch {
        promise.reject(withError: error)
      }
    }
    return promise
  }

  private func notOpen() -> Error {
    return RuntimeError.error(withMessage: isClosed ? "The document is closed" : "The document is not open")
  }
}

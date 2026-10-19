import Foundation
import PDFKit

/// Loads the view's sources in parallel (`PdfDocumentLoader` each) and
/// shows them as one document: the source's own for one source, and for
/// several an empty `ColorSchemeDocument` with every source's pages
/// inserted in order. All or nothing: the first source that fails cancels
/// the others and is reported with its index.
///
/// Main thread, except the assembly, which runs off it.
final class DocumentSetLoader {
  private static let assemblyQueue = DispatchQueue(label: "com.betterpdf.assemble", qos: .userInitiated)

  private let sources: [NativePdfSource]
  private let task = PdfLoadTask()
  private let progress: (_ loadedBytes: Int64, _ totalBytes: Int64?) -> Void
  private let completion: (Result<DisplayedDocument, PdfLoadFailure>) -> Void
  private var loaded: [LoadedDocument?]
  /// The sources' latest download progress; nil until a source downloads.
  private var downloads: [(loadedBytes: Int64, totalBytes: Int64?)?]
  private let throttle = ProgressThrottle()
  private var isFinished = false
  private var isReleased = false

  /// `progress` (downloads only, summed and throttled) and `completion`
  /// are called on the main queue unless the task is cancelled; the final
  /// progress update precedes `completion`.
  static func load(
    sources: [NativePdfSource],
    progress: @escaping (_ loadedBytes: Int64, _ totalBytes: Int64?) -> Void,
    completion: @escaping (Result<DisplayedDocument, PdfLoadFailure>) -> Void
  ) -> PdfLoadTask {
    let loader = DocumentSetLoader(sources: sources, progress: progress, completion: completion)
    loader.start()
    return loader.task
  }

  private init(
    sources: [NativePdfSource],
    progress: @escaping (Int64, Int64?) -> Void,
    completion: @escaping (Result<DisplayedDocument, PdfLoadFailure>) -> Void
  ) {
    self.sources = sources
    self.progress = progress
    self.completion = completion
    loaded = Array(repeating: nil, count: sources.count)
    downloads = Array(repeating: nil, count: sources.count)
  }

  private func start() {
    // Cancelled by the view before the result: the sources that loaded are
    // released (those still loading release themselves).
    // Weak: once the result is delivered, nothing may keep the loader (and
    // with it every source's document) alive.
    _ = task.attach { [weak self] in
      guard let self else { return }
      DispatchQueue.main.async { if !self.isFinished { self.releaseLoaded() } }
    }
    guard !sources.isEmpty else {
      var failure = PdfLoadFailure(code: .invalidSource, message: "No PDF source was given")
      failure.sourceIndex = 0
      DispatchQueue.main.async { [self] in finish(.failure(failure)) }
      return
    }
    for (index, source) in sources.enumerated() {
      let child = PdfDocumentLoader.load(
        source: source,
        password: source.password,
        progress: { [self] loadedBytes, totalBytes in
          downloads[index] = (loadedBytes, totalBytes)
          reportProgress(isFinal: false)
        }
      ) { [self] result in
        switch result {
        case .success(let document):
          loaded[index] = document
          if loaded.allSatisfy({ $0 != nil }) { assemble(loaded.compactMap { $0 }) }
        case .failure(var failure):
          failure.sourceIndex = index
          fail(failure)
        }
      }
      // Cancelled meanwhile: the child's result is never delivered.
      if !task.attach(child.cancel) { child.cancel() }
    }
  }

  /// The summed progress of the sources downloading; `totalBytes` only
  /// while each of them knows its size.
  private func reportProgress(isFinal: Bool) {
    let active = downloads.compactMap { $0 }
    guard !active.isEmpty, !isFinished, !task.isCancelled else { return }
    let loadedBytes = active.reduce(0) { $0 + $1.loadedBytes }
    let totals = active.compactMap(\.totalBytes)
    let totalBytes = totals.count == active.count ? totals.reduce(0, +) : nil
    let nowMs = DispatchTime.now().uptimeNanoseconds / 1_000_000
    guard throttle.shouldEmit(nowMs: nowMs, loadedBytes: loadedBytes, isComplete: isFinal) else { return }
    progress(loadedBytes, totalBytes)
  }

  private func fail(_ failure: PdfLoadFailure) {
    guard !isFinished else { return }
    // Sources still loading are cancelled (their copies are unpinned as
    // they finish); those that loaded are released here.
    task.cancel()
    releaseLoaded()
    finish(.failure(failure))
  }

  private func assemble(_ documents: [LoadedDocument]) {
    reportProgress(isFinal: true)
    let passwords = sources.map { $0.password ?? "" }
    Self.assemblyQueue.async { [self] in
      // Nothing else uses these documents yet, so they may be read here.
      let displayed = DisplayedDocument(assembling: zip(documents, passwords).map { (loaded: $0, password: $1) })
      DispatchQueue.main.async { [self] in
        if task.isCancelled {
          releaseLoaded()
        } else {
          finish(.success(displayed))
        }
      }
    }
  }

  private func finish(_ result: Result<DisplayedDocument, PdfLoadFailure>) {
    guard !isFinished else { return }
    isFinished = true
    completion(result)
  }

  /// Unpins the downloaded copies of the sources that loaded, once.
  private func releaseLoaded() {
    guard !isReleased else { return }
    isReleased = true
    for pin in loaded.compactMap({ $0?.pin }) { DocumentCache.shared.store.unpin(pin) }
  }
}

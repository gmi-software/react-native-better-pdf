import Foundation
import NitroModules
import PDFKit

/// Resolves a `NativePdfSource` to a local file and opens it as a
/// `PDFDocument`, entirely off the main thread.
///
/// Remote documents come from the process-wide `DocumentCache` and are
/// opened through a pin (`DocumentCacheStore.pin`), so the view's separate
/// reads of the same file (outline, page sizes, page images) keep finding it
/// when the copy is replaced or removed while displayed.
final class PdfDocumentLoader {
  private static let workQueue = DispatchQueue(label: "com.betterpdf.load", qos: .userInitiated, attributes: .concurrent)

  /// Loads the document. `progress` (remote downloads only, throttled) and
  /// `completion` are called on the main queue, unless cancelled; the final
  /// progress update always precedes `completion`. `isDisplayed` documents
  /// can be drawn dark (see `ColorSchemeDocument`); others are plain.
  static func load(
    source: NativePdfSource,
    password: String?,
    isDisplayed: Bool = true,
    progress: @escaping (_ loadedBytes: Int64, _ totalBytes: Int64?) -> Void,
    completion: @escaping (Result<LoadedDocument, PdfLoadFailure>) -> Void
  ) -> PdfLoadTask {
    let task = PdfLoadTask()
    let finish: (Result<LoadedDocument, PdfLoadFailure>) -> Void = { result in
      DispatchQueue.main.async {
        if !task.isCancelled {
          completion(result)
        } else if case .success(let loaded) = result, let pin = loaded.pin {
          DocumentCache.shared.store.unpin(pin)
        }
      }
    }
    let throttle = ProgressThrottle()
    let reportProgress: (Int64, Int64?, Bool) -> Void = { loadedBytes, totalBytes, isComplete in
      let nowMs = DispatchTime.now().uptimeNanoseconds / 1_000_000
      guard throttle.shouldEmit(nowMs: nowMs, loadedBytes: loadedBytes, isComplete: isComplete) else { return }
      DispatchQueue.main.async {
        if !task.isCancelled { progress(loadedBytes, totalBytes) }
      }
    }
    workQueue.async {
      resolveLocalFile(source: source, task: task, progress: reportProgress) { fileResult in
        // Downloads complete on URLSession's delegate queue; parse on ours.
        workQueue.async {
          switch fileResult {
          case .failure(let failure):
            finish(.failure(failure))
          case .success(let file):
            if task.isCancelled {
              if let pin = file.pin { DocumentCache.shared.store.unpin(pin) }
              return
            }
            switch openDocument(file, password: password, isDisplayed: isDisplayed) {
            case .failure(let failure):
              if let pin = file.pin { DocumentCache.shared.store.unpin(pin) }
              finish(.failure(failure))
            case .success(let document):
              finish(.success(LoadedDocument(
                document: document, url: file.url, filePath: file.filePath, isStale: file.isStale, pin: file.pin
              )))
            }
          }
        }
      }
    }
    return task
  }

  // MARK: - Source resolution

  private static func resolveLocalFile(
    source: NativePdfSource,
    task: PdfLoadTask,
    progress: @escaping (Int64, Int64?, Bool) -> Void,
    completion: @escaping (Result<ResolvedFile, PdfLoadFailure>) -> Void
  ) {
    if let base64 = source.base64 {
      return completion(decodedFile(base64))
    }
    let uri = source.uri
    if uri.isEmpty {
      return completion(.failure(.invalidSource(uri)))
    }
    if uri.hasPrefix("/") {
      return completion(existingFile(URL(fileURLWithPath: uri)))
    }
    guard let url = URL(sourceUri: uri), let scheme = url.scheme?.lowercased() else {
      return completion(.failure(.invalidSource(uri)))
    }
    switch scheme {
    case "file":
      completion(existingFile(url))
    case "http", "https":
      let subscription = DocumentCache.shared.fetch(source: source, allowStale: true, listener: .init(
        onProgress: progress,
        onReady: { copy, isStale in
          // Without a pin, the copy itself still opens; only its later
          // replacement would affect the separate reads.
          let pin = try? DocumentCache.shared.store.pin(copy)
          completion(.success(ResolvedFile(url: pin ?? copy.fileURL, filePath: copy.fileURL.path, isStale: isStale, pin: pin)))
        },
        onFailed: { completion(.failure($0)) }
      ))
      if !task.attach(subscription.cancel) { subscription.cancel() }
    default:
      completion(.failure(.invalidSource(uri)))
    }
  }

  private static func existingFile(_ url: URL) -> Result<ResolvedFile, PdfLoadFailure> {
    var isDirectory: ObjCBool = false
    guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory), !isDirectory.boolValue else {
      return .failure(.fileNotFound(url.path))
    }
    return .success(ResolvedFile(url: url, filePath: url.path, isStale: false, pin: nil))
  }

  private static func decodedFile(_ text: ArrayBuffer) -> Result<ResolvedFile, PdfLoadFailure> {
    guard text.isOwner else {
      return .failure(PdfLoadFailure(code: .invalidSource, message: "The base64 text is not in a native buffer"))
    }
    switch Base64Document.decode(UnsafeBufferPointer(start: text.data, count: text.size)) {
    case .failure(let failure):
      return .failure(failure)
    case .success(let data):
      do {
        let pin = try DocumentCache.shared.store.pin(data: data)
        return .success(ResolvedFile(url: pin, filePath: nil, isStale: false, pin: pin))
      } catch {
        return .failure(PdfLoadFailure(
          code: .invalidPdf,
          message: "The decoded base64 source could not be written",
          nativeDescription: error.localizedDescription
        ))
      }
    }
  }

  // MARK: - Opening

  private static func openDocument(_ file: ResolvedFile, password: String?, isDisplayed: Bool) -> Result<PDFDocument, PdfLoadFailure> {
    let url = file.url
    // PDFDocument(url:) parses the cross-reference table synchronously; this
    // is why the whole load runs on `workQueue`.
    // The displayed document draws dark pages (see ColorSchemeDocument).
    let opened: PDFDocument? = isDisplayed ? ColorSchemeDocument(url: url) : PDFDocument(url: url)
    guard let document = opened else {
      if file.filePath == nil {
        return .failure(PdfLoadFailure(code: .invalidPdf, message: "The base64 source is not a readable PDF document"))
      }
      return .failure(.invalidPdf(url.path))
    }
    if document.isLocked {
      guard let password, !password.isEmpty else {
        return .failure(PdfLoadFailure(code: .passwordRequired, message: "This PDF is password protected"))
      }
      guard document.unlock(withPassword: password) else {
        return .failure(PdfLoadFailure(code: .invalidPassword, message: "The password is incorrect"))
      }
    }
    if document.pageCount == 0 {
      let name = file.filePath == nil ? "The base64 PDF" : "The PDF at \(url.path)"
      return .failure(PdfLoadFailure(code: .invalidPdf, message: "\(name) has no pages"))
    }
    return .success(document)
  }
}

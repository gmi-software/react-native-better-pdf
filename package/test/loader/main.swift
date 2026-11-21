// Runs ios/PdfDocumentLoader.swift and the cache behind it on macOS for
// loader.test.ts: one command per line on stdin, one result line each.
//
//   load <uri> [cache=persistent] [maxAgeMs=<n>] [password=<p>]
//   download <uri> [cache=persistent] [maxAgeMs=<n>]
//   entry <uri>
//   replace <uri>
//   fixture <path> [password=<p>]
import Foundation
import NitroModules
import PDFKit

// Stand-ins for the types Nitro generates.
enum PdfCacheMode: String { case session, persistent }
enum PdfErrorCode: String {
  case invalidSource = "invalid-source"
  case fileNotFound = "file-not-found"
  case networkError = "network-error"
  case httpError = "http-error"
  case invalidPdf = "invalid-pdf"
  case passwordRequired = "password-required"
  case invalidPassword = "invalid-password"
  case unsupportedPdf = "unsupported-pdf"
  case pageRenderFailed = "page-render-failed"
  case outOfMemory = "out-of-memory"
}
struct PdfError {
  let code: PdfErrorCode
  let message: String
  let page: Double?
  let sourceIndex: Double?
  let httpStatus: Double?
  let nativeDescription: String?
}
struct NativePdfSource {
  var uri: String
  var base64: ArrayBuffer? = nil
  var headers: [String: String]? = nil
  var cache: PdfCacheMode? = nil
  var maxAgeMs: Double? = nil
  var cacheKey: String? = nil
  var password: String? = nil
}
final class ColorSchemeDocument: PDFDocument {}

setvbuf(stdout, nil, _IONBF, 0)

func source(_ words: [Substring]) -> NativePdfSource {
  var source = NativePdfSource(uri: String(words[1]))
  for option in words.dropFirst(2) {
    let parts = option.split(separator: "=", maxSplits: 1)
    let value = String(parts[1])
    switch parts[0] {
    case "cache": source.cache = PdfCacheMode(rawValue: value)
    case "maxAgeMs": source.maxAgeMs = Double(value)
    case "password": source.password = value
    default: fatalError("Unknown option \(option)")
    }
  }
  return source
}

func run(_ words: [Substring], done: @escaping (String) -> Void) {
  switch words[0] {
  case "load":
    let source = source(words)
    _ = PdfDocumentLoader.load(source: source, password: source.password, isDisplayed: false, progress: { _, _ in }) { result in
      switch result {
      case .success(let loaded):
        if let pin = loaded.pin { DocumentCache.shared.store.unpin(pin) }
        done("loaded pages=\(loaded.document.pageCount) stale=\(loaded.isStale)")
      case .failure(let failure):
        done("failed \(failure.code.rawValue)")
      }
    }
  case "download":
    _ = DocumentCache.shared.fetch(source: source(words), allowStale: false, listener: .init(
      onProgress: { _, _, _ in },
      onReady: { copy, _, _ in done("stored \(copy.mode.rawValue)") },
      onFailed: { done("failed \($0.code.rawValue)") }
    ))
  case "entry":
    let copy = DocumentCache.shared.store.find(key: source(words).storeKey())
    done(copy.map { "entry \($0.mode.rawValue) bytes=\($0.fileURL.fileSize())" } ?? "entry none")
  case "replace":
    let store = DocumentCache.shared.store
    let key = source(words).storeKey()
    func commit(_ text: String) -> CachedCopy {
      let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
      try! Data(text.utf8).write(to: file)
      return try! store.commit(key: key, file: file, mode: .session, metadata: CacheMetadata(validatedAtMs: 0))
    }
    let opened = try! store.pin(commit("%PDF-opened"))
    let newer = try! store.pin(commit("%PDF-newer"))
    let removedNewer = store.remove(key: key, ifCurrentIs: .init(opened)!)
    let removedOpened = store.remove(key: key, ifCurrentIs: .init(newer)!)
    done("newer \(removedNewer ? "removed" : "kept"), then its own \(removedOpened ? "removed" : "kept")")
  case "fixture":
    let document = PDFDocument()
    document.insert(PDFPage(), at: 0)
    let password = words.dropFirst(2).first.map { String($0.split(separator: "=")[1]) }
    let options: [PDFDocumentWriteOption: Any] = password.map { [.userPasswordOption: $0, .ownerPasswordOption: $0 + "-owner"] } ?? [:]
    done(document.write(to: URL(fileURLWithPath: String(words[1])), withOptions: options) ? "written" : "not written")
  default:
    fatalError("Unknown command \(words[0])")
  }
}

func next() {
  guard let line = readLine() else { exit(0) }
  run(line.split(separator: " ")) { result in
    print(result)
    DispatchQueue.main.async(execute: next)
  }
}

DispatchQueue.main.async(execute: next)
dispatchMain()

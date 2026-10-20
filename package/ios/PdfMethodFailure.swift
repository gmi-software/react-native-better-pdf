import Foundation

/// Why a view or `PdfDocument` method rejected. Nitro rejections carry only
/// a message, so JS reads the code from its `[code] ` prefix
/// (`PdfMethodError.ts`); an underlying error's description follows on the
/// next line, which JS keeps as `nativeDescription`.
struct PdfMethodFailure: Error, CustomStringConvertible {
  enum Code: String {
    case notLoaded = "not-loaded"
    case closed
    case invalidArgument = "invalid-argument"
    case notSupported = "not-supported"
    case notPermitted = "not-permitted"
    case busy
    case ioError = "io-error"
    case invalidPdf = "invalid-pdf"
    case outOfMemory = "out-of-memory"
    case unknown
  }

  let code: Code
  let message: String
  let underlying: Error?

  init(_ code: Code, _ message: String, underlying: Error? = nil) {
    self.code = code
    self.message = message
    self.underlying = underlying
  }

  var description: String {
    let coded = "[\(code.rawValue)] \(message)"
    guard let underlying else { return coded }
    return "\(coded)\n\(String(describing: underlying))"
  }

  static let notLoaded = PdfMethodFailure(.notLoaded, "No document is loaded")
  static let closed = PdfMethodFailure(.closed, "The document was closed")
}

extension Error {
  /// This error as a `PdfMethodFailure`: file system errors are `io-error`.
  var asMethodFailure: PdfMethodFailure {
    if let failure = self as? PdfMethodFailure { return failure }
    let error = self as NSError
    let isFileError = error.domain == NSCocoaErrorDomain || error.domain == NSPOSIXErrorDomain
    return PdfMethodFailure(isFileError ? .ioError : .unknown, error.localizedDescription, underlying: self)
  }
}

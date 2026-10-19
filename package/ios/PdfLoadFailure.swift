import Foundation

/// A load/render failure, convertible to the JS-facing `PdfError` struct.
struct PdfLoadFailure: Error {
  let code: PdfErrorCode
  let message: String
  var page: Int? = nil
  /// The view's source that failed to load.
  var sourceIndex: Int? = nil
  var httpStatus: Int? = nil
  var nativeDescription: String? = nil

  func toPdfError() -> PdfError {
    return PdfError(
      code: code,
      message: message,
      page: page.map(Double.init),
      sourceIndex: sourceIndex.map(Double.init),
      httpStatus: httpStatus.map(Double.init),
      nativeDescription: nativeDescription
    )
  }

  static func invalidSource(_ uri: String) -> PdfLoadFailure {
    return PdfLoadFailure(code: .invalidSource, message: "Unsupported PDF source: \"\(uri)\"")
  }

  static func fileNotFound(_ path: String) -> PdfLoadFailure {
    return PdfLoadFailure(code: .fileNotFound, message: "No file at \(path)")
  }

  static func invalidPdf(_ path: String) -> PdfLoadFailure {
    return PdfLoadFailure(code: .invalidPdf, message: "The file at \(path) is not a readable PDF document")
  }
}

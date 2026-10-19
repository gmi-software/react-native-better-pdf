import NitroModules
import PDFKit

/// A validated `renderPage` call, shared by the view and `PdfDocument`.
struct PageImageRequest {
  let page: Int
  let width: Int
  let isJpeg: Bool
  let quality: CGFloat

  /// Throws for a page outside the document or a width outside 1–4096
  /// pixels (rejected rather than clamped).
  init(page: Double, pageCount: Int, options: PdfRenderPageOptions) throws {
    guard page.isFinite, page == page.rounded(.down), page >= 0, page < Double(pageCount) else {
      throw RuntimeError.error(withMessage: "Page \(page) is outside the document (0–\(pageCount - 1))")
    }
    guard options.width.isFinite, options.width >= 1, options.width <= Self.maxWidth else {
      throw RuntimeError.error(withMessage: "width must be 1–\(Int(Self.maxWidth)) pixels, got \(options.width)")
    }
    self.page = Int(page)
    width = Int(options.width.rounded())
    isJpeg = options.format == .jpeg
    quality = CGFloat(options.quality.flatMap { $0.isFinite ? min(max($0, 0), 1) : nil } ?? 0.9)
  }

  /// Renders `page` of `document` and writes the image file: the requested
  /// page's index in `document`'s file (`self.page` for a document of one
  /// source). Call on the thread that owns `document`.
  func render(pageInFile page: Int, from document: PDFDocument) throws -> PdfPageImage {
    guard let pdfPage = document.page(at: page) else {
      throw RuntimeError.error(withMessage: "Page \(page) could not be read")
    }
    let image = try pdfPage.renderImage(width: width)
    guard let data = isJpeg ? image.jpegData(compressionQuality: quality) : image.pngData() else {
      throw RuntimeError.error(withMessage: "The page image could not be encoded")
    }
    let file = try FileManager.default.newRenderFile(pathExtension: isJpeg ? "jpg" : "png")
    try data.write(to: file)
    return PdfPageImage(
      uri: file.absoluteString,
      width: Double(image.size.width),
      height: Double(image.size.height)
    )
  }

  private static let maxWidth: Double = 4096
}

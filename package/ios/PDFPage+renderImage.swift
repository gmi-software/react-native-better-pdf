import PDFKit
import UIKit

extension PDFPage {
  /// Renders the page as displayed (crop box, rotation) `width` pixels wide
  /// on white, the height following its aspect ratio. Call on a thread no
  /// other code draws this page's document on.
  func renderImage(width: Int) throws -> UIImage {
    let size = displaySize
    guard size.width > 0, size.height > 0 else {
      throw PdfMethodFailure(.invalidPdf, "The page has no area")
    }
    let height = max(1, Int((Double(width) * size.height / size.width).rounded()))
    guard width * height <= Self.maxImagePixels else {
      throw PdfMethodFailure(.invalidArgument, "A \(width)×\(height) image exceeds the limit of \(Self.maxImagePixels) pixels; use a smaller width")
    }
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let pixels = CGSize(width: width, height: height)
    return UIGraphicsImageRenderer(size: pixels, format: format).image { context in
      UIColor.white.setFill()
      context.fill(CGRect(origin: .zero, size: pixels))
      // PDF space has y up; draw(with:to:) applies the box origin and rotation.
      let cgContext = context.cgContext
      cgContext.translateBy(x: 0, y: pixels.height)
      cgContext.scaleBy(x: pixels.width / size.width, y: -pixels.height / size.height)
      draw(with: .cropBox, to: cgContext)
    }
  }

  /// 4096 × 4096, the same limit as Android.
  private static let maxImagePixels = 4096 * 4096
}

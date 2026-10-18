import PDFKit

/// The displayed document: its own delegate, so every page PDFKit creates
/// is a `ColorSchemePage`, drawn dark while `isDark` is set, every text
/// markup annotation a `ColorSchemeAnnotation` and every form widget a
/// `FormWidgetAnnotation`. Separate instances of the file (page images,
/// printing, outline) are plain `PDFDocument`s and keep the document's
/// colours; the one `getFormFields` reads is a `ColorSchemeDocument`, so
/// its widgets are read as the displayed document's are.
final class ColorSchemeDocument: PDFDocument, PDFDocumentDelegate {
  private let lock = NSLock()
  private var dark = false

  /// Set on the main thread; read by pages on PDFKit's drawing threads.
  var isDark: Bool {
    get {
      lock.lock()
      defer { lock.unlock() }
      return dark
    }
    set {
      lock.lock()
      dark = newValue
      lock.unlock()
    }
  }

  /// Main thread: whether the reader can fill the form in.
  var isFormFillingEnabled = true
  /// Main thread: a widget's value was set, by the reader or by PDFKit
  /// loading its page (see `FormInteraction`).
  var widgetChanged: ((FormWidgetAnnotation) -> Void)?
  private var isQuiet = false

  func widgetDidChange(_ widget: FormWidgetAnnotation) {
    guard !isQuiet else { return }
    widgetChanged?(widget)
  }

  /// Runs `body` — code setting values — without reporting widget changes.
  /// (Writing this document would report too: PDFKit sets buttons' states
  /// again, measured; exports write a separate instance instead.)
  func withoutReportingChanges<T>(_ body: () throws -> T) rethrows -> T {
    isQuiet = true
    defer { isQuiet = false }
    return try body()
  }

  override init?(url: URL) {
    super.init(url: url)
    delegate = self
  }

  func classForPage() -> AnyClass {
    ColorSchemePage.self
  }

  /// Text markup draws differently on dark pages; widgets report changes.
  func `class`(forAnnotationType annotationType: String) -> AnyClass {
    if annotationType == "Widget" || annotationType == "/Widget" { return FormWidgetAnnotation.self }
    return ColorSchemeAnnotation.isMarkup(annotationType) ? ColorSchemeAnnotation.self : PDFAnnotation.self
  }
}

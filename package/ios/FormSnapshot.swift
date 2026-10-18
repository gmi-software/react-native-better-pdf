import PDFKit

/// The values of a displayed document's changed form fields, read on the
/// main thread to fill in a separate instance of the same file on another
/// one, which is then written, drawn or read there while PDFView keeps the
/// main thread: PDFKit objects are not thread-safe, and writing a long
/// document takes seconds (measured: 5.7 s for 500 pages). The other
/// fields are as in the file in both instances.
struct FormSnapshot {
  struct Entry {
    let page: Int
    /// Its place among the page's annotations.
    let index: Int
    let name: String?
    let bounds: CGRect
    /// Text fields and choices.
    let text: String?
    /// Buttons.
    let state: PDFWidgetCellState?
  }

  let entries: [Entry]

  /// Main thread: every widget of [form]'s changed fields; nil while the
  /// form is as loaded.
  init?(changesOf form: DocumentForm) {
    guard form.isChanged else { return nil }
    entries = form.changedWidgets().compactMap { widget -> Entry? in
      guard let page = widget.page, let document = page.document,
            let index = page.annotations.firstIndex(where: { $0 === widget }) else { return nil }
      let isButton = widget.widgetFieldType == .button
      return Entry(
        page: document.index(for: page),
        index: index,
        name: widget.fieldName,
        bounds: widget.bounds,
        text: isButton ? nil : widget.widgetStringValue,
        state: isButton ? widget.buttonWidgetState : nil
      )
    }.sorted { ($0.page, $0.index) < ($1.page, $1.index) }
  }

  /// Fills [document] in — an instance of the same file that only the
  /// calling thread uses. Widgets are found where they were, by name and
  /// place.
  func apply(to document: PDFDocument) {
    for entry in entries {
      guard let page = document.page(at: entry.page) else { continue }
      let annotations = page.annotations
      let isSame = { (annotation: PDFAnnotation) in
        annotation.type == "Widget" && annotation.fieldName == entry.name && annotation.bounds == entry.bounds
      }
      let widget = annotations.indices.contains(entry.index) && isSame(annotations[entry.index])
        ? annotations[entry.index]
        : annotations.first(where: isSame)
      guard let widget else { continue }
      if let state = entry.state, widget.buttonWidgetState != state { widget.buttonWidgetState = state }
      if let text = entry.text, widget.widgetStringValue != text { widget.widgetStringValue = text }
    }
  }
}

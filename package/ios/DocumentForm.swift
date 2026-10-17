import PDFKit

/// The form (AcroForm) of a document: its fields' names in the document's
/// order and the pages of each field's widgets, read from the file's
/// dictionaries without loading a page. Field values live in PDFKit's
/// widget annotations (`FormWidgetAnnotation`), which hold the reader's
/// changes; loading a page's annotations takes time (measured: 170–220 ms
/// for 1,000 pages on the simulator), so only the pages asked for are loaded.
/// On the thread of its document: main for the displayed one.
final class DocumentForm {
  private let document: PDFDocument
  /// Fully qualified names, in the order of the AcroForm's field tree.
  private let fieldOrder: [String: Int]
  private let widgetPages: [Int]
  /// The pages of each field's widgets, by fully qualified name.
  private let fieldPages: [String: [Int]]
  /// Widgets whose values changed since loading, by the reader or by code.
  private var changed: [ObjectIdentifier: FormWidgetAnnotation] = [:]

  init(document: PDFDocument) {
    self.document = document
    var order: [String: Int] = [:]
    for name in Self.fieldNames(of: document) where order[name] == nil {
      order[name] = order.count
    }
    fieldOrder = order
    (widgetPages, fieldPages) = Self.widgetPages(of: document)
  }

  /// Every field with its widgets, in the field tree's order (fields only
  /// PDFKit knows of after the others, in page order). Loads every page
  /// with widgets.
  func fields() -> [(name: String, widgets: [FormWidgetAnnotation])] {
    var byName: [String: [FormWidgetAnnotation]] = [:]
    var pageOrder: [String] = []
    for index in widgetPages {
      guard let page = document.page(at: index) else { continue }
      for case let widget as FormWidgetAnnotation in page.annotations {
        guard let name = widget.fieldName, !name.isEmpty else { continue }
        if byName[name] == nil { pageOrder.append(name) }
        byName[name, default: []].append(widget)
      }
    }
    let names = pageOrder.enumerated().sorted { a, b in
      (fieldOrder[a.element] ?? Int.max, a.offset) < (fieldOrder[b.element] ?? Int.max, b.offset)
    }.map(\.element)
    return names.map { ($0, byName[$0] ?? []) }
  }

  /// The widgets of the field named `name`. Loads the pages they are on.
  func widgets(named name: String) -> [FormWidgetAnnotation] {
    let found = widgets(named: name, on: fieldPages[name] ?? [])
    // A name PDFKit spells otherwise than the file's `/T` entries do.
    return found.isEmpty ? widgets(named: name, on: widgetPages) : found
  }

  private func widgets(named name: String, on pages: [Int]) -> [FormWidgetAnnotation] {
    pages.flatMap { index -> [FormWidgetAnnotation] in
      guard let page = document.page(at: index) else { return [] }
      return page.annotations.compactMap { $0 as? FormWidgetAnnotation }.filter { $0.fieldName == name }
    }
  }

  /// Whether a value changed since loading.
  var isChanged: Bool { !changed.isEmpty }

  func noteChange(of widget: FormWidgetAnnotation) {
    changed[ObjectIdentifier(widget)] = widget
  }

  /// Every widget of the fields whose values changed since loading (a
  /// changed widget without a name on its own).
  func changedWidgets() -> [FormWidgetAnnotation] {
    var names = Set<String>()
    var unnamed: [FormWidgetAnnotation] = []
    for widget in changed.values {
      if let name = widget.fieldName, !name.isEmpty {
        names.insert(name)
      } else {
        unnamed.append(widget)
      }
    }
    return names.flatMap(widgets(named:)) + unnamed
  }

  /// Pages whose `/Annots` hold a widget, and the pages of each field's
  /// widgets, from the page dictionaries.
  private static func widgetPages(of document: PDFDocument) -> (all: [Int], byField: [String: [Int]]) {
    guard let file = document.documentRef else { return ([], [:]) }
    var all: [Int] = []
    var byField: [String: [Int]] = [:]
    for index in 0..<file.numberOfPages {
      guard let dictionary = file.page(at: index + 1)?.dictionary else { continue }
      var annots: CGPDFArrayRef?
      guard CGPDFDictionaryGetArray(dictionary, "Annots", &annots), let annots else { continue }
      for position in 0..<CGPDFArrayGetCount(annots) {
        var annotation: CGPDFDictionaryRef?
        var subtype: UnsafePointer<CChar>?
        guard CGPDFArrayGetDictionary(annots, position, &annotation), let annotation,
              CGPDFDictionaryGetName(annotation, "Subtype", &subtype), let subtype,
              strcmp(subtype, "Widget") == 0 else { continue }
        if all.last != index { all.append(index) }
        if let name = fieldName(of: annotation), byField[name]?.last != index {
          byField[name, default: []].append(index)
        }
      }
    }
    return (all, byField)
  }

  /// A widget's fully qualified field name: the `/T` entries of it and its
  /// `/Parent`s, outermost first.
  private static func fieldName(of widget: CGPDFDictionaryRef) -> String? {
    var parts: [String] = []
    var node: CGPDFDictionaryRef? = widget
    for _ in 0..<32 {
      guard let current = node else { break }
      var partial: CGPDFStringRef?
      if CGPDFDictionaryGetString(current, "T", &partial), let partial,
         let part = CGPDFStringCopyTextString(partial) as String? {
        parts.append(part)
      }
      var parent: CGPDFDictionaryRef?
      node = CGPDFDictionaryGetDictionary(current, "Parent", &parent) ? parent : nil
    }
    return parts.isEmpty ? nil : parts.reversed().joined(separator: ".")
  }

  /// Fully qualified field names, walking the AcroForm's `/Fields` tree.
  private static func fieldNames(of document: PDFDocument) -> [String] {
    guard let catalog = document.documentRef?.catalog else { return [] }
    var acroForm: CGPDFDictionaryRef?
    var fields: CGPDFArrayRef?
    guard CGPDFDictionaryGetDictionary(catalog, "AcroForm", &acroForm), let acroForm,
          CGPDFDictionaryGetArray(acroForm, "Fields", &fields), let fields else { return [] }
    var names: [String] = []
    func visit(_ array: CGPDFArrayRef, prefix: String?, depth: Int) {
      guard depth < 32 else { return }
      for position in 0..<CGPDFArrayGetCount(array) {
        var node: CGPDFDictionaryRef?
        guard CGPDFArrayGetDictionary(array, position, &node), let node else { continue }
        var partial: CGPDFStringRef?
        let part = CGPDFDictionaryGetString(node, "T", &partial)
          ? partial.flatMap { CGPDFStringCopyTextString($0) as String? } : nil
        // Widgets of a field have no name of their own.
        guard let part else { continue }
        let name = prefix.map { "\($0).\(part)" } ?? part
        names.append(name)
        var kids: CGPDFArrayRef?
        if CGPDFDictionaryGetArray(node, "Kids", &kids), let kids { visit(kids, prefix: name, depth: depth + 1) }
      }
    }
    visit(fields, prefix: nil, depth: 0)
    return names
  }
}

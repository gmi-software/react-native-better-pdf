import NitroModules
import PDFKit

extension DocumentForm {
  /// Sets fields' values; all or none: throws before changing anything for
  /// an unknown or read-only field or a value its type does not take.
  func setValues(_ values: [NativePdfFormValue]) throws {
    let changes = try values.map { value -> (widgets: [FormWidgetAnnotation], apply: () -> Void) in
      let widgets = widgets(named: value.name)
      guard let first = widgets.first, let type = first.formFieldType else {
        throw RuntimeError.error(withMessage: "The form has no field named \"\(value.name)\"")
      }
      guard !widgets.contains(where: { $0.isReadOnlyInDocument }) else {
        throw RuntimeError.error(withMessage: "The form field \"\(value.name)\" is read-only")
      }
      return (widgets, try Self.change(widgets, type: type, to: value))
    }
    for change in changes {
      change.apply()
      change.widgets.forEach(noteChange(of:))
    }
  }

  private static func change(
    _ widgets: [FormWidgetAnnotation], type: PdfFormFieldType, to value: NativePdfFormValue
  ) throws -> () -> Void {
    let name = value.name
    func mismatch(_ expected: String) -> Error {
      RuntimeError.error(withMessage: "The form field \"\(name)\" (\(type.stringValue)) takes \(expected)")
    }
    let first = widgets[0]
    switch type {
    case .text:
      guard value.isChecked == nil, value.options == nil else { throw mismatch("a string or null") }
      let text = value.text ?? ""
      if first.maximumLength > 0, text.count > first.maximumLength {
        throw RuntimeError.error(withMessage: "The form field \"\(name)\" takes at most \(first.maximumLength) characters")
      }
      return { widgets.forEach { $0.widgetStringValue = text } }
    case .checkbox:
      guard value.text == nil, value.options == nil else { throw mismatch("a boolean or null") }
      let state: PDFWidgetCellState = value.isChecked == true ? .onState : .offState
      return { widgets.forEach { $0.buttonWidgetState = state } }
    case .radio:
      guard value.isChecked == nil, value.options == nil else { throw mismatch("an option or null") }
      if let option = value.text, !widgets.contains(where: { $0.buttonWidgetStateString == option }) {
        throw RuntimeError.error(withMessage: "The form field \"\(name)\" has no option \"\(option)\"")
      }
      return {
        // Off first: turning one button on leaves the others as they are.
        widgets.filter { $0.buttonWidgetStateString != value.text }.forEach { $0.buttonWidgetState = .offState }
        widgets.filter { $0.buttonWidgetStateString == value.text }.forEach { $0.buttonWidgetState = .onState }
      }
    case .comboBox:
      guard value.isChecked == nil, value.options == nil else { throw mismatch("an option or null") }
      let text = value.text ?? ""
      let options = first.choices ?? []
      if !text.isEmpty, !options.contains(text), first.fieldFlags & FormFieldFlag.edit == 0 {
        throw RuntimeError.error(withMessage: "The form field \"\(name)\" has no option \"\(text)\"")
      }
      return { widgets.forEach { $0.widgetStringValue = text } }
    case .listBox:
      guard value.isChecked == nil else { throw mismatch("options or null") }
      let selection = value.options ?? value.text.map { [$0] } ?? []
      let options = first.choices ?? []
      if let unknown = selection.first(where: { !options.contains($0) }) {
        throw RuntimeError.error(withMessage: "The form field \"\(name)\" has no option \"\(unknown)\"")
      }
      // PDFKit keeps one selected option per list box.
      guard selection.count <= 1 else {
        throw RuntimeError.error(withMessage: "The form field \"\(name)\": selecting several options is not supported on iOS")
      }
      return { widgets.forEach { $0.widgetStringValue = selection.first ?? "" } }
    case .signature, .pushButton:
      throw RuntimeError.error(withMessage: "The form field \"\(name)\" (\(type.stringValue)) has no value to set")
    }
  }
}

// bench <pdf> <password> <first field> <last field>; see README.md in this folder.
import CryptoKit
import PDFKit
import UIKit

setvbuf(stdout, nil, _IONBF, 0)

// Stand-ins for the Nitro-generated types.
enum RuntimeError: Error {
  case error(withMessage: String)
}

enum PdfFormFieldType: String {
  case text, checkbox, radio, signature
  case comboBox = "combo-box", listBox = "list-box", pushButton = "push-button"
  var stringValue: String { rawValue }
}

struct PdfFormWidget: Equatable {
  let page: Double, x: Double, y: Double, width: Double, height: Double
}

struct NativePdfFormField: Equatable {
  let name: String
  let type: PdfFormFieldType
  let isReadOnly: Bool
  let isRequired: Bool
  let widgets: [PdfFormWidget]
  let text: String
  let isChecked: Bool
  let options: [String]
  let selectedOptions: [String]
  let isMultiline: Bool
  let maxLength: Double
  let isMultiSelect: Bool
  let isEditable: Bool
  let isSigned: Bool
}

struct NativePdfFormValue {
  let name: String
  let text: String?
  let isChecked: Bool?
  let options: [String]?
}

let arguments = CommandLine.arguments
guard arguments.count == 5 else {
  print("usage: bench <pdf> <password or -> <first field> <last field>")
  exit(2)
}
let url = URL(fileURLWithPath: arguments[1])
let password = arguments[2] == "-" ? nil : arguments[2]
let firstName = arguments[3]
let lastName = arguments[4]

func time(_ body: () -> Void) -> Double {
  let start = CACurrentMediaTime()
  body()
  return (CACurrentMediaTime() - start) * 1000
}

func runLoop(for seconds: TimeInterval) {
  RunLoop.main.run(until: Date(timeIntervalSinceNow: seconds))
}

/// How long each main-queue ping from another thread waited (ms).
final class StallProbe {
  private let lock = NSLock()
  private var waits: [Double] = []
  private var isRunning = true

  /// Returns once pings are being sent.
  func start() {
    defer { runLoop(for: 0.05) }
    Thread.detachNewThread { [self] in
      while running() {
        let sent = CACurrentMediaTime()
        let done = DispatchSemaphore(value: 0)
        DispatchQueue.main.async { [self] in
          let waited = (CACurrentMediaTime() - sent) * 1000
          lock.lock()
          waits.append(waited)
          lock.unlock()
          done.signal()
        }
        done.wait()
        usleep(1000)
      }
    }
  }

  /// The longest wait, once a ping sent during the work got through.
  func stop() -> Double {
    runLoop(for: 0.05)
    lock.lock()
    defer { lock.unlock() }
    isRunning = false
    return waits.max() ?? 0
  }

  private func running() -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return isRunning
  }
}

func open(_ url: URL) -> ColorSchemeDocument {
  guard let document = ColorSchemeDocument(url: url) else {
    print("cannot open \(url.path)")
    exit(2)
  }
  if document.isLocked { _ = document.unlock(withPassword: password ?? "") }
  return document
}

let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
let pdfView = PDFView(frame: window.bounds)
pdfView.displayMode = .singlePageContinuous
pdfView.displayDirection = .vertical
pdfView.autoScales = true
window.addSubview(pdfView)
window.isHidden = false

/// A freshly loaded document in the PDF view, as the viewer shows it.
func display() -> ColorSchemeDocument {
  pdfView.document = nil
  let document = open(url)
  pdfView.document = document
  pdfView.layoutIfNeeded()
  runLoop(for: 0.3)
  return document
}

/// Where the fields are, from another instance (not timed).
let pageOfField: [String: Int] = {
  let document = open(url)
  var pages: [String: Int] = [:]
  for index in 0..<document.pageCount {
    for case let widget as FormWidgetAnnotation in document.page(at: index)?.annotations ?? [] {
      if let name = widget.fieldName, pages[name] == nil { pages[name] = index }
    }
  }
  return pages
}()

/// The first widget of the field `name`, on a page the reader sees: its
/// annotations are loaded.
func readerWidget(named name: String, in document: PDFDocument) -> FormWidgetAnnotation {
  guard let index = pageOfField[name], let page = document.page(at: index) else {
    print("no field \(name)")
    exit(2)
  }
  pdfView.go(to: page)
  runLoop(for: 0.3)
  return page.annotations.lazy.compactMap { $0 as? FormWidgetAnnotation }.first { $0.fieldName == name }!
}

// What HybridBetterPdfView does on the main thread, by version.
#if CHANGES_SNAPSHOT
func noteReaderChange(_ widget: FormWidgetAnnotation, form: DocumentForm, document: ColorSchemeDocument) {
  form.noteChange(of: widget)
}
func filledFormSnapshot(form: DocumentForm, document: ColorSchemeDocument) -> FormSnapshot? {
  FormSnapshot(changesOf: form)
}
#else
func noteReaderChange(_ widget: FormWidgetAnnotation, form: DocumentForm, document: ColorSchemeDocument) {
  document.noteFormChanged()
}
func filledFormSnapshot(form: DocumentForm, document: ColorSchemeDocument) -> FormSnapshot? {
  document.formChangeCount > 0 ? FormSnapshot(form: form) : nil
}
#endif

/// As `openCopy`: another instance, filled in; read as the displayed one is.
func openCopy(filledIn snapshot: FormSnapshot?) -> ColorSchemeDocument {
  let copy = open(url)
  snapshot?.apply(to: copy)
  return copy
}

func fields(of form: DocumentForm, in document: PDFDocument) -> [NativePdfFormField] {
  form.fields().compactMap { NativePdfFormField(name: $0.name, widgets: $0.widgets, document: document, box: .cropBox) }
}

func digest(_ fields: [NativePdfFormField]) -> String {
  let text = fields.map { String(describing: $0) }.joined(separator: "\n")
  return SHA256.hash(data: Data(text.utf8)).prefix(6).map { String(format: "%02x", $0) }.joined()
}

var failures: [String] = []

func row(_ operation: String, main: Double, stall: Double, total: Double? = nil, note: String = "") {
  print(String(format: "| %@ | %.1f | %.1f | %@ | %@ |", operation, main, stall,
               total.map { String(format: "%.1f", $0) } ?? "—", note))
}

/// The reader edits a field: the value is set, the change noted, and the
/// field built for `onFormChange` (the view does this in the next main
/// queue turn; the form is created on first use).
func readerEdit(_ name: String) {
  let document = display()
  let widget = readerWidget(named: name, in: document)
  let probe = StallProbe()
  probe.start()
  var field: NativePdfFormField?
  let main = time {
    let form = DocumentForm(document: document)
    widget.widgetStringValue = "Edited"
    noteReaderChange(widget, form: form, document: document)
    field = NativePdfFormField(name: name, widgets: form.widgets(named: name), document: document, box: .cropBox)
  }
  let stall = probe.stop()
  if field?.text != "Edited" { failures.append("edit \(name): \(String(describing: field?.text))") }
  row("first edit, \(name)", main: main, stall: stall, note: field?.text == "Edited" ? "" : "WRONG VALUE")
}

print("| Operation | Main thread (ms) | Longest stall (ms) | Total (ms) | Note |")
print("| --- | ---: | ---: | ---: | --- |")

do {
  let document = display()
  let main = time { _ = DocumentForm(document: document) }
  row("open the form", main: main, stall: main)
}

readerEdit(firstName)
readerEdit(lastName)

// getFormFields after an edit on the first page.
do {
  let document = display()
  let widget = readerWidget(named: firstName, in: document)
  let form = DocumentForm(document: document)
  widget.widgetStringValue = "Edited"
  noteReaderChange(widget, form: form, document: document)
  let probe = StallProbe()
  probe.start()
  var result: [NativePdfFormField]?
  let started = CACurrentMediaTime()
  #if CHANGES_SNAPSHOT
  var snapshot: FormSnapshot?
  let main = time { snapshot = filledFormSnapshot(form: form, document: document) }
  DispatchQueue.global(qos: .userInitiated).async { [snapshot] in
    let copy = openCopy(filledIn: snapshot)
    let read = fields(of: DocumentForm(document: copy), in: copy)
    DispatchQueue.main.async { result = read }
  }
  while result == nil { runLoop(for: 0.005) }
  #else
  let main = time { result = fields(of: form, in: document) }
  #endif
  let total = (CACurrentMediaTime() - started) * 1000
  let stall = probe.stop()
  // The displayed document's own values, read on main (not timed).
  let expected = fields(of: form, in: document)
  let isSame = result == expected
  if !isSame { failures.append("getFormFields differs from the displayed document") }
  row("getFormFields", main: main, stall: stall, total: total,
      note: "\(result?.count ?? 0) fields, \(digest(result ?? []))\(isSame ? "" : " DIFFERS")")
}

// exportDocument (and renderPage, print) after edits on the first and last pages.
do {
  let document = display()
  let form = DocumentForm(document: document)
  for name in [firstName, lastName] {
    let widget = readerWidget(named: name, in: document)
    widget.widgetStringValue = "Edited \(name)"
    noteReaderChange(widget, form: form, document: document)
  }
  let probe = StallProbe()
  probe.start()
  var snapshot: FormSnapshot?
  let main = time { snapshot = filledFormSnapshot(form: form, document: document) }
  let stall = probe.stop()
  var data: Data?
  let write = time {
    let copy = openCopy(filledIn: snapshot)
    data = copy.dataRepresentation(options: copy.protectedWriteOptions(unlockPassword: copy.isEncrypted ? password : nil))
  }
  let exported = FileManager.default.temporaryDirectory.appendingPathComponent("bench-export.pdf")
  try? data?.write(to: exported)
  let reopened = open(exported)
  let values = fields(of: DocumentForm(document: reopened), in: reopened)
  let expected = fields(of: form, in: document)
  // PDFKit writes the field tree in another order.
  let isSame = values.sorted { $0.name < $1.name } == expected.sorted { $0.name < $1.name }
  if !isSame { failures.append("the export's fields differ from the displayed document") }
  row("exportDocument (snapshot on main, then write)", main: main, stall: stall, total: main + write,
      note: "\(snapshot?.entries.count ?? 0) widgets copied\(isSame ? "" : ", EXPORT DIFFERS")")
}

// setFormFieldValues on the last page.
do {
  let document = display()
  let value = NativePdfFormValue(name: lastName, text: "Set", isChecked: nil, options: nil)
  let probe = StallProbe()
  probe.start()
  var error: Error?
  let main = time {
    let form = DocumentForm(document: document)
    do {
      try document.withoutReportingChanges { try form.setValues([value]) }
    } catch let thrown {
      error = thrown
    }
  }
  let stall = probe.stop()
  if let error { failures.append("setFormFieldValues: \(error)") }
  row("setFormFieldValues, \(lastName)", main: main, stall: stall, note: error.map { "\($0)" } ?? "")
}

// Every field's widgets, as `widgets(named:)` finds them, against all pages.
do {
  let document = open(url)
  let form = DocumentForm(document: document)
  var all: [String: [ObjectIdentifier]] = [:]
  for index in 0..<document.pageCount {
    for case let widget as FormWidgetAnnotation in document.page(at: index)?.annotations ?? [] {
      if let name = widget.fieldName { all[name, default: []].append(ObjectIdentifier(widget)) }
    }
  }
  let wrong = all.filter { name, widgets in form.widgets(named: name).map(ObjectIdentifier.init) != widgets }
  if !wrong.isEmpty { failures.append("widgets(named:) for \(wrong.keys.sorted())") }
  print("widgets(named:): \(all.count) fields, \(wrong.isEmpty ? "OK" : "WRONG for \(wrong.keys.sorted())")")
}

print(failures.isEmpty ? "checks: OK" : "checks: FAILED \(failures)")
exit(failures.isEmpty ? 0 : 1)

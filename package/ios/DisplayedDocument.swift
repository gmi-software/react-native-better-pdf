import Foundation
import PDFKit

/// What the view shows: one document, made of one or more sources.
struct DisplayedDocument {
  /// The source's own document, or one assembled from the pages of
  /// several, which has no file of its own.
  let document: PDFDocument
  /// In source order.
  let sources: [DisplayedSource]

  /// The sources' documents shown as one: a lone source's own document, or
  /// an empty `ColorSchemeDocument` with every source's pages inserted in
  /// order. Call before anything else uses the documents; off the main
  /// thread is fine.
  init(assembling loaded: [(loaded: LoadedDocument, password: String)]) {
    var sources: [DisplayedSource] = []
    var firstPage = 0
    for item in loaded {
      let pageCount = item.loaded.document.pageCount
      let file = SourceFile(url: item.loaded.url, password: item.password, firstPage: firstPage)
      sources.append(DisplayedSource(loaded: item.loaded, file: file, pageCount: pageCount))
      firstPage += pageCount
    }
    self.sources = sources
    if let only = loaded.first, loaded.count == 1 {
      document = only.loaded.document
      return
    }
    let assembled = ColorSchemeDocument()
    for item in loaded {
      let source = item.loaded.document
      for index in 0..<source.pageCount {
        guard let page = source.page(at: index) else { continue }
        // Measured with PDFKit on a Mac: ~0.2 ms a page (186 ms for the
        // 1000-page scan), inserting ~6 µs.
        page.resolveLinkDestinations()
        assembled.insert(page, at: assembled.pageCount)
      }
    }
    document = assembled
  }

  /// The source whose file is the whole document; nil with several.
  var single: DisplayedSource? { sources.count == 1 ? sources[0] : nil }

  var isSet: Bool { single == nil }

  /// The sources' files, for reads off the main thread.
  var files: [SourceFile] { sources.map(\.file) }

  /// The file showing `page` of the document, and the page's index in it.
  func file(ofPage page: Int) -> (file: SourceFile, page: Int)? {
    guard let source = sources.last(where: { $0.file.firstPage <= page }),
          page < source.file.firstPage + source.pageCount else {
      return nil
    }
    return (source.file, page - source.file.firstPage)
  }
}

/// One source of the displayed document, kept open (and its downloaded
/// copy pinned) while it is shown: an assembled document's pages belong
/// to it.
struct DisplayedSource {
  let loaded: LoadedDocument
  let file: SourceFile
  let pageCount: Int

  func toLoadedSource() -> PdfLoadedSource {
    return PdfLoadedSource(
      pageCount: Double(pageCount),
      firstPage: Double(file.firstPage),
      filePath: loaded.filePath,
      isStale: loaded.isStale
    )
  }
}

/// A source's file as reads off the main thread open it again: a value, so
/// a read holds none of the view's PDFKit objects.
struct SourceFile: Equatable {
  let url: URL
  /// The password it was opened with, `""` for none.
  let password: String
  /// Zero-based page of the displayed document that is its first page.
  let firstPage: Int
}

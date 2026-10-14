# Limitations

For an overview of what each platform supports, see the
[capability matrix](../README.md#capability-matrix).

- **Not implemented yet**: editing annotations (forms are filled in, not
  restructured); facing pages in horizontal or paged layouts, or right to
  left.
- **Dark pages on iOS**: annotations keep their own colours (see
  [Dark pages](guides/layout-and-appearance.md#dark-pages)).
- **Forms**: JavaScript actions and calculations in a form do not run;
  signature fields cannot be signed (their places are reported); XFA forms
  are not supported. iOS writes exports of a changed form anew with PDFKit
  (field hierarchy flattened into dotted names, field order may change;
  content PDFKit cannot read is not kept; seconds for long documents, in
  the background) and keeps one selected option per list box.
  Android: filling in needs Android 15+, or 12–14 with SDK extension 13
  (elsewhere fields are read from the file, `setFormFieldValues` rejects
  and `exportDocument` copies the file); a choice's custom text cannot be
  typed by touch; a radio group that cannot be turned off cannot be
  cleared; screen readers do not reach form fields.
- **Page overlays** are not clipped to their page, and are not drawn in
  page images or printing. On iOS an overlay's drawing is scaled with the
  page, so its text can soften at high zoom (sharp at 2.5× in tests);
  Android draws it again at the new size.
- **Text selection**: iOS uses PDFKit's selection, which can span pages.
  Android draws its own: a selection stays on one page, Select all selects
  that page, copying ends the selection (as in a text field), dragging a
  handle to the edge does not scroll, and a handle dragged past the other
  end stops at the first character.
- **Outline (`getOutline`)**: on Android, encrypted documents reject (their
  titles are encrypted). iOS (PDFKit) leaves PDF 1.1 named destinations
  (`/Dest /name`) without a page.
- **Metadata (`PdfDocument.getMetadata`)**: on Android, encrypted documents
  reject, like the outline. XMP metadata is not read, only the information
  dictionary.
- **Android**: several features come from the platform renderer's newer
  API — on Android 15+, and on Android 12–14 when the device has SDK
  extension 13 (the PDF module updated through Google Play system updates;
  the library then uses `PdfRendererPreV`). "Android 15+" below includes
  those devices.
  - Annotations: the platform renderer draws highlights, sticky notes and
    free text from Android 15+, stamps from Android 17, form fields always —
    and never squares, circles, lines, polygons, ink, underline or
    strike-out (measured; see docs/api-design.md "Annotations"). iOS draws
    all of them.
  - Password-protected PDFs require Android 15+; other devices report
    `unsupported-pdf`.
  - Text selection requires Android 15+; other devices ignore long presses.
  - Search requires Android 15+; other devices report `isSupported: false`.
  - Links work on every Android version, in every form (read from the
    file). In password-protected documents they come from `PdfRenderer`
    instead: Android 15+ (or 12–14 with SDK extension 13), and of the
    internal links only GoTo actions with an XYZ destination; other links
    there behave like the rest of the page and are reported through
    `onPageTap`.
  - `PdfRenderer` renders one page at a time per *process*, so several
    visible viewers share one render pipeline.
  - Page sizes are discovered as pages are first rendered (reading them up
    front costs memory equal to the file size in `PdfRenderer`). Documents
    with mixed page sizes may re-anchor slightly the first time a page far
    from the viewport is visited.
  - Documents are parsed in-process; the platform recommends an isolated
    process for untrusted input, which this library does not provide.
- **iOS**: PDFKit does not expose cache statistics; `getStats()` reports
  `-1` for them.
- `data:`/base64 sources are intentionally unsupported: the viewer reads
  files (see [Base64 documents](guides/sources-and-loading.md#base64-documents)).
- Downloads use `GET` only, run only while the app runs, and are not
  limited in total size (see
  [Offline reading and caching](guides/offline-and-caching.md)).

## What's next

Being considered for later releases:

- Password-protected documents, search and text selection on Android
  versions without the newer renderer API, with the library's own PDF
  reading.
- Editing annotations.
- Downloads with `POST` and a body.
- Screen reader access to form fields on Android, and text selections
  across pages.

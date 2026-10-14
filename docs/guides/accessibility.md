# Accessibility

Screen readers get the document's text and position:

- **Android (TalkBack):** each page on screen is an accessibility node that
  reads "Page 3 of 12" followed by the page's text (Android 15+, or 12–14
  with SDK extension 13; older versions read the label only). The document
  is a scrollable list, so TalkBack's scroll gestures move through it (one
  page at a time with `enablePaging`).
- **iOS (VoiceOver):** PDFKit exposes the page text; the viewer adds a "Page
  3 of 12" announcement when the page changes.
- Localise the label with `pageAccessibilityLabel`, e.g.
  `"Strona {page} z {pageCount}"`.
- Images inside pages have no descriptions on either platform (PDF alt text
  is not read by the platform renderers).

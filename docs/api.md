# API reference

Every component, object and type is exported from the package root. The
[guides](../README.md#guides) show each feature in use.

## Components

| Component | Description |
| --- | --- |
| `PdfView` | The viewer |
| `PdfPageOverlay` | A React view attached to a point of a page; a child of `PdfView` |

## `PdfView` props

| Prop | Type | Default | |
| --- | --- | --- | --- |
| `source` | `PdfSource \| PdfSource[]` | — | required; a list is shown as one document, see [Several documents as one](guides/sources-and-loading.md#several-documents-as-one) |
| `password` | `string` | — | for every source without a `password` of its own; changing it re-opens the document (no re-download) |
| `initialPage` | `number` | `0` | zero-based, rounded to the nearest page, applied once per loaded document |
| `initialScale` | `number` | `1` | zoom applied once per loaded document, relative to the fitted page |
| `initialViewport` | `PdfViewport` | | position applied once per loaded document, e.g. where the reader left off; takes precedence over `initialPage` / `initialScale`; see [Reading position](guides/navigation.md#reading-position) |
| `minScale` / `maxScale` | `number` | `1` / `4` | relative to the fitted page (see `fitPolicy`) |
| `pageSpacing` | `number` | `8` | dp / pt; negative, `NaN` and infinite values are the default |
| `enableDoubleTapZoom` | `boolean` | `true` | double tap toggles zoom; `false` also delivers `onPageTap` without the ~300 ms double-tap wait |
| `scrollEnabled` | `boolean` | `true` | `false`: drags go to parent views (a surrounding `ScrollView` scrolls); zoom, taps and `goToPage` still work |
| `pageAccessibilityLabel` | `string` | `'Page {page} of {pageCount}'` | how screen readers name a page; localise it |
| `showsVerticalScrollIndicator` | `boolean` | `true` | platform scroll bar while scrolling |
| `showsHorizontalScrollIndicator` | `boolean` | `true` | shown only while zoomed wider than the view |
| `enableTextSelection` | `boolean` | `true` | long press selects text, handles adjust it, the system menu copies; `false` turns selection off (Android: 15+, or 12–14 with SDK extension 13) |
| `enableFormFilling` | `boolean` | `true` | form fields can be filled in by touch; `false` shows them as they are (taps on them are page taps); see [Forms](guides/forms.md) |
| `horizontal` | `boolean` | `false` | pages side by side |
| `enablePaging` | `boolean` | `false` | one page per swipe, along the `horizontal` axis |
| `enableRTL` | `boolean` | `false` | horizontal layouts run right to left (iOS: with `enablePaging` only) |
| `singlePage` | `boolean` | `false` | one static page (`initialPage`, switched by `goToPage`) for thumbnails; ignores touches |
| `fitPolicy` | `'width' \| 'height' \| 'page'` | `'width'`, or `'page'` with `horizontal` / `enablePaging` | how pages are scaled at zoom 1 |
| `spreadMode` | `'none' \| 'pairs' \| 'book'` | `'none'` | facing pages side by side in the vertical continuous layout; see [Facing pages](guides/layout-and-appearance.md#facing-pages) |
| `colorScheme` | `'light' \| 'dark'` (or `useColorScheme()`'s value) | `'light'` | `'dark'` shows pages with lightness inverted and hues kept; see [Dark pages](guides/layout-and-appearance.md#dark-pages) |
| `contentInset` | `{ top?, bottom? }` | none | dp / pt that pages scroll under, e.g. a translucent header; see [Translucent bars](guides/layout-and-appearance.md#translucent-bars) |
| `children` | `PdfPageOverlay` elements | | React views placed on pages, moving with them; see [Page overlays](guides/page-overlays.md) |
| `searchHighlightColor` | `ColorValue` | `'rgba(255, 214, 0, 0.4)'` | background of search matches; use a translucent colour |
| `searchCurrentHighlightColor` | `ColorValue` | `'rgba(255, 140, 0, 0.6)'` | background of the current match |
| `onLoad` | `({ pageCount, firstPageWidth, firstPageHeight, filePath?, isStale, sources }) => void` | | first page's size in PDF points, as displayed (crop box, rotation applied); `filePath`: the local file being displayed (downloaded copy, local file or extracted asset; not for `{ base64 }`, `content://` or several sources); `isStale`: an outdated stored copy shown because the server could not be reached (see [Offline reading and caching](guides/offline-and-caching.md)); `sources`: each source's `{ pageCount, firstPage, filePath?, isStale }` |
| `onLoadProgress` | `({ loadedBytes, totalBytes? }) => void` | | remote downloads only (not when a stored copy is used); at most every 100 ms plus once on completion |
| `onPageChange` | `({ page, pageCount }) => void` | | zero-based |
| `onError` | `(error: PdfError) => void` | | |
| `onLinkPress` | `({ url }) => void` | | tap on a URL link; the viewer never opens URLs itself |
| `onPageTap` | `({ page, x, y }) => void` | | tap on a page outside links; zero-based page, `x`/`y` in PDF points from the top-left of the page as displayed (crop box, rotation applied) |
| `onScaleChange` | `({ scale }) => void` | | once per settled zoom change (pinch/double-tap end, `zoomTo`); relative to the fitted page |
| `onTextSelectionChange` | `({ text }) => void` | | once per settled selection (long press, handle drag or Select all ends); `text` is `''` when cleared |
| `onViewportChange` | `({ viewport, firstVisiblePage, lastVisiblePage, isAtEnd }) => void` | | once after loading and after every settled scroll, zoom or rotation, never per frame; see [Reading position](guides/navigation.md#reading-position) |
| `onFormChange` | `({ field }) => void` | | the reader changed a form field: at once for a checkbox, radio button or choice, when editing ends for text; see [Forms](guides/forms.md) |
| `onSearchChange` | `({ query, matchCount, currentMatch?, currentPage?, isComplete, isSupported }) => void` | | search progress and the current match; see [Search](guides/search.md) |

Plus all `View` props (`style`, `testID`, …). They apply to the viewer's
box, which clips the document: `borderRadius` rounds it on both platforms
(a shadow needs a surrounding view on iOS).

A document shorter than the view starts at the top in vertical continuous
layouts; horizontal layouts centre pages across, and paged layouts centre
each page. The viewer adds no safe-area insets on either platform, like
`ScrollView`: keep it below the status bar (e.g. in a `SafeAreaView`).

## `PdfViewRef` methods

| Method | Description |
| --- | --- |
| `goToPage(page, options?)` | Scroll to a zero-based page (clamped to the document), animated unless `{ animated: false }`. Ignored before `onLoad`: the first page is `initialPage` |
| `zoomTo(scale, options?)` | Zoom relative to the fitted page, clamped to `minScale` / `maxScale`. Ignored before `onLoad`: the first zoom is `initialScale` |
| `goToViewport(viewport)` | Jump to a position from `onViewportChange`. Ignored before `onLoad`: the first position is `initialViewport` |
| `getOutline()` | The table of contents |
| `getPageSizes()` | Every page's size in PDF points |
| `renderPage(page, options)` | A PNG or JPEG file of a page |
| `print(options?)` | The system print dialog; rejects with several sources |
| `getFormFields()` / `setFormFieldValues(values)` / `exportDocument(options?)` | Read, fill in and save the form; reject with several sources |
| `search(query)` / `searchNext()` / `searchPrevious()` / `clearSearch()` | Full-text search. `search` is ignored before `onLoad` |
| `getStats()` | Render and cache statistics, for diagnostics |

`goToPage`, `zoomTo` and `goToViewport` ignore `NaN` and `±Infinity`, e.g.
`goToPage(Number(text) - 1)` while a page field holds a letter. A fractional
page is rounded to the nearest page on both platforms: `goToPage(1.6)` opens
page 2.

Calling the methods through a ref:

```tsx
import { useRef } from 'react';
import { PdfView, type PdfViewRef } from 'react-native-better-pdf';

const pdf = useRef<PdfViewRef>(null);
<PdfView ref={pdf} style={{ flex: 1 }} source={source} />;

// Later:
pdf.current?.goToPage(41);                     // zero-based, animated
pdf.current?.goToPage(0, { animated: false });
pdf.current?.zoomTo(2);                        // relative to the fitted page
pdf.current?.zoomTo(1);                        // reset
const toc = await pdf.current?.getOutline();   // [{ title, page?, children }]
const sizes = await pdf.current?.getPageSizes(); // [{ width, height }] per page, PDF points
const image = await pdf.current?.renderPage(0, { width: 600 }); // { uri, width, height }, a PNG file
const printed = await pdf.current?.print();    // { isSubmitted }, the system print dialog
const fields = await pdf.current?.getFormFields(); // [{ name, type, value… }], see Forms
await pdf.current?.setFormFieldValues({ name: 'Anna', agree: true });
const { filePath } = await pdf.current!.exportDocument(); // the filled-in PDF
pdf.current?.search('invoice');                // highlights matches, see onSearchChange
pdf.current?.searchNext();                     // wraps around; searchPrevious() too
pdf.current?.clearSearch();                    // or search('')
pdf.current?.goToViewport(saved);              // a position from onViewportChange
```

## Objects

| Object | Description |
| --- | --- |
| `PdfDocument` | `PdfDocument.open(source, options?)`: a document without a view — `pageCount`, `getMetadata()`, `getPageSizes()`, `getOutline()`, `renderPage()`, `close()` |
| `PdfCache` | Stored downloads: `download()`, `getEntry()`, `remove()`, `getByteSize()`, `clear()` |
| `PdfOpenError` | Rejection of `PdfDocument.open`, with the view's error `code` |
| `PdfDownloadError` | Rejection of `PdfCache.download`, with a `code` and `httpStatus` |

## Errors

`error.code` is stable and identical on both platforms:

`invalid-source` · `file-not-found` · `network-error` · `http-error`
(`error.httpStatus`) · `invalid-pdf` · `password-required` ·
`invalid-password` · `unsupported-pdf` · `page-render-failed` (`error.page`) ·
`out-of-memory`

`error.message` is human-readable and may change; `error.nativeDescription`
carries the platform error for logging. Load failures carry
`error.sourceIndex`, the source that could not be loaded (`0` for a single
source; unset when the set failed as a whole, as when Android runs out of
memory laying out many sources' pages).

## Types

| Type | Description |
| --- | --- |
| `PdfViewProps` | Props for `PdfView` |
| `PdfPageOverlayProps` | Props for `PdfPageOverlay` |
| `PdfSource` / `PdfUriSource` / `PdfFileSource` / `PdfBase64Source` | Where a document comes from: a URI (with cache options for downloads), a file path, a base64 string or a bundled asset; all but the asset can carry their own `password` |
| `PdfLoadedSource` | One source as `onLoad` reports it: `{ pageCount, firstPage, filePath?, isStale }` |
| `PdfError` / `PdfErrorCode` | Errors and their stable codes |
| `PdfViewport` | A reading position: `{ page, x, y, scale }` |
| `PdfFormField` | A form field, discriminated by `type` (`PdfTextFormField`, `PdfCheckboxFormField`, …) |
| `PdfOutlineItem` | An outline entry: `{ title, page?, children }` |
| `PdfPageSize` | `{ width, height }` in PDF points |
| `PdfPageImage` | A rendered page: `{ uri, width, height }` |
| `PdfFitPolicy` / `PdfSpreadMode` / `PdfColorScheme` | Layout options |
| `PdfCacheEntry` / `PdfCacheMode` | Stored downloads |
| `PdfDocumentMetadata` | Title, author, dates and the rest of the information dictionary |
| `Pdf…Event` | Event payloads (`PdfLoadEvent`, `PdfPageTapEvent`, `PdfSearchChangeEvent`, …) |

Every type is exported from the package root; see
[docs/api-design.md](api-design.md) for the decisions behind them.

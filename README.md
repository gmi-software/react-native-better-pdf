<img width="2188" height="718" alt="react-native-better-pdf" src="./assets/react-native-better-pdf.png" />

<div align="center">

A native, virtualized PDF viewer for React Native, built on [Nitro Views](https://nitro.margelo.com/docs/guides/view-components) and the New Architecture.

[![npm version](https://img.shields.io/npm/v/react-native-better-pdf.svg)](https://www.npmjs.com/package/react-native-better-pdf)
[![CI](https://github.com/gmi-software/react-native-better-pdf/actions/workflows/ci.yml/badge.svg)](https://github.com/gmi-software/react-native-better-pdf/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
![TypeScript](https://img.shields.io/badge/TypeScript-Ready-blue.svg)
![Expo Development Build](https://img.shields.io/badge/Expo-development%20build-000020.svg)

PDFKit on iOS, the platform `PdfRenderer` on Android: no bundled PDFium, no WebView, no JS in the scroll, zoom and render path.

[Features](#features) • [Installation](#installation) • [Quick start](#quick-start) • [Capability matrix](#capability-matrix) • [Documentation](#documentation) • [Public API](#public-api)

**Full documentation** lives in [`docs/`](docs). Start with [Migrating from react-native-pdf](docs/migrating-from-react-native-pdf.md), [Architecture](ARCHITECTURE.md) and [API design](docs/api-design.md).

</div>

---

## Table of contents

- [Features](#features)
- [Compared with react-native-pdf](#compared-with-react-native-pdf)
- [Requirements](#requirements)
- [Capability matrix](#capability-matrix)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Usage](#usage)
- [Reading position](#reading-position)
- [Offline reading and caching](#offline-reading-and-caching)
- [Page images](#page-images)
- [Documents without a view](#documents-without-a-view)
- [Facing pages](#facing-pages)
- [Translucent bars](#translucent-bars)
- [Dark pages](#dark-pages)
- [Page overlays](#page-overlays)
- [Forms](#forms)
- [Printing](#printing)
- [Search](#search)
- [Accessibility](#accessibility)
- [Public API](#public-api)
- [Limitations](#limitations)
- [Performance philosophy](#performance-philosophy)
- [Example app](#example-app)
- [Documentation](#documentation)
- [Common problems](#common-problems)
- [Development](#development)
- [What's next](#whats-next)

## Features

- **Performance first** - Scrolling, zooming, rendering, caching and prefetching never touch the JS thread. JS sends configuration and commands and receives settled events (a page change, a tap, a finished zoom), never per-frame ones.
- **New Architecture native** - Built on Nitro Views for Fabric.
- **The OS's own PDF engines** - PDFKit on iOS, `PdfRenderer` on Android with a purpose-built native viewer (single canvas view, byte-budgeted page cache, zoom tiles, prefetch). No bundled PDFium, no WebView, no `react-native-blob-util`.
- **Any source** - Local files, bundled assets (`require()`), `content://` URIs and remote documents with headers, downloaded natively.
- **Offline reading** - Downloads kept for the session or across launches, revalidated with `ETag` / `Last-Modified` and shown when the server cannot be reached; `PdfCache` downloads ahead of time with progress and cancellation.
- **Layouts** - Vertical continuous, horizontal, paged, single page and right to left; fit to width, height or page; facing pages for tablets; `contentInset` for translucent bars; rounded cards.
- **Reading position** - Settled viewport events, restore on open or on demand, end-of-document detection.
- **Links and taps** - URL links reported to JS, links to other pages followed natively (every link form, on every Android version), page taps in PDF points.
- **Search** - Native full-text search with highlighted matches and next/previous navigation.
- **Text selection** - The platform's handles and Copy / Select all menu.
- **Forms** - AcroForm fields filled in by touch or from code, read back by name, exported as a filled-in PDF.
- **Page overlays** - React views (signatures, markers, form controls) attached to points of a page, moved natively while it scrolls and zooms.
- **Dark pages** - Lightness inverted, hues kept, for reading at night.
- **Outline, page sizes, page images, printing** - The table of contents, every page's size, PNG / JPEG page images rendered off the main thread, and the system print dialog.
- **Documents without a view** - `PdfDocument` reads page count, metadata, page sizes, the outline and page images, e.g. for document lists with covers.
- **Password-protected documents** - With stable `password-required` / `invalid-password` errors.
- **Accessibility** - VoiceOver and TalkBack read each page's text and position.
- **Typed errors** - Stable error codes, identical on both platforms.
- **Memory bounded by the viewport** - Page virtualization, render cache, prefetch and cancellation on Android; pixels released under memory pressure and while a screen is hidden.
- **Expo friendly** - Works in development builds through autolinking; no config plugin.

## Compared with react-native-pdf

Measured on release builds of the same example app, which contains both
libraries; method and full results in [docs/benchmarks.md](docs/benchmarks.md).

**Size** (Android, one ABI, 2026-10-07):

| | react-native-better-pdf | react-native-pdf 7.0.5 |
| --- | ---: | ---: |
| Native code and dex | **≈ 1.9 MB** | ≈ 5.3 MB (bundled PDFium, AndroidPdfViewer, Gson, react-native-blob-util) |

Plus `react-native-nitro-modules` (≈ 1 MB), shared by every Nitro module in
an app.

**Speed and memory** (Android emulator, release builds, medians of 3 runs,
2026-10-07):

| Document | Load: better-pdf / rn-pdf | Load over HTTP, download included | Peak memory (PSS) | CPU while scrolling |
| --- | --- | --- | --- | --- |
| 10 pages, text | 55 / 82 ms | 72 / 113 ms | 118 / 90 MB (rn-pdf lower) | 15 / 22% |
| 100 pages, images | 64 / 198 ms | 128 / 262 ms | 130 / 131 MB | 24 / 50% |
| 500 pages, scans | 64 / 482 ms | 585 / 965 ms | 138 / 551 MB | 28 / 113% |
| 1000 pages, scans | 91 / 746 ms | — | 144 / 897 MB | 27 / 114% |

Page jumps reach the page-change event in 0 ms (react-native-pdf: 34–50
ms). On iOS both libraries render with PDFKit, so the numbers are close:
better-pdf loads as fast or faster in most runs and peaks at the same or
lower memory.

**What react-native-pdf users report**, and how this library handles it:

| Reported in react-native-pdf | react-native-better-pdf |
| --- | --- |
| Android 16 KB page size ([#960](https://github.com/wonday/react-native-pdf/issues/960) and others) | Native library 16 KB aligned |
| New Architecture / Fabric ([#878](https://github.com/wonday/react-native-pdf/issues/878) and others) | Built for Fabric on Nitro Views |
| `react-native-blob-util` crashes and setup ([#796](https://github.com/wonday/react-native-pdf/issues/796) and others) | Not used: downloads are native |
| Android "Already closed" crashes ([#830](https://github.com/wonday/react-native-pdf/issues/830) and others) | Closing a document while it renders, searches or exports is handled and stress-tested |
| Link taps on Android ([#983](https://github.com/wonday/react-native-pdf/issues/983) and others) | Every link form on every Android version |
| Search ([#555](https://github.com/wonday/react-native-pdf/issues/555) and others) | Built in, with highlights (Android: see the [capability matrix](#capability-matrix)) |
| Viewport events, restoring the position ([#492](https://github.com/wonday/react-native-pdf/issues/492) and others) | `onViewportChange`, `initialViewport`, `goToViewport` |
| Filling in and saving forms ([#748](https://github.com/wonday/react-native-pdf/issues/748) and others) | Fill, read and export (Android: see the [capability matrix](#capability-matrix)) |
| Cache control and offline reading ([#952](https://github.com/wonday/react-native-pdf/issues/952) and others) | Cache options on the source, `PdfCache` |

The full list, with reaction counts, is in
[docs/community-requests.md](docs/community-requests.md); a prop-by-prop
migration guide is in
[docs/migrating-from-react-native-pdf.md](docs/migrating-from-react-native-pdf.md).

## Requirements

| Requirement | Version / note |
| --- | --- |
| React Native | `0.81+` |
| React Native architecture | New Architecture (Fabric); Nitro Views do not support the legacy renderer |
| Nitro Modules | `react-native-nitro-modules >=0.37.0` |
| iOS | `15.1+` |
| Android | `minSdkVersion 24+`; some features need a newer Android, see the [capability matrix](#capability-matrix) |
| Expo | SDK 54+ with a development build (verified with SDK 57); Expo Go is not supported |

Developed and tested with React Native 0.86.3 / Expo SDK 57, Xcode 27,
Kotlin 2.1, AGP 8.12 / Gradle 9.3, Node 24.

Not supported:

- Expo Go: it cannot load third-party native code.
- `data:` / base64 sources, by design: write the data to a file and open the
  file, see [Base64 documents](#base64-documents).
- Editing annotations; see [What's next](#whats-next).

## Capability matrix

Several Android features come from the platform renderer's newer API: on
Android 15+, and on Android 12–14 when the device has SDK extension 13 (the
PDF module updated through Google Play system updates; the library then uses
`PdfRendererPreV`).

| Capability | iOS | Android 15+, or 12–14 with SDK extension 13 | Other Android versions |
| --- | --- | --- | --- |
| Viewing, zoom, layouts, offline, overlays, dark pages | Supported | Supported | Supported |
| Outline, page sizes, page images, printing, `PdfDocument` | Supported | Supported (password-protected documents: no outline or metadata) | Supported |
| Links and page taps | Supported | Supported (password-protected documents: URL links and GoTo actions with an XYZ destination) | Supported |
| Password-protected documents | Supported | Supported | Unsupported: `unsupported-pdf` |
| Search | Supported | Supported | Unsupported: `isSupported: false` |
| Text selection | Supported | Supported | Unsupported: long presses are ignored |
| Forms: fill in, set from code, export | Supported | Supported | Read only: `getFormFields` works, `setFormFieldValues` rejects, `exportDocument` copies the file |
| Annotations drawn | All | Highlights, notes, free text and form fields; stamps from Android 17; no shapes, ink, underline or strike-out | Form fields |
| Screen readers read page text | Supported | Supported | The page label only |

Details per feature are in its section below and in
[Limitations](#limitations).

## Installation

```bash
bun add react-native-better-pdf react-native-nitro-modules
```

```bash
npm install react-native-better-pdf react-native-nitro-modules
```

```bash
yarn add react-native-better-pdf react-native-nitro-modules
```

```bash
pnpm add react-native-better-pdf react-native-nitro-modules
```

Bare React Native: `cd ios && pod install`, then rebuild the app.

### Expo

No config plugin is needed. Rebuild your development build:

```bash
npx expo prebuild
npx expo run:ios   # or run:android
```

## Quick start

```tsx
import { PdfView } from 'react-native-better-pdf';

export function Reader() {
  return (
    <PdfView
      style={{ flex: 1 }}
      source={{ uri: 'https://example.com/report.pdf' }}
      onLoad={({ pageCount }) => console.log('pages', pageCount)}
      onPageChange={({ page, pageCount }) => console.log(page + 1, '/', pageCount)}
      onError={(error) => console.warn(error.code, error.message)}
    />
  );
}
```

### Links and taps

```tsx
<PdfView
  source={source}
  onLinkPress={({ url }) => {
    if (url.startsWith('https://')) Linking.openURL(url);
  }}
  onPageTap={({ page, x, y }) => toggleToolbar()}
/>
```

URL links are reported, never opened by the viewer. Links to other pages
are followed natively. `onPageTap` reports taps outside links in PDF points
from the top-left of the page as displayed.

### Imperative API

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

## Usage

### Sources

```ts
type PdfSource =
  | PdfUriSource        // { uri, headers? } — https://, http://, file://, content:// (Android)
  | { filePath: string } // absolute path
  | number;              // require('./file.pdf')
```

Remote files are downloaded natively and reused for the rest of the app
session — or kept for offline reading; see
[Offline reading and caching](#offline-reading-and-caching). An inline
`source={{ uri }}` object is fine: the source is compared by value, so
re-renders never reload the document.

### Base64 documents

`source` does not take base64 or `data:` URIs: the viewer reads files
(Android's `PdfRenderer` needs one), and a document-sized string in a prop
would be processed again on the JS thread on every render. Write the data to
a file once and open the file:

```tsx
// Expo: expo-file-system
import { File, Paths } from 'expo-file-system';

const file = new File(Paths.cache, `${id}.pdf`);
file.write(base64, { encoding: 'base64' });

<PdfView source={{ uri: file.uri }} />;
```

```tsx
// Bare React Native: @dr.pogodin/react-native-fs (the react-native-fs API)
import { CachesDirectoryPath, writeFile } from '@dr.pogodin/react-native-fs';

const path = `${CachesDirectoryPath}/${id}.pdf`;
await writeFile(path, base64, 'base64');

<PdfView source={{ filePath: path }} />;
```

Give each document its own file name: a source with the same path does not
reload the view, even after the file is rewritten.

When the base64 comes from your own API (a PDF wrapped in JSON), return the
PDF itself and pass its URL with `headers` instead: it is then downloaded
natively, with progress, caching and offline reading (see
[Offline reading and caching](#offline-reading-and-caching)), and is a
quarter smaller than its base64 form.

### Props

| Prop | Type | Default | |
| --- | --- | --- | --- |
| `source` | `PdfSource` | — | required |
| `password` | `string` | — | changing it re-opens the document (no re-download) |
| `initialPage` | `number` | `0` | zero-based, applied once per loaded document |
| `initialScale` | `number` | `1` | zoom applied once per loaded document, relative to the fitted page |
| `initialViewport` | `PdfViewport` | | position applied once per loaded document, e.g. where the reader left off; takes precedence over `initialPage` / `initialScale`; see [Reading position](#reading-position) |
| `minScale` / `maxScale` | `number` | `1` / `4` | relative to the fitted page (see `fitPolicy`) |
| `pageSpacing` | `number` | `8` | dp / pt |
| `enableDoubleTapZoom` | `boolean` | `true` | double tap toggles zoom; `false` also delivers `onPageTap` without the ~300 ms double-tap wait |
| `scrollEnabled` | `boolean` | `true` | `false`: drags go to parent views (a surrounding `ScrollView` scrolls); zoom, taps and `goToPage` still work |
| `pageAccessibilityLabel` | `string` | `'Page {page} of {pageCount}'` | how screen readers name a page; localise it |
| `showsVerticalScrollIndicator` | `boolean` | `true` | platform scroll bar while scrolling |
| `showsHorizontalScrollIndicator` | `boolean` | `true` | shown only while zoomed wider than the view |
| `enableTextSelection` | `boolean` | `true` | long press selects text, handles adjust it, the system menu copies; `false` turns selection off (Android: 15+, or 12–14 with SDK extension 13) |
| `enableFormFilling` | `boolean` | `true` | form fields can be filled in by touch; `false` shows them as they are (taps on them are page taps); see [Forms](#forms) |
| `horizontal` | `boolean` | `false` | pages side by side |
| `enablePaging` | `boolean` | `false` | one page per swipe, along the `horizontal` axis |
| `enableRTL` | `boolean` | `false` | horizontal layouts run right to left (iOS: with `enablePaging` only) |
| `singlePage` | `boolean` | `false` | one static page (`initialPage`, switched by `goToPage`) for thumbnails; ignores touches |
| `fitPolicy` | `'width' \| 'height' \| 'page'` | `'width'`, or `'page'` with `horizontal` / `enablePaging` | how pages are scaled at zoom 1 |
| `spreadMode` | `'none' \| 'pairs' \| 'book'` | `'none'` | facing pages side by side in the vertical continuous layout; see [Facing pages](#facing-pages) |
| `colorScheme` | `'light' \| 'dark'` (or `useColorScheme()`'s value) | `'light'` | `'dark'` shows pages with lightness inverted and hues kept; see [Dark pages](#dark-pages) |
| `contentInset` | `{ top?, bottom? }` | none | dp / pt that pages scroll under, e.g. a translucent header; see [Translucent bars](#translucent-bars) |
| `children` | `PdfPageOverlay` elements | | React views placed on pages, moving with them; see [Page overlays](#page-overlays) |
| `searchHighlightColor` | `ColorValue` | `'rgba(255, 214, 0, 0.4)'` | background of search matches; use a translucent colour |
| `searchCurrentHighlightColor` | `ColorValue` | `'rgba(255, 140, 0, 0.6)'` | background of the current match |
| `onLoad` | `({ pageCount, firstPageWidth, firstPageHeight, filePath?, isStale }) => void` | | first page's size in PDF points, as displayed (crop box, rotation applied); `filePath`: the local file being displayed (downloaded copy, local file or extracted asset; not for `content://`); `isStale`: an outdated stored copy shown because the server could not be reached (see [Offline reading and caching](#offline-reading-and-caching)) |
| `onLoadProgress` | `({ loadedBytes, totalBytes? }) => void` | | remote downloads only (not when a stored copy is used); at most every 100 ms plus once on completion |
| `onPageChange` | `({ page, pageCount }) => void` | | zero-based |
| `onError` | `(error: PdfError) => void` | | |
| `onLinkPress` | `({ url }) => void` | | tap on a URL link; the viewer never opens URLs itself |
| `onPageTap` | `({ page, x, y }) => void` | | tap on a page outside links; zero-based page, `x`/`y` in PDF points from the top-left of the page as displayed (crop box, rotation applied) |
| `onScaleChange` | `({ scale }) => void` | | once per settled zoom change (pinch/double-tap end, `zoomTo`); relative to the fitted page |
| `onTextSelectionChange` | `({ text }) => void` | | once per settled selection (long press, handle drag or Select all ends); `text` is `''` when cleared |
| `onViewportChange` | `({ viewport, firstVisiblePage, lastVisiblePage, isAtEnd }) => void` | | once after loading and after every settled scroll, zoom or rotation, never per frame; see [Reading position](#reading-position) |
| `onFormChange` | `({ field }) => void` | | the reader changed a form field: at once for a checkbox, radio button or choice, when editing ends for text; see [Forms](#forms) |
| `onSearchChange` | `({ query, matchCount, currentMatch?, currentPage?, isComplete, isSupported }) => void` | | search progress and the current match; see [Search](#search) |

Plus all `View` props (`style`, `testID`, …). They apply to the viewer's
box, which clips the document: `borderRadius` rounds it on both platforms
(a shadow needs a surrounding view on iOS).

A document shorter than the view starts at the top in vertical continuous
layouts; horizontal layouts centre pages across, and paged layouts centre
each page. The viewer adds no safe-area insets on either platform, like
`ScrollView`: keep it below the status bar (e.g. in a `SafeAreaView`).

### Errors

`error.code` is stable and identical on both platforms:

`invalid-source` · `file-not-found` · `network-error` · `http-error`
(`error.httpStatus`) · `invalid-pdf` · `password-required` ·
`invalid-password` · `unsupported-pdf` · `page-render-failed` (`error.page`) ·
`out-of-memory`

`error.message` is human-readable and may change; `error.nativeDescription`
carries the platform error for logging.

### Loading state

There is no built-in spinner. Show your own until `onLoad` or `onError`;
for remote documents `onLoadProgress` reports the download:

```tsx
onLoadProgress={({ loadedBytes, totalBytes }) =>
  setProgress(totalBytes === undefined ? undefined : loadedBytes / totalBytes)
}
```

`totalBytes` is `undefined` when the server does not report a size.

## Reading position

Reopen a document where the reader left off, and know what they have read:

```tsx
const saved = storage.get(documentId); // a PdfViewport, or undefined

<PdfView
  source={source}
  initialViewport={saved}
  onViewportChange={({ viewport, isAtEnd }) => {
    storage.set(documentId, viewport);
    if (isAtEnd) markAsRead(documentId);
  }}
/>;
```

- A `PdfViewport` is `{ page, x, y, scale }`: the point of `page` (in PDF
  points from its top-left corner) at the view's top-left corner, and the
  zoom. PDF points do not depend on the screen, so a position survives
  rotation, another device or another layout mode. `x` / `y` are negative
  when the corner is in the margin or gap before the page.
- `onViewportChange` fires once after loading and then whenever the view
  stops moving (scroll, fling, zoom, page turn, rotation, `goToPage`…),
  only when the position changed. `firstVisiblePage` / `lastVisiblePage`
  give the pages on screen; `isAtEnd` is true while the end of the last
  page is on screen.
- `ref.goToViewport(viewport)` jumps back to a position without animation,
  e.g. for "back to where I was" after following a table-of-contents entry.
- Paged layouts (`enablePaging`, `singlePage`) restore the page and zoom;
  on iOS the position within a zoomed page is not restored.

## Offline reading and caching

Remote documents are downloaded natively and stored. The source says for
how long, and how often the server is asked for a newer version:

```tsx
const source: PdfUriSource = {
  uri: `https://example.com/documents/${id}.pdf`,
  headers: { Authorization: `Bearer ${token}` },
  cache: 'persistent',        // keep across launches (default 'session')
  cacheKey: `document-${id}`, // the token changes: identify the copy by id
  maxAgeMs: 0,                // ask the server on every open (default: never)
};

<PdfView source={source} onLoad={({ isStale }) => setOfflineBanner(isStale)} />;
```

- **`cache`**: `'session'` keeps the download until the app exits; opening
  the document again does not download it. `'persistent'` keeps it across
  launches where the OS does not purge it and backups do not include it,
  until you remove it.
- **`maxAgeMs`**: once a copy is older, opening the document asks the
  server whether it changed (`If-None-Match` / `If-Modified-Since`). An
  unchanged document is not downloaded again; a changed one replaces the
  copy.
- **Offline**: when the server cannot be reached (no connection, no answer
  within 10 seconds, or a 5xx response), the view shows the stored copy and
  `onLoad` reports `isStale: true`. Without a copy it fails with
  `network-error`. A 4xx response is an error even with a copy.
- **`cacheKey`** identifies the copy instead of `uri` and `headers`. Set it
  when they change between opens — signed URLs, rotating tokens — or the
  copy is not found again. Sources with the same `cacheKey` are the same
  document: a new URL or token does not reload the view.
- A response that is not a PDF (a login page, a captive portal) fails with
  `invalid-pdf` and is never stored.

`PdfCache` manages the copies without a view, with the same source object:

```ts
import { PdfCache, PdfDownloadError } from 'react-native-better-pdf';

// "Download for offline reading", with progress and cancellation.
const controller = new AbortController();
try {
  const entry = await PdfCache.download(source, {
    onProgress: ({ loadedBytes, totalBytes }) => setProgress({ loadedBytes, totalBytes }),
    signal: controller.signal,
  }); // { filePath, byteSize, cache, validatedAtMs }
} catch (error) {
  if (error instanceof PdfDownloadError && error.httpStatus === 401) await refreshToken();
}

await PdfCache.getEntry(source); // the stored copy, or undefined; no network
await PdfCache.remove(source);   // true when there was a copy
await PdfCache.getByteSize();    // all stored copies, in bytes
await PdfCache.clear();          // e.g. on sign-out
```

- `download` resolves at once when the stored copy is fresh enough, and
  joins a download of the same copy already in progress (e.g. by a
  `PdfView`): the file is downloaded once. Unlike the view, it never falls
  back to an old copy — it rejects (`PdfDownloadError` with a `code`, or an
  `AbortError` when cancelled) and the old copy stays.
- Cancelling stops the download unless a `PdfView` is waiting for it too.
- `remove` and `clear` do not disturb documents on screen and do not stop
  downloads in progress (those still store their result). Page images from
  `renderPage` are not affected.
- Session copies from the previous launch are deleted on first use.
- There is no list of stored copies (it would mean keeping URLs and
  headers on disk) and no size limit: keep track of what you download and
  remove what is no longer needed. Downloads run while the app runs; there
  are no background transfers.

## Page images

```tsx
const image = await pdf.current?.renderPage(page, {
  width: 160 * PixelRatio.get(), // pixels; the height follows the page
  format: 'jpeg',                // default 'png'
  quality: 0.8,                  // JPEG only, default 0.9
});
<Image source={{ uri: image.uri }} style={{ width: 160, aspectRatio: image.width / image.height }} />;
```

- The page as displayed (crop box, rotation), on white, with the
  annotations the viewer draws. `width` is 1–4096 pixels, and an image is
  at most 4096 × 4096 pixels' worth (a very tall page needs a smaller
  width).
- Rendered off the main thread on a separate renderer, so the view keeps
  scrolling and its caches are untouched.
- The file is written to the app's cache directory (`BetterPdf/renders`)
  and is yours: move it to keep it, delete it when done. The OS may clear
  cache files when storage is low.
- For thumbnails that stay on screen, a `singlePage` `PdfView` (see the
  example's thumbnail strip) needs no files; `renderPage` is for images
  you pass elsewhere — `<Image>` lists, sharing, uploads.
- Without a view, `PdfDocument` renders the same images (next section).

## Documents without a view

`PdfDocument` opens a document without showing it — for a document list
with titles, page counts and covers, or to check a password before opening
the viewer:

```ts
import { PdfDocument, PdfOpenError } from 'react-native-better-pdf';

try {
  const document = await PdfDocument.open(source, { password });
  try {
    document.pageCount;                          // 12
    const { title, author, createdAtMs } = await document.getMetadata();
    const cover = await document.renderPage(0, { width: 300 });
    const sizes = await document.getPageSizes(); // as in the view's ref
    const toc = await document.getOutline();     // as in the view's ref
  } finally {
    document.close();
  }
} catch (error) {
  if (error instanceof PdfOpenError && error.code === 'password-required') askForPassword();
}
```

- `open` takes every source `PdfView` takes and opens it the same way:
  remote documents are downloaded (or taken from the stored copy, see
  [Offline reading and caching](#offline-reading-and-caching)), with
  `onProgress` and an `AbortSignal` as options. It fails with a
  `PdfOpenError` whose `code` is one of the view's error codes. Offline, a
  remote document opens from its outdated copy and `isStale` is `true`.
- `getMetadata()` reads the document information dictionary: `title`,
  `author`, `subject`, `keywords`, `creator`, `producer`, and `createdAtMs`
  / `modifiedAtMs` (milliseconds since the epoch; `new Date(createdAtMs)`).
  Missing or empty entries are `undefined`.
- `getPageSizes`, `getOutline` and `renderPage` work like the view's ref
  methods. Calls run one after another, so requesting twenty thumbnails at
  once renders them one at a time.
- **Call `close()`** when done: an open document keeps its file open until
  then (or until the JS garbage collector releases it, which can take
  long). Calls after `close()` reject.

## Facing pages

```tsx
const { width, height } = useWindowDimensions();
<PdfView source={source} spreadMode={width > height ? 'book' : 'none'} />;
```

- `'pairs'` shows pages 1–2, 3–4, … side by side; `'book'` shows the first
  page alone, like a cover, then 2–3, 4–5, …, like a magazine.
- Each page fits half the view's width; a page without a partner (the
  cover, an odd last page) is centred. Rows scroll vertically.
- `onPageChange` reports the first page of the spread under the viewport
  centre (the last page at the end). Switching `spreadMode` or the layout
  keeps the page the reader was on.
- Vertical continuous layouts only: ignored with `horizontal`,
  `enablePaging` and `singlePage` (iOS's PDFKit lays out facing pages only
  there). Facing pages run left to right.

## Translucent bars

```tsx
<View style={{ flex: 1 }}>
  <PdfView
    source={source}
    style={{ flex: 1 }}
    contentInset={{ top: headerHeight, bottom: toolbarHeight }}
  />
  <View style={[StyleSheet.absoluteFill, { bottom: undefined, height: headerHeight, backgroundColor: 'rgba(255, 255, 255, 0.85)' }]} />
</View>
```

- Pages scroll under the bars, but the first page starts below `top` and
  the document ends above `bottom`, as with a `ScrollView`'s
  `contentInset`. Values are dp / pt and do not scale with zoom.
- Everything that names a position refers to the area between the bars:
  `goToPage` puts the page's top below the header, `initialViewport`,
  `goToViewport` and `onViewportChange` use its top-left corner, the
  current page is the one at its centre, `isAtEnd` means the last page's
  bottom is above the toolbar, search results scroll into it, and
  `fitPolicy` `'height'` / `'page'` fit pages between the bars.
- Vertical continuous layouts only (facing pages included); horizontal,
  paged and single-page layouts ignore it, like `spreadMode`.

## Dark pages

```tsx
const scheme = useColorScheme();
<PdfView
  source={source}
  colorScheme={scheme} // 'dark' turns pages dark; anything else keeps them
  style={{ flex: 1, backgroundColor: scheme === 'dark' ? '#27272A' : '#E4E4E7' }}
/>;
```

- Lightness inverted, hues kept: white paper turns black, black text
  white, and a red heading stays red (lighter). Photos are inverted too.
- The area around pages is the view's `backgroundColor`; set a dark one.
- Search and selection highlights keep their colours. Page images
  (`renderPage`) and printing keep the document's colours.
- Annotations: Android darkens whatever its renderer draws. On iOS,
  PDFKit draws annotations apart from the page, so they keep their own
  colours; highlights, underlines and strike-outs are lightened onto the
  dark page instead of disappearing.

## Page overlays

```tsx
import { PdfPageOverlay, PdfView } from 'react-native-better-pdf';

<PdfView source={source} style={{ flex: 1 }}>
  {fields.map((field) => (
    <PdfPageOverlay
      key={field.id}
      page={field.page} // zero-based
      x={field.x} // PDF points from the page's top-left,
      y={field.y} // as onPageTap reports them
      width={180}
      height={40}
    >
      <Pressable style={{ flex: 1 }} onPress={() => sign(field)}>
        <Text>Sign here</Text>
      </Pressable>
    </PdfPageOverlay>
  ))}
</PdfView>;
```

- Coordinates are those of `onPageTap`: PDF points from the top-left
  corner of the page as displayed (crop box, rotation applied), so an
  overlay can go where the reader tapped.
- The overlay is laid out at `width` × `height` dp / pt and scaled with
  the page, as if printed on it. `scalesWithZoom={false}` keeps its size
  (a pin, a badge), its top-left corner on the point.
- Native code moves overlays in the same frame as their pages — scrolling,
  zooming, paging, resizing — without JS. An overlay is hidden while its
  page is off screen (also from screen readers) and while no document is
  shown.
- Between overlays, touches reach the document.
- `style` cannot move or resize an overlay, and a `transform` on it is
  overwritten; transform a view inside it instead.
- Meant for tens of overlays: each is moved every frame the document
  moves (23 overlays: ~0.1 ms per frame on iOS, ~0.2 ms on Android, debug
  builds).

## Forms

```tsx
<PdfView
  ref={pdf}
  source={source}
  onFormChange={({ field }) => console.log(field.name, field)}
/>;

const fields = await pdf.current!.getFormFields();
// [{ name: 'name', type: 'text', value: '', isRequired: true, widgets: [{ page: 0, x, y, width, height }] },
//  { name: 'agree', type: 'checkbox', isChecked: false, … },
//  { name: 'plan', type: 'radio', value: 'basic', options: ['basic', 'pro'], … }, …]

await pdf.current!.setFormFieldValues({
  name: 'Anna', // text, or a combo box's option
  agree: true, // checkbox
  plan: 'pro', // radio: the option
  colours: ['Blue'], // list box: the options
  'address.city': 'Kraków', // fully qualified names
});
const { filePath } = await pdf.current!.exportDocument(); // share or upload it
```

- Text fields, checkboxes, radio buttons, combo and list boxes are filled
  in by touch: iOS with PDFKit's own editing, Android with a text field
  over the form field (kept above the keyboard) and a list to pick
  choices from. `enableFormFilling={false}` leaves them as they are.
- `onFormChange` reports the reader's changes only — at once for a toggle
  or choice, when editing ends for text, never per keystroke — not
  `setFormFieldValues`.
- `getFormFields()` returns every field with its current value, typed by
  `type` (`PdfFormField`); `widgets` are where it is shown, in the same
  coordinates as `onPageTap` — e.g. to place a `PdfPageOverlay` on a
  signature field. Choices are their displayed labels.
- `setFormFieldValues()` sets fields by name, all or none: it rejects,
  changing nothing, for an unknown or read-only field or a value its type
  does not take.
- `exportDocument()` writes the document as shown to a new file (the
  cache directory by default, or `{ filePath }`), keeping its password
  protection, off the main thread; the source file is never changed.
  Printing and `renderPage` show the form as filled in too.
- `getFormFields`, `setFormFieldValues`, `exportDocument`, `print` and
  `renderPage` first end text editing, so what the reader is typing counts.
- Edits live with the open document: reopening the source shows it as
  stored. Export to keep them.

## Printing

```tsx
const result = await pdf.current?.print({ jobName: 'Invoice 7' });
if (result?.isSubmitted) showToast('Sent to the printer');
```

- Opens the system print dialog for the document as loaded and resolves
  when it closes: `isSubmitted` is `true` when the user printed or saved
  as PDF, `false` when they closed the dialog.
- Prints password-protected documents too: the print system opens files
  without a password, so it gets an unlocked copy that never touches disk
  (in memory on iOS; written straight to the print spooler on Android).
  Works for `content://` sources, which have no file path.
- `jobName` names the job in the print queue and the file when saving as
  PDF; by default the source's file name, or `'Document'`.
- Rejects before `onLoad`, while the view's print dialog is open, when the
  app is not in the foreground, and on iOS when the device cannot print or
  the document's permissions forbid printing (Android has no API to read
  them and prints anyway). On iPad the dialog opens as a popover from the
  view.
- Printing a file the app already has needs no viewer: `onLoad`'s
  `filePath` works with `expo-print` or `react-native-print`. This method
  adds what they cannot: protected documents, `content://` sources, and the
  dialog anchored to the viewer on iPad.

## Search

```tsx
const [search, setSearch] = useState<PdfSearchChangeEvent>();

<PdfView ref={pdf} source={source} onSearchChange={setSearch} />;

pdf.current?.search(query);   // new query: restarts; '' clears
pdf.current?.searchNext();    // or searchPrevious(); both wrap around

// "3 / 17", or "3 / 17…" while the rest of the document is searched
const label = search?.isSupported === false
  ? 'Search unavailable'
  : `${(search?.currentMatch ?? -1) + 1} / ${search?.matchCount ?? 0}${search?.isComplete ? '' : '…'}`;
```

- Search is native and runs in the background: matches are highlighted as
  pages are searched, starting at the current page, and the first match at
  or after it becomes current and is scrolled into view. Case-insensitive.
- `onSearchChange` fires at most 10 times per second while pages are
  searched, and immediately for the first match, `searchNext`/`searchPrevious`,
  completion and clearing. `currentMatch` counts matches in document order.
- Matches are highlighted in `searchHighlightColor` (translucent yellow)
  and the current one in `searchCurrentHighlightColor` (orange). Any React
  Native colour works except `PlatformColor` / `DynamicColorIOS`, which
  fall back to the defaults.
- Loading another document ends the search (with an event whose `query` is
  `''`).
- Android searches with the platform renderer's text API (Android 15+, or
  12–14 with SDK extension 13); elsewhere the search ends at once with
  `isSupported: false`.

## Accessibility

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

## Public API

### Components

| Component | Description |
| --- | --- |
| `PdfView` | The viewer |
| `PdfPageOverlay` | A React view attached to a point of a page; a child of `PdfView` |

### Objects

| Object | Description |
| --- | --- |
| `PdfDocument` | `PdfDocument.open(source, options?)`: a document without a view — `pageCount`, `getMetadata()`, `getPageSizes()`, `getOutline()`, `renderPage()`, `close()` |
| `PdfCache` | Stored downloads: `download()`, `getEntry()`, `remove()`, `getByteSize()`, `clear()` |
| `PdfOpenError` | Rejection of `PdfDocument.open`, with the view's error `code` |
| `PdfDownloadError` | Rejection of `PdfCache.download`, with a `code` and `httpStatus` |

### `PdfViewRef` methods

| Method | Description |
| --- | --- |
| `goToPage(page, options?)` | Scroll to a zero-based page, animated unless `{ animated: false }` |
| `zoomTo(scale, options?)` | Zoom relative to the fitted page |
| `goToViewport(viewport)` | Jump to a position from `onViewportChange` |
| `getOutline()` | The table of contents |
| `getPageSizes()` | Every page's size in PDF points |
| `renderPage(page, options)` | A PNG or JPEG file of a page |
| `print(options?)` | The system print dialog |
| `getFormFields()` / `setFormFieldValues(values)` / `exportDocument(options?)` | Read, fill in and save the form |
| `search(query)` / `searchNext()` / `searchPrevious()` / `clearSearch()` | Full-text search |
| `getStats()` | Render and cache statistics, for diagnostics |

### Types

| Type | Description |
| --- | --- |
| `PdfViewProps` | Props for `PdfView` |
| `PdfPageOverlayProps` | Props for `PdfPageOverlay` |
| `PdfSource` / `PdfUriSource` | Where a document comes from, with cache options for downloads |
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
[docs/api-design.md](docs/api-design.md) for the decisions behind them.

## Limitations

- **Not implemented yet**: editing annotations (forms are filled in, not
  restructured); facing pages in horizontal or paged layouts, or right to
  left.
- **Dark pages on iOS**: annotations keep their own colours (see
  [Dark pages](#dark-pages)).
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
  files (see [Base64 documents](#base64-documents)).
- Downloads use `GET` only, run only while the app runs, and are not
  limited in total size (see
  [Offline reading and caching](#offline-reading-and-caching)).

## Performance philosophy

1. The main and JS threads never wait on document I/O or rendering.
2. No per-frame JS: no `onScroll`/`onZoom` events, no React-managed pages.
3. Memory is bounded by the viewport, not the document or the zoom level.
4. Claims come from measurements. [docs/benchmarks.md](docs/benchmarks.md)
   describes the method and publishes the numbers — including where this
   library is not faster.

## Example app

```bash
bun install
bun run fixtures                        # test documents (~650 MB), served by the next line
node benchmarks/serve-fixtures.mjs      # on :8090 (Android: adb reverse tcp:8090 tcp:8090)
cd example && bunx expo run:ios         # or run:android
```

The example app lives in [example](example), an Expo development build. It
shows every feature on generated documents (forms, links, outlines, scans of
1,000 pages, encrypted and damaged files) and runs the Maestro end-to-end
flows in [example/e2e](example/e2e).

## Documentation

- [Migrating from react-native-pdf](docs/migrating-from-react-native-pdf.md) — prop-by-prop migration guide
- [Agent skill](https://github.com/gmi-software/better-skills/tree/main/skills/react-native-better-pdf) — teaches AI coding agents this API and the migration from react-native-pdf: `npx skills add gmi-software/better-skills --skill react-native-better-pdf`
- [Architecture](ARCHITECTURE.md) — threading, virtualization, cache, lifecycle
- [API design](docs/api-design.md) — API decisions vs. react-native-pdf
- [Platform research](docs/architecture/research.md)
- [Benchmarks](docs/benchmarks.md) — methodology and results
- [Audit](docs/audit.md) — engineering audit and open issues
- [Community requests](docs/community-requests.md) — what react-native-pdf users ask for, and where this library stands
- [Releases](https://github.com/gmi-software/react-native-better-pdf/releases) — release notes for every version
- [Contributing](CONTRIBUTING.md)

## Common problems

| Problem | Solution |
| --- | --- |
| The viewer does not appear in Expo Go | Use a development build (`npx expo run:ios` / `run:android`); Expo Go cannot load native modules. |
| An error about `BetterPdfView` right after installing | Rebuild the native app: `pod install` and a new build on iOS, a new build on Android. |
| Errors about the legacy architecture | Enable the New Architecture; Nitro Views need Fabric. |
| A password-protected document fails with `unsupported-pdf` on Android | The device is below Android 15 without SDK extension 13, where the platform renderer cannot open protected files. |
| Search reports `isSupported: false`, long presses select nothing | Same devices as above; see the [capability matrix](#capability-matrix). |
| A remote document fails with `invalid-pdf` | The server answered with something else, often a login page: check the URL and `headers`. |
| A signed URL or rotating token downloads the document on every open | Set `cacheKey` on the source to identify the stored copy. |
| The document is a base64 string | Write it to a file and open the file; see [Base64 documents](#base64-documents). |
| Tapping a URL link does nothing | The viewer never opens URLs: open them in `onLinkPress`. |
| `onPageTap` arrives about 300 ms after the tap | It waits for a possible double tap; `enableDoubleTapZoom={false}` reports taps at once. |
| The first page sits under the status bar or a header | The viewer adds no safe-area insets: place it in a `SafeAreaView`, or use `contentInset` for translucent bars. |

## Development

```bash
# Install dependencies
bun install

# Type checks, lint and JS unit tests
bun run typecheck
bun run lint
bun run test

# Regenerate Nitro bindings after editing *.nitro.ts
cd package && bun run specs

# Android JVM unit tests (layout, prefetch, cache, the PDF parser)
cd example/android && ./gradlew :react-native-better-pdf:testDebugUnitTest

# End-to-end flows on a simulator or emulator
example/e2e/run.sh <simulator-udid | emulator-serial>
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the toolchain, the end-to-end
flows and benchmarks.

## What's next

Being considered for later releases:

- Password-protected documents, search and text selection on Android
  versions without the newer renderer API, with the library's own PDF
  reading.
- Editing annotations.
- Downloads with `POST` and a body.
- Screen reader access to form fields on Android, and text selections
  across pages.

## License

MIT - see [LICENSE](LICENSE).

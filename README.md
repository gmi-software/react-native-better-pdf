<img width="2188" height="718" alt="react-native-better-pdf" src="./assets/react-native-better-pdf.png" />

<div align="center">

**On Android, your react-native-pdf screen ships its own PDFium and peaks at 550 MB on a 500-page scan? Use the PDF engine the OS already has.**

A replacement for **[react-native-pdf](https://github.com/wonday/react-native-pdf)**: PDFKit on iOS, the platform `PdfRenderer` on Android, rendered natively through **[Nitro Views](https://nitro.margelo.com/docs/guides/view-components)**. No bundled PDFium, no WebView, no `react-native-blob-util`, and no JS in the scroll, zoom and render path.

[![npm version](https://img.shields.io/npm/v/react-native-better-pdf.svg)](https://www.npmjs.com/package/react-native-better-pdf)
[![npm downloads](https://img.shields.io/npm/dw/react-native-better-pdf.svg)](https://www.npmjs.com/package/react-native-better-pdf)
[![GitHub stars](https://img.shields.io/github/stars/gmi-software/react-native-better-pdf?style=flat)](https://github.com/gmi-software/react-native-better-pdf/stargazers)
[![New Architecture](https://img.shields.io/badge/New_Architecture-required-0F52FF.svg)](https://reactnative.dev/architecture/landing-page)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![CI](https://github.com/gmi-software/react-native-better-pdf/actions/workflows/ci.yml/badge.svg)](https://github.com/gmi-software/react-native-better-pdf/actions/workflows/ci.yml)

[Benchmark](#benchmark) • [Migration](#migration) • [Installation](#installation) • [Usage](#usage) • [Documentation](#documentation) • [Example app](./example)

</div>

## Benchmark

The same app, the same documents and the same layout for both libraries. Medians of 3 runs of release builds on the Android emulator (API 37), both libraries in one session, from the [benchmark harness](docs/benchmarks.md):

| Document | | Time to `onLoad` | Peak memory (PSS) | CPU while scrolling (all cores) |
| --- | --- | --- | --- | --- |
| 10 pages, text | react-native-pdf 7.0.5 | 67 ms | 90 MB | 33% |
| | **react-native-better-pdf** | **57 ms** | **85 MB** | **16%** |
| 100 pages, images | react-native-pdf 7.0.5 | 165 ms | 130 MB | 59% |
| | **react-native-better-pdf** | **43 ms** | **128 MB** | **26%** |
| 500 pages, scans | react-native-pdf 7.0.5 | 417 ms | 550 MB | 111% |
| | **react-native-better-pdf** | **47 ms** | **104 MB** | **29%** |
| 1,000 pages, scans | react-native-pdf 7.0.5 | 622 ms | 898 MB | 111% |
| | **react-native-better-pdf** | **56 ms** | **142 MB** | **34%** |

- **Memory.** react-native-pdf's peak grows with the document: 550 MB at 500 pages, 898 MB at 1,000. Here it stays between 85 and 142 MB, bounded by the pages around the viewport. Pages that are all grey (text, greyscale scans) are kept in one byte per pixel.
- **Size.** Native code and dex for one Android ABI: ≈ 2.1 MB, against ≈ 5.3 MB for react-native-pdf with its bundled PDFium, AndroidPdfViewer, Gson and react-native-blob-util. `react-native-nitro-modules` adds ≈ 1 MB, shared by every Nitro module in the app.
- **Load.** `onLoad` fires once the document is open and the first page's size is known, before that page is drawn. react-native-pdf fires `onLoadComplete` after its own load.

Measured on an emulator: compare the libraries, not the absolute times. Frame times there are the same for both libraries, so comparing jank needs a device. On iOS both libraries render with PDFKit, so loads and memory are close, and this library links more code: 526 KB against 161 KB, most of it generated bindings. The [benchmarks](docs/benchmarks.md) describe the method, and the [full results](docs/performance/results.md) include every run and the iOS numbers.

## Migration

Remove `react-native-pdf` (and `react-native-blob-util`, unless something else in your app uses it), [install](#installation) this library, then:

```diff
- import Pdf from 'react-native-pdf';
+ import { PdfView } from 'react-native-better-pdf';

- <Pdf
-   source={{ uri: 'https://example.com/file.pdf', cache: true }}
+ <PdfView
+   source={{ uri: 'https://example.com/file.pdf' }}
-   onLoadComplete={(numberOfPages) => setPageCount(numberOfPages)}
-   onPageChanged={(page, numberOfPages) => setPage(page)}
-   onError={(error) => console.warn(error)}
+   onLoad={({ pageCount }) => setPageCount(pageCount)}
+   onPageChange={({ page, pageCount }) => setPage(page + 1)}
+   onError={(error) => console.warn(error.code, error.message)}
    style={{ flex: 1 }}
  />
```

Callbacks take one event object, and `page` counts from 0. [Migrating from react-native-pdf](docs/migrating-from-react-native-pdf.md) maps every prop, callback and method, and lists what changes silently.

## Fixes over react-native-pdf

| Issue | Status |
| --- | --- |
| Android 16 KB page size ([#960](https://github.com/wonday/react-native-pdf/issues/960) and others) | Native library 16 KB aligned |
| New Architecture / Fabric ([#878](https://github.com/wonday/react-native-pdf/issues/878) and others) | Built for Fabric on Nitro Views |
| `react-native-blob-util` crashes and setup ([#796](https://github.com/wonday/react-native-pdf/issues/796) and others) | Not used: downloads are native |
| Android "Already closed" crashes ([#830](https://github.com/wonday/react-native-pdf/issues/830) and others) | Closing a document while it renders, searches or exports is handled and stress-tested |
| Link taps on Android ([#983](https://github.com/wonday/react-native-pdf/issues/983) and others) | Every link form on every Android version (password-protected documents: see [Limitations](docs/limitations.md)) |
| Search ([#555](https://github.com/wonday/react-native-pdf/issues/555) and others) | Built in, with highlights (Android: see the [capability matrix](docs/compatibility.md#capability-matrix)) |
| Viewport events, restoring the position ([#492](https://github.com/wonday/react-native-pdf/issues/492) and others) | `onViewportChange`, `initialViewport`, `goToViewport` |
| Filling in and saving forms ([#748](https://github.com/wonday/react-native-pdf/issues/748) and others) | Fill, read and export (Android: see the [capability matrix](docs/compatibility.md#capability-matrix)) |
| Cache control and offline reading ([#952](https://github.com/wonday/react-native-pdf/issues/952) and others) | Cache options on the source, `PdfCache` |

The full list, with reaction counts, is in [Community requests](docs/community-requests.md).

> [!CAUTION]
> Search, text selection, password-protected documents and filling in forms need Android 15, or Android 12–14 with SDK extension 13 (the PDF module updated through Google Play system updates). On other Android versions a password-protected document fails with `unsupported-pdf`, search reports `isSupported: false`, long presses select nothing and forms are read only. See the [capability matrix](docs/compatibility.md#capability-matrix).

## Installation

iOS 15.1+ and Android 7.0+ (API 24), React Native **0.83+** with the **New Architecture**, and `react-native-nitro-modules` 0.37.0+. Expo SDK 55+ needs a [development build](https://docs.expo.dev/develop/development-builds/introduction/); Expo Go does not work.

```bash
npm install react-native-better-pdf react-native-nitro-modules
cd ios && pod install && cd ..
```

Rebuild the native app after installing. With Expo, install with `npx expo install` instead. No config plugin is needed: run `npx expo prebuild`, then `npx expo run:ios` or `run:android`. See [Compatibility](docs/compatibility.md) for the tested versions and what each Android version supports.

## Usage

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

`PdfView` takes every `View` prop. `source` also takes a local file, a bundled `require()`, a `content://` URI, `{ base64 }`, or an array of sources shown as one document: see [Sources and loading](docs/guides/sources-and-loading.md).

## Documentation

| | |
| --- | --- |
| [API reference](docs/api.md) | Every prop, ref method, object, error code and type |
| [Sources and loading](docs/guides/sources-and-loading.md) | Remote, local and bundled files, `content://` URIs, request headers and redirects, base64 data, several files as one document, loading state |
| [Navigation and reading position](docs/guides/navigation.md) | Links and page taps; saving and restoring where the reader left off |
| [Offline reading and caching](docs/guides/offline-and-caching.md) | Cache modes, revalidation with `ETag` / `Last-Modified`, offline fallback, `PdfCache` |
| [Layout and appearance](docs/guides/layout-and-appearance.md) | Facing pages, translucent bars, dark pages |
| [Search](docs/guides/search.md) | Native full-text search with highlights |
| [Forms](docs/guides/forms.md) | Filling in, reading and exporting form fields |
| [Page overlays](docs/guides/page-overlays.md) | React views attached to points of a page, moved natively while it scrolls and zooms |
| [Page images and documents without a view](docs/guides/page-images-and-documents.md) | Page images, covers and metadata with `PdfDocument` |
| [Printing](docs/guides/printing.md) | The system print dialog |
| [Accessibility](docs/guides/accessibility.md) | VoiceOver and TalkBack read each page |
| [Compatibility](docs/compatibility.md) | Requirements, tested versions and what each Android version supports |
| [Migrating from react-native-pdf](docs/migrating-from-react-native-pdf.md) | Every prop, callback and method, what changes silently, and common migrations |
| [Limitations](docs/limitations.md) | Known platform constraints and what's next |
| [Troubleshooting](docs/troubleshooting.md) | Expo Go, New Architecture errors, documents that fail to open |
| [Performance](docs/benchmarks.md) | Benchmark method and results, including where this library is not faster |
| [Architecture](ARCHITECTURE.md) | Threading, virtualization, cache and lifecycle |
| [Example app](./example) | Every feature on generated documents, the benchmark screen and the Maestro flows |

Design notes: [API design](docs/api-design.md), [platform research](docs/architecture/research.md), the [audit](docs/audit.md) and [community requests](docs/community-requests.md).

AI coding agents learn this API and the migration from react-native-pdf with the [agent skill](https://github.com/gmi-software/better-skills/tree/main/skills/react-native-better-pdf): `npx skills add gmi-software/better-skills --skill react-native-better-pdf`.

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) for the toolchain, tests, end-to-end flows, benchmarks and commit conventions. Releases are published from CI, see [RELEASING.md](./RELEASING.md). Report vulnerabilities privately, see [SECURITY.md](./SECURITY.md).

## License

MIT, see [LICENSE](./LICENSE).

---

<div align="center">

Built and maintained by [GMI Software](https://gmi.software/open-source). If it saves your PDF screen, a ⭐ helps other React Native developers find it.

</div>

<img width="2188" height="718" alt="react-native-better-pdf" src="./assets/react-native-better-pdf.png" />

<div align="center">

A native, virtualized PDF viewer for React Native, built on [Nitro Views](https://nitro.margelo.com/docs/guides/view-components) and the New Architecture.

[![npm version](https://img.shields.io/npm/v/react-native-better-pdf.svg)](https://www.npmjs.com/package/react-native-better-pdf)
[![CI](https://github.com/gmi-software/react-native-better-pdf/actions/workflows/ci.yml/badge.svg)](https://github.com/gmi-software/react-native-better-pdf/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
![TypeScript](https://img.shields.io/badge/TypeScript-Ready-blue.svg)
![Expo Development Build](https://img.shields.io/badge/Expo-development%20build-000020.svg)

PDFKit on iOS, the platform `PdfRenderer` on Android: no bundled PDFium, no WebView, no JS in the scroll, zoom and render path.

[Features](#features) • [Installation](#installation) • [Quick start](#quick-start) • [Documentation](#documentation) • [Capability matrix](#capability-matrix)

</div>

---

## Features

- **Performance first** — scrolling, zooming, rendering and prefetching run natively. JS sends configuration and commands and receives settled events, never per-frame ones.
- **The OS's own PDF engines** — PDFKit on iOS, `PdfRenderer` on Android with a purpose-built native viewer. No bundled PDFium, no WebView, no `react-native-blob-util`.
- **Memory bounded by the viewport** — page virtualization, render cache, prefetch and cancellation on Android; pixels released under memory pressure and while a screen is hidden.
- **Any source** — local files, bundled assets (`require()`), `content://` URIs and remote documents with headers, downloaded natively.
- **Offline reading** — downloads kept for the session or across launches, revalidated with `ETag` / `Last-Modified`; `PdfCache` downloads ahead of time.
- **Layouts** — vertical, horizontal, paged, single page and right to left; facing pages, translucent bars and dark pages.
- **Reading position** — settled viewport events, restore on open or on demand, end-of-document detection.
- **Links, search and text selection** — links followed or reported to JS, page taps in PDF points, native full-text search with highlights.
- **Forms** — AcroForm fields filled in by touch or from code, read back by name and exported as a filled-in PDF.
- **Page overlays** — React views attached to points of a page, moved natively while it scrolls and zooms.
- **Document APIs** — outline, page sizes, metadata, page images and printing, with or without a view.
- **Password protection, typed errors, accessibility** — stable error codes identical on both platforms; VoiceOver and TalkBack read each page.

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

`data:` / base64 sources are not supported by design: write the data to a file
and open the file, see
[Base64 documents](docs/guides/sources-and-loading.md#base64-documents). Known
gaps are listed in [Limitations](docs/limitations.md).

## Installation

```bash
npm install react-native-better-pdf react-native-nitro-modules
```

Yarn, pnpm and Bun work the same way. On bare React Native, run
`cd ios && pod install` and rebuild the app.

**Expo:** no config plugin is needed. Rebuild your development build:

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

`PdfView` also takes every `View` prop. The [API reference](docs/api.md) lists
all props and ref methods; the [guides](#guides) cover each feature.

## Documentation

### Guides

| Guide | Covers |
| --- | --- |
| [Sources and loading](docs/guides/sources-and-loading.md) | Remote, local and bundled files, request headers, base64 data, loading state |
| [Navigation and reading position](docs/guides/navigation.md) | Links and page taps; saving and restoring where the reader left off |
| [Offline reading and caching](docs/guides/offline-and-caching.md) | Cache modes, revalidation, offline fallback, `PdfCache` |
| [Layout and appearance](docs/guides/layout-and-appearance.md) | Facing pages, translucent bars, dark pages |
| [Search](docs/guides/search.md) | Native full-text search with highlights |
| [Forms](docs/guides/forms.md) | Filling in, reading and exporting form fields |
| [Page overlays](docs/guides/page-overlays.md) | React views attached to points of a page |
| [Page images and documents without a view](docs/guides/page-images-and-documents.md) | Page images, covers and metadata with `PdfDocument` |
| [Printing](docs/guides/printing.md) | The system print dialog |
| [Accessibility](docs/guides/accessibility.md) | VoiceOver and TalkBack |

### Reference

- [API reference](docs/api.md) — props, ref methods, objects, errors and types
- [Limitations](docs/limitations.md) — known platform constraints and what's next
- [Troubleshooting](docs/troubleshooting.md) — common problems and their fixes
- [Migrating from react-native-pdf](docs/migrating-from-react-native-pdf.md) — prop-by-prop migration guide
- [Agent skill](https://github.com/gmi-software/better-skills/tree/main/skills/react-native-better-pdf) — teaches AI coding agents this API and the migration from react-native-pdf: `npx skills add gmi-software/better-skills --skill react-native-better-pdf`
- [Releases](https://github.com/gmi-software/react-native-better-pdf/releases) — release notes for every version

### Design and internals

- [Architecture](ARCHITECTURE.md) — threading, virtualization, cache, lifecycle
- [API design](docs/api-design.md) — API decisions vs. react-native-pdf
- [Benchmarks](docs/benchmarks.md) — methodology and results
- [Platform research](docs/architecture/research.md)
- [Audit](docs/audit.md) — engineering audit and open issues
- [Community requests](docs/community-requests.md) — what react-native-pdf users ask for, and where this library stands

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

Details are in each feature's guide and in [Limitations](docs/limitations.md).

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

Coming from react-native-pdf? The
[migration guide](docs/migrating-from-react-native-pdf.md) maps every prop and
shows how this library handles the issues its users report most.

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

## Contributing

Contributions are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers the
toolchain, tests, end-to-end flows, benchmarks and commit conventions. Please
report security issues privately as described in [SECURITY.md](SECURITY.md).

## License

MIT - see [LICENSE](LICENSE).

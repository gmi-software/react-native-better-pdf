# Research notes

Research conducted 2026-09-29 before implementation. Every version and API
fact below was verified against installed packages, the Nitro source tree
(`mrousavy/nitro@85c69e3`, 2026-09-22) or the Android platform sources shipped
with SDK 37 — not recalled from memory.

## Toolchain snapshot

| Component                  | Version found               | Source                                          |
| -------------------------- | --------------------------- | ----------------------------------------------- |
| react-native (latest)      | 0.87.1                      | npm                                             |
| react-native (Expo SDK 57) | 0.86.3                      | `expo/bundledNativeModules.json`                |
| react                      | 19.2.3 (Expo 57 pin)        | `expo/bundledNativeModules.json`                |
| expo                       | 57.0.26                     | npm `latest`                                    |
| react-native-nitro-modules | 0.37.1                      | npm                                             |
| nitrogen                   | 0.37.1                      | npm                                             |
| RN iOS minimum             | 15.1                        | `react-native/scripts/cocoapods/helpers.rb`     |
| RN Android min/target SDK  | 24 / 36                     | `react-native/gradle/libs.versions.toml`        |
| RN Kotlin / AGP            | 2.1.20 / 8.12.0             | `react-native/gradle/libs.versions.toml`        |
| Nitro template AGP / NDK   | 9.3.1 / 29.0.14206865       | `nitro/packages/template/android`               |
| Xcode (dev machine)        | 27.0                        | `xcodebuild -version`                           |
| react-native-pdf (latest)  | 7.0.5                       | npm                                             |

The example app targets Expo SDK 57 (RN 0.86.3) because that is the newest
RN an Expo development build can use today. The library itself declares
`react-native >= 0.83` as a peer. The Nitro Views floor is 0.78 with the New
Architecture, but below 0.82 iOS never calls a Nitro view's `onDropView`
([#26](https://github.com/gmi-software/react-native-better-pdf/issues/26)),
and 0.83 (Expo SDK 55) is the oldest line the example builds on.

## Chosen Nitro architecture

**One Nitro `HybridView` (`BetterPdfView`) implemented in Swift and Kotlin.
No standalone Hybrid Objects, no hand-written C++.**

Verified Nitro 0.37 view facts (from `react-native-nitro-modules/src/views`,
`ios/views/HybridView.swift`, `android/.../views/HybridView.kt`, the
`react-native-nitro-test` `TestView`, and generated code):

- A view is declared as `HybridView<Props, Methods>` in a `*.nitro.ts` spec.
- Nitrogen generates a Fabric ShadowNode + ComponentDescriptor (C++), a Swift
  protocol / Kotlin abstract class, an Android `ViewManager`, and a
  `<Name>Config.json` view config.
- JS obtains the host component with
  `getHostComponent<Props, Methods>(name, () => ViewConfig)`.
- Function props must be wrapped with `callback(fn)` (`{ f: fn }`) because
  React Native otherwise converts functions to booleans.
- Methods are called through `hybridRef` (itself a wrapped callback). They run
  on the **JS thread**; props set by React arrive on the **UI thread**.
- Swift: `var view: UIView`, `beforeUpdate()`, `afterUpdate()`, `onDropView()`.
  Kotlin: constructor receives `ThemedReactContext`, `override val view: View`,
  same lifecycle hooks. `RecyclableView.prepareForRecycle()` is optional.
- Android requires registering `HybridBetterPdfViewManager` in the package's
  `createViewManagers`.
- **Prop diffing is by JS identity.** `ReactProp::fromRawValue` compares the new
  `jsi::Value` against the previous one with `strictEquals`; the generated
  `updateProps` then only calls setters whose `ReactProp` entry changed. So an
  inline `source={{ uri }}` object re-sets the native source on every React
  render. The JS wrapper therefore memoizes the source by value and passes
  identity-stable callbacks, and native setters compare structurally as a
  second line of defence.
- Errors: a Swift `throw` / Kotlin exception inside a *method* becomes a JS
  `Error`. There is no typed error channel; typed load/render errors are
  delivered through an `onError(PdfError)` callback carrying a string-union
  code (Nitrogen maps `'password-required'` to Swift `.passwordRequired` /
  Kotlin `PASSWORD_REQUIRED`).

### Why this is correct

The hot path (scroll, zoom, render, cache, prefetch) must never cross the JS
boundary, so the only things that do cross it are configuration (props),
commands (methods) and low-frequency events (callbacks). A single Hybrid View
expresses exactly that. A separate `PdfDocument` Hybrid Object (load once,
share between views, query page count without a view) is a sensible future
addition but not needed for the MVP.

### Why no hand-written C++

The prompt asks to use C++ only where it adds architectural value. We looked
for shared logic worth hoisting:

- **Virtualization / cache / prefetch** — iOS gets these from PDFKit
  (`PDFView` recycles page views and tiles internally). Only Android needs a
  hand-written implementation, so a shared C++ core would have one caller and
  would add a JNI hop to a per-frame path.
- **Source/state** — trivially small; lives in the Nitro spec types.

Nitro still generates C++ for the bridge (ShadowNode, JSI converters); that
is where C++ belongs here. Revisit if a second platform renderer
(e.g. a shared PDFium backend) is ever added.

## Platform strategy

### iOS — PDFKit

`PDFView` already provides everything the MVP needs natively:

- `displayMode = .singlePageContinuous`, `displayDirection = .vertical`.
- It is backed by a `UIScrollView`: scroll, fling, pinch zoom and pan are
  native UIKit gestures. Double-tap zoom is built in.
- It virtualizes internally: page views are created for visible pages only and
  content is rendered as tiles on background threads (PDFKit's tiled layers),
  so a 1000-page document does not create 1000 views or bitmaps.
- `minScaleFactor` / `maxScaleFactor` bound zoom; `scaleFactorForSizeToFit`
  gives the fit-width baseline we express `minScale`/`maxScale` against.
- `.PDFViewPageChanged` reflects PDFKit's `currentPage`, which never reaches a
  last page shorter than the viewport; `onPageChange` therefore observes the
  internal scroll offset instead (audit M8).
- `PDFDocument(url:)` parses synchronously and can take noticeable time for
  large files, so it is created on a background queue and assigned to the
  view on the main thread.
- `PDFDocument.isLocked` / `unlock(withPassword:)` handle encryption.

Decision: **wrap `PDFView`, do not re-implement virtualization on iOS.**
Re-implementing tiling on top of `PDFPage.draw(with:to:)` would be more code,
slower, and worse on memory than Apple's implementation. We own everything
around it: loading, remote download, error mapping, scale mapping, initial
page, events, lifecycle.

### Android — PdfRenderer

`android.graphics.pdf.PdfRenderer` is a renderer, not a viewer, so the viewer
is built natively in Kotlin: a single custom `View` that draws pages onto its
canvas (no child view per page), plus a render worker and a bitmap cache.

Verified from `sources/android-37.0/android/graphics/pdf/PdfRenderer.java`:

| Property                       | Finding                                                                                          |
| ------------------------------ | ------------------------------------------------------------------------------------------------ |
| Concurrency                    | "only a single page opened at any given time"; access is internally locked. No parallel renders per document. |
| Construction cost              | Must be created on a worker thread ("can be long-running while loading").                       |
| Input                          | Requires a **seekable** `ParcelFileDescriptor` → remote PDFs must be downloaded to a file first.  |
| Ownership                      | Takes ownership of the FD and closes it on `close()`.                                            |
| Bitmap format                  | Destination must be `ARGB_8888` (4 bytes/pixel).                                                  |
| Partial rendering              | `render(bitmap, clip, matrix, mode)` renders any affine-transformed region → enables zoom tiles. |
| Cancellation                   | A render call cannot be interrupted once started.                                                |
| Passwords                      | `PdfRenderer(fd, LoadParams)` exists only from API 35. Before that, encrypted files throw `SecurityException`. |
| Untrusted input                | Docs recommend isolating untrusted documents in a separate process.                              |
| Errors                         | `IOException` (corrupt), `SecurityException` (password / unsupported security), `IllegalArgumentException` (non-seekable). |

Consequences for the design:

- One render thread per document (parallelism is impossible anyway).
  Requests go through a priority queue so stale work is dropped **before** it
  starts; since in-flight renders cannot be cancelled, their size is bounded.
- Memory is dominated by ARGB bitmaps: an A4 page at 1080 px wide is ~6.6 MB.
  A byte-budgeted LRU cache, a bitmap reuse pool and viewport-sized zoom tiles
  (instead of whole pages at 4×) keep memory bounded.
- Password-protected PDFs are supported on API 35+ only. Below that we report
  `password-required` / `unsupported-pdf` rather than crash.
  (`PdfRendererPreV` backports password support to API 31–34 via SDK
  extension 13; not wired in the MVP.)

**Correction found by measurement (performance audit B1):** PDFium keeps
every object parsed for an opened page — including compressed image data —
until the *document* is closed, even after `Page.close()`. Opening every page
to read its size (the only way `PdfRenderer` exposes page sizes) retained
native memory equal to the file size (400 MB for a 395 MB document) and took
1.7 s. Page sizes are therefore discovered lazily and the renderer is
re-created periodically; see `docs/performance/results.md` §2.

`androidx.pdf` (`PdfViewerFragment`) was considered and rejected: it is a
Fragment (awkward inside a Fabric view), requires API 31+ with SDK
extensions, and pulls in a large dependency for a UI we cannot tune.

## Existing libraries (react-native-pdf 7.0.5)

Read from the published package:

- Remote downloads, caching and base64 decoding happen **in JS** via
  `react-native-blob-util` (a required peer), with SHA-1 of the URL computed
  by `crypto-js` in JS.
- Android ships a PDFium fork (`io.legere:pdfiumandroid`) plus
  `barteksc`-style `PDFView`, adding several MB of native libraries per ABI.
- Page numbers are 1-based; `fitPolicy` is a magic number `0 | 1 | 2`.
- Props like `scale`, `page`, `enablePaging`, `singlePage`, `horizontal`,
  `enableRTL`, `enableAntialiasing`, `trustAllCerts` expand the surface
  considerably. `onScaleChanged` pushes zoom state to JS continuously.
- `setPage` is implemented by `setNativeProps`.

Those observations drive `docs/api-design.md`.

## Important constraints

- New Architecture only (Nitro Views require Fabric).
- Expo: works in development builds via standard autolinking; no config
  plugin is needed (no permissions or Info.plist keys beyond what RN apps
  already have). **Expo Go is not supported** — it cannot load third-party
  native code.
- The example monorepo uses Bun with `linker = "hoisted"` because
  react-native-pdf's Gradle/CocoaPods integration expects a flat
  `node_modules`.

## Rejected approaches

| Approach                                              | Why rejected                                                                                       |
| ----------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Classic bridge module / `RCTEventEmitter`             | Legacy architecture; the task forbids it and Nitro supersedes it.                                  |
| React-managed page list (FlatList of page images)     | Puts virtualization, layout and zoom in JS; every scroll frame touches JS.                          |
| JS PDF renderer (pdf.js) / WebView                    | Slow, memory heavy, and not native.                                                                |
| Bundling PDFium on Android                            | Several MB per ABI, native-crash surface we would own. PdfRenderer is PDFium inside the platform.  |
| RecyclerView of `ImageView`s on Android               | Pinch zoom + horizontal pan across a RecyclerView is fragile; a single canvas view is simpler and gives full control of bitmap memory. |
| Shared C++ virtualization core                        | Only Android would use it; adds JNI hop per frame without a second consumer.                        |
| `onScroll` / `onZoom` events                          | Turns the viewer into an event bridge; no concrete MVP use case.                                   |
| Custom tiling renderer on iOS                         | PDFKit already tiles and virtualizes, better than we could.                                        |

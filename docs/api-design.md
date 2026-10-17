# API design

This records the reasoning behind the public API before it is stabilized.
Input: the react-native-pdf 7.0.5 surface (see
`docs/architecture/research.md`), the Nitro View constraints, and the
performance rules in `ARCHITECTURE.md`.

## Principles

1. **Configuration in, meaningful events out.** Props describe the document
   and interaction limits. Callbacks fire on state changes a UI would act on
   (loaded, failed, page changed) — never per frame.
2. **Zero-based pages everywhere.** Matches JS arrays and both native APIs.
   react-native-pdf's 1-based pages are a steady source of off-by-one bugs.
3. **Scale is relative to the fitted page.** `1` means the page fits the
   view as `fitPolicy` says (fit width by default), regardless of device or
   page size.
4. **Stable error codes, unstable messages.** Match on `error.code`.
5. **Omit what we can't do well natively on both platforms** rather than ship
   platform-specific booleans.

## Public API

```tsx
import { PdfView, type PdfViewRef } from 'react-native-better-pdf';

const ref = useRef<PdfViewRef>(null);

<PdfView
  ref={ref}
  style={{ flex: 1 }}
  source={{ uri: 'https://example.com/file.pdf', headers: { Authorization } }}
  password={password}
  initialPage={0}
  minScale={1}
  maxScale={4}
  pageSpacing={8}
  onLoad={({ pageCount }) => {}}
  onPageChange={({ page, pageCount }) => {}}
  onError={(error) => {
    if (error.code === 'password-required') askForPassword();
  }}
/>;

ref.current?.goToPage(42);            // animated by default
ref.current?.goToPage(42, { animated: false });
ref.current?.zoomTo(2);
ref.current?.zoomTo(1);               // "reset zoom"
```

### Source

```ts
type PdfSource =
  | PdfUriSource      // { uri, headers?, cache?, maxAgeMs?, cacheKey? }: http(s)://, file://, content://
  | { filePath: string } // absolute path
  | PdfBase64Source      // { base64 }: the document's bytes
  | number;              // require('./doc.pdf')
```

- Normalized in JS into one native shape `{ uri, base64?, headers?, cache?,
  maxAgeMs?, cacheKey? }` so native code handles a single representation
  (`uri` is `''` for base64).
- Remote files are downloaded **natively** (URLSession / HttpURLConnection)
  into a shared cache, keyed by a hash of `cacheKey` or of the URL and
  headers. No blob-util, no JS base64. See "Caching and offline".
- **Headers are checked natively before any request**, the same way on
  both platforms: a name must be an RFC 9110 token, a value must not
  contain control characters other than tab. Otherwise the source fails
  with `invalid-source`; the message names the header, never the value
  (it may hold a token). A stored copy is not served for such a source
  either, fresh or not: it could never be revalidated. Unchecked, the
  platforms disagreed and neither reported it: Android's
  `setRequestProperty` throws, which crashed the download thread and left
  the copy's download in flight forever in 1.0.0; URLSession (tried on
  macOS CFNetwork) silently drops a value with CR or LF — a broken
  `Authorization` became the server's 401 —
  and sends an empty name, or one with spaces or line breaks, as is. The
  check sits in `DocumentCache` next to the scheme check, so `PdfView`,
  `PdfCache.download` and `PdfDocument.open` report it through their usual
  error paths; in JS it would run while `PdfView` renders, where it could
  only throw. A value ending in a newline, which Android's HTTP stack
  strips, is rejected too: iOS drops it. Values outside ASCII stay allowed
  (Android has always sent them as UTF-8), but iOS sends Latin-1 and cuts
  the value at the first character outside it, so the docs say to keep
  values ASCII. Android's download thread also turns any other exception
  into a failure, so a download always ends.
- The normalized source is memoized **by value** so re-rendering the parent
  with an inline object literal does not reload the document (Nitro diffs
  props by identity — see research notes).
- **Base64 sources** (`{ base64 }`, 1.1; issue #6). 1.0 left them out: the
  library would own a temporary file (Android's `PdfRenderer` reads a file
  descriptor), and a document-sized string would be normalized and compared
  on every render. Both turned out cheap to answer, and writing the file was
  the one step a bare React Native app (pdfmake, no file system library)
  could not do in a few lines:
  - **The text crosses as bytes, not as a string.** Nitro converts a struct
    completely each time it crosses, on the thread that receives it, and has
    no native-only fields. A `string` field became a Java `String` on
    Android's UI thread (31–39 ms for 20 MB, once 177 ms) and a Swift
    `String` on iOS's main thread (12–39 ms). An `ArrayBuffer` crosses as a
    pointer. So `NativePdfSource.base64` is an `ArrayBuffer`: JS copies the
    string into it once per distinct text (`nativeSource.ts`,
    `TextEncoder.encodeInto`, one byte per character; Hermes has
    `encodeInto` since React Native 0.74, other engines take a loop). The
    buffer comes from `NitroModules.createNativeArrayBuffer` (Nitro 0.35.9+,
    inside the 0.37.0 floor) because a JS-created one may only be read on
    the JS thread, and the view reads its prop on the main thread, the loader
    on its own. Both platforms check that the buffer is native-owned before
    they read it.
  - **Android** decodes in C++ (`Base64Document.cpp`), from the buffer
    straight into `cacheDir/BetterPdf/open/<uuid>.pdf` through JNI, 256 KB at
    a time: no Java string and no byte array of the document's size. It then
    opens the descriptor and deletes the file at once: the session reads only
    the descriptor (`dup` for renderers, positional reads for the parser,
    printing and export), as for `content://`, so nothing stays on disk even
    when the app is killed. The directory is emptied once per process, before
    the first write, for a crash between writing and deleting.
  - **iOS** decodes the buffer on the load queue (`Base64Document.swift`)
    into a pin (`DocumentCacheStore.pin(data:)`), the temporary file
    downloaded documents are already opened through. Pins are released on a
    source change, on dispose and on `PdfDocument.close`, and the pin
    directory is emptied at the next start; for base64 the pin is the only
    copy.
  - **JS** keeps the string out of `sourceKey`'s JSON and compares it with
    `Object.is` before it builds a buffer: free for the same string, linear
    for an equal string of another identity, and a new buffer only for a new
    text. Natively, `isSameSource` compares the buffers first and by
    identity (every base64 source has `uri: ''`); the bytes are never
    compared.
  - **One set of decoding rules, two decoders.** Standard alphabet, ASCII
    whitespace ignored (MIME base64 from Java and .NET backends), padding
    optional; anything else, `data:` URIs, URL-safe base64 and `''` are
    `invalid-source` with a message saying which, word for word the same on
    both platforms. Characters outside ASCII reach the decoders as `0x80`
    bytes (from the first one on), so the reported index is the string's.
    The platforms' own decoders disagree: Foundation's `Data(base64Encoded:)`
    takes `=` inside the last group, extra `=` and `''`, and Android's would
    need a Java array. `package/test/base64` builds the C++ and the Swift
    decoder with the host's compilers, runs both on the same cases
    (`vectors.json`, turned into bytes by `nativeSource.ts`), and checks that
    they agree on all 8.1 M texts of up to 6 bytes from 14 symbols that
    exercise every rule. It is part of `bun run test`; the Swift half needs
    `swiftc`, so CI's iOS job runs the file too. iOS hands padded input
    without whitespace to Foundation first, where it agrees with the rules
    (checked against them for every string of up to 8 characters over a
    12-character alphabet): for 20 MB Foundation takes 6 ms, the Swift loop
    65 ms optimized and 2.6 s in a debug build. One C++ decoder for both
    platforms would need a Swift–C++ bridge into this pod, and Foundation is
    still faster for the common input.
  - **Android's `-fvisibility-ms-compat`** instead of `-fvisibility=hidden`
    (`CMakeLists.txt`). Nitro's `ArrayBuffer` and `MutableBufferNativeState`
    have no key function, so every library emits its own copy of their RTTI.
    Hidden, this library's copy never equals NitroModules' (Android's libc++
    compares `type_info` by address), and the header-inlined converter took
    the native buffer for a JS one: "`size()` can only be accessed
    synchronously on the JS Thread!". `-fvisibility-ms-compat` keeps
    functions and variables hidden but gives types default visibility. It
    costs 126 KiB per ABI (1,724 → 1,850 KiB) against redeclaring just the
    two types with default visibility in a force-included header, which a
    new Nitro type on the same path would silently break. Allocating the
    buffer in this library would not help: `NativeArrayBuffer`'s vtable, and
    with it its RTTI, lives in NitroModules, and the converter still casts to
    `ArrayBuffer` with this library's copy. iOS links statically and builds
    with default visibility, so it is not affected.
  - **No `data:` URIs.** A `uri` that is sometimes the document itself would
    need its own rules everywhere `uri` is read (cache keys, schemes,
    `PdfCache`); the prefix gets a hint in the error instead.
  - **Not in `PdfCache`** (nothing to download), and no public
    `{ data: ArrayBuffer }`: the same field type and write path would carry
    raw bytes, but that is an API of its own.
  - **Memory, not time, limits the size.** Measured, a 20 MB document opens
    within tens of milliseconds of the same file and holds neither thread
    (see [results §18](performance/results.md#18-base64-sources-2026-10-15)).
    While it is shown, its text is held twice: the app's string and the
    native buffer, each a third larger than the document. Large documents
    are better written to a file (see
    [Base64 documents](guides/sources-and-loading.md#base64-documents)), and
    an API that returns a PDF as base64 in JSON is better served by returning
    the PDF and passing `uri` + `headers`: native download, progress,
    caching, offline.

### Props

| Prop           | Type                  | Default | Notes                                                                 |
| -------------- | --------------------- | ------- | --------------------------------------------------------------------- |
| `source`       | `PdfSource`           | —       | Required.                                                             |
| `password`     | `string`              | —       | Changing it reloads the document (downloaded file is reused).         |
| `initialPage`  | `number`              | `0`     | Applied once per loaded document. Changing it later has no effect.    |
| `initialScale` | `number`              | `1`     | Like `initialPage`, for zoom (relative to the fitted page). Not an `onScaleChange`. |
| `minScale`     | `number`              | `1`     | Relative to the fitted page (`fitPolicy`).                             |
| `maxScale`     | `number`              | `4`     | Relative to the fitted page (`fitPolicy`).                             |
| `enableRTL`    | `boolean`             | `false` | Right-to-left horizontal layouts; see "Right to left".                |
| `singlePage`   | `boolean`             | `false` | One static page; see "Single page".                                   |
| `fitPolicy`    | `'width' \| 'height' \| 'page'` | see "Fit policy" | How a page is scaled at zoom 1.                    |
| `pageSpacing`  | `number`              | `8`     | dp / pt between pages.                                                |
| `enableDoubleTapZoom` | `boolean` | `true` | Double tap toggles zoom (Android 2×, iOS PDFKit's step); `false` also makes `onPageTap` immediate. |
| `scrollEnabled` | `boolean` | `true` | Drag scrolling; see "Scroll enabled". |
| `pageAccessibilityLabel` | `string` | `'Page {page} of {pageCount}'` | Screen-reader page label; see "Accessibility". |
| `showsVerticalScrollIndicator` | `boolean` | `true` | Same name and meaning as React Native's `ScrollView`.            |
| `showsHorizontalScrollIndicator` | `boolean` | `true` | Only visible while a page is zoomed wider than the view.        |
| `enableTextSelection` | `boolean` | `true` | Long press selects text; `false` turns selection off; see "Text selection". |
| `horizontal`   | `boolean`             | `false` | Pages side by side; see "Layout modes".                               |
| `enablePaging` | `boolean`             | `false` | One page per swipe; see "Layout modes".                               |
| `onLoad`       | `(e: PdfLoadEvent)`   | —       | `{ pageCount, firstPageWidth, firstPageHeight, filePath? }` (PDF points). |
| `onLoadProgress` | `(e: PdfLoadProgressEvent)` | — | `{ loadedBytes, totalBytes? }`; remote only; see "Load progress".      |
| `onPageChange` | `(e: PdfPageChangeEvent)` | —   | `{ page, pageCount }`. The page under the viewport centre (the last page once scrolled to the end). |
| `onError`      | `(e: PdfError)`       | —       | Load and render errors.                                               |
| `onLinkPress`  | `(e: PdfLinkPressEvent)` | —    | `{ url }`. URL links only; see "Links and taps".                       |
| `onPageTap`    | `(e: PdfPageTapEvent)` | —      | `{ page, x, y }` in PDF points from the page's top-left.               |
| `onScaleChange` | `(e: PdfScaleChangeEvent)` | — | `{ scale }` once the zoom settles; see "Scale change".               |
| `onTextSelectionChange` | `(e: PdfTextSelectionChangeEvent)` | — | `{ text }` once the selection settles, `''` when cleared; see "Text selection". |
| `style` and other `ViewProps` | | | Standard React Native view props.                              |

`initialPage` is deliberately *initial*: a controlled `page` prop (as in
react-native-pdf) fights with native scrolling — every page change must round
trip through JS state and back, and a stale render snaps the user back.
Navigation after load is imperative (`goToPage`).

### Imperative API

| Method                                    | Notes                                                       |
| ----------------------------------------- | ----------------------------------------------------------- |
| `goToPage(page, { animated = true })`     | Zero-based; clamped to `[0, pageCount - 1]`. Ignored before `onLoad`; the first page is `initialPage`. |
| `zoomTo(scale, { animated = true })`      | Clamped to `[minScale, maxScale]`. `zoomTo(1)` resets zoom. Ignored before `onLoad`; the first zoom is `initialScale`. |
| `getStats()`                              | Diagnostics (cache bytes, hits, renders). Synchronous, cheap. |
| `getOutline()`                            | `Promise<PdfOutlineItem[]>`; see "Outline". |
| `search(query)` / `searchNext()` / `searchPrevious()` / `clearSearch()` | See "Search". |

`resetZoom()` was dropped: it is `zoomTo(1)` (or `zoomTo(minScale)`), and
one fewer method keeps the surface small.

**Numbers that are not finite are ignored.** `PdfView` drops `NaN` and
`±Infinity` passed to `goToPage`, `zoomTo` and `goToViewport` (and a
`scale` that is not positive) before they reach native, so both platforms
behave the same. In 1.0.0 iOS crashed on `goToPage(NaN)` (Swift's
`Int(_:)` traps), zoomed out on `zoomTo(NaN)`, and Android ignored both.
`NaN` most often comes from parsing a page field (`Number('a') - 1`), where
doing nothing is the expected result. Native code still guards: iOS
converts page numbers through the `Int32` range, so a huge finite page
(`1e20`) is clamped to the last page, as on Android.

### Loading state

There is no built-in spinner (react-native-pdf's `renderActivityIndicator`).
The component does not re-render itself while loading; apps show their own
indicator until `onLoad`/`onError`. A `usePdfLoadState()`-style hook can be
added later without native changes.

### Errors

```ts
type PdfErrorCode =
  | 'invalid-source'      // unsupported scheme / empty uri / invalid header
  | 'file-not-found'
  | 'network-error'       // transport failure
  | 'http-error'          // non-2xx, see error.httpStatus
  | 'invalid-pdf'         // not a PDF / corrupt
  | 'password-required'
  | 'invalid-password'
  | 'unsupported-pdf'     // e.g. encrypted PDF where Android cannot decrypt it
  | 'page-render-failed'  // see error.page
  | 'out-of-memory';
```

`error.nativeDescription` carries the raw platform message for logs.

## What we omit from react-native-pdf, and why

| react-native-pdf                         | Decision                | Reason                                                                     |
| ---------------------------------------- | ----------------------- | -------------------------------------------------------------------------- |
| `page` (controlled)                      | → `initialPage` + `goToPage` | Controlled page fights native scrolling.                             |
| `scale`                                  | → `zoomTo`              | Same reason as `page`.                                                     |
| `onScaleChanged`                         | → `onScaleChange`       | Once per settled zoom change, never per frame; see "Scale change".        |
| `onLoadProgress`                         | → `onLoadProgress`      | `{ loadedBytes, totalBytes? }` instead of a percentage; throttled natively (see "Load progress"). |
| `fitPolicy: 0 \| 1 \| 2`                 | → `'width' \| 'height' \| 'page'` | Named values instead of numbers; see "Fit policy".             |
| `showsVerticalScrollIndicator`, `showsHorizontalScrollIndicator` | Kept | Same names as React Native's `ScrollView`. Android draws the platform's own fading scroll bars; iOS toggles PDFKit's. |
| `horizontal`, `enablePaging`             | Kept                    | Same names; see "Layout modes".                                           |
| `singlePage`                             | Kept                    | Same name; see "Single page".                                             |
| `enableRTL`                              | Kept                    | Same name; see "Right to left".                                           |
| `enableAntialiasing`                     | Omitted                 | Always on.                                                                 |
| `enableAnnotationRendering`              | Omitted                 | Rendered wherever the platform can; see "Annotations".                   |
| `enableTextSelection`                    | Kept                    | Same name and default; `false` really turns selection off (react-native-pdf only muted the event). See "Text selection". |
| `onTextSelectionChange`                  | → `onTextSelectionChange` | `{ text }` once per settled selection instead of `{ nativeEvent: { type, text } }` per handle move. |
| `onPageSingleTap`                        | → `onPageTap`           | Zero-based page and PDF-point coordinates (react-native-pdf: 1-based page, view coordinates that differ per platform and ignore zoom). |
| `onPressLink`                            | → `onLinkPress`         | Same meaning; internal links are followed natively, URLs never opened by the viewer. |
| `trustAllCerts`                          | Omitted                 | Security foot-gun.                                                         |
| `source.cache`, `cacheFileName`, `expiration` | → `cache`, `cacheKey`, `maxAgeMs` | See "Caching and offline": `'persistent'` instead of `true`, a key instead of a file name, revalidation instead of deletion. |
| `source.method`, `source.body`           | Omitted                 | `GET` only for now. (`body`, a request body, is read by react-native-pdf's JS but missing from its types.) |
| `renderActivityIndicator`, `progressContainerStyle` | Omitted      | App-owned UI; see "Loading state".                                        |
| base64 `data:` sources                   | → `{ base64 }`          | The base64 without the `data:…;base64,` prefix; see "Source".             |

## Nitro spec mapping

The public component is a thin wrapper around the generated host component:

- `PdfView` (public) → `BetterPdfView` (native, name chosen to avoid
  colliding with other libraries' `PdfView` component registrations).
- `ref` → a small object implemented over `hybridRef` that converts
  `{ animated }` options into positional booleans and hides `getStats`
  plumbing.
- Callbacks are routed through refs so their identity never changes after
  mount; changing `onPageChange` between renders costs no native prop update.
- `onDropView` releases the callbacks on both platforms. Native holds each
  as a JS function, a GC root; its closure shares an environment with the
  ref that `hybridRef` fills, and the hybrid object owns the native view.
  Kept, that cycle kept every closed viewer alive for the life of the JS
  runtime (measured, `docs/performance/results.md` §14). The JS side keeps
  its ref: StrictMode's simulated unmount would not call `hybridRef` again.

## Links and taps

```ts
onLinkPress?: (event: { url: string }) => void;
onPageTap?: (event: { page: number; x: number; y: number }) => void;
```

Decisions:

- **URL links are reported, never opened.** A PDF is untrusted input; opening
  `tel:`, `sms:`, custom schemes or phishing URLs is the app's policy
  decision. Without `onLinkPress`, URL links do nothing (react-native-pdf
  behaves the same way).
- **Links to other pages are followed natively** and not reported: JS has
  nothing to decide, and a round trip would add latency to navigation.
- **A tap is either a link press or a page tap**, never both. Taps in gaps
  between pages are not reported.
- **Coordinates are PDF points from the page's top-left**, identical on both
  platforms and at every zoom level, so they can position overlays or
  annotations. Verified end-to-end: a tap at 85% of an A4 page's width
  reports x ≈ 505 on both platforms. The page is the page as displayed
  (crop box, rotation applied); see "Page overlays" for rotated pages.
- **Single taps wait for the double-tap window**, so a double tap zooms
  without also producing a page tap. This adds ~300 ms of latency to
  `onPageTap`. With `enableDoubleTapZoom={false}` nothing waits: every tap
  is reported on finger up (a double tap becomes two page taps).
- **Names** follow this library's event naming (`onPageChange`,
  `onPageTap`, `onLinkPress`) rather than react-native-pdf's.

Platform limitations (measured, not assumed):

- Android reads links from the file with the library's own parser
  (`PdfLinkReader`), on every Android version: URI actions; GoTo actions
  and `/Dest` entries with explicit, named (the catalog's `/Dests`) and
  name-tree destinations, `/Fit` and the rest alike; the Named actions
  NextPage, PrevPage, FirstPage and LastPage; a link's `/QuadPoints` (one
  area per line) over its `/Rect`. Other actions (another file, launching
  an app, JavaScript) are not links; taps on them are page taps. The
  platform API (`PdfRenderer.Page.getLinkContents()` / `getGotoLinks()`)
  was not enough: it needs Android 15 (or 12–14 with SDK extension 13),
  and on API 35 reports only internal links written as a GoTo action with
  an XYZ destination (`/Dest` arrays and `/Fit` destinations came back
  empty in a probe with four link forms).
- The links are read on a thread of their own once the document opens, so
  the first pages render meanwhile; a tap on a link before they arrive is
  a page tap. The forms are covered by `PdfLinkReaderTest` (`links`,
  `links-all`), and `viewer-links` passes on Android 14 without SDK
  extension 13, on Android 17 and on iOS.
- The parser does not decrypt. In password-protected documents (and in a
  damaged file whose page tree the renderer repaired: the parser's pages
  could differ) links come from `PdfRenderer` as before, page by page.
- iOS uses PDFKit, which handles every link form; on the `links-all`
  fixture it follows a Named action (PrevPage) and a page-number
  destination (`[4 /Fit]`, zero-based like Android) too.
- On both platforms a tap on an annotation whose action the viewer does
  not perform (JavaScript, launching an app) is a page tap (`viewer-links`).

## Load progress

```ts
onLoadProgress?: (event: { loadedBytes: number; totalBytes?: number }) => void;
```

- **Bytes, not a percentage.** The server may not report a size
  (`Content-Length` missing, or cleared because the response is
  transparently decompressed); `totalBytes` is then `undefined` and a
  percentage would be a lie. Apps derive the fraction when it exists.
- **Time-based throttle: at most one event per 100 ms, plus one final event
  on completion**, delivered before `onLoad`. Percent-step throttling was
  rejected: with an unknown size there are no percents, and for a fast
  download 1% steps still mean 100 events. The time contract bounds JS work
  at ~10 events per second for any file size or network speed (unit tested:
  400 MB in 2 s → 21 events).
- **Remote downloads only.** Local files open without a download phase;
  stored copies that are used, or confirmed unchanged by the server, open
  without progress events (see "Caching and offline").
- The throttle runs natively on the download thread
  (`ProgressThrottle` in Kotlin and Swift); JS never sees dropped updates.

## Layout modes

```ts
horizontal?: boolean;   // default false
enablePaging?: boolean; // default false
```

- **Two booleans, not a `layout` union.** They are independent (four valid
  combinations) and match react-native-pdf's and `ScrollView`'s names, which
  keeps migration a no-op.
- **Fit.** By default, vertical continuous fits the page width, as before;
  horizontal and paged layouts fit each page *whole* into the view, so one
  page is exactly one screen. `fitPolicy` overrides this.
- **Paging** snaps to exactly one page per swipe regardless of fling
  velocity (e2e: three fast swipes → three pages). While zoomed in, paging is
  suspended so the user can pan the page; zooming out resumes it.
- **Switching modes keeps the current page** and resets zoom.
- **Same axis on both platforms.** iOS pages with PDFKit's page view
  controller, which pages along `displayDirection` — vertically unless
  `horizontal` (verified in the view hierarchy and by the
  `viewer-layout-modes` e2e flow on both platforms).

## Fit policy

```ts
fitPolicy?: 'width' | 'height' | 'page';
```

- **Named values, same prop name.** react-native-pdf's `0 | 1 | 2` become
  `'width' | 'height' | 'page'`; keeping the name makes the migration a
  search-and-replace.
- **Default depends on the layout** (`'width'` vertical continuous, `'page'`
  with `horizontal` or `enablePaging`) and is resolved by the `PdfView`
  wrapper, so the native view always receives a concrete value.
- **Zoom is relative to the fit**: `minScale`, `maxScale` and `zoomTo(1)`.
- **Pages wider than the view** (e.g. `'height'` with landscape pages) scroll
  sideways; content starts centred. **Pages longer than the view in paged
  mode** (e.g. `'width'` with a receipt) scroll within the page on Android
  before the next swipe turns the page.
- **Platform differences, measured on the `mixed-sizes` fixture:**
  - Android fits every page individually. PDFKit applies one scale to the
    whole document: `'width'` in a vertical layout uses PDFKit's size-to-fit
    (the widest page fits); other fits are computed from the first page. On
    documents with uniform page sizes the platforms match.
  - iOS paging (PDFKit's page view controller) turns the page on any swipe,
    also inside a page longer than the view; such a page is read by zooming.

## Single page

```tsx
<Pressable onPress={() => open(page)}>
  <PdfView source={source} singlePage initialPage={page} style={thumb} />
</Pressable>
```

- **Same meaning as react-native-pdf:** one page, touches disabled — a
  thumbnail or preview. The page is `initialPage` (react-native-pdf used its
  `page` prop); `ref.goToPage()` switches it without animation.
- **Touches pass through** to React Native parents on both platforms, so a
  `Pressable` receives taps and a scrolling list scrolls from on top of the
  thumbnails (e2e: `viewer-single-page` taps a thumbnail and scrolls a strip
  of twelve by dragging on them). Consequently no `onPageTap`/`onLinkPress`,
  no zoom.
- **Only that page is rendered** (Android skips prefetch entirely); measured
  on Android: twelve thumbnails of the 100-page fixture added 0.2 MB PSS and
  4.8 MB native heap (one measurement, emulator). Each view opens the
  document itself, so long thumbnail lists should be virtualized
  (`FlatList`).
- Takes precedence over `horizontal` and `enablePaging`; default fit is
  `'page'`.

## Right to left

- **Meaning:** in a horizontal layout the first page is on the right and
  reading continues to the left (Arabic, Hebrew, manga). Vertical layouts and
  `singlePage` are unaffected.
- **Page numbers keep document order** on both platforms. react-native-pdf's
  Android implementation reversed the page array instead, which also reversed
  vertical layouts and every page number reported to JS; we don't replicate
  that.
- **Android:** the layout mirrors positions only; page indices, render
  requests, links and taps are unchanged. Paging snaps, prefetch direction
  and the "last page at the far end" rule follow scroll order
  (`nextInScrollOrder`), unit tested.
- **iOS (measured):** PDFKit's `displaysRTL` takes effect only with its page
  view controller (`enablePaging`); in a continuous horizontal layout it is
  ignored and pages stay left to right — the same as react-native-pdf on iOS.
  Mirroring the view ourselves would mirror the page content too. The e2e
  flow (`viewer-rtl`) runs the continuous part on Android only.

## Android 12–14: `PdfRendererPreV`

Passwords, links, text selection and annotation flags come from
`PdfRenderer` APIs added in Android 15. The same renderer ships to Android
12–14 as `PdfRendererPreV` in the PDF module that Google Play system updates
deliver (SDK extension 13).

- **Selection:** API 35+ uses `PdfRenderer`; API 31–34 with
  `SdkExtensions.getExtensionVersion(S) >= 13` uses `PdfRendererPreV` for
  every document (not only encrypted ones), so those devices get the whole
  API 35 feature set; anything else uses `PdfRenderer` without passwords.
  Both sit behind `DocumentRenderer`/`DocumentPage`, so the session, the
  renderer recycling and every feature are shared.
- **Error for unsupported devices:** `unsupported-pdf`, "Password-protected
  PDFs require Android 15, or Android 12–14 with the updated PDF module (SDK
  extension 13)".
- **Tested:** no emulator image for API 31–34 carries extension 13 (the
  newest, `android-34-ext12`, stops at 12; the plain API 34 image has 7). The
  `PdfRendererPreV` path was therefore verified by forcing it on the API 35
  emulator (extension 13): password, links, text selection, rendering,
  errors, outline, initial page/zoom and annotations flows all pass, and the
  annotation rendering matches `PdfRenderer`'s. The selection and fallback
  were verified on the API 34 emulator (extension 7): regular documents
  render through `PdfRenderer`, and an encrypted one reports the error
  above (`e2e/android-legacy/encrypted-unsupported.yaml`). A real Android
  12–14 device with extension 13 has not been tested.
- Android 7–11 (API 24–30) cannot be supported with the platform renderer.

## Initial scale and file path

- **`initialScale`** replaces react-native-pdf's `scale` prop the way
  `initialPage` replaces `page`: applied once when a document loads, clamped
  to [`minScale`, `maxScale`], later zoom through `zoomTo`. It is the
  baseline for `onScaleChange`, not an event. The initial page starts at its
  top and, when zoomed wider than the view, horizontally centred — on both
  platforms (e2e `viewer-initial`: at 2× a tap at the screen centre reports
  x ≈ 297 pt, the middle of an A4 page).
- **`onLoad`'s `filePath`** replaces `onLoadComplete`'s `path`: the absolute
  path of the file actually displayed — the source itself for local files,
  the stored copy for downloads (valid until it is removed), the
  extracted copy for bundled assets. `undefined` when no file exists on disk
  (Android `content://`). No `file://` prefix, matching `{ filePath }`
  sources.
- Found while adding `initialScale`: on iOS, showing a new document went
  back to the page PDFKit considered current after navigating to
  `initialPage` (a "keep the current page" step meant for layout changes ran
  on first presentation). Invisible before, since zooming was what exposed
  the order; fixed.

## Annotations

Annotations are drawn from their appearance streams, always on (no
`enableAnnotationRendering` switch — nothing to gain from hiding them).
Measured with the `annotations` fixture (one annotation of each kind, with
appearance stream and Print flag):

Android 12–14 with SDK extension 13 behaves like Android 15–16 (the same
flags through `PdfRendererPreV`; verified by forcing that renderer on an
API 35 emulator).

| Kind | iOS | Android < 15 | Android 15–16 | Android 17+ |
| --- | --- | --- | --- | --- |
| Form field (widget) | ✅ | ✅ | ✅ | ✅ |
| Highlight | ✅ | ❌ | ✅ | ✅ |
| FreeText | ✅ | ❌ | ✅ | ✅ |
| Text (sticky note) | ✅ | ❌ | ✅ | ✅ |
| Stamp | ✅ | ❌ | ❌ | ✅ |
| Square, Circle, Line, Polygon, Ink, Underline, StrikeOut | ✅ | ❌ | ❌ | ❌ |

- Android's `PdfRenderer` draws no annotations but form fields unless asked
  through `RenderParams` flags (API 35: highlight, text; API 37 adds stamp,
  free text). Print mode draws none either (tested with the Print flag set).
  The flags are passed from API 35 on (`PdfRendererPage+renderForDisplay.kt`;
  API 37's as literals, as the module compiles against API 36; verified on an
  API 37 emulator). Measured cost: none beyond noise (mean page render
  41.2 ms without vs 41.9 ms with the flags, 100-page fixture, 3 runs each).
- API 37's annotation API (`getPageAnnotations`) covers the same three kinds,
  so it cannot fill the gap either. The remaining kinds would need drawing
  the appearance streams ourselves — e.g. by merging them into a rendering
  copy of the page — which is not implemented.
- The earlier claim "annotations always render" was wrong for Android; it
  was found while checking API parity with react-native-pdf (whose bundled
  PDFium draws every kind).

## Scale change

```ts
onScaleChange?: (event: { scale: number }) => void;
```

- **Settled, not per frame.** react-native-pdf's `onScaleChanged` fired
  during gestures, putting JS on the hot path. This event fires once the
  zoom settles — after a pinch or double tap ends or `zoomTo` finishes —
  whatever caused it, and only when the value changed (by more than 0.1%).
  Rotation and fit changes keep the relative zoom and send nothing; the
  initial fit is the baseline, not an event.
- **Same unit as `zoomTo`/`minScale`/`maxScale`**: relative to the fitted
  page.
- **Detection:** Android reports in its existing 80 ms settle step (the one
  that renders zoom tiles). iOS debounces `PDFViewScaleChanged` by 100 ms and
  waits while the scroll view is zooming, bouncing or tracking; PDFKit posts
  that notification only when an animated zoom (its double tap) *starts*, so
  scale changes seen through the scroll offset's per-frame KVO also restart
  the wait.
- **iOS paged layouts:** PDFKit's page view controller fits every page
  itself, with insets that differ from the continuous layout (measured: a
  226×3000 pt page at 0.1939 vs 0.1918 computed — enough for a spurious
  "1.01"). The scale PDFKit shows a page at when it arrives is taken as that
  page's 1.0, and the check waits out page turns (during one, `currentPage`
  and `scaleFactor` briefly disagree — measured as a spurious "3.52").
- **Tested** (`viewer-scale-change`): an animated `zoomTo(2.5)` yields exactly
  one event (a counter in the example app), scrolling while zoomed and
  repeating the same zoom yield none, double tap yields one, and paging
  through pages of different sizes yields none. The Nitro
  boundary test lists it as reviewed (name matches its per-frame guard).
- **Measured difference:** iOS's double tap zooms to PDFKit's own step (1.70
  for an A4 page on an iPhone 17 Pro simulator); Android's to 2×.

## Double-tap zoom

- **Same name and default as react-native-pdf.** `false` disables the
  double-tap zoom only; pinch and `zoomTo` keep working.
- **Taps become immediate when it is off**: there is no second tap to wait
  for. Android detaches GestureDetector's double-tap listener (every tap is
  handled on finger up); iOS disables PDFKit's zoom double-tap recognizer —
  the text interaction's double tap (select a word) stays — and the page-tap
  recognizer stops requiring double taps to fail (decided per gesture in
  `shouldRequireFailureOf`, so recognizers PDFKit creates later, e.g. for
  paging, are covered).
- **Tested through `onPageTap` coordinates**: a tap at 85% of an A4 page's
  width reports x ≈ 505 pt only when not zoomed (`viewer-double-tap`, with a
  zoom-on control). Android runs it through adb (`double-tap/run-android.sh`):
  Maestro's double tap leaves ~17 ms between taps, below GestureDetector's
  40 ms minimum, so it never registers — and a Maestro-only "off" test would
  pass even if the setting did nothing.

## Scroll enabled

```tsx
<ScrollView>
  <PdfView source={terms} scrollEnabled={false} style={{ height: 420 }} />
</ScrollView>
```

- **Same name and default as React Native's `ScrollView`** and
  react-native-pdf (whose iOS side set `UIScrollView.scrollEnabled`, and
  Android side `enableSwipe(false)`).
- **Only dragging is disabled** — flings and paging swipes included. Pinch
  and double-tap zoom, taps, links and `goToPage` / `zoomTo` keep working.
- **The drag belongs to the parents**: the typical use is a document inside
  a scrolling form. Android no longer blocks parent interception on touch
  down (only once a pinch starts); iOS disables panning on every PDFKit
  scroll view (including the page view controller's) and re-applies it when
  the layout mode replaces them. The e2e flow `viewer-scroll-enabled`
  checks both directions: with `false` a drag on the document scrolls the
  form, with `true` the same drag scrolls the document and not the form.

## Text selection

```ts
enableTextSelection?: boolean; // default true
onTextSelectionChange?: (event: { text: string }) => void;
```

- **Same names and default as react-native-pdf**, whose iOS viewer always
  let PDFKit select text: its `enableTextSelection` only gated the event
  (and Android had no selection). Here `false` really turns selection off,
  and both platforms select.
- **Platform UI, as in a read-only text field.** A long press selects the
  word under the finger, handles adjust the selection, and the system menu
  offers Copy and Select all. iOS is PDFKit's own selection (a selection can
  span pages; Select all selects the document). Android draws its own on the
  platform renderer's text APIs, with the theme's highlight colour and
  handles and a floating toolbar; a selection stays on one page and Select
  all selects that page. Copying ends the selection on Android, as in a
  `TextView`; PDFKit keeps it (checked on iOS 26.5).
- **Settled, not per frame.** PDFKit posts a selection change for every
  handle movement, and react-native-pdf forwarded each one. This event fires
  once the selection stops changing and no finger is down — after a long
  press, a handle drag, Select all or a clear — and only when the text
  differs from the last event. `text` is `''` when the selection is cleared
  (a tap elsewhere, Copy on Android, loading another document).
- **Taps**: a tap while text is selected clears the selection and is still
  reported through `onPageTap` (or `onLinkPress`), on both platforms
  (verified end-to-end). On iOS a double tap on text also selects a word
  (PDFKit's text interaction; with double-tap zoom on or off); Android
  selects only on a long press. A long press with selection off does nothing: it
  is not a tap on either platform.
- **Disabling on iOS** uses public API only: the gesture recognizers every
  `UITextInteraction` in PDFKit's view tree lists in
  `gesturesForFailureRequirements` (long press, word taps, handle drags) are
  disabled, and re-disabled when PDFKit creates new page views (layout
  changes, paging). Link taps are not among them. Probed on iOS 26.5:
  PDFKit hosts selection in `UITextInteraction`s on `PDFDocumentView`
  (continuous) and on each page's `PDFTextInputView` (paged).
- **Settle detection on iOS**: PDFKit's handles track their own touches, so
  the scroll view's `isTracking` stays false during a handle drag. A
  passive gesture recognizer on the container (never recognizes, cancels or
  delays anything) reports whether a finger is down; the event waits 100 ms
  after the last change and until the finger lifts.
- **Android threading**: page text is only reachable through an open page,
  and `PdfRenderer` allows one open page at a time, so selections are
  resolved on the document's render thread. They use an urgent slot in the
  render queue: taken before any pending render, never dropped by a viewport
  change, and replaced only by a newer selection (a handle drag sends one
  per move, ~100 per second; only the latest waiting one runs). Results
  older than the one on screen are discarded. These page opens do not count
  towards the renderer recycling (`RECYCLE_AFTER_PAGES`): they re-open the
  same, already parsed page.
- **Android boundaries**: `PageSelection`'s public model keeps a
  boundary's text index only when it has no point, and PdfRenderer always
  resolves boundaries to points (measured on API 35: index `-1`, point on the
  baseline). During a handle drag the fixed end is therefore sent back as
  the point PdfRenderer returned, which resolves to the same character. A
  handle dragged past the other end does not reverse the selection:
  PdfRenderer stops at the first character. Line breaks (`\r\n` in
  PdfRenderer's text) are reported as `\n`, as on iOS.
- **Android 15+ (API 35)**, like links: `PdfRenderer.Page.selectContent` is
  where the platform renderer started exposing text — also on Android 12–14
  with SDK extension 13 (`PdfRendererPreV.Page.selectContent`). Other
  devices ignore long presses.
- **Not yet on Android**: selections across pages, and scrolling while a
  handle is dragged to the view's edge.
- **Tested** (`viewer-text-selection`, both platforms): a long press
  selects "Page" (event #1), dragging the stop handle over the next line
  produces exactly one event (#2), a tap clears (#3) and is still a page
  tap, and with selection off neither a long press in the continuous nor in
  the paged layout selects anything (no Copy menu, no event); turned back
  on in the paged layout, a long press selects again.

## Outline

```ts
const toc: PdfOutlineItem[] = await ref.current.getOutline();
type PdfOutlineItem = { title: string; page?: number; children: PdfOutlineItem[] };
```

- **A method, not an `onLoad` field** (react-native-pdf's 4th
  `onLoadComplete` argument): most apps never show it, and reading it costs
  real time on large outlines (measured below). One-shot async work is a
  `Promise`. Cached after the first call.
- **A tree in JS, flat at the Nitro boundary.** Nitro structs cannot be
  recursive; native sends `{ title, page?, depth }` depth-first and the
  wrapper rebuilds the tree (`buildOutlineTree`, unit tested).
- **`page` is optional**: grouping entries, URL/remote actions and broken
  references have no page in this document.
- **Android has no outline API** (checked `android.jar` for API 35–37:
  `PdfRenderer` exposes links, text and images only). A bounded reader of
  the PDF structure (`outline/`) parses cross-reference tables and streams
  (incl. `/Prev` chains and hybrid files), object streams, the outline,
  explicit/GoTo/named destinations (name tree and PDF 1.1 `/Dests`) and the
  page tree, and rebuilds broken cross-references by scanning like viewers
  do (`startxref` is looked for in the last 64 KB: files may end in junk).
  Cross-references that read but do not lead to the catalog are rebuilt
  too. The rebuild takes the newest trailer naming a catalog — a `trailer`
  dictionary or a cross-reference stream's — and without one the newest
  catalog among the objects, those in object streams too; it stops at
  1,000,000 objects.
  The cross-references are read once per document and shared by every
  read, so a damaged file is scanned once. It reads a duplicate of the file
  descriptor with positional reads, so rendering is not blocked.
- **Untrusted input**: every recursion and loop is bounded (64 levels,
  10,000 entries, nesting, string, stream-expansion and xref limits; objects
  whose reading needs another object — an indirect `/Length`, an object
  stream — nest 32 deep at most); cycles are broken. So is time: the
  parsers of one read examine at most 16 bytes for each byte of the file
  and of the object streams it decodes, plus 1 Mi, and the read fails
  beyond that. Without it, objects whose parse runs on to the end of the
  file — a stream without `endstream`, an unterminated string — were parsed
  once per object or per reference, in time growing with the square of the
  file's size (`PdfReadTimeTest`: 4–14 s for crafted files of a few hundred
  KB, now under 0.3 s). Decoding is bounded too: a stream's data is at
  most 32 MB, read or decoded; one read reads and decodes at most 16 bytes
  of streams for each byte of the file, plus 64 MB; it keeps the most
  recently used object streams up to 32 MB of decoded data (others are
  decoded again when needed), and an object
  stream it cannot decode (an unsupported filter, data expanding beyond
  the 32 MB limit) is not decoded again for every object needed from it
  (`PdfDecodeTimeTest`: 100 object streams expanding to 31 MB each, 3 GB
  before; 5,000 links into an undecodable 4 MB stream, 20 GB read). The
  fixtures and files from PDFKit, Quartz and pypdf use at most 1.4 parse
  units and 0.9 stream bytes per byte, rebuilds included. Deterministic
  fuzz tests (500 mutated files, 300 reads of generated chains of up to
  2,000 objects on a 256 KB stack, and 200 reads of generated files of
  such runaway objects) must end in a result or `PdfFormatException`
  within 2 s each. A stack overflow the limits miss reads as
  `PdfFormatException` too, never a crash. Encrypted documents reject.
- **Tested against three producers** (the generator in classic and
  object-stream form, PDFKit, pypdf), with PDFKit as the reference.
- **iOS** walks `PDFOutline` with the same limits and title normalization.
  PDFKit objects are not thread-safe and the view's document is used by
  `PDFView`, so the outline is read from a separate `PDFDocument` of the same
  file on a background queue.
- **Measured** (`outline-large`: 1,000 entries, 3 runs; example app's
  timing):

  | | first call | cached |
  | --- | --- | --- |
  | Android emulator | 60–83 ms, background thread | 2–6 ms |
  | iOS simulator | 219–223 ms, background queue | 0–1 ms |

  Reading on the iOS main thread was faster (130 ms) but blocked it for ~8
  frames; rejected.
- **Known difference:** PDFKit leaves PDF 1.1 named destinations
  (`/Dest /name` looked up in the catalog's `/Dests`) without a page; Android
  resolves them.

### Props are never reset to `undefined` natively

Removing a prop (`fitPolicy="page"` → no prop) makes Fabric send `null`,
which Nitro 0.37's optional converters reject with a fatal JS error ("Value
is null, expected a String"). This affected every optional prop and was
found while cycling `fitPolicy` back to its default. The wrapper therefore
sends concrete defaults for every configuration prop and keeps an event
subscribed once it has been (a removed handler becomes a no-op). Covered by a
unit test and the last step of the `viewer-fit-policy` e2e flow.

## Accessibility

Measured first (the accessibility tree, as Maestro reads it on both
platforms — the same tree TalkBack and VoiceOver use):

| Before | iOS (PDFKit) | Android |
| --- | --- | --- |
| Page text | ✅ exposed by PDFKit | ❌ no node at all — the document was invisible to TalkBack |
| Which page | ❌ only the scroll bar ("99 pages" = screens, not PDF pages) | ❌ |

Design:

- **Android:** `PdfAccessibilityHelper` (`ExploreByTouchHelper`) exposes one
  virtual node per visible page — label plus the page's text, bounds clipped
  to the view — inside a host that reports itself as a scrollable
  `ScrollView` with collection info (page count) and handles
  `ACTION_SCROLL_FORWARD/BACKWARD` (a page in paged layouts, 90% of a screen
  otherwise). The host's state description follows the current page.
- **Text is read only while an accessibility service is on**, only for
  visible pages, as `PageTextQuery` work queued after their renders (dropped
  with them when they scroll away) and cached for the 32 pages nearest the
  reader. Without a service nothing changes. It comes from
  `getTextContents` — Android 15+, or 12–14 with SDK extension 13; older
  versions read the label only (verified on API 34: "Page 1 of 100").
- **iOS:** PDFKit already exposes the text; the viewer posts
  `UIAccessibility.pageScrolled` with the label on page changes while
  VoiceOver runs.
- **One string, localisable:** `pageAccessibilityLabel` with `{page}`
  (1-based) and `{pageCount}`. A template rather than a JS callback, so no
  native→JS round trip is needed to announce a page.
- **`testID` keeps working:** React Native maps `testID` to the view's
  resource id in its own accessibility delegate, which it installs only on
  views without one; ours replaces it, so the helper sets the resource id
  itself. Found by the regression run (`viewer-scroll-enabled` could no
  longer find `embedded-pdf`).
- **Tested:** `viewer-accessibility` asserts the page nodes with text on
  Android (first and last page), a localised label, and PDFKit's text on
  iOS. Not tested automatically: speech itself, TalkBack's scroll actions
  and the VoiceOver announcement (they need a real screen-reader session).

## Search

```ts
// ref
search(query: string): void;   // '' clears
searchNext(): void;
searchPrevious(): void;
clearSearch(): void;
// prop
onSearchChange?: (event: PdfSearchChangeEvent) => void;

interface PdfSearchChangeEvent {
  query: string;
  matchCount: number;
  currentMatch?: number;   // zero-based, document order
  currentPage?: number;    // zero-based
  isComplete: boolean;
  isSupported: boolean;
}
```

The most requested missing feature of react-native-pdf (7 issues, see
`community-requests.md`).

- **Methods plus one state event, not a `Promise<Match[]>`.** Searching a
  large document takes time; a Promise would show nothing until the end and
  would hand JS every match's rectangles, which JS cannot use (highlights and
  scrolling are native). The viewer owns the matches; JS gets the state it
  needs for a "3 / 17" label and next/previous buttons.
- **One event with the full state** rather than separate progress / match /
  current events, so a `useState` setter can be passed directly. Throttled
  to ≤ 10/s while pages are searched (a 1000-page document would otherwise
  send 1000 events), immediate for the first match, navigation, completion
  and clearing.
- **Starts at the current page.** Pages are searched from the reader's page
  to the end and then from the start; the first match at or after the
  current page becomes current, as in browser find bars. `currentMatch` is
  still the index in document order, so the label stays meaningful when
  more matches arrive before it.
- **Case-insensitive, no options yet.** PDFKit takes `.caseInsensitive`;
  Android's `PdfRenderer.Page.searchText` always ignores case. Whole-word
  or case-sensitive search would need filtering on one platform only, so it
  waits for a request.
- **`isSupported` is a runtime capability**, not a platform check: Android
  gains text search with the renderer's text API (15+, or 12–14 with SDK
  extension 13), and devices without it end the search at once with
  `isSupported: false`. The flag is the same for every search on a device,
  so an app can hide its search UI after the first search reports `false`.
- **Highlights**: all matches in translucent yellow, the current one in
  orange, on both platforms (PDFKit `highlightedSelections`; Android draws
  them over the page bitmaps, so zoom and tiles keep them aligned).
  PDFKit's setter costs time per selection on the visible pages (measured:
  1,602 matches on three pages, 900 ms as separate selections, 5 ms as one
  selection per page, drawn the same), so iOS hands it each page's matches
  as one selection, for the visible pages and one either side, plus the
  current match, and updates the list as pages come into view (see
  `performance/results.md`, section 15).
- **Colours are props** (`searchHighlightColor`, `searchCurrentHighlightColor`,
  `ColorValue`), not arguments of `search()`: they are styling, belong with
  the rest of the view's appearance and apply to a search already shown.
  The wrapper flattens them with `processColor` to the ARGB number the
  native props take; `PlatformColor` and `DynamicColorIOS` cannot be
  flattened, so they fall back to the defaults (a dark-mode app passes a
  colour chosen from `useColorScheme()`). PDFKit keeps pages it has drawn
  when only a highlight's colour changes, so iOS resets
  `highlightedSelections` when colours or the current match change
  (measured: without it, the old colour stayed on screen).
- **Tested:** `viewer-search` (both platforms) checks counts, next,
  previous, wrapping backwards, the page scrolled to, a query in different
  case, a custom highlight colour and the highlights on a 1,000-page
  document (checked on the screenshots), and no matches.
  `SearchResultsTest` covers ordering, indexes and wrapping.

## Viewport

```ts
// props
initialViewport?: PdfViewport;
onViewportChange?: (event: PdfViewportChangeEvent) => void;
// ref
goToViewport(viewport: PdfViewport): void;

interface PdfViewport { page: number; x: number; y: number; scale: number }
interface PdfViewportChangeEvent {
  viewport: PdfViewport;
  firstVisiblePage: number;
  lastVisiblePage: number;
  isAtEnd: boolean;
}
```

The second most requested feature of react-native-pdf (11 issues: save and
restore the position, scroll events, "read to the end"; see
`community-requests.md`).

- **A position is a page point, not a scroll offset.** Offsets depend on the
  screen size, zoom, page spacing and layout mode; a point on a page in PDF
  points does not, so a saved position survives rotation, another device or
  a switch to horizontal. The anchor is the view's top-left corner, the
  same corner on every layout (including RTL), so saving and restoring are
  exact inverses. The page is the one nearest to the corner, and
  `x` / `y` go negative in the margin or gap before it rather than snapping
  to the page, so restoring is exact there too. Verified end-to-end: a
  zoomed, scrolled position is reproduced to the rounding of the display
  (whole points) on both platforms, also through `initialViewport`.
- **Settled, never per frame.** Like `onScaleChange`: once the view stops
  (same settle rule as zoom on both platforms), and only when the position
  moved by more than rounding noise (0.5 pt, 0.001 zoom) or the visible
  pages or `isAtEnd` changed. Also once after loading (and after a
  rotation), so an app has a position without the user scrolling. There is
  no per-frame scroll event: it would put JS on the hot path, and every use
  case found (save, restore, read tracking) needs only settled positions.
- **The saved value is what the event carries.** `viewport` is nested
  rather than spread into the event so it can be stored and passed back
  unchanged (`initialViewport={saved}`, `goToViewport(saved)`); the event's
  other fields describe the screen, not the position.
- **`isAtEnd` is derived natively** because JS cannot know it: whether the
  last page's far edge (bottom; right, or left in RTL) is on screen depends
  on page sizes and spacing. The half gap around a page counts as the page's
  (a page exactly as wide as the view is "at the end" at its own slot).
- **`initialViewport` in addition to `goToViewport`:** calling the method
  from `onLoad` would show the first page for a frame. The native prop for
  the initial position is one internal struct (`page`, `scale`, optional
  `x` / `y`) that `PdfView` builds from `initialPage` / `initialScale` or
  `initialViewport`, so there is one code path per platform.
- **Before `onLoad`** `goToPage`, `goToViewport` and `zoomTo` are ignored
  on both platforms, also while a new `source` loads. The initial props are
  read when the document opens, so a saved position that arrives after
  mount goes there. Called from `onLoad` while the view has no size yet (a
  tab not shown, a container animated from zero width), they apply once
  the view is laid out: the first layout goes to that position and zoom
  instead of the initial ones, without animation. In continuous layouts
  the initial zoom stays the baseline, so a different zoom is reported
  through `onScaleChange` then, as after any `zoomTo`. In 1.0.1 iOS showed
  the initial position and zoom there instead (e2e `viewer-initial`, "Zero
  width" entries).
- **A page outside the document** is clamped, and its point dropped: the
  nearest page is shown from its top, for `initialViewport` and
  `goToViewport` on both platforms. Clamping is native because only native
  knows the page count. In 1.0.0 Android kept the point of a negative
  initial page and crashed on load (`initialViewport` with `page: -1`).
- **No animation** for `goToViewport`: it changes zoom and position at once,
  and its uses (resume, go back) are jumps. `goToPage` stays the animated
  navigation.
- **Paged layouts** restore page and zoom. Android also restores the point
  when zoomed in; iOS's page view controller has one scroll view per page,
  so the point within a zoomed page is not restored there. Both settle on
  the page when not zoomed, as a swipe does.

## Page sizes

```ts
// ref
getPageSizes(): Promise<PdfPageSize[]>; // index = zero-based page
interface PdfPageSize { width: number; height: number } // PDF points
```

react-native-pdf reported only the first page's size (#302, #346, #898 ask
for every page: overlays, thumbnails and layouts for mixed-size documents).

- **All pages at once, asynchronously.** One call answers every use case
  found (they all need many pages); a per-page method would be a native
  round trip per page. Async because it reads the file off the main thread,
  and cached for the document.
- **As displayed**: the crop box clipped to the media box, width and height
  swapped for /Rotate 90° / 270° — what both platforms draw, and what
  `onLoad`'s first-page size now reports on both (iOS used to report the
  unrotated media box).
- **Android without PdfRenderer:** opening every page in the renderer makes
  PDFium keep everything it parsed (measured: 400 MB of native heap for a
  1000-page scan, see `PdfDocumentSession`). The page tree already holds
  the boxes, so the `outline/` parser reads them — MediaBox, CropBox and
  Rotate with inheritance from `/Pages` nodes, PDFium's defaults (Letter
  when the media box is missing, no crop box when it misses the media box,
  quarter turns) — without opening a page. Encrypted documents (the parser
  has no security handler) and trees whose page count disagrees with the
  renderer fall back to opening each page on a separate renderer, recycled
  every 48 pages like the render thread's.
- **Tested** against PDFKit on a fixture with an inherited media box,
  rotations of 90°, 270°, 180° and −90°, a crop box, an offset origin and a
  crop box larger than the media box (`page-boxes`): unit tests for the
  parser (classic xref and object streams), and `viewer-page-sizes` on both
  platforms, including the encrypted copy that takes Android's renderer
  path.
- **Not used for layout yet.** Android's layout still starts from the first
  page's size and corrects pages as they are rendered (see
  [Limitations](limitations.md)); the page-tree sizes could remove that
  re-anchoring.

## Page images

```ts
// ref
renderPage(page: number, options: PdfRenderPageOptions): Promise<PdfPageImage>;
interface PdfRenderPageOptions { width: number; format?: 'png' | 'jpeg'; quality?: number }
interface PdfPageImage { uri: string; width: number; height: number } // file://, pixels
```

Requested in react-native-pdf as thumbnail export (#998, #43, #457, #811).

- **A file, not pixels in JS.** Every use found ends in an `<Image>`, a
  share sheet or an upload, all of which take a file URI; bytes in JS
  (base64 or an `ArrayBuffer`) would cost a copy through the JS heap for no
  use. The caller owns the file (cache directory, unique name per call).
- **`width` in pixels, height derived.** The image's unit is pixels and the
  result reports pixels; a caller showing it at `w` points multiplies by
  `PixelRatio.get()`. The height follows the page's displayed aspect ratio
  (see "Page sizes"), so rotated and cropped pages come out as on screen.
  Requirements are validated (page in range, `width` 1–4096, at most
  4096 × 4096 pixels so a tall page cannot exhaust memory) and reject
  rather than clamp: a silently smaller image would be a surprise.
- **Separate renderer, off the main thread.** Android opens a short-lived
  renderer on a duplicate of the file descriptor (PDFium serialises renders
  process-wide, so this costs the view at most one page's render), renders
  on white like the view, and closes it, so nothing stays cached. iOS draws
  with PDFKit on a separate `PDFDocument` on the read queue (PDFKit objects
  are not thread-safe). Measured on the simulator / emulator: 3–15 ms
  for a 300 px PNG of a one-line text page, file write included (scanned
  or dense pages take longer: their render time).
- **On the view's ref**, because the view owns the open document (and its
  password). `PdfDocument` renders without a view (see "Documents without
  a view").
- **Tested:** `viewer-page-image` on both platforms checks pixel sizes for
  a plain and a rotated page, PNG and JPEG, and the encrypted copy; the
  screenshots were checked for orientation and content against the viewer.

## Caching and offline

```ts
type PdfUriSource = {
  uri: string; headers?: Record<string, string>;
  cache?: 'session' | 'persistent'; // default 'session'
  maxAgeMs?: number;                // default: never revalidated
  cacheKey?: string;                // default: uri + headers
};
PdfCache.download(source, { onProgress?, signal? }): Promise<PdfCacheEntry>;
PdfCache.getEntry(source): Promise<PdfCacheEntry | undefined>;
PdfCache.remove(source): Promise<boolean>;
PdfCache.clear(): Promise<void>;
PdfCache.getByteSize(): Promise<number>;
interface PdfCacheEntry { filePath: string; byteSize: number; cache: PdfCacheMode; validatedAtMs: number }
// PdfLoadEvent gains `isStale: boolean`.
```

Requested in react-native-pdf as cache control, purge, per-URL
invalidation, prefetch and offline reading (#952, #964, #572, #566, #689,
#696, #246); audit M5.

- **Options on the source, not a separate config.** `PdfView` and
  `PdfCache` take the same object, so "download for offline" and "open"
  cannot disagree about which copy they mean. react-native-pdf's
  `cache: true` becomes `cache: 'persistent'` (a type error points there).
- **Two lifetimes.** `'session'` (the 0.1 behaviour) keeps a download until
  the app exits; the previous launch's session copies are deleted on first
  use, so they no longer linger in `Caches` until the OS purges them.
  `'persistent'` lives where the OS does not purge it (iOS Application
  Support, Android `noBackupFilesDir`) and is excluded from backups (Apple
  rejects apps that back up re-downloadable content). An offline copy that
  can vanish under storage pressure would not be an offline copy. A
  persistent copy also serves session requests; a session copy becomes
  persistent through a hard link, so paths already handed out stay valid.
- **`maxAgeMs` revalidates, it does not delete.** react-native-pdf's
  `expiration` deleted the copy and downloaded again. Here an older copy is
  checked with a conditional request (`ETag` / `Last-Modified`, stored next
  to the copy): `304` costs one round trip, `200` replaces the copy. `0`
  checks on every open. Server `Cache-Control` is not interpreted: the app
  knows how fresh its documents must be, and the copy must survive offline
  whatever the server said. Milliseconds, as everywhere in JS; the unit is
  in the name.
- **Offline: stale copy in the view, error in `download()`.** When
  revalidation fails because the server cannot be reached (connection
  error, a timeout — 10 s instead of 30–60 s, so a dead network shows the
  copy soon — or 5xx), `PdfView` shows the stored copy and reports
  `isStale`; a reader would rather see yesterday's document than an error.
  `PdfCache.download()` is an explicit "make sure it is current", so it
  rejects and leaves the copy. 4xx is an error in both: the server answered.
- **`cacheKey`** replaces `uri` + `headers` as the key. With S3-style
  signed URLs or rotating bearer tokens the default key changes on every
  open, so a persistent copy would never be found again (and each one would
  be orphaned). It is also the document's identity: a new URL or token with
  the same key updates the source without reloading the view.
- **Only PDFs are stored.** A `200` that does not start with `%PDF-` within
  1024 bytes (login page, captive portal) fails with `invalid-pdf` instead
  of being kept — persistently — as the document.
- **One download per copy.** A list prefetching a document and a viewer
  opening it share one request (`SharedDownload`); cancelling one waiter
  leaves the others, and the request stops when nobody waits.
- **Copies replaced or removed while displayed.** Files are replaced with
  `rename(2)` and removed with `unlink`, so open descriptors keep the old
  data: Android's renderer holds one; PDFKit keeps its file open (verified:
  after both, an open `PDFDocument` still reads the old pages). iOS also
  re-opens the displayed file by path for outline, page sizes and page
  images, so it opens a *pin* — a hard link in `Caches/BetterPdf/open`,
  released with the document — instead of the copy itself.
- **`PdfCache` is a JS object over an internal Hybrid Object**
  (`BetterPdfCache`), against the usual rule of exporting Nitro objects 1:1:
  Nitro rejections carry only a message, so failures cross as a result
  struct and become `PdfDownloadError`s with `code` / `httpStatus`, and
  `AbortSignal` cancellation needs JS (`cancelDownload(requestId)` natively).
- **Not included:** listing stored copies (it would mean storing URLs and
  headers, possibly with tokens, on disk), a size limit or LRU eviction
  (copies are the app's offline content, not a transparent cache),
  background transfers, `POST`.
- **Tested:** JVM tests run the Android cache against a local HTTP server
  (reuse, 304, changed document, stale fallback, 5xx vs 4xx, non-PDF
  responses, invalid headers, shared and cancelled downloads, `cacheKey`,
  promotion, restart, cleanup). `viewer-offline` runs the same story on both
  platforms with the fixture server's simulated outage
  (`/__control/offline`), including an app restart.

## Short documents, rounded corners, safe area

No new props: `style.borderRadius` and the layout of a short document now
behave the same on both platforms.

Requested in react-native-pdf as top alignment of a single page (#909,
#920), rounded corners on iOS (#920, PR #993), the first page starting
under the iOS status bar (#513, #994) and removable padding (#520, already
`pageSpacing={0}`).

- **A short document starts at the top** of vertical continuous layouts.
  Android always placed it there; PDFKit centres it by moving its document
  view down by half the free height (measured: content insets zero, the
  document view's origin at `(viewHeight - contentHeight) / 2`). PDFKit
  centres within the scroll view's content insets, so a bottom inset as
  tall as the free space leaves nothing to centre, and PDFKit keeps laying
  out pages itself. The inset follows the content size (zoom), and the
  offset is held at the top while the document fits: going to the first
  page while PDFKit still centred it left the offset below the moved-up
  document, cutting the page off (seen once the example's toolbar wrapped
  to another row). Horizontal
  layouts centre pages across and paged layouts centre each page in its
  slot, on both platforms; a centred vertical document has no use case
  that a paged layout does not cover, so there is no prop for it.
- **Rounded corners through the box.** `PdfView` renders the native view
  inside a `View` that takes `style` and the other View props, with
  `overflow: 'hidden'` and `collapsable={false}` (the box must exist for
  the clip and `testID`). Android ignores `borderRadius` on custom native
  views (React Native 0.86's `BaseViewManager.setBorderRadius` only logs
  "unsupported"; Nitro views use it); iOS rounds the background but clips
  children only with `overflow: 'hidden'`. Clipping the box works on both,
  verified with page content at the corners. Cost: one plain view; a
  shadow on iOS needs a surrounding view.
- **No automatic safe-area insets on iOS.** PDFKit's scroll view used
  `.automatic` (measured), but going to a page ignores content insets, so
  a viewer under the status bar can open with the first page partly behind
  it (as reported for react-native-pdf; not reproduced here, the example
  has no full-screen viewer). `.never` matches React Native's `ScrollView`
  default and Android.
- **Not included:** rounded corners on every page (not requested).
  Scrolling padding came later; see "Content inset".
- **Tested:** `viewer-short-document` on both platforms (the view's top
  within 9 PDF points of the page, also after zooming in and out), the
  corners on screenshots against a dark background, the box in a unit
  test, and the full e2e suite (the component tree changed).

## Closing while work runs

react-native-pdf's most reported Android crash is "Already closed"
(#830, #882, #976, #959, #989, #987, #1041): a renderer or page used after
it was closed. This library was audited for that class — anything closed
or released while another thread, or a callback posted earlier, still
uses it — and stress-tested.

- **Held by design:** the displayed document's renderer and its pages are
  opened and closed on the render thread only; `close()` only signals.
  Every other reader (outline, metadata, page sizes, page images, search,
  printing, export, the form) works on its own duplicate of the file
  descriptor, taken under a lock with which the session closes the
  original, so closing the document never invalidates one in use; a
  read started after closing rejects. Renderers never have two pages open.
  Bitmaps are recycled only once no frame can draw them.
- **Fixed by the audit:**
  - Exceptions Kotlin does not make anyone catch (`IOException`, the
    library's own `PdfFailure`) could end the render thread: an I/O error
    while copying a source crashed the app, and a renderer that could not
    be re-created after 48 pages ended rendering and left form, export,
    print and page-image promises pending. The render thread now catches
    them per work item (that page fails, the next item opens a renderer
    again) and when opening; the print and search threads catch running
    out of memory.
  - `close()` removed results already posted to the main thread, so a
    form read or export finishing just as the view went away never
    settled its promise; posts now stay and check whether the document
    is still open. Waiting for form work reports the closing as an error,
    and a print dialog opens only for the document still shown.
  - Exporting over the displayed file (`exportDocument({ filePath })` with
    `onLoad`'s `filePath`) truncated it while it was read — and on iOS a
    form as loaded deleted it before copying. Both platforms reject it
    now; Android writes exports beside the target and renames them over
    it, so a failed write leaves an existing file as it was.
  - A choice list open while the document changed applied its pick to the
    new document; it is closed with the old one.
  - A renderer that failed to open kept its file descriptor before Android
    15; it is closed now. Measuring page sizes could close a renderer twice
    and report "Document already closed" instead of the real error.
  - JS-thread methods read the session and the page count together, so a
    document swapped in meanwhile cannot mix them.
- **Tested:** `stress-close-during-work` on both platforms (5 rounds of
  leaving documents while renders, a search, a page image, a download, the
  outline, a form fill and export run, and `PdfDocument` closed while it
  renders page images: no crash in logcat or the iOS crash reports),
  `viewer-forms` (an export over the source is rejected), and the full
  suite on both.

## Content inset

```ts
contentInset?: { top?: number; bottom?: number }; // dp / pt, default none
```

Requested in react-native-pdf as padding pages scroll under (#520, PR
#993 unmerged) and the first page starting below a bar (#513, #994).

- **`ScrollView`'s semantics and name**: space the content may scroll
  into at each end, not padding inside it; not scaled by zoom. Top and
  bottom only — the requests are bars above and below; left and right can
  be added without breaking the type. Negative and non-finite values are
  0; `PdfView` sends concrete values (Fabric's null-prop rule), stable
  while unchanged.
- **Vertical continuous layouts only** (facing pages included), like
  `spreadMode`: paged layouts use a page view controller on iOS, and bars
  over a horizontal document would need the cross axis fitted instead.
- **Positions refer to the area between the insets**: `goToPage`, the
  initial position, `goToViewport` and `onViewportChange` use its top-left
  corner; the current page is the one at its centre; `isAtEnd` is the
  last page's bottom above the bottom inset; search results scroll into
  it; zoom buttons zoom around its centre; `fitPolicy` `'height'` /
  `'page'` fit pages into its height. One place per platform defines it
  (`clearance` on iOS, `clearTop` / `clearBottom` on Android, zero outside
  vertical continuous layouts) and every geometry helper goes through it.
- **iOS**: the scroll view's `contentInset` — PDFKit lays pages out and
  zooms with it set (measured with 120 pt: no drift after 2.5× and back),
  one function owning it together with the free height that keeps a short
  document at the top. `go(to:)` ignores content insets (measured: the
  page's top lands at the view's top, exactly the inset under the bar), so
  page navigation moves the page below the inset afterwards, as far as the
  scrollable area allows (a short last page stays at the end). The scroll
  indicator keeps between the bars.
- **Android**: the scroll bounds extend past each end by the inset (view
  pixels, so zoom does not scale it); the layout is untouched except for
  the height it fits pages into. The scroll bar counts the insets. The
  form editor is kept above the bottom inset too.
- **Tested:** `viewer-inset` on both platforms (the `Inset` entry: 80 /
  60 dp bars), a unit test for the prop, and the full suite on both.

## Forms

```ts
enableFormFilling?: boolean; // default true
onFormChange?: (event: { field: PdfFormField }) => void;
ref.getFormFields(): Promise<PdfFormField[]>;
ref.setFormFieldValues(values: Record<string, PdfFormFieldValue>): Promise<void>;
ref.exportDocument(options?: { filePath?: string }): Promise<{ filePath: string }>;
```

Requested in react-native-pdf with annotation editing and signature
fidelity (community request 6). PDFKit already let readers fill forms in
on iOS; Android showed them read-only.

- **Fields by name, typed by `type`**: a discriminated union (`text`,
  `checkbox`, `radio`, `combo-box`, `list-box`, `signature`,
  `push-button`), each with its own members only, set with plain values
  by fully qualified name (`'address.city'`). Native code sends one flat
  struct with every member concrete, which `PdfView` narrows; values go
  the other way as `{ text | isChecked | options }`. Choices are their
  displayed labels on both platforms (`/Opt` export values are mapped).
- **`onFormChange` reports the reader's changes only**, text when editing
  ends — settled, like the other events; code knows what it set.
- **All or none**: `setFormFieldValues` checks every value against its
  field (unknown, read-only, type, option, maximum length) before it
  changes anything.
- **iOS: PDFKit fills in, the library observes.** PDFKit posts nothing
  for value changes and sets widget values while it loads pages too
  (measured: a radio group's state on every load). A `PDFAnnotation`
  subclass for widgets (the document's `class(forAnnotationType:)`, as for
  text markup on dark pages) reports value sets; they count as the
  reader's only between `PDFViewAnnotationWillHit` and
  `PDFViewAnnotationHit` (buttons, measured) or while PDFKit's text field
  or view in the PDF view is editing (text and choices, set when editing
  ends). Writing the document sets buttons again; those are muted.
  `enableFormFilling={false}` overrides `isReadOnly` on the subclass:
  PDFKit honours it, and exports keep the field's own flags (`/Ff`
  unchanged, measured).
- **iOS names and order** come from the AcroForm tree through CGPDF;
  PDFKit's `fieldName` is fully qualified (measured). Pages with widgets
  are found from page dictionaries, so no page loads to list fields.
- **Android: the platform renderer edits, the library does the rest.**
  `PdfRenderer` (API 35) and `PdfRendererPreV` (SDK extension 13) report a
  page's widgets, apply edits (`getFormWidgetInfos`, `applyEdit`) and write
  the document, but widgets have no field names — the accessibility label
  is the tooltip (`/TU`) when there is one, else the partial name, and
  empty for radio buttons — and their indices count widgets only,
  signatures left out (all measured). So the field tree is parsed from the
  file (the parser that reads outlines; an encrypted document from an
  unprotected copy the renderer writes to the cache directory, deleted
  afterwards) and matched to the renderer's widgets by page and rectangle
  (±1.5 pt: the renderer rounds to whole points), the rest in order.
  Values then follow the renderer's widgets after every change.
- **Android: edits survive renderer recycling.** The render thread
  re-creates its renderer every 48 page opens to release PDFium's caches,
  and edits live in the renderer, so every edit is kept and applied again,
  in order, to each new renderer before it renders — all of them, as a
  field can show on several pages. Writes, printing and page images use
  renderers of their own with the same edits.
- **Android UI**: a checkbox or radio button changes on tap (a click
  edit); a choice opens a list; text is typed in an `EditText` over the
  field. The native view is now a `FrameLayout` holding the document view
  and that editor, which follows the page every frame (like page
  overlays), is scrolled above the keyboard from the IME inset
  (edge-to-edge windows do not resize), and is applied when the keyboard's
  action, a tap elsewhere, its page leaving the screen or a call from code
  ends editing. The changed page renders again, its old bitmap shown until
  then (the render thread posts in order, so no older result can follow).
- **Form work is never dropped**: edits, reads and sets run on the render
  thread in order, ahead of renders, through a new ordered lane of
  `RenderQueue` (text selection queries replace each other; edits must
  not).
- **Exports keep the password.** Android writes with
  `removePasswordProtection = false`, keeping the original encryption,
  and keeps the file's field order (measured on the fixture). On iOS a
  form as loaded exports as a copy of the file; a changed one is written
  with PDFKit (`dataRepresentation`, the document's password as user and
  owner password), which rewrites the whole document: PDF 1.3, nested
  fields flattened into dotted names, the `/Fields` order changed, and
  content PDFKit cannot read dropped (all measured; an image whose JPEG
  did not match its declared colour space, which PDFKit does not draw
  either, came out as an empty page — a fixture bug, since fixed, that
  showed it). Pages it can read come out identical (the 500-page scan,
  compared pixel for pixel). Default file:
  `<caches>/BetterPdfExports/<uuid>/<document name>.pdf`.
- **Exports never replace a directory.** A `filePath` naming one (or
  ending in `/`) is rejected on both platforms: iOS used to remove
  whatever was at the destination before copying a form as loaded, which
  deleted a directory with everything in it. Both platforms now write
  the copy beside the target and rename it over it, so a failed write
  also leaves an existing file as it was.
- **Exports never go into the download cache.** A remote document's
  `onLoad` `filePath` is the cache's copy. iOS displays it through a pin
  (a hard link) and compared the export path with the pin only, so an
  export to `filePath` replaced the copy: reopened within `maxAgeMs`,
  after a `304` or offline, the form came back filled in as if the server
  had sent it (seen on a simulator). Both platforms now reject `onLoad`'s
  `filePath` as the displayed file, and any path in the store's session,
  persistent, partial or pin directories (another document's copy,
  `PdfCache`'s `filePath`) with "The export path is in the cache of
  downloaded documents". Paths are compared after resolving symlinks, so
  `/data/data` and `/data/user/0` (Android) are the same directory.
- **Line breaks** are `\n` on both platforms (PDFium reports `\r\n`).
- **iOS writes off the main thread.** PDFKit writes a long document in
  seconds (30 ms for the fixture, 354 ms for the 100-page `medium`, 5.7 s
  for the 500-page scan `large`; macOS PDFKit, M5 Pro), and its objects
  are not thread-safe, so the displayed document cannot be written in the
  background. Instead the main thread reads the changed fields' widgets
  (a `FormSnapshot`) and a separate instance of the file, filled in with
  them, is written on a queue of its own — for exports, and for printing
  and page images once the form changed; `getFormFields` reads such an
  instance on the read queue. Measured on a 502-page form (the fixture's
  pages and `large`'s scans): the main thread 1.3 ms, the write 3.9 s in
  the background while the document kept scrolling (it took the main
  thread for the whole write before).
- **iOS loads only the pages a field is on.** Loading a page's widgets
  takes time, so the view finds a field's pages in the page dictionaries
  (a field → pages index, built on first use) and loads those. Reading
  every widget on the main thread, as before #28, took 170–370 ms per
  call on a 1,000-page form and 1–3 s on 5,000 pages (simulator); now an
  edit, `setFormFieldValues` and the snapshot take 0.1–15 ms there, and
  65–70 ms at 5,000 pages (see `docs/performance/results.md`, section
  19).
- **Cost (Android):** `applyEdit` 0.1–2 ms and writing the 2-page fixture
  3 ms (emulator).
- **Not included:** form JavaScript (calculations, validation,
  formatting), signing, XFA, flattening, resetting a form; on Android
  typing a choice's custom text by touch and screen-reader access to
  fields; on iOS several options in a list box.
- **Tested:** `viewer-forms` on both platforms, a downloaded form included
  (exports over and beside its copy rejected, reopened as served); JVM
  tests for the cache directories' check and the AcroForm
  reader (order, inherited types and flags, values, radio options from
  appearance states, cropped and rotated pages, object streams, widgets
  found through page annotations, encrypted files), widget matching, value
  planning and the ordered queue lane; unit tests for the JS conversion;
  probes for exported flags, encrypted exports (reopened: password asked),
  page images of a filled form and a field at the bottom of a page above
  the keyboard; the full suite on both platforms.

## Page overlays

```tsx
<PdfView source={source}>
  <PdfPageOverlay page={0} x={72} y={600} width={180} height={40}>
    {/* scalesWithZoom?: boolean — default true */}
  </PdfPageOverlay>
</PdfView>
```

Requested in react-native-pdf as views that stay on a point of a page
while it scrolls and zooms — form fields, signatures, markers (#350,
#254, #449, #484); apps there converted coordinates themselves from
scale and offset events.

- **A component, not a conversion API.** A page-to-view conversion needs
  the viewport on every frame: a per-frame event (`onViewRectChange`)
  puts JS on the scroll path, and the overlay trails its page by a frame
  or more whenever JS is busy (`nitroBoundary.test` rejects per-frame
  events). Instead JS says once where each overlay goes (page and
  rectangle) and native code moves the React views, in the same frame as
  the pages.
- **Coordinates are `onPageTap`'s**: PDF points from the top-left corner
  of the page as displayed (crop box, rotation applied), on both
  platforms, so a tap can place an overlay. This found an iOS bug:
  `onPageTap` and viewport positions there used the unrotated page — on a
  `/Rotate 90` page a tap further right reported a smaller y. They now
  follow the displayed page, as Android's renderer does (measured on
  `page-boxes`: three taps report 209,138 / 421,138 / 209,339 on iOS and
  211,158 / 421,158 / 211,383 on Android, y differing with the page's
  place on screen; 180° and −90° pages checked against their on-screen
  geometry). A viewport saved on iOS on a rotated page before this fix
  restores elsewhere.
- **Overlays live in a layer above the native view**, a sibling inside
  PdfView's box: Android's Nitro view (a `SimpleViewManager`) cannot host
  React children. Each `PdfPageOverlay` is a `View` with a generated
  `nativeID`; the native view gets the layer's `nativeID` and the
  placements (id, page, rectangle, `scalesWithZoom`) as props, finds the
  layer beside it and the views in it, and keeps weak references. iOS
  reads React Native's `nativeId` through KVC (its component view class is
  not visible to Swift); Android uses `ReactFindViewUtil` and the view tag.
  The layer passes touches between overlays through (`box-none`).
- **Placed by a transform.** React lays an overlay out at its size, off
  screen (`left: -100000`); native code sets a translation and a scale
  (iOS: the view's `transform`; Android: `translationX/Y` and
  `scaleX/Y` around the top-left corner), in the coordinates of the
  overlay's parent, so overlays nested in other views work. The app's
  `style` comes first and cannot move or resize an overlay; a `transform`
  style would be overwritten.
- **When:** Android's document view reports every viewport change
  (scroll, fling, zoom animation, relayout, document cleared). iOS
  observes the scroll view's `contentOffset`, zoom, layout and page
  changes and, in paged layouts, the offset and size of every page's
  scroll view in PDFKit's page view controller: zooming and panning a page
  there does not move the main scroll view (measured: overlays stayed at
  their unzoomed place until those were observed). The controller creates
  them as pages show, so they are collected again on page changes.
- **Hidden off screen**: an overlay whose page is not visible is hidden
  (`isHidden` / `INVISIBLE`, so also from screen readers and touches), and
  every overlay while no document is shown (loading, failed).
- **Scaling**: by default the view scales with the page — content laid
  out as if PDF points were dp looks printed on it. `scalesWithZoom={false}`
  keeps the size with the top-left corner on the point (a 28 pt pin stayed
  28 pt at 2.5×, on the same word). iOS scales the view's drawing; text
  was sharp at 2.5× on both platforms, but can soften on iOS towards high
  zoom.
- **JS sends changes only**: a registry keyed by overlay keeps the
  placements; a re-render with the same rectangles sends nothing (the
  same array), and a `PdfView` without children renders no layer.
- **Cost:** one placement pass per frame the document moves. With 23
  overlays: ~0.11 ms per pass on iOS (simulator, debug build) and ~0.2 ms
  on Android (emulator, debug build), 1–2 % of a 60 Hz frame. iOS runs
  the pass about three times as often for the same gestures (several
  scroll views and zoom notifications report per frame). Meant for tens
  of overlays, not one per page of a long document.
- **Not included:** clipping overlays to their page; overlays in page
  images or printing; a page-to-view conversion API.
- **Tested:** `viewer-overlays` on both platforms (taps by testID at fit,
  after the page scrolls in, after a far jump and back with all hidden
  meanwhile, after a resize, zoomed in continuous and paged layouts, in
  paged RTL and pairs); unit tests for registration, unchanged
  re-renders, removal, style order and use outside a `PdfView`; probes for
  the pin at 2.5× and for taps on every rotation of `page-boxes`; the full
  suite on both platforms.

## Dark pages

```ts
colorScheme?: 'light' | 'dark' | 'unspecified' | null; // default 'light'
```

Requested in react-native-pdf as night mode (#325, #996 — a patch
exposing AndroidPdfViewer's plain inversion on Android) and as a
cross-platform theme that does not "distort document colours" (#1038).

- **Lightness inverted, hues kept**, not plain inversion: plain inversion
  turns a red heading cyan and every photo into a negative — #1038's
  complaint. Photos are still inverted (a dark mode cannot know which
  images are photos).
- **`colorScheme`, typed to take `useColorScheme()` as it is**: React
  Native 0.86 returns `'light' | 'dark' | 'unspecified'`, older versions
  `null`; anything but `'dark'` keeps the document's colours. Native code
  gets a concrete `'light' | 'dark'`. React Native does not export its
  `ColorSchemeName`, so the union is spelled out.
- **Android: a colour filter at draw time.** Inversion followed by a 180°
  hue rotation (the luminance-preserving matrix of CSS `hue-rotate`) as a
  `ColorMatrixColorFilter` on the paint that draws page bitmaps; the
  white placeholder becomes black. Rendered bitmaps, the cache and page
  images are untouched, toggling is an `invalidate()`, and highlights and
  the selection use their own paints.
- **iOS: a `PDFPage` subclass**, through `classForPage()` on a
  `PDFDocument` subclass that is its own delegate (the loader creates it;
  separate instances for page images, printing and the outline stay
  plain). When dark, a page is drawn on white, inverted with a difference
  blend against white, then drawn again into a transparency layer
  composited in the hue mode — hue from the page, saturation and
  lightness from the inverted result. Drawn straight in the hue mode,
  appearance streams that set their own blend mode would escape it.
- **iOS annotations keep their colours** (measured on iOS 26: PDFKit
  draws them apart from the page, so the page override never sees them)
  and PDFKit composites text markup (highlight, underline, strike-out,
  squiggly) in the multiply mode, which turned it black on a dark page. An
  annotation subclass (`class(forAnnotationType:)`) skips PDFKit's markup
  pass while dark, and the page draws markup itself in the screen mode —
  the inverse of multiply — with highlights at half strength so white
  text on them should stay readable (not measured: the fixture's
  highlight covers no text). Toggling light → dark → light restores
  PDFKit's own markup (checked on the `annotations` fixture). Only these
  four types get the subclass; links and form fields stay PDFKit's.
- **Redrawing on iOS:** PDFKit keeps the tiles it drew. `setNeedsDisplay`
  on its layers, `annotationsChanged(on:)` for every page and toggling
  `pageShadowsEnabled` all left them (measured); switching `displayBox`
  away and back makes it draw every page again and ends in the same
  layout, so the exact scroll offset is put back. Paged layouts keep their
  page (checked in Paged and Paged H); taps and links still map correctly
  afterwards (`viewer-links` after three toggles).
- **Cost:** `stress-rapid` on the 1000-page scan on iOS, dark vs light:
  213 s vs 218 s, peak RSS 675 MB vs 670 MB (debug build, simulator) —
  drawing each tile twice did not show. Android filters at draw time.
- **Not included:** sepia or custom page colours; inverting annotation
  colours on iOS (would need annotations drawn offscreen and filtered).
- **Tested:** `viewer-dark` on both platforms (the Embedded card switches
  while shown; the screenshot is reviewed by hand — Maestro cannot check
  colours), the prop mapping in a unit test, the `annotations` fixture
  compared on both platforms, runtime toggles in paged layouts and on
  `page-boxes` (crop box ≠ media box), and the full suite on both.

## Facing pages

```ts
spreadMode?: 'none' | 'pairs' | 'book'; // default 'none'
```

Requested in react-native-pdf as two pages side by side on tablets
(#415, 6 reactions: landscape, swipe to the next two; #921; #666).

- **Vertical continuous only, on both platforms.** Measured on iOS 26 with
  the `small` fixture: PDFKit's `.twoUpContinuous` lays out facing pages
  (with `displaysAsBook` for a cover), but its page view controller —
  which `enablePaging` uses — always shows single pages; horizontal
  two-up overlaps pages; and `.twoUp` without the page view controller
  shows one spread that no swipe moves. Paged spreads (#415's swipe) would
  mean a page view controller of our own with one `PDFView` per spread,
  re-implementing search, selection, links, the viewport and
  accessibility on iOS — out of scope. Android lays out everything
  itself, but offers the same subset so the prop means the same on both.
- **One literal union, named by what the reader sees** (`pairs` / `book`
  rather than pdf.js's odd / even, or a boolean plus a cover flag). No
  `'auto'`: the app knows its breakpoints, and switching `spreadMode` with
  `useWindowDimensions()` keeps the reader's page.
- **Geometry follows PDFKit** (measured on screenshots): each page fits
  half the width at `fitPolicy` `'width'`, facing pages touch at the
  spine, and a page without a partner — the cover, an odd last page — is
  centred; rows are separated by `pageSpacing`. Android also fits each
  page individually (iOS applies one scale to the document, as without
  spreads), so it centres a row of two different widths as a whole and
  centres the shorter page along its row. `'height'` and `'page'` fit
  half-width columns on iOS too.
- **The current page is the spread's first page** under the viewport
  centre — the spine is the centre, so "the page under the centre" would
  be arbitrary — and the last page once scrolled to the end, as without
  spreads. Index arithmetic on both platforms (`spreadStart`).
- **Switching keeps the reader's page, not the spread's first page.**
  Both platforms keep a reading page: the reported page, except that with
  facing pages it stays on the page the reader navigated to or was on
  while that page is on screen. Two rules were not enough: keeping it only
  while its spread was the reported one walked readers back a page per
  switch (6 → 5 → 4), and on a tall view the row under the centre is the
  one below the row `goToPage` put at the top, which walked them forward
  (6 → 8, caught by `viewer-layout-modes` after `viewer-spreads`).
  `goToPage`, `goToViewport` and the initial page set it. iOS puts the kept
  page's spread at the top after a layout switch and after a resize that
  lost it (PDFKit's `go(to:)` left it anywhere on screen; a resize is what
  rotation does — `viewer-spreads` resizes by opening the search bar).
- **Android:** `PdfDocumentLayout` now lays out rows of one or two pages;
  every lookup (`slotStart`, `pageAt`, `visibleRange`, `isEndVisible`,
  scroll order for accessibility) works on rows, and `pagePointAt` /
  `pagePointNear` choose the facing page by x. The refactor kept all
  existing layout tests passing unchanged before spreads were added.
- **Not included:** right-to-left spreads (`enableRTL` stays horizontal
  only), spreads in `singlePage` thumbnails.
- **Tested:** 11 JVM layout tests (pairs, book, odd counts, unequal
  pages, fit policies, lookups, points by x, end, ignored in other
  layouts); `viewer-spreads` on both platforms (goToPage lands on 11–12 in
  pairs and 10–11 in a book, the last page at the end, the reader's page
  kept); the full e2e suite on both platforms.

## Printing

```ts
ref.print(options?: { jobName?: string }): Promise<{ isSubmitted: boolean }>;
```

Requested in react-native-pdf as print (and share) of the displayed
document (#378, #303, #371) — one reaction each, all from 2019; people
printed `onLoadComplete`'s path with another library. The method exists
for what those libraries cannot do: password-protected documents (the
print system opens files without a password), `content://` sources (no
file path), and anchoring the dialog to the viewer on iPad.

- **On the view, not standalone.** It prints the document as loaded —
  unlocked, downloaded — with no second download or password prompt.
  Printing without a viewer is not part of `PdfDocument` (see "Documents
  without a view").
- **Protected documents print from an unlocked copy that never touches
  disk.** iOS: the unlocked document is written with an owner password
  nobody knows, its permissions and no user password, so it opens without
  one; serialized off the main thread on a separate instance and handed
  to `UIPrintInteractionController` as data. Without options PDFKit's
  `dataRepresentation()` depends on the cipher: it drops RC4-40's
  encryption (the `encrypted` fixture) but keeps AES-128's
  (`aes-encrypted`), so the print system would ask for the password
  again. For such a document `present` returns false but still runs the
  completion when the prompt closes, so the promise settles only once.
  Android: `PdfRenderer.write(fd,
  removePasswordProtection = true)` (API 35, and `PdfRendererPreV` on 12–14
  with extension 13 — every renderer that can open a protected document)
  writes straight into the spooler's descriptor. Other documents print
  from the file itself (iOS) or a positional copy of the open descriptor
  (Android), so the spooler gets the original, vector bytes.
- **A dialog opens only for the document still shown.** Preparing takes
  seconds for long documents (iOS: 6–10 s to write the unlocked copy of
  500 scanned pages on a simulator; Android waits for form work). A view
  closed or given another `source` meanwhile rejects with "The document
  was closed" rather than open the dialog over another screen, or for
  the previous document. The shared controller's `printingItem` is
  already nil when the completion runs after the dialog was closed
  (iOS 26.5 simulator, for a file and for data; not checked after
  submitting), so the view does not clear it.
- **`isSubmitted` is measured, not assumed.** iOS reports it in the
  completion handler. On Android the adapter's `onFinish` cannot tell: a
  cancelled job is still "created" when the dialog closes and turns
  "cancelled" within 500 ms, while a saved job is already "completed"
  (measured on API 37). The view waits up to 2 s for the job to leave
  "created".
- **Permissions:** iOS refuses documents whose permissions forbid printing
  (`allowsPrinting`); Android's `PdfRenderer` exposes no permissions, so
  it prints them.
- **Default job name** from the source in JS (`report.pdf` from a URL
  without its query), so native reads no metadata. `'Document'` when the
  source names no file — including bundled assets in development, which
  Metro serves from `/assets/?unstable_path=…`.
- **Tested:** `viewer-print` on both platforms (dialog previews the
  bundled sample and the unlocked protected fixture, closing resolves
  `false`); on Android the protected document saved through "Save as PDF"
  resolves `true`, and the saved file is unencrypted with both pages
  (checked with PDFKit). On iOS, leaving `scanned-encrypted` right after
  Print opens no dialog over the list. Not tested: submitting on iOS (a
  simulator has no printer; Printer Simulator is not installed), a
  `source` change during preparation (the example has no control for it;
  the same check covers it) and real printers.

## Documents without a view

```ts
const document = await PdfDocument.open(source, { password?, onProgress?, signal? });
document.pageCount; // number
document.filePath;  // string | undefined, as in onLoad
document.isStale;   // boolean, as in onLoad
document.getMetadata(): Promise<PdfDocumentMetadata>;
document.getPageSizes(): Promise<PdfPageSize[]>;
document.getOutline(): Promise<PdfOutlineItem[]>;
document.renderPage(page, { width, format?, quality? }): Promise<PdfPageImage>;
document.close();
interface PdfDocumentMetadata {
  title?: string; author?: string; subject?: string; keywords?: string;
  creator?: string; producer?: string;
  createdAtMs?: number; modifiedAtMs?: number;
}
// open() rejects with a PdfOpenError ({ code, httpStatus?, nativeDescription? }) or an AbortError.
```

This was the first open question. Uses: a document list with page counts,
titles and covers; thumbnails without a viewer per page (the example's
strip mounted twelve `singlePage` viewers); checking a password before
showing the viewer.

- **A class over an internal Hybrid Object** (`BetterPdfDocument`), like
  `PdfCache`: opening fails with codes apps act on (`password-required`),
  but Nitro rejections carry only a message, so failures cross as a result
  struct and become `PdfOpenError`s; `AbortSignal` needs JS.
- **Opens like the view.** The same sources, cache options, shared
  downloads, password handling and error codes. Offline, a remote document
  opens from its outdated copy and reports `isStale`, as in `onLoad`: a
  list rendering covers offline should show them. (`PdfCache.download()` is
  the strict "make sure it is current".)
- **Reads like the view.** `getPageSizes`, `getOutline` and `renderPage`
  share the view's code: same validation, image files, limits and platform
  differences (Android rejects the outline of encrypted documents).
- **Metadata is the new part**: the document information dictionary
  (`/Info`). A method, not a field of the result, because Android has no
  API for it: the bounded parser of "Outline" reads it, so untrusted
  structure is parsed only when asked and opening costs what the view's
  does. iOS reads PDFKit's `documentAttributes`. Dates are milliseconds
  since the epoch, like `validatedAtMs`; a date without a time zone is read
  as UTC. Absent or empty entries are left out. XMP metadata streams are
  not read. Android rejects for encrypted documents (the parser has no
  security handler, and their strings are encrypted), like the outline.
- **`close()` releases the document now.** A Hybrid Object is released when
  Hermes collects its JS reference, which can take arbitrarily long (audit
  C3), while an open document holds a file descriptor (Android) or a parsed
  `PDFDocument` and a pin of the downloaded copy (iOS). Calls already
  running finish; later calls reject; `close()` is idempotent. Native
  `dispose()` and deinit/finalization close too, as a safety net.
- **Android keeps only the file descriptor.** Each call opens a renderer on
  a duplicate and closes it (3–27 ms, see `PdfDocumentSession`), so PDFium
  keeps nothing between calls (audit C1: a renderer retains every page it
  parsed). One thread per document runs the calls in order, so twelve
  thumbnail requests hold one page bitmap at a time, not twelve.
- **iOS keeps its `PDFDocument`**, confined to one serial queue per
  document, so calls do not re-parse the file. (The view re-opens the file
  for these reads because its document belongs to `PDFView` on the main
  thread.)
- **Not included:** showing a `PdfDocument` in a `PdfView` (the view opens
  its own; PDFKit documents cannot be shared across threads), printing,
  search and text extraction without a view.
- **Measured** (`probe-document-timing`, example's Docs tab, local files,
  3 runs after a warm-up; JS-side times, reads awaited one after another):

  | | iOS simulator | Android emulator (API 37) |
  | --- | --- | --- |
  | `open` (any size, incl. 414 MB) | 1–2 ms | 3–18 ms |
  | `getMetadata`, `getOutline` (small) | 0–1 ms | 1–38 ms |
  | `getPageSizes`, 100 / 1000 pages | 1–2 / 52–53 ms | 49–108 / 328–360 ms |
  | `renderPage` 300 px PNG (cover) | 2–14 ms | 38–152 ms |
  | 12 × 150 px JPEG at once, text+photo / scan | 51–54 / 81–97 ms | 297–320 / 541–661 ms |

  Android's renderer per call costs its open on every page image; the
  thumbnail case stays at one bitmap at a time. Native heap with the
  1000-page scan (414 MB) open after every read and twelve thumbnails:
  +2.3 MB over the same screen with no document (93.6 → 95.9 MB) — PDFium
  keeps nothing between calls.
- **Tested:** `PdfDocument.test.ts` (source forms, typed errors, abort
  before and during opening, `close()` twice, calls after `close()`); JVM
  tests for the information dictionary (classic and object-stream files,
  PDFKit and pypdf producers, no dictionary, encrypted) and PDF dates (every
  time-zone form, partial and invalid dates — the same 27 cases checked
  against the Swift port, which shares the regular expression and, like
  Foundation, the Julian calendar before 1582), and 500 corrupted files
  through the reader. `pdf-document` on both platforms: the `metadata`
  fixtures (UTF-16 title, empty and padded values, offset and UTC dates) as
  file and download, the encrypted copy without, with a wrong and with the
  right password, rotated and cropped pages, the outline fixture, the
  bundled asset, a file that is not a PDF, calls after `close()`, and
  twelve thumbnails requested at once. The view's flows over the shared
  code (page images, page sizes, outline, passwords, printing, offline,
  errors) pass on both platforms.

## Open questions


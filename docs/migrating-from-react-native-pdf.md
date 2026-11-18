# Migrating from react-native-pdf

This guide maps every prop, callback and method of
[react-native-pdf](https://github.com/wonday/react-native-pdf) 7.x to
`react-native-better-pdf`, lists behaviour that changes silently, and shows
workarounds for what is not supported yet. Defaults and signatures below were
read from react-native-pdf 7.0.5's source, not from its README.

## Common react-native-pdf issues

How this library handles what react-native-pdf users report most:

| Reported in react-native-pdf | react-native-better-pdf |
| --- | --- |
| Android 16 KB page size ([#960](https://github.com/wonday/react-native-pdf/issues/960) and others) | Native library 16 KB aligned |
| New Architecture / Fabric ([#878](https://github.com/wonday/react-native-pdf/issues/878) and others) | Built for Fabric on Nitro Views |
| `react-native-blob-util` crashes and setup ([#796](https://github.com/wonday/react-native-pdf/issues/796) and others) | Not used: downloads are native |
| Android "Already closed" crashes ([#830](https://github.com/wonday/react-native-pdf/issues/830) and others) | Closing a document while it renders, searches or exports is handled and stress-tested |
| Link taps on Android ([#983](https://github.com/wonday/react-native-pdf/issues/983) and others) | Every link form on every Android version (password-protected documents: see [Limitations](limitations.md)) |
| Search ([#555](https://github.com/wonday/react-native-pdf/issues/555) and others) | Built in, with highlights (Android: see the [capability matrix](../README.md#capability-matrix)) |
| Viewport events, restoring the position ([#492](https://github.com/wonday/react-native-pdf/issues/492) and others) | `onViewportChange`, `initialViewport`, `goToViewport` |
| Filling in and saving forms ([#748](https://github.com/wonday/react-native-pdf/issues/748) and others) | Fill, read and export (Android: see the [capability matrix](../README.md#capability-matrix)) |
| Cache control and offline reading ([#952](https://github.com/wonday/react-native-pdf/issues/952) and others) | Cache options on the source, `PdfCache` |

The full list, with reaction counts, is in
[community-requests.md](community-requests.md).

## Before you start

- **New Architecture only.** `react-native-better-pdf` is a Nitro View and
  needs Fabric and React Native 0.83+ (Expo SDK 55+). If your app still runs
  the legacy architecture, migrate that first.
- **Expo:** works in development builds; Expo Go is not supported (same as
  react-native-pdf).
- **Dependencies:** remove `react-native-blob-util` if nothing else in your app
  uses it — downloads now happen natively. Add
  `react-native-nitro-modules`.

```sh
npm uninstall react-native-pdf react-native-blob-util
npm install react-native-better-pdf react-native-nitro-modules
cd ios && pod install
```

## The minimal change

```diff
- import Pdf from 'react-native-pdf';
+ import { PdfView } from 'react-native-better-pdf';

- <Pdf
-   source={{ uri: 'https://example.com/file.pdf', cache: true }}
+ <PdfView
+   source={{ uri: 'https://example.com/file.pdf', cache: 'persistent' }}
-   onLoadComplete={(numberOfPages) => setPageCount(numberOfPages)}
-   onPageChanged={(page, numberOfPages) => setPage(page)}
-   onError={(error) => console.warn(error)}
+   onLoad={({ pageCount }) => setPageCount(pageCount)}
+   onPageChange={({ page, pageCount }) => setPage(page + 1)}
+   onError={(error) => console.warn(error.code, error.message)}
    style={{ flex: 1 }}
  />
```

## Mechanical changes and changes that need review

These can be done by search and replace: the import and component name,
renamed props and callbacks, page numbers ± 1, `fitPolicy` numbers to names,
`cache: true` → `cache: 'persistent'`, `cacheFileName` → `cacheKey`,
`expiration: n` → `maxAgeMs: n * 1000` for `n > 0` (drop `expiration: 0`),
`data:` URIs → `{ base64 }`, `setPage(n)` →
`goToPage(n - 1, { animated: false })` and the [type names](#types).

These need a decision:

| What | Why |
| --- | --- |
| Sources without `cache` | Downloaded on every mount before; now kept for the session. Add `maxAgeMs: 0` where the content changes. |
| `trustAllCerts` (default `true`) | Certificates are always validated: fix the server. |
| `source.method: 'POST'`, `source.body` | Only `GET`. |
| `blob:` URIs (iOS) | Not supported: pass the URL or `{ base64 }`. |
| `onPageSingleTap` coordinates | Now PDF points on the page; remove your own conversion. |
| Table of contents | Read with `ref.getOutline()`; its page numbers were already 0-based. |
| `onError` handlers that replace the viewer | On Android a page failure also arrives here while the document stays open. |
| `page` changed to navigate | `initialPage` is read once per document: use `ref.goToPage()`, or `key` in recycled cells. |
| Defaults you rely on | Fit, maximum zoom, page gap, background, loading text: see below. |

## Behaviour that changes silently

Read these first — none of them causes a type error, all of them change what
users see.

| Topic | react-native-pdf | react-native-better-pdf | What to do |
| --- | --- | --- | --- |
| **Page numbers** | 1-based `page`, `setPage`, `onPageChanged` and `onPageSingleTap` (on Android the current page, not the tapped one); `tableContents` `pageIdx` 0-based | 0-based everywhere | Add or subtract 1 at the boundary, except for the outline. |
| **Remote documents without `cache`** | Downloaded again on every mount: a URL whose content changes showed the new content on every open | Kept for the app session and not checked again: the first download is shown until the app restarts | Add `maxAgeMs: 0`: the server is asked on every open, and when it sends `ETag` or `Last-Modified`, an unchanged document is not downloaded again. See [Caching](#caching). |
| **`expiration: 0`** | `0` (or no `expiration`) never expires | `maxAgeMs: 0` checks with the server on every open | Convert only `expiration > 0` to `maxAgeMs`; leave `maxAgeMs` out for `0`. |
| **`page` prop** | Applied whenever it changed | `initialPage` is read once per loaded document | Navigate with `ref.goToPage()`; give recycled list cells `key={page}`. See [Thumbnails](#thumbnails). |
| **`onError` after loading (Android)** | Errors came only from loading | A page that cannot be rendered is reported with `error.page` set, after `onLoad`, and the document stays open | Show an error screen only when `error.page === undefined`. See [Errors](#errors). |
| **`onLoadComplete` on Android** | Fired again after every prop change (the document was reloaded) | `onLoad` fires once per loaded document | Move work that relied on the repeat. |
| **TLS certificates** | `trustAllCerts` defaults to **`true`** — invalid certificates are accepted | Certificates are always validated | Fix the server's certificate. Downloads that "worked" before may now fail with `network-error`. |
| **Download progress value** | `onLoadProgress(fraction)` — `0…1`, despite the name | `onLoadProgress({ loadedBytes, totalBytes? })` | `totalBytes === undefined ? undefined : loadedBytes / totalBytes` |
| **Loading UI** | Shows `xx.xx%` text (or `renderActivityIndicator`) while downloading | Shows nothing | Render your own indicator until `onLoad`, or `onError` without `error.page`. |
| **Background** | `#EEE` behind the pages | Transparent | `style={{ backgroundColor: '#EEE' }}` |
| **Fit** | `fitPolicy` default `2` (fit whole page) | `'width'` when vertical and continuous; `'page'` with `horizontal`, `enablePaging` or `singlePage` | Pass `fitPolicy="page"` to keep react-native-pdf's default. |
| **Max zoom** | `maxScale` default `3` | `4` | Pass `maxScale={3}` to keep the old limit. |
| **Page gap** | `spacing` default `10` | `pageSpacing` default `8` | Pass `pageSpacing={10}`. |
| **Short documents** | Centred vertically (#909, #920) | Start at the top in vertical continuous layouts, on both platforms | Nothing. |
| **Rounded corners** | `borderRadius` does not clip the pages on iOS (#920, #993) | `style.borderRadius` clips the document on both platforms | Nothing. |
| **Safe area (iOS)** | PDFKit adds the safe area as an inset, and going to a page ignores it: the first page can start under the status bar (#513, #994) | No automatic insets, like `ScrollView` and Android | Keep the viewer below the status bar (e.g. in a `SafeAreaView`). |
| **Current page** | Platform-specific (iOS: PDFKit's current page) | Page under the viewport centre; the last page once scrolled to the end — identical on both platforms | Usually nothing; UI showing "page X" becomes consistent. |
| **`cache: true`** | Persistent, keyed by SHA-1 of the URL computed in JS (or `cacheFileName`) | `cache: 'persistent'`, keyed by URL + headers (or `cacheKey`) | `cache: true` → `cache: 'persistent'`, `cacheFileName` → `cacheKey`; see [Caching](#caching). |
| **Errors** | `Error` objects with a platform-dependent message; download failures also carry `error.status`, the HTTP status | `PdfError` object with a stable `code` | Match on `error.code`; `error.status` → `error.code === 'http-error'` and `error.httpStatus`. See [Errors](#errors). |

## Props

| react-native-pdf | react-native-better-pdf | Notes |
| --- | --- | --- |
| `source={{ uri }}` | `source={{ uri }}` | `http(s)://`, `file://`, `content://` (Android). |
| `source={require('./x.pdf')}` | `source={require('./x.pdf')}` | No Metro config needed, and it also works in Android release builds, where react-native-pdf could not open it. |
| `source.headers` | `source.headers` | Same, but sent to the URL's origin only: a redirect to another origin drops them. See [Redirects](guides/sources-and-loading.md#redirects). |
| no `source.cache` | `source.maxAgeMs: 0` | react-native-pdf downloaded the file on every mount; `maxAgeMs: 0` asks the server on every open instead. See [Caching](#caching). |
| `source.cache: true` | `source.cache: 'persistent'` | Kept across launches, never purged by the OS. See [Caching](#caching). |
| `source.cacheFileName` | `source.cacheKey` | Identifies the copy instead of the URL and headers. |
| `source.expiration: n` (seconds, `n > 0`) | `source.maxAgeMs: n * 1000` | `expiration: 0` meant "never expires": leave `maxAgeMs` out. An older copy is revalidated with the server (304 keeps it) instead of deleted, and still shown offline. |
| `source.method`, `source.body` | — | Only `GET` (`body` is read by react-native-pdf but missing from its types). |
| `source={{ uri: 'data:application/pdf;base64,…' }}` | `source={{ base64: '…' }}` | Without the `data:…;base64,` prefix (1.1+); see [Base64 documents](#base64-documents). |
| `source={{ uri: 'bundle-assets://…' }}` | `require()` | Bundle the file with Metro instead; it works in release builds on both platforms. |
| `source={{ uri: 'blob:…' }}` (iOS) | — | Fails with `invalid-source`. Pass the document's URL (with `headers`) so it is downloaded natively, or its bytes as `{ base64 }`. |
| `page` | `initialPage` (0-based) + `ref.goToPage()` | `page` is applied on change in react-native-pdf; here the initial page is read once per loaded document and later navigation is imperative. |
| `scale` | `initialScale` + `ref.zoomTo(scale)` | Relative to the fitted page; applied once on load, like `initialPage`. |
| `minScale`, `maxScale` | `minScale`, `maxScale` | Relative to the fitted page; `maxScale` default differs (3 → 4). |
| `spacing` | `pageSpacing` | Default 10 → 8. |
| `password` | `password` | Android: Android 15+, or Android 12–14 with SDK extension 13 (updated system PDF module); other devices report `unsupported-pdf`. react-native-pdf's bundled PDFium supported every version. |
| `showsVerticalScrollIndicator`, `showsHorizontalScrollIndicator` | same | Same names and defaults; react-native-pdf applied them on iOS only, now both platforms do. |
| `horizontal`, `enablePaging` | same | Same names and defaults. Paging swipes along the layout axis (vertical unless `horizontal`) on both platforms. |
| `singlePage` | `singlePage` | Shows `initialPage` (react-native-pdf: `page`); touches are ignored as before. Default fit is the whole page. |
| `enableRTL` | `enableRTL` | Horizontal layouts only, page numbers in document order on both platforms. react-native-pdf on Android also reversed vertical layouts and page numbers; iOS (PDFKit) supports it only with `enablePaging`, as before. |
| `fitPolicy={0 \| 1 \| 2}` | `fitPolicy="width" \| "height" \| "page"` | Named values. iOS applies one scale to the whole document (see the API docs). |
| — | `spreadMode="pairs" \| "book"` | New: facing pages side by side in the vertical continuous layout. |
| `nightMode` (patched in on Android, #996) | `colorScheme="dark"` | Both platforms; hues kept rather than plainly inverted. |
| `scrollEnabled` | `scrollEnabled` | Same name and default. Disables drag scrolling (also paging swipes); the drag goes to parent views. |
| `enableDoubleTapZoom` | `enableDoubleTapZoom` | Same name and default, different steps: react-native-pdf cycled minimum → middle → maximum zoom; here Android zooms in to 2× (twice `minScale` if that is more, at most `maxScale`) and, when zoomed in, back out to `minScale`; iOS uses PDFKit's own step. When `false`, `onPageTap` also fires without waiting for a possible second tap. |
| `enableAntialiasing` | — | Always on. |
| `enableAnnotationRendering` | — | Always on where the platform can: iOS draws every annotation; Android draws only some kinds (see [Limitations](limitations.md)). react-native-pdf's bundled PDFium drew all kinds on Android. |
| `enableTextSelection` | `enableTextSelection` | Same name and default (`true`). `false` now turns selection off (react-native-pdf only stopped the event), and Android selects too (Android 15+). |
| `usePDFKit` | — | iOS always uses PDFKit. |
| `trustAllCerts` | — | Intentionally not supported. |
| `renderActivityIndicator`, `progressContainerStyle` | — | Render your own; see `onLoadProgress`. |
| `style` and other `View` props | same | `testID` and the other `View` props go to the view that wraps the native PDF view (react-native-pdf set them on the native view itself). |
| — | `ref.print()` | New: the system print dialog for the loaded document, protected documents included. |
| — | `contentInset={{ top, bottom }}` | New: pages scroll under a translucent header or toolbar (PR #993 in react-native-pdf, unmerged). |
| — | `<PdfPageOverlay page x y width height>` children | New: React views on pages that follow scrolling and zoom, placed natively (#350, #254, #449, #484). |
| — | `enableFormFilling`, `onFormChange`, `ref.getFormFields()`, `ref.setFormFieldValues()`, `ref.exportDocument()` | New: AcroForm filling by touch and from code, and the filled-in PDF as a file. |

## Callbacks

| react-native-pdf | react-native-better-pdf |
| --- | --- |
| `onLoadComplete(numberOfPages, path, { width, height }, tableContents)` | `onLoad({ pageCount, firstPageWidth, firstPageHeight, filePath })` — the size is the first page's in PDF points (react-native-pdf: the size as displayed, on iOS of the *last* page); `filePath` is `path` (absolute, no `file://`); table of contents via `ref.getOutline()`; every page's size via `ref.getPageSizes()` |
| `onPageChanged(page, numberOfPages)` — 1-based | `onPageChange({ page, pageCount })` — 0-based |
| `onLoadProgress(fraction)` | `onLoadProgress({ loadedBytes, totalBytes? })` — throttled to ≤ 10 per second |
| `onError(error: Error)` | `onError({ code, message, page?, sourceIndex?, httpStatus?, nativeDescription? })` — on Android also after `onLoad`, with `page` set, while the document stays open |
| `onPageSingleTap(page, x, y)` — 1-based (Android: the current page, not the tapped one), view coordinates | `onPageTap({ page, x, y })` — 0-based, the tapped page, PDF points from the page's top-left, identical on both platforms |
| `onPressLink(url)` | `onLinkPress({ url })` — links to other pages are followed natively and not reported |
| `onScaleChanged(scale)` | `onScaleChange({ scale })` — once per settled change, not during the gesture; relative to the fitted page |
| `onTextSelectionChange({ nativeEvent: { type, text } })` — per handle move | `onTextSelectionChange({ text })` — once per settled selection; `text` is `''` when cleared |

`onPageSingleTap` coordinates were view pixels on Android and view points on
iOS; if you converted them to page coordinates yourself, remove that code.

## Methods

```diff
+ import type { PdfViewRef } from 'react-native-better-pdf';
- const pdf = useRef(null);
+ const pdf = useRef<PdfViewRef>(null);
- pdf.current?.setPage(5);                       // 1-based, jumps
+ pdf.current?.goToPage(4, { animated: false }); // 0-based; animated by default
+ pdf.current?.zoomTo(2);                        // replaces the `scale` prop
```

`goToPage` is ignored before `onLoad` and while a new `source` loads;
react-native-pdf kept the page and showed it once the document loaded.
Pass `initialPage` for the page a document opens at, and call `goToPage`
from `onLoad` or later.

react-native-pdf's `setNativeProps` has no counterpart: pass props instead.

## Types

| react-native-pdf | react-native-better-pdf |
| --- | --- |
| `Pdf` (default export) | `PdfView` (named export) |
| `PdfProps` | `PdfViewProps` |
| `PdfRef` | `PdfViewRef` |
| `Source` | `PdfSource`; `{ uri }` alone is `PdfUriSource` |
| `PdfError` (`Error & { status? }`) | `PdfError`, a plain object with `code`; `onError` receives `PdfLoadError \| PdfPageError` |
| `TableContent` | `PdfOutlineItem` |
| `TextSelectionChangeEvent` | `PdfTextSelectionChangeEvent` |

## Errors

react-native-pdf passes `Error` objects whose messages differ by platform;
download failures also carry `error.status`, the HTTP status. Replace
message matching with the stable code:

```diff
- onError={(error) => {
-   if (String(error).includes('Password')) askForPassword();
-   else if (error.status === 404) showNotFound();
- }}
+ onError={(error) => {
+   if (error.code === 'password-required') askForPassword();
+   else if (error.code === 'invalid-password') showWrongPassword();
+   else if (error.code === 'http-error' && error.httpStatus === 404) showNotFound();
+ }}
```

react-native-pdf reported errors only while loading. On Android, `onError`
here also reports a page that could not be rendered, after `onLoad`, with
`error.page` set; the document stays open. Don't replace the viewer then:

```tsx
onError={(error) => {
  if (error.page !== undefined) return; // one page failed; the rest is shown
  setLoadError(error);
}}
```

| Situation | `error.code` |
| --- | --- |
| No file / unreadable `file://` | `file-not-found` |
| Unsupported URI | `invalid-source` |
| Connection failed, TLS error, timeout | `network-error` |
| Server answered non-2xx (`error.httpStatus`) | `http-error` |
| Not a PDF, corrupt | `invalid-pdf` |
| Encrypted, no password given | `password-required` |
| Wrong password | `invalid-password` |
| Encrypted PDF on Android without password support (< 15, except 12–14 with SDK extension 13) | `unsupported-pdf` |
| A page could not be rendered (`error.page`) | `page-render-failed` |
| Out of memory | `out-of-memory` |

## Common migrations

### Page indicator

```diff
- const [page, setPage] = useState(1);
- <Pdf onPageChanged={(p, n) => { setPage(p); setTotal(n); }} />
- <Text>{page} / {total}</Text>
+ const [page, setPage] = useState(0);
+ <PdfView onPageChange={(e) => { setPage(e.page); setTotal(e.pageCount); }} />
+ <Text>{page + 1} / {total}</Text>
```

### Remembering where the reader was

react-native-pdf could only report the page. Save the full position (page,
point on the page and zoom) and pass it back when the document is opened
again:

```tsx
<PdfView
  source={source}
  initialViewport={saved}
  onViewportChange={({ viewport }) => save(viewport)}
/>
```

### Jumping to a page from a prop

react-native-pdf applied `page` whenever it changed, without animation.
Keep that behaviour with an effect:

```tsx
const pdf = useRef<PdfViewRef>(null);
useEffect(() => {
  pdf.current?.goToPage(targetPage - 1, { animated: false });
}, [targetPage]);

<PdfView ref={pdf} initialPage={targetPage - 1} source={source} />;
```

### Download progress

```diff
- <Pdf
-   source={{ uri }}
-   onLoadProgress={(fraction) => setProgress(fraction)}
-   renderActivityIndicator={(progress) => <ProgressBar value={progress} />}
- />
+ function Viewer({ uri }: { uri: string }) {
+   const [progress, setProgress] = useState<number | undefined>();
+   const [loaded, setLoaded] = useState(false);
+   return (
+     <View style={{ flex: 1 }}>
+       <PdfView
+         style={{ flex: 1 }}
+         source={{ uri }}
+         onLoadProgress={({ loadedBytes, totalBytes }) =>
+           setProgress(totalBytes === undefined ? undefined : loadedBytes / totalBytes)
+         }
+         onLoad={() => setLoaded(true)}
+       />
+       {!loaded && <ProgressBar value={progress} />}
+     </View>
+   );
+ }
+
+ <Viewer key={uri} uri={uri} />
```

`key={uri}` starts each document with fresh state; without it, the bar
stays hidden after the first document. Handle `onError` too, or the bar
stays when loading fails.

### Links

react-native-pdf did not open links either; the callback shape changes:

```diff
- onPressLink={(url) => Linking.openURL(url)}
+ onLinkPress={({ url }) => {
+   // Validate: a PDF is untrusted input.
+   if (url.startsWith('https://')) Linking.openURL(url);
+ }}
```

### Thumbnails

```diff
- <TouchableOpacity onPress={() => open(page)}>
-   <Pdf source={source} singlePage page={page} style={styles.thumb} />
- </TouchableOpacity>
+ <Pressable onPress={() => open(page)}>
+   <PdfView key={page} source={source} singlePage initialPage={page - 1} style={styles.thumb} />
+ </Pressable>
```

A `singlePage` view ignores touches, so the `Pressable` gets the tap and a
surrounding list scrolls from on top of it. Only that page is rendered.

`key={page}` loads the view again when a recycled list cell (FlashList) or
a page picker reuses it for another page; without it, the old page stays.

For an image file instead (lists of `<Image>`s, sharing, uploads), render
the page once: `await ref.current?.renderPage(page, { width })` returns
`{ uri, width, height }`.

### Table of contents

react-native-pdf passed the outline to `onLoadComplete` as
`{ title, pageIdx, children, mNativePtr }`, with `pageIdx` 0-based (a
string on iOS). It is now read on demand through the ref, the same shape on
both platforms, still 0-based:

```diff
+ const pdf = useRef<PdfViewRef>(null);
+ const [toc, setToc] = useState<PdfOutlineItem[]>([]);
- <Pdf
+ <PdfView
    source={source}
-   onLoadComplete={(pages, path, size, tableContents) => setToc(tableContents)}
+   ref={pdf}
+   onLoad={() => {
+     pdf.current?.getOutline().then(setToc, () => setToc([]));
+   }}
  />
```

```ts
type PdfOutlineItem = { title: string; page?: number; children: PdfOutlineItem[] };
```

`page` is `undefined` for entries that only group others or link
elsewhere. `getOutline()` rejects on Android for encrypted documents (their
titles are encrypted), so handle the rejection. When `source` changes,
give the component that holds `toc` a `key`, as in
[Download progress](#download-progress): a load that fails keeps no old
outline, and a late result for the previous document is dropped.

### Selected text

```diff
- onTextSelectionChange={({ nativeEvent }) =>
-   setSelected(nativeEvent.type === 'selectionChanged' ? nativeEvent.text : '')
- }
+ onTextSelectionChange={({ text }) => setSelected(text)}
```

The event now arrives once the selection settles (after the long press or
handle drag), not for every handle movement, and on Android too (Android
15+). A tap on the page clears the selection and is still an `onPageTap`.

### Search

react-native-pdf had no search. Here it is built in:

```tsx
<PdfView ref={pdf} source={source} onSearchChange={setSearch} />;
pdf.current?.search('invoice');
pdf.current?.searchNext();
```

Matches are highlighted natively; see the [Search guide](guides/search.md).

### Tap to toggle toolbars

```diff
- onPageSingleTap={() => setChromeVisible((v) => !v)}
+ onPageTap={() => setChromeVisible((v) => !v)}
```

Taps on the gaps between pages are not reported.

## Caching

react-native-pdf deleted the file and downloaded it again on every mount
unless `source.cache` was set. With `cache`, it kept the file until
`expiration` seconds had passed (`0` or none: forever), then downloaded it
again. `react-native-better-pdf` always keeps the download for the app
session and, without `maxAgeMs`, does not check it again:

| react-native-pdf | react-native-better-pdf |
| --- | --- |
| no `cache` | `maxAgeMs: 0`: the server is asked on every open (when it sends `ETag` or `Last-Modified`, an unchanged document is not downloaded again) |
| `cache: true` | `cache: 'persistent'` |
| `cache: true, expiration: n` (`n > 0`) | `cache: 'persistent', maxAgeMs: n * 1000` |
| `cache: true, expiration: 0` (or none) | `cache: 'persistent'`, no `maxAgeMs` |
| `cacheFileName` | `cacheKey` |

```diff
  <PdfView
    source={{
      uri,
-     cache: true,
-     cacheFileName: documentId,
-     expiration: 24 * 60 * 60,
+     cache: 'persistent',
+     cacheKey: documentId,
+     maxAgeMs: 24 * 60 * 60 * 1000,
    }}
+   onLoad={({ isStale }) => setOfflineBanner(isStale)}
  />
```

- After `maxAgeMs` the copy is not deleted: the server is asked whether it
  changed (`ETag` / `Last-Modified`). When the server cannot be reached, the
  view shows the stored copy and `onLoad` reports `isStale: true`.
- Persistent copies are not purged by the OS and not backed up. Remove them
  with `PdfCache.remove(source)` / `PdfCache.clear()`.
- Downloading ahead of time ("available offline"), checking for a copy and
  deleting it need no view and no file-system library:

```ts
import { PdfCache } from 'react-native-better-pdf';

await PdfCache.download(source, { onProgress, signal });
const entry = await PdfCache.getEntry(source); // undefined when not stored
```

See [Offline reading and caching](guides/offline-and-caching.md).

## Base64 documents

Pass the base64 itself as `{ base64 }` (1.1+), without the `data:` prefix:

```ts
// react-native-pdf
<Pdf source={{ uri: `data:application/pdf;base64,${base64}` }} />
// react-native-better-pdf
<PdfView source={{ base64 }} />
```

react-native-pdf wrote the data to a cache file itself (named, without
`cacheFileName`, by a SHA-1 of the whole string computed in JS, and written
with react-native-blob-util), and kept the file. Here the string is decoded
natively and `onLoad`'s `filePath` is `undefined`. Nothing stays on disk
once the document closes: iOS keeps a temporary copy only while it is
shown, Android not even that.
Line breaks in the base64 are ignored; URL-safe base64 is not accepted.

While the document is shown, its text is held twice (your string and a
native copy). Write large documents to a file and open the file, and if the
base64 comes from your own API, consider returning
the PDF itself and passing its URL with `headers`: it is then downloaded
natively, with progress, caching and offline reading. See
[Base64 documents](guides/sources-and-loading.md#base64-documents).

## Not supported yet

| Feature | Status |
| --- | --- |
| Passwords on Android 7–11, and 12–14 without SDK extension 13 | Not possible with the platform renderer |
| Windows | Not planned |
| Links in password-protected documents on Android < 15 (except 12–14 with SDK extension 13), and their internal links other than GoTo actions with XYZ destinations | Platform limitation — see [Limitations](limitations.md) (links in other documents work on every version) |
| Text selection on Android < 15 (except 12–14 with SDK extension 13); selections across pages on Android | Platform limitation below 15; cross-page selection not yet — see [Limitations](limitations.md) |

If one of these blocks your migration, please open an issue describing the use
case.

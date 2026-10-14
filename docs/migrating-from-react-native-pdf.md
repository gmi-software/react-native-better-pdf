# Migrating from react-native-pdf

This guide maps every prop, callback and method of
[react-native-pdf](https://github.com/wonday/react-native-pdf) 7.x to
`react-native-better-pdf`, lists behaviour that changes silently, and shows
workarounds for what is not supported yet. Defaults and signatures below were
read from react-native-pdf 7.0.5's source, not from its README.

## Before you start

- **New Architecture only.** `react-native-better-pdf` is a Nitro View and
  needs Fabric (React Native 0.78+; we test 0.81+). If your app still runs the
  legacy architecture, migrate that first.
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

## Behaviour that changes silently

Read these first — none of them causes a type error, all of them change what
users see.

| Topic | react-native-pdf | react-native-better-pdf | What to do |
| --- | --- | --- | --- |
| **Page numbers** | 1-based everywhere (`page`, `setPage`, `onPageChanged`, `onPageSingleTap`) | 0-based everywhere | Add or subtract 1 at the boundary. |
| **TLS certificates** | `trustAllCerts` defaults to **`true`** — invalid certificates are accepted | Certificates are always validated | Fix the server's certificate. Downloads that "worked" before may now fail with `network-error`. |
| **Download progress value** | `onLoadProgress(fraction)` — `0…1`, despite the name | `onLoadProgress({ loadedBytes, totalBytes? })` | `totalBytes === undefined ? undefined : loadedBytes / totalBytes` |
| **Loading UI** | Shows `xx.xx%` text (or `renderActivityIndicator`) while downloading | Shows nothing | Render your own indicator until `onLoad` / `onError`. |
| **Background** | `#EEE` behind the pages | Transparent | `style={{ backgroundColor: '#EEE' }}` |
| **Fit** | `fitPolicy` default `2` (fit whole page) | `'width'` when vertical and continuous; `'page'` with `horizontal` or `enablePaging` | Pass `fitPolicy="page"` to keep react-native-pdf's default. |
| **Max zoom** | `maxScale` default `3` | `4` | Pass `maxScale={3}` to keep the old limit. |
| **Page gap** | `spacing` default `10` | `pageSpacing` default `8` | Pass `pageSpacing={10}`. |
| **Short documents** | Centred vertically (#909, #920) | Start at the top in vertical continuous layouts, on both platforms | Nothing. |
| **Rounded corners** | `borderRadius` does not clip the pages on iOS (#920, #993) | `style.borderRadius` clips the document on both platforms | Nothing. |
| **Safe area (iOS)** | PDFKit adds the safe area as an inset, and going to a page ignores it: the first page can start under the status bar (#513, #994) | No automatic insets, like `ScrollView` and Android | Keep the viewer below the status bar (e.g. in a `SafeAreaView`). |
| **Current page** | Platform-specific (iOS: PDFKit's current page) | Page under the viewport centre; the last page once scrolled to the end — identical on both platforms | Usually nothing; UI showing "page X" becomes consistent. |
| **Remote file cache** | Opt-in (`source.cache`), persistent, keyed by SHA-1 of the URL computed in JS | Always: for the app session by default, across launches with `cache: 'persistent'`; keyed by URL + headers, or `cacheKey` | `cache: true` → `cache: 'persistent'`; see [Caching](#caching). |
| **Errors** | `Error` objects; the message is the only information | `PdfError` struct with a stable `code` | Match on `error.code`; see [Errors](#errors). |

## Props

| react-native-pdf | react-native-better-pdf | Notes |
| --- | --- | --- |
| `source={{ uri }}` | `source={{ uri }}` | `http(s)://`, `file://`, `content://` (Android). |
| `source={require('./x.pdf')}` | `source={require('./x.pdf')}` | Same; no Metro config needed. |
| `source.headers` | `source.headers` | Same. |
| `source.cache: true` | `source.cache: 'persistent'` | Kept across launches, never purged by the OS. See [Caching](#caching). |
| `source.cacheFileName` | `source.cacheKey` | Identifies the copy instead of the URL and headers. |
| `source.expiration` (seconds) | `source.maxAgeMs` (milliseconds) | An older copy is revalidated with the server (304 keeps it) instead of deleted, and still shown offline. |
| `source.method`, `source.body` | — | Only `GET` (`body` is read by react-native-pdf but missing from its types). |
| `source={{ uri: 'data:application/pdf;base64,…' }}` | — | Write the data to a file and open it; see [Base64 documents](#base64-documents). |
| `source={{ uri: 'bundle-assets://…' }}` | `require()` | Bundle the file as an asset instead. |
| `page` | `initialPage` (0-based) + `ref.goToPage()` | `page` is applied on change in react-native-pdf; here the initial page is a prop and later navigation is imperative. |
| `scale` | `initialScale` + `ref.zoomTo(scale)` | Relative to the fitted page; applied once on load, like `initialPage`. |
| `minScale`, `maxScale` | `minScale`, `maxScale` | Relative to the fitted page; `maxScale` default differs (3 → 4). |
| `spacing` | `pageSpacing` | Default 10 → 8. |
| `password` | `password` | Android: Android 15+, or Android 12–14 with SDK extension 13 (updated system PDF module); other devices report `unsupported-pdf`. react-native-pdf's bundled PDFium supported every version. |
| `showsVerticalScrollIndicator`, `showsHorizontalScrollIndicator` | same | Same names and defaults. |
| `horizontal`, `enablePaging` | same | Same names and defaults. Paging swipes along the layout axis (vertical unless `horizontal`) on both platforms. |
| `singlePage` | `singlePage` | Shows `initialPage` (react-native-pdf: `page`); touches are ignored as before. Default fit is the whole page. |
| `enableRTL` | `enableRTL` | Horizontal layouts only, page numbers in document order on both platforms. react-native-pdf on Android also reversed vertical layouts and page numbers; iOS (PDFKit) supports it only with `enablePaging`, as before. |
| `fitPolicy={0 \| 1 \| 2}` | `fitPolicy="width" \| "height" \| "page"` | Named values. iOS applies one scale to the whole document (see the API docs). |
| — | `spreadMode="pairs" \| "book"` | New: facing pages side by side in the vertical continuous layout. |
| `nightMode` (patched in on Android, #996) | `colorScheme="dark"` | Both platforms; hues kept rather than plainly inverted. |
| `scrollEnabled` | `scrollEnabled` | Same name and default. Disables drag scrolling (also paging swipes); the drag goes to parent views. |
| `enableDoubleTapZoom` | `enableDoubleTapZoom` | Same name and default. When `false`, `onPageTap` also fires without waiting for a possible second tap. |
| `enableAntialiasing` | — | Always on. |
| `enableAnnotationRendering` | — | Always on where the platform can: iOS draws every annotation; Android draws only some kinds (see the README's Limitations). react-native-pdf's bundled PDFium drew all kinds on Android. |
| `enableTextSelection` | `enableTextSelection` | Same name and default (`true`). `false` now turns selection off (react-native-pdf only stopped the event), and Android selects too (Android 15+). |
| `usePDFKit` | — | iOS always uses PDFKit. |
| `trustAllCerts` | — | Intentionally not supported. |
| `renderActivityIndicator`, `progressContainerStyle` | — | Render your own; see `onLoadProgress`. |
| `style` and other `View` props | same | |
| — | `ref.print()` | New: the system print dialog for the loaded document, protected documents included. |
| — | `contentInset={{ top, bottom }}` | New: pages scroll under a translucent header or toolbar (PR #993 in react-native-pdf, unmerged). |
| — | `<PdfPageOverlay page x y width height>` children | New: React views on pages that follow scrolling and zoom, placed natively (#350, #254, #449, #484). |
| — | `enableFormFilling`, `onFormChange`, `ref.getFormFields()`, `ref.setFormFieldValues()`, `ref.exportDocument()` | New: AcroForm filling by touch and from code, and the filled-in PDF as a file. |

## Callbacks

| react-native-pdf | react-native-better-pdf |
| --- | --- |
| `onLoadComplete(numberOfPages, path, { width, height }, tableContents)` | `onLoad({ pageCount, firstPageWidth, firstPageHeight, filePath })` — `filePath` is `path` (absolute, no `file://`); table of contents via `ref.getOutline()`; every page's size (not only the first) via `ref.getPageSizes()` |
| `onPageChanged(page, numberOfPages)` — 1-based | `onPageChange({ page, pageCount })` — 0-based |
| `onLoadProgress(fraction)` | `onLoadProgress({ loadedBytes, totalBytes? })` — throttled to ≤ 10 per second |
| `onError(error: Error)` | `onError({ code, message, page?, httpStatus?, nativeDescription? })` |
| `onPageSingleTap(page, x, y)` — 1-based, view coordinates | `onPageTap({ page, x, y })` — 0-based, PDF points from the page's top-left, identical on both platforms |
| `onPressLink(url)` | `onLinkPress({ url })` — links to other pages are followed natively and not reported |
| `onScaleChanged(scale)` | `onScaleChange({ scale })` — once per settled change, not during the gesture; relative to the fitted page |
| `onTextSelectionChange({ nativeEvent: { type, text } })` — per handle move | `onTextSelectionChange({ text })` — once per settled selection; `text` is `''` when cleared |

`onPageSingleTap` coordinates were view pixels on Android and view points on
iOS; if you converted them to page coordinates yourself, remove that code.

## Methods

```diff
  const pdf = useRef(null);
- pdf.current?.setPage(5);          // 1-based
+ pdf.current?.goToPage(4);         // 0-based, animated
+ pdf.current?.goToPage(4, { animated: false });
+ pdf.current?.zoomTo(2);           // replaces the `scale` prop
```

Type the ref with `PdfViewRef`:

```ts
import type { PdfViewRef } from 'react-native-better-pdf';
const pdf = useRef<PdfViewRef>(null);
```

## Errors

react-native-pdf passes `Error` objects whose messages differ by platform.
Replace message matching with the stable code:

```diff
- onError={(error) => {
-   if (String(error).includes('Password')) askForPassword();
- }}
+ onError={(error) => {
+   if (error.code === 'password-required') askForPassword();
+   else if (error.code === 'invalid-password') showWrongPassword();
+ }}
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

react-native-pdf applied `page` whenever it changed. Keep that behaviour with
an effect:

```tsx
const pdf = useRef<PdfViewRef>(null);
useEffect(() => {
  pdf.current?.goToPage(targetPage - 1);
}, [targetPage]);

<PdfView ref={pdf} initialPage={targetPage - 1} source={source} />;
```

### Download progress

```diff
- <Pdf
-   onLoadProgress={(fraction) => setProgress(fraction)}
-   renderActivityIndicator={(progress) => <ProgressBar value={progress} />}
- />
+ const [progress, setProgress] = useState<number | undefined>();
+ const [loaded, setLoaded] = useState(false);
+ <View style={{ flex: 1 }}>
+   <PdfView
+     onLoadProgress={({ loadedBytes, totalBytes }) =>
+       setProgress(totalBytes === undefined ? undefined : loadedBytes / totalBytes)
+     }
+     onLoad={() => setLoaded(true)}
+   />
+   {!loaded && <ProgressBar value={progress} />}
+ </View>
```

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
+   <PdfView source={source} singlePage initialPage={page - 1} style={styles.thumb} />
+ </Pressable>
```

A `singlePage` view ignores touches, so the `Pressable` gets the tap and a
surrounding list scrolls from on top of it. Only that page is rendered.

For an image file instead (lists of `<Image>`s, sharing, uploads), render
the page once: `await ref.current?.renderPage(page, { width })` returns
`{ uri, width, height }`.

### Table of contents

react-native-pdf passed the outline to `onLoadComplete` (Android:
`{ title, pageIdx, children, mNativePtr }`; iOS: its own shape). It is now
read on demand, the same shape on both platforms, zero-based:

```diff
- onLoadComplete={(pages, path, size, tableContents) => setToc(tableContents)}
+ onLoad={async () => setToc(await pdf.current?.getOutline())}
```

```ts
type PdfOutlineItem = { title: string; page?: number; children: PdfOutlineItem[] };
```

`page` is `undefined` for entries that only group others or link elsewhere
(react-native-pdf reported `pageIdx` `-1` or omitted them).

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

Matches are highlighted natively; see the README's "Search" section.

### Tap to toggle toolbars

```diff
- onPageSingleTap={() => setChromeVisible((v) => !v)}
+ onPageTap={() => setChromeVisible((v) => !v)}
```

Taps on the gaps between pages are not reported.

## Caching

react-native-pdf re-downloaded on every mount unless `source.cache` was set,
and then kept the file until `expiration`. `react-native-better-pdf` always
keeps the download for the app session; the source's options cover the rest:

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

See the README's "Offline reading and caching" section.

## Base64 documents

`data:` URIs are not supported. react-native-pdf accepted them by writing the
data to a cache file itself (named, without `cacheFileName`, by a SHA-1 of
the whole string computed in JS, and written with react-native-blob-util).
Here that step is yours, done once:

```ts
// Expo: expo-file-system
import { File, Paths } from 'expo-file-system';

const file = new File(Paths.cache, `${id}.pdf`);
file.write(base64, { encoding: 'base64' });
<PdfView source={{ uri: file.uri }} />;
```

```ts
// Bare React Native: @dr.pogodin/react-native-fs (the react-native-fs API)
import { CachesDirectoryPath, writeFile } from '@dr.pogodin/react-native-fs';

const path = `${CachesDirectoryPath}/${id}.pdf`;
await writeFile(path, base64, 'base64');
<PdfView source={{ filePath: path }} />;
```

Give each document its own file name: a source with the same path does not
reload the view, even after the file is rewritten. If the base64 comes from
your own API, returning the PDF itself and passing its URL with `headers` is
better still: it is then downloaded natively, with progress, caching and
offline reading. See the README's "Base64 documents" section.

## Not supported yet

| Feature | Status |
| --- | --- |
| Passwords on Android 7–11, and 12–14 without SDK extension 13 | Not possible with the platform renderer |
| Windows | Not planned |
| Links in password-protected documents on Android < 15 (except 12–14 with SDK extension 13), and their internal links other than GoTo actions with XYZ destinations | Platform limitation — see the README (links in other documents work on every version) |
| Text selection on Android < 15 (except 12–14 with SDK extension 13); selections across pages on Android | Platform limitation below 15; cross-page selection not yet — see the README |

If one of these blocks your migration, please open an issue describing the use
case.

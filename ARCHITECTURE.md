# Architecture

`react-native-better-pdf` is one Nitro Hybrid View (`BetterPdfView`) with a
thin React wrapper (`PdfView`), plus Hybrid Objects for the download cache
(`BetterPdfCache`, wrapped by `PdfCache`) and for documents opened without a
view (`BetterPdfDocument`, wrapped by `PdfDocument`). Everything that happens per frame (scrolling,
zooming, rendering, caching, prefetching) happens natively. JS sends
configuration and commands and receives three kinds of events.

Background research and rejected alternatives: [`docs/architecture/research.md`](docs/architecture/research.md).
Performance model, measurements and optimizations: [`docs/performance/`](docs/performance/).
Public API rationale: [`docs/api-design.md`](docs/api-design.md).

```text
┌──────────────────────────── JS thread ────────────────────────────┐
│ <PdfView>  (src/PdfView.tsx)                                      │
│   • normalizes + memoizes source by value                         │
│   • identity-stable callbacks (ref-forwarded)                     │
│   • ref → goToPage / zoomTo / getStats / getOutline               │
└──────────────┬───────────────────────────────▲────────────────────┘
               │ props (on change only)         │ onLoad / onPageChange / onError
               ▼                                │
┌──────────── Nitro (generated C++: ShadowNode, JSI converters) ────┐
└──────────────┬───────────────────────────────▲────────────────────┘
               ▼                                │
┌──── iOS: HybridBetterPdfView.swift ────┐ ┌── Android: HybridBetterPdfView.kt ──┐
│ PdfDocumentLoader (download, open)     │ │ PdfDocumentSession (download, open, │
│ PDFView (PDFKit): scroll, zoom,        │ │   render thread, queue)             │
│   virtualization, tiled rendering      │ │ PdfDocumentView: gestures, layout,  │
│                                        │ │   drawing, cache, tiles, prefetch   │
└────────────────────────────────────────┘ └─────────────────────────────────────┘
```

## Source layout

```text
package/
  src/
    specs/BetterPdfView.nitro.ts   Nitro spec: props, methods, event/error types
    specs/BetterPdfCache.nitro.ts  Nitro spec of the download cache (internal)
    specs/BetterPdfDocument.nitro.ts  Nitro spec of a document without a view (internal)
    PdfView.tsx                    public component (wrapper over the host component)
    PdfCache.ts                    public cache API: AbortSignal, PdfDownloadError
    PdfDocument.ts                 public document API: open (AbortSignal, PdfOpenError), close
    source.ts                      PdfSource union → native { uri, headers, cache options }
  nitrogen/generated/              Nitrogen output (committed; regenerate with `bun run specs`)
  ios/
    HybridBetterPdfView.swift      PDFView host, scale mapping, events, lifecycle
    PdfContainerView.swift         taps, links, text selection settle / disable
    PdfSearchController.swift      PDFKit async find, highlights, next/previous
    SettleDebouncer.swift          "viewport stopped moving" for scale and viewport events
    PdfDocumentLoader.swift        source resolution, cache fetch + pin, PDFDocument open
    PdfLoadFailure.swift           Swift error → PdfError
    DocumentCache.swift            fresh copy or shared download/revalidation, stale fallback
    DocumentCacheStore.swift       copies on disk: session/persistent dirs, metadata, pins
    SharedDownload.swift           one URLSession request per copy, conditional with a copy
    HybridBetterPdfCache.swift     BetterPdfCache: PdfCache's native side
    HybridBetterPdfDocument.swift  BetterPdfDocument: a PDFDocument confined to one serial queue
    PageImageRequest.swift         renderPage validation, render and file write (view and document)
    PDFDocument+metadata.swift     information dictionary from CGPDFDocument, PdfDate
  android/src/main/java/com/margelo/nitro/betterpdf/
    HybridBetterPdfView.kt         props/methods/events, session lifecycle
    PdfDocumentSession.kt          PdfRenderer + FD owner, render thread, renderer recycling
    RenderQueue.kt                 pure: replace-on-submit work queue (cancellation)
    PdfDocumentView.kt             the viewer View: gestures, drawing, cache, tiles
    TextSelectionController.kt     long press, handles, highlight, floating Copy / Select all toolbar
    PageTextSelection.kt           pure: selection ends, handle anchors, drags
    SearchController.kt            search(), next/previous, onSearchChange throttling
    PdfSearchTask.kt               search thread with its own renderer (duplicated FD)
    SearchResults.kt               pure: matches by page, current match, wrapping
    DocumentViewport.kt            pure: settled position (page point, zoom, visible pages, end)
    PdfDocumentLayout.kt           pure: page geometry, O(log n) lookups
    PrefetchPlanner.kt             pure: render priority order
    LruByteCache.kt                pure: byte-budgeted LRU
    BitmapPool.kt                  bitmap reuse + eager recycling
    MemoryBudgets.kt               pure: bitmap pool budget
    PdfSourceResolver.kt           source → seekable ParcelFileDescriptor
    DocumentCache.kt               fresh copy or shared download/revalidation, stale fallback
    DocumentCacheStore.kt          copies on disk: session/persistent dirs, metadata
    SharedDownload.kt              one HttpURLConnection request per copy, conditional with a copy
    HybridBetterPdfCache.kt        BetterPdfCache: PdfCache's native side
    HybridBetterPdfDocument.kt     BetterPdfDocument: the FD and one worker thread, no open renderer
    DocumentReads.kt               outline, metadata, page sizes, page images on duplicated FDs
                                   (view session and document)
    PageImageRequest.kt            renderPage validation and file write (view and document)
    outline/                       bounded PDF parser: outline, page tree, /Info (PdfDate)
```

No hand-written C++: the only consumer of shared logic would be Android
(PDFKit already virtualizes on iOS), so a C++ core would add a JNI hop to the
per-frame path without a second caller. See the research notes.

## Threading

| Operation                                   | iOS                                  | Android                                          |
| ------------------------------------------- | ------------------------------------ | ------------------------------------------------ |
| React render / prop diff                    | JS thread                            | JS thread                                        |
| Prop setters, `afterUpdate`, `onDropView`   | main                                 | main                                             |
| `goToPage` / `zoomTo` (via `hybridRef`)     | JS thread → `DispatchQueue.main`     | JS thread → main `Handler`                       |
| `getStats`                                  | JS thread, lock-protected snapshot   | JS thread, `@Volatile` / atomic counters         |
| `getOutline` (first call; then cached)      | main → `com.betterpdf.read` queue, separate `PDFDocument` | main, after earlier form work → the document's `BetterPdf-read` thread (in call order with page images and page sizes, exits when idle), duplicated FD (`outline/` reader) |
| `renderPage`                                | main → `com.betterpdf.read` queue: separate `PDFDocument`, `UIGraphicsImageRenderer`, file write | main, after earlier form work → the document's `BetterPdf-read` thread (in call order): separate renderer on a duplicated FD (closed after the page), PNG/JPEG encode, file write |
| `getPageSizes` (first call; then cached)    | main → `com.betterpdf.read` queue, separate `PDFDocument` | main, after earlier form work → `BetterPdf-read` thread, duplicated FD: page tree (`outline/` reader); encrypted: a separate renderer opening each page |
| `exportDocument`                            | main → `com.betterpdf.write` queue: separate `PDFDocument` (nothing filled in: a file copy), file write | main, after earlier form work → the document's `BetterPdf-write` thread: separate renderer on a duplicated FD (no form support: a file copy), file write |
| Remote download (`DocumentCache`)           | lookup on `com.betterpdf.cache`; transfer and store on the `URLSession` delegate queue | lookup and transfer on `BetterPdf-download` threads (`HttpURLConnection`); the document worker waits |
| Download progress (throttle ≤ 10/s)         | throttled on the delegate queue → main | throttled on the download thread → main         |
| `PdfCache` (`getEntry`, `remove`, `clear`, `getByteSize`) | `com.betterpdf.cache.api` queue, disk only | `Promise.parallel` thread, disk only |
| `PdfDocument.open`                          | as the view's open (`PdfDocumentLoader`), plain `PDFDocument` | `BetterPdf-document` thread: source, then a renderer to count pages (closed) |
| `PdfDocument` reads (in call order)         | the document's `com.betterpdf.document` serial queue, its own `PDFDocument` | the document's `BetterPdf-document` thread (exits when idle), `DocumentReads` on duplicated FDs |
| Open + parse document                       | concurrent background queue          | document worker thread                           |
| Page size measurement                       | PDFKit (lazy, internal)              | lazily, render thread, when a page is first opened |
| Scroll, fling, pinch, double-tap            | main (UIScrollView inside PDFView)   | main (`OverScroller`, gesture detectors)         |
| Visible-range / page-change computation     | PDFKit, main                         | main, O(log n) binary search                     |
| Page rendering                              | PDFKit background tile rendering     | one `BetterPdf-render` thread per document       |
| Cache insert / evict / draw                 | PDFKit internal                      | main only (no locking needed)                    |
| Bitmap pool                                 | —                                    | acquire: render thread, release: main (locked)   |
| Text selection                              | PDFKit (main); settle reported on main | query: render thread (urgent queue slot); UI: main |
| Viewport events (`onViewportChange`)        | settle detected on main (scroll-offset KVO + 100 ms debounce) | settle detected on main (same settle as tiles and zoom) |
| Search                                      | PDFKit async find (background); matches and highlights on main | `BetterPdf-search` thread with its own renderer; results on main |
| Event callbacks                             | invoked on main; Nitro hops to JS    | invoked on main; Nitro hops to JS                |

Rule: nothing ever blocks the main thread on document I/O or rendering, and
nothing blocks JS on native work (`getStats` reads a snapshot).

## Android viewer internals

### Coordinate model

`PdfDocumentLayout` places pages along a main axis (vertical, or
horizontal with `horizontal`) in unzoomed content pixels, separated by
`pageSpacing`. Every page is scaled individually by the fit policy (width,
height or whole page); pages narrower than the widest one are centred on the
cross axis, whose extent is at least the view. Paged layouts give every page a viewport-sized slot, so scrolling to
`slotStart(i)` shows exactly page `i`; after a fling or a drag the view snaps
to the next/previous/nearest slot (`snapToPage`) unless zoomed in. A page
longer than the view gets a slot of its own length and scrolls freely within
it; flings are bounded to the page until its edge is reached. `singlePage`
reuses the paged layout with the scroll bounds confined to one slot, renders
only that page (no prefetch) and returns `false` from `onTouchEvent`, so
touches reach the parents. Right-to-left horizontal layouts keep the
per-page arrays in visual order and map page indices at the layout's API
(`visual(index)`), so everything outside `PdfDocumentLayout` keeps document
page indices. The viewport state is
`(scale, offsetX, offsetY)` in zoomed pixels. A frame is:

```text
canvas.clipRect(view bounds)          // RN parents disable clipChildren
canvas.translate(-offsetX, -offsetY)
canvas.scale(scale)
for page in layout.visibleRange(offsetY/scale, (offsetY+height)/scale):
    draw base bitmap (or white placeholder) into the page rect
    draw the page's zoom tile, if any, into its content rect
```

### Virtualization

No child views, no per-page objects beyond a `FloatArray` of tops/heights.
A 1000-page document costs 12 KB of layout arrays; only visible pages are
touched per frame.

### Render pipeline

```text
viewport change (main)
  └─ PrefetchPlanner.plan(visible, center, direction)
       └─ requests = planned pages not cached at the current width
                     (+ zoom tiles for visible pages when settled)
            └─ session.submit(requests)   // replaces the queue
                 └─ render thread: pop highest priority → openPage → render → close
                      └─ post(main): cache.put / tiles.put → invalidate
```

- **Cancellation**: `PdfRenderer.Page.render` cannot be interrupted, so the
  queue (`RenderQueue`) is *replaced* on every viewport change and unwanted
  requests are dropped before they start (`rendersCancelled`). The in-flight
  request is never duplicated. Both guarantees are unit tested.
- **Priority**: visible pages (nearest to center first) → zoom tiles →
  pages ahead in the scroll direction → pages behind.
- **Direction changes** take effect on the next frame; queued work for the old
  direction is discarded.
- **Links**: read from the file once it opens, by the Kotlin parser on a
  thread of its own (`PdfLinkReader`: every Android version, every link
  form), and kept per page on the UI thread. Where the parser cannot read
  the file (encrypted, or a page tree the renderer disagrees with), each
  page's links come from the renderer instead, read on the render thread
  while the page is open for its first render (`DocumentPage.readLinks`,
  API 35+ or `PdfRendererPreV`). A confirmed single tap is mapped to page
  points (`PdfDocumentLayout.pagePointAt`) and hit-tested against them:
  internal links scroll natively, URL links go to `onLinkPress`, anything
  else to `onPageTap`.
- **Text selection**: a long press or handle move submits a
  `TextSelectionQuery` to the queue's urgent slot, which the render thread
  takes before any render and a newer query replaces before it starts. The
  thread opens the page, calls `selectContent` (API 35+ or
  `PdfRendererPreV`) and posts the
  result; the view keeps it in page points and draws the highlight and
  handles in view coordinates on top of the pages.
- **Failure isolation**: a page that throws is recorded in `failedPages`,
  reported once (`page-render-failed`) and drawn as a placeholder, never
  retried in a loop.

### Page sizes and PDFium's document cache

`PdfRenderer` only reports a page's size from an opened page, and PDFium keeps
every object it parsed for an opened page (for scans: the compressed image
data) until the document is closed. Measured on the 395 MB, 1000-page
fixture: opening every page to read sizes took 1.7 s and retained 400 MB of
native heap. So:

- At load only page 0 is opened. Every other page is **estimated** at page
  0's size, which is exact for the common uniform document.
- A page's real size is recorded the first time it is opened for rendering.
  If it differs from the estimate, the layout is rebuilt with the top visible
  page anchored (deferred until the viewport settles if a fling or zoom
  animation is running). Base bitmaps are always rendered at the page's real
  aspect ratio, and tiles are only requested for pages whose base bitmap
  exists, so an estimate never distorts or misplaces content.
- The renderer is re-created from a duplicate of the original file descriptor
  every 48 page opens (`RECYCLE_AFTER_PAGES`), which drops PDFium's cache.
  Creating a renderer is cheap (3–27 ms measured, no retained memory) because
  it does not touch pages.

Trade-off: parsing moved from load time to first render of each page, and
pages far from the viewport sit at estimated positions until visited, so a
`goToPage` into a document of mixed page sizes lands on the estimated
position first and is re-anchored after that page is measured.

### Cache strategy

| Layer        | What                                             | Bound                                                   |
| ------------ | ------------------------------------------------ | ------------------------------------------------------- |
| Base cache   | one bitmap per page, ≤ fit width (`LruByteCache`)| `clamp(maxHeap / 6, 24 MB, 128 MB)`, ≥ 4 viewports      |
| Zoom tiles   | one viewport-sized full-res crop per visible page| visible pages × 1 viewport                              |
| Bitmap pool  | free bitmaps for reuse                           | `max(8 MB, 1 viewport, 1 base page)`, or base page + 1 viewport when tiles are drawn at zoom 1; 0 while detached |

- Visible pages are *protected*: they are never evicted to make room, so the
  budget can be exceeded only by what is on screen.
- Very tall pages have their base bitmap capped at 2 viewports of pixels;
  tiles supply detail when zoomed.
- Zoomed out (`minScale < 1`), pages render at half their fit-width size
  at zoom ½ and below, and at a quarter at ¼ and below
  (`BaseResolution`): never fewer pixels than are on screen, and the
  visible pages' memory stays a few viewports instead of growing with
  1 / zoom² as more of them come into view: they fit the 128 MB cap down
  to zoom 0.01 on phones and tablets. Pages stay at a quarter below ¼, so
  zooming back in starts from a usable picture.
- The pool fits the largest base page of the current render plan. In portrait
  a fit-width page is smaller than the viewport; in landscape, on tablets and
  on unfolded foldables it is larger, and the pool recycles any bitmap larger
  than its budget, so a viewport-sized pool would never reuse a page. When
  the page is also capped below screen resolution (landscape phones), tiles
  are drawn even at zoom 1, and the pool fits a page and a tile together so
  the two do not push each other out.
- Cache key is the page; entries rendered at a different width (rotation,
  resize, crossing zoom ½ or ¼) are re-requested and replaced.
- Memory pressure: `onTrimMemory(RUNNING_LOW, RUNNING_CRITICAL)`/`onLowMemory`
  trims the cache to the visible pages and empties the pool, then keeps only
  visible pages for 30 s; the pool comes back on the first render request
  after that. `out-of-memory` during a render trims the same way and is
  reported once. The `RUNNING_*` levels are not delivered since API 34.
- App UI hidden (`onTrimMemory(UI_HIDDEN)` and the background levels, on
  every trip to the background): the same trim and queued renders dropped,
  without the 30 s backoff. Nothing renders while the window is hidden: the
  queue is dropped when it hides, and an off-screen page that was already
  rendering is discarded. When it shows again, the visible pages' neighbours
  are prefetched right away.
- Detached from window (e.g. a screen hidden under a native stack): all
  bitmaps are dropped; the document stays open and re-renders on attach.
- Bitmaps that leave the cache go to the pool or are `recycle()`d
  immediately rather than waiting for a GC (the Java heap of a PDF viewer is
  tiny, so GCs are rare and native pixel memory would pile up).
- Bitmaps are released one frame after they leave the screen
  (`postOnAnimation`), so the render thread never overwrites pixels that the
  RenderThread may still be drawing.

### Scroll indicators

The platform's own fading scroll bars (theme-styled thumbs), fed from the
viewport state through `computeVertical/HorizontalScroll{Range,Offset,Extent}`
and woken by `awakenScrollBars()` whenever the offsets change. The view is
constructed with the ScrollView style because that is the only public way for
a `View` created in code to get its scroll bar drawables initialized
(`initializeScrollbars(TypedArray)` was removed from the SDK). No JS is
involved; the bars fade on the platform's schedule.

### Accessibility

`PdfAccessibilityHelper` describes the canvas to accessibility services:
one virtual node per visible page (label + text), the host as a scrollable
list. `PdfDocumentView` implements `PdfAccessibilityHost` (visible pages,
page bounds in view coordinates, hit testing, labels, cached text) and
queues `PageTextQuery` work for visible pages without text only while
`AccessibilityManager.isEnabled`. The render thread answers it through
`DocumentPage.readText()`.

### Search

`SearchController` starts one `PdfSearchTask` per query. The task runs on
its own thread with its own `PdfRenderer` over a duplicated file descriptor
(the view's render thread and its queue are untouched), searches pages from
the current one to the end and then from the start, and posts each page's
matches to the main thread; a new query or document cancels it. The
renderer is reopened every 48 pages to cap PDFium's per-page cache. The
platform serialises all `PdfRenderer` calls in a process on one lock, so
the search interleaves with rendering page by page. `SearchResults` keeps
matches by page; `PdfDocumentView` draws the highlights of visible pages
and scrolls the current match into view (0.3 of the viewport from the top
unless it is already fully visible).

### Zoom

Pinch zooms around the focal point; double-tap toggles between `minScale`
and `max(2, 2 × minScale)`; horizontal pan is enabled when content is wider
than the view. During a gesture the base bitmaps are simply scaled (cheap,
slightly soft); zooming out past ½ or ¼ also renders the visible pages
again at half or a quarter of their size. When the viewport settles for
80 ms and the screen shows ≥ 15% more pixels than the base layer has, one
full-resolution tile per visible page is rendered using `PdfRenderer`'s
clip + matrix. Tiles carry their own content-space rect and scale, so a
tile that finishes after the user zoomed again still lands in the right
place.

### Lifecycle

| Event                 | Effect                                                                       |
| --------------------- | ---------------------------------------------------------------------------- |
| `source`/`password` change | old session `close()`d; new session started                              |
| `onDropView`          | `on*` callbacks released, session closed, all bitmaps dropped                |
| `session.close()`     | queue cleared, download aborted, pending main-thread results dropped; the worker finishes the in-flight render, then closes the renderer (and with it the FD) |
| Detach from window    | bitmaps dropped, pool emptied, queue emptied                                 |
| Window hidden         | queue emptied, a late off-screen render discarded; on `UI_HIDDEN`, off-screen pages dropped and pool emptied |
| Window shown again    | render plan resubmitted, so neighbours of the visible pages are prefetched   |

`PdfRenderer` and `PdfRenderer.Page` are created *and* closed on the worker
thread; pages are always closed via `use {}`.

## iOS internals

`PDFView` (`.singlePageContinuous`, or `.singlePage` with user interaction
disabled for `singlePage`; `displayDirection` from `horizontal`;
`usePageViewController` for `enablePaging`; `displaysRTL` for `enableRTL`,
effective with the page view controller only) provides scrolling, zooming,
double-tap, page virtualization and background tile rendering. The wrapper:

- loads on a background queue (`PDFDocument(url:)` parses synchronously),
  gets remote documents from `DocumentCache` (see "Download cache") and
  opens them through a pin, cancels on source change;
- maps `minScale`/`maxScale` (relative to the fitted page; one scale for the
  whole document, see `fitPolicy`) to PDFKit's absolute
  `minScaleFactor`/`maxScaleFactor`, re-deriving on layout changes and keeping
  the user's relative zoom across rotation;
- applies `initialPage` after the first layout with a non-zero fit scale
  (`go(to:)` before layout is ignored by PDFKit);
- reports `PDFViewPageChanged` as `onPageChange`, de-duplicated;
- keeps the reader's page through layout mode switches and resizes: PDFKit
  passes through other pages while it relays out (and after a resize of a
  horizontal layout settles on page 1, measured), so pages seen during a
  relayout are not reported, and the page from before a resize is restored
  if PDFKit lost it;
- searches with `beginFindString` (asynchronous; matches arrive through
  `PDFDocumentDidFindMatch`) and shows them with `highlightedSelections`:
  one selection per page, for the visible pages and one either side, and
  the current match;
- in `onDropView`, releases the `on*` callbacks and destroys the `PDFView`
  with its document, so PDFKit releases its tiles immediately; the search
  and the form drop their references to the document then and when the
  source changes.

PDFKit does not expose cache metrics; `getStats()` returns `-1` for those
fields on iOS.

## Download cache

Remote documents go through one `DocumentCache` per process (same design on
both platforms), used by every view and by `PdfCache`:

```text
fetch(source) ──► stored copy fresh for source.maxAgeMs? ──yes──► deliver
                      │ no / none
                      ▼
          SharedDownload for the key in flight? ──yes──► join it
                      │ no
                      ▼
          GET (conditional with the copy's ETag / Last-Modified)
             304 → mark validated · 200 + %PDF- → rename into place
             unreachable / 5xx → view: stored copy as stale · PdfCache: error
```

- **Checked first:** an `http(s)` URL and valid headers (token names, no
  control characters but tab in values), else `invalid-source` with no
  request; see `docs/api-design.md` "Source".
- **Key:** SHA-256 of `cacheKey`, else of `uri` + sorted headers. Files:
  `<key>.pdf` + metadata (`validatedAtMs`, `etag`, `lastModified`).
- **Where:** session copies in the cache directory (`BetterPdf/session`),
  deleted with unfinished downloads on the first use in a process;
  persistent copies in iOS Application Support / Android `noBackupFilesDir`
  (`BetterPdf/documents`), excluded from backups. A persistent copy wins; a
  session copy is promoted with a hard link.
- **Writes** never modify a file in place: new copies are renamed over the
  old ones and removal unlinks, so a displayed document keeps reading its
  file. iOS views open a pin (hard link in `BetterPdf/open`) because they
  re-open the displayed file by path for outline, page sizes and images.
- **Waiting:** one subscriber per view load or `PdfCache.download()`.
  Cancelling removes the subscriber; the request stops when none remain.
- **Tests:** `DocumentCacheTest` (JVM, local HTTP server) and the
  `viewer-offline` e2e flow on both platforms.

## Known platform constraints

- **Android PdfRenderer serializes all documents in a process** on a static
  PDFium lock (`PdfProcessor.sPdfiumLock`, API 35+). Two visible `PdfView`s
  render one page at a time between them, and a closing document's in-flight
  render can delay the next document's first page by one render.
- **Passwords on Android need API 35+, or API 31–34 with SDK extension 13**
  (`LoadParams` with `PdfRenderer` or `PdfRendererPreV`, chosen by
  `DocumentRenderer.open`). Other devices report `unsupported-pdf`.
- **Android page positions are estimates until visited** in documents whose
  pages differ in size (see "Page sizes" above).
- **Untrusted documents** are parsed in-process by the platform renderers.
  Android's docs recommend an isolated process for untrusted input; this
  library does not do that (see `docs/audit.md`).

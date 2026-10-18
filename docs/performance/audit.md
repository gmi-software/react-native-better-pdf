# Performance audit

Scope: `react-native-better-pdf` 0.1 as of 2026-09-29. Method: read every
stage of the pipeline, classify each JS↔native crossing, then **measure**
before changing anything. Numbers come from `docs/performance/results.md`;
anything not measured is marked `UNKNOWN`.

Measurement environment (Android): `sdk_gphone64_arm64` emulator, API 35,
1080×2400, release build, `benchmarks/run-android.mjs`. Emulator CPU/GPU are
not representative of devices; results are used **relatively** (before/after,
library vs. library), never as absolute device claims.

## 1. Pipeline

```text
JS  <PdfView>                         JS thread
 ↓  props on change (Nitro diff by identity)
Nitro  ReactProp / ShadowNode          JS thread → UI thread (Fabric commit)
 ↓
Native view  HybridBetterPdfView       UI thread
 ↓  source change → session
Document  PdfDocumentSession (A) / PDFDocument (iOS)   worker thread
 ↓
Page selection  PdfDocumentLayout.visibleRange + PrefetchPlanner   UI thread (A) / PDFKit (iOS)
 ↓  submit(requests) — queue replaced
Rendering  PdfRenderer.Page.render (A) / PDFKit tiles (iOS)        render thread
 ↓  post(main)
Bitmap  LruByteCache / tiles / BitmapPool (A) / PDFKit (iOS)       UI thread
 ↓  invalidate
Native view  onDraw → canvas.drawBitmap                            UI thread → RenderThread
 ↓
Screen
```

### Stage details

| Stage | Thread | Allocations | Copies / serialization | Sync / locks | Lifecycle events | Repeated work |
| --- | --- | --- | --- | --- | --- | --- |
| `PdfView` render | JS | `normalizeSource` object + `sourceKey` string per parent render | none unless value changed | — | mount / unmount | only when the *parent* re-renders; the component has no state |
| Prop transfer | JS → UI | `ReactProp` entry per *changed* prop | `NativePdfSource` converted once per change | Fabric commit | — | none: identity-stable source & callbacks (tested) |
| Load (Android) | worker | FD, `PdfRenderer`, **page-size list** | — | `PdfProcessor.sPdfiumLock` (static, process-wide) | `onOpened` → `onLoad` | **all pages opened once to read sizes** (see B1) |
| Load (iOS) | background queue | `PDFDocument` | — | — | `onLoad` | PDFKit parses lazily |
| Page selection (A) | UI, per frame | `IntRange`s, planner `ArrayList`, `Base` request objects, `RectF` from `scrollBounds()` | — | — | page-change event when page changes | O(log n) lookups; request list compared to the last one |
| Render (A) | render thread | bitmap (pooled), `Matrix`, `Rect` | pixels written once into the target bitmap | `sPdfiumLock` per call | — | an in-flight render cannot be cancelled |
| Cache (A) | UI | `LinkedHashMap` node per insert | — | none (UI-confined) | evict → pool or `recycle()` | — |
| Draw (A) | UI | none (reused `RectF`/`Rect`/`Paint`) | GPU texture upload per new bitmap | — | — | `cache.get` per visible page per frame |
| iOS draw | PDFKit | PDFKit tiles | — | — | — | — |

### Operation table (Android, baseline)

| Operation | Thread | Allocations | JS involvement | Native involvement | Potential bottleneck |
| --- | --- | ---: | ---: | ---: | --- |
| document load | worker | O(pages) sizes; **native heap ≈ file size** (B1) | 1 `onLoad` | open + measure every page | **measured: 1692 ms / +400 MB for 1000 pages** |
| first page | render thread | 1 bitmap (≈6.6 MB at 1080 px) | none | render | waits for load (B1) |
| scroll | UI | ~10 small objects / frame (UNKNOWN GC impact) | `onPageChange` per page crossed | visible range, planner, draw | UNKNOWN until GC measured |
| page activation | UI → render | 1 request object | none | cache lookup, queue replace | — |
| page render | render thread | pooled bitmap, `Matrix` | none | `PdfRenderer` | mean 20.5 ms / page on `extreme` (probe) |
| zoom | UI | none per frame | none | scale transform per frame; tiles after settle | — |
| cache lookup | UI | none | none | `LinkedHashMap.get` | — |
| prefetch | UI → render | request objects | none | queue replace | — |
| page release | UI | none | none | pool or `recycle()` after one frame | — |

## 2. Hot paths

**A — scrolling** `finger → OverScroller/GestureDetector → setOffsets → visibleRange → planner → submit → draw`.
All UI thread, no JS. The only JS crossing is `onPageChange`, **once per page
boundary crossed** (not per frame), and only if the app passed the prop.

**B — pinch zoom** `ScaleGestureDetector → zoomAround → draw (scaled base bitmaps)`.
No rendering during the gesture; tiles are requested 80 ms after the gesture
ends. No JS, no Nitro calls.

**C — page rendering** `planner → cache miss → queue → render thread → post → cache.put → invalidate`.
Entirely native. JS is never involved per page.

**D — recycling**: there are no page views to recycle. The Android viewer is a
single `View` drawing bitmaps; page "containers" are bitmaps, recycled
through `BitmapPool`. iOS: PDFKit reuses its internal page views.

## 3. Nitro boundary classification

| Crossing | Direction | Class | Frequency | Payload |
| --- | --- | --- | --- | --- |
| `source`, `password` | JS → native | COLD | on value change | `{ uri, headers? }` |
| `initialPage`, `minScale`, `maxScale`, `pageSpacing` | JS → native | COLD | on value change | number |
| callbacks (`onLoad`, `onError`, `onPageChange`, `hybridRef`) | JS → native | COLD | once (identity-stable) | function handle |
| `goToPage`, `zoomTo` | JS → native | WARM | user action | 2 scalars |
| `getStats` | JS → native | COLD (diagnostic) | on demand | 10 numbers |
| `onLoad`, `onError` | native → JS | COLD | per document | small struct |
| `onPageChange` | native → JS | WARM | per page boundary crossed | 2 numbers |
| `onLoadProgress` | native → JS | WARM | ≤ 10/s during a remote download, 0 otherwise | 2 numbers |
| `onLinkPress`, `onPageTap` | native → JS | COLD | per user tap | small struct |

HOT crossings: **none**. No scroll, zoom, visibility or render-lifecycle data
crosses Nitro. No PDF bytes, bitmaps or page arrays cross Nitro in either
direction.

## 4. React audit

- `PdfView` holds no state → it never re-renders itself. During scrolling it
  renders **0 times** unless the app re-renders it.
- Stabilized (each crosses the Nitro boundary, where a new identity costs a
  native setter call): source (by value), the three event callbacks, and
  `hybridRef`. Covered by `src/PdfView.test.tsx`.
- Not memoized, on purpose: `normalizeSource`/`sourceKey` run per parent
  render. They are O(headers) string work, cheaper than a memo cache check
  would save.
- One `useLayoutEffect` without deps (assigns the latest handlers to a ref):
  O(1), no allocation beyond the object literal.
- No context, no subscriptions, no effects that trigger renders.
- `React.memo` is **not** added: with no internal state and stable native
  props, a parent re-render costs one shallow host-component update with no
  changed props.
- The example app re-renders its screen on every `onPageChange` (it shows
  the page number). That is app code and is the intended usage; it happens
  per page, not per frame.

## 5. Findings

| # | Area | Finding | Evidence | Status |
| --- | --- | --- | --- | --- |
| B1 | Android load / memory | Reading page sizes opened every page; PDFium retained each page's parsed objects until the document closed. | Native heap +400 MB / 1692 ms for the 1000-page fixture; constructor alone ≤ 27 ms / 0 MB. | **Fixed**: lazy sizes + renderer recycling. Load 1182 → 134 ms, peak 532 → 144 MB (results §2, §6). |
| B2 | Android memory | Evicted bitmaps left to the GC. | Stress sampling. | **Fixed**: eager `recycle()` (results §3). |
| B3 | Android memory | Bitmap pool retained up to 2 viewports. | Benchmark. | **Changed**: 1 viewport; −3–5% peak PSS, +~15 allocations/min (results §4). |
| B4 | Android scroll | 7.2% of pages not rendered when they scrolled into view (5-min soak). | Forward-only vs reversing runs: all scroll misses come from direction changes. | **Reduced** to 2.6% (results §5, §8). |
| C2 | Android scheduling | Plan larger than cache budget → thrashing. | Reproduced: misses 55–70%, renders +60–90%. | **Fixed**: budget covers plan; unit tested (results §5). |
| C3 | iOS memory | Closed viewers leaked their `PDFView` while JS held `hybridRef`. | `deinit` probe; +150 MB per 10 open/close. | **Fixed**: +6 MB per 10 (results §10). |
| B5 | Android scheduling | In-flight renders cannot be cancelled. | Platform API. | Accepted: at most one wasted render per viewport change; `rendersCancelled` stays low (20–27 in 5-min soaks). |
| B6 | Android concurrency | One static PDFium lock per process. | `PdfProcessor.sPdfiumLock`. | Documented. |
| L2 | Android scroll | Small per-frame allocations. | No GC pauses or jank attributable (janky frames at emulator noise level). | Not optimized — no measurable cost. |

## 6. Second audit (after the changes)

### JS

- **Renders during scrolling**: 0 by `PdfView` itself (no state). The app
  re-renders only if its `onPageChange` handler sets state.
- **JS callbacks during scrolling**: `onPageChange` only, one per page
  crossed — 29–49 in 8 s of fast flinging on Android, identical to
  react-native-pdf (results §6).
- **Nitro calls during scrolling/zooming**: 0 (JS → native). Enforced
  structurally by `nitroBoundary.test.ts`.

### Native (Android)

- **Active page views**: 0 — one `View` draws bitmaps. **Resident page
  bitmaps**: 5 at steady state (`getStats().residentPages`).
- **Pages rendered simultaneously**: 1 (one render thread per document; the
  platform allows one open page at a time).
- **Allocations**: 186 bitmap allocations for 1722 renders over a 5-minute
  soak — 89% of renders reuse a pooled bitmap.
- **Recycling**: bitmaps recycle through `BitmapPool`; the PDFium document
  state is recycled every 48 page opens.

### Memory

- **Cache ceiling**: max(24 MB, heap/6, 4 viewports), grown to cover the
  render plan, hard cap 128 MB; visible pages are never evicted.
- **Memory pressure**: `onTrimMemory(RUNNING_LOW+)`/`onLowMemory` → cache
  trimmed to visible pages, pool emptied, prefetch paused for 30 s; the
  viewer keeps working and re-renders on demand.
- **Bitmap release**: on eviction (pool or `recycle()`), on detach (all), on
  drop (all, including the pool). 5-minute soak: PSS 124 → 136 → 143 MB
  (start / 1 min / 5 min), peak 149 MB; react-native-pdf ~900 MB.

### Rendering

- **Obsolete renders**: never started (queue replaced per viewport change;
  unit tested); the in-flight one completes.
- **Viewport priority**: visible (centre first) → zoom tiles → ahead →
  behind.
- **Zoom**: no PDF rendering during the gesture; viewport-sized tiles after
  80 ms of stillness.

### Architecture

- Hot paths are native on both platforms; iOS delegates them to PDFKit.
- No copies of PDF data or pixels across JS↔native; pixels are written once
  by the renderer and drawn directly.
- Abstractions added in this pass are small and each carries a test:
  `RenderQueue`, `PrefetchPlanner.fitToBudget`.

## 7. Remaining bottlenecks and next opportunities

1. **Direction-change misses (2.6%)**: a low-resolution thumbnail layer (as
   react-native-pdf/AndroidPdfViewer does) would turn remaining white
   placeholders into blurry previews for ~0.4 MB per page.
2. **Small-document memory**: was ~26 MB above react-native-pdf on the
   10-page fixture, from full-resolution ARGB page bitmaps. Grey pages now
   take one byte per pixel with the same pixels (results §20): 90.9 vs
   92.1 MB. Colour pages stay ARGB; what is left on `small` is the code of
   Android's PDF module (~9.6 MB, API 35+). Hardware bitmaps would drop the
   CPU copy of drawn pages but need device measurements: the emulator does
   not count graphics memory.
3. **iOS device profiling**: Instruments (Allocations, Leaks, Core Animation)
   on hardware — simulator footprint is only a proxy (audit M7).
4. **Render time moved to first view (B1 trade-off)**: parsing now happens on
   first render (mean render time 74–85 → 88–121 ms per page on the emulator). A background
   low-priority size pre-scan with renderer recycling could restore
   positions for mixed-size documents without the memory cost.
5. **Untrusted input isolation** on Android (isolated render process).

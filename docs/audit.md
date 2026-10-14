# Engineering audit

Final audit of `react-native-better-pdf` 0.1 (2026-09-29), covering the
checklist in the original brief. Performance-specific findings, with
measurements, are in [performance/audit.md](performance/audit.md) and
[performance/results.md](performance/results.md). Items marked **fixed** were
found during this project and are verified by a test or a measurement.

## Critical

None open.

| Fixed | Finding | Evidence / verification |
| --- | --- | --- |
| C1 | Android load retained memory equal to the file size (opening every page to read its size kept PDFium's parsed objects alive): 400 MB and 1.7 s for the 1000-page fixture. | Probe + benchmark; after fix: load ~110 ms, peak PSS 532 → ~140 MB (results §2, §6). |
| C2 | Cache thrashing when the render plan exceeds the cache budget: every render evicts another planned page. Latent on phones, likely on tablets/landscape. | Reproduced (misses 11% → 55–70%, renders +60–90%); fixed by growing the budget to cover the plan (`PrefetchPlanner.fitToBudget`, unit tested). |
| C3 | iOS: every closed viewer leaked its `PDFView` (~7.5 MB) because JS held the hybrid object via `hybridRef` until a Hermes GC; memory grew ~150 MB per 10 open/close cycles of large documents. | Probes on `onDropView`/`deinit`; fixed by destroying the `PDFView` in `onDropView` — growth now ~6 MB per 10 cycles (results §10). |

## High

| # | Finding | Status |
| --- | --- | --- |
| H1 | **Untrusted PDFs are parsed in-process.** Android's docs recommend an isolated process for untrusted input; a PDFium exploit would run with the app's permissions. PDFKit on iOS has the same property. Our own Kotlin readers also parse untrusted bytes on Android — the outline, the page tree, links, form fields and (for `PdfDocument.getMetadata`) the information dictionary (memory-safe JVM code; bounded; fuzz tested — see api-design "Outline" and `PdfOutlineFuzzTest`). | Open. Document for integrators; an isolated-service renderer is a possible future option on Android. 1.0.1: a chain of distinct objects, each needed to read the one before (an indirect `/Length`, an object stream), was not bounded, and a crafted file overflowed the links thread's stack and crashed the app; such reads now stop at 32 nested objects, a stack overflow reads as an unreadable file, and the fuzz test generates deep chains. |
| H2 | Evicted bitmaps were left to the GC; pixel memory depended on GC timing. | Fixed (`BitmapPool` recycles eagerly). |
| H3 | Android view painted outside its bounds (RN parents disable `clipChildren`). | Fixed (`clipRect`), found in e2e screenshots. |
| H4 | Tiles could be placed with the wrong scale if the user zoomed while a tile was rendering. | Fixed (tile carries its own content rect and scale). |
| H5 | **Removing any optional prop crashed the app** (both platforms): Fabric sends `null` for a removed prop and Nitro 0.37's `std::optional` converters accept only `undefined` ("Value is null, expected a String"). Same for removing an event handler. | Fixed in the `PdfView` wrapper: concrete defaults for every prop, events stay subscribed once used. Unit test + `viewer-fit-policy` e2e step. Found while cycling `fitPolicy`. |
| H6 | **Android drew no annotations except form fields** (highlights, notes, stamps, shapes, ink), while the docs claimed they always render. | Partly fixed: `RenderParams` flags draw highlight, free text and notes from API 35 and stamps from API 37 (measured per kind, no render-time cost). Shapes, ink, underline and strike-out remain undrawable with `PdfRenderer`; documented in README and api-design "Annotations". |

## Medium

| # | Finding | Status |
| --- | --- | --- |
| M1 | All Android documents in a process share PDFium's static lock (`PdfProcessor.sPdfiumLock`); two visible viewers render serially, and a closing document's in-flight render delays the next one. | Platform constraint; documented. |
| M2 | Password-protected PDFs needed Android 15+ (`LoadParams`); earlier versions report `unsupported-pdf`. | Extended: Android 12–14 with SDK extension 13 now use `PdfRendererPreV` (passwords, links, text selection, annotation flags). Verified by forcing that renderer on API 35; the fallback verified on an API 34 emulator (extension 7); not yet on a real 12–14 device with extension 13. Android 7–11 remain unsupported (platform limit). |
| M3 | Mixed-page-size documents: pages far from the viewport sit at estimated positions until first rendered; the layout re-anchors when a size is corrected. | Accepted trade-off for C1; verified visually with the `mixed-sizes` fixture (`viewer-mixed-sizes` flow). |
| M4 | 2.6% of pages (5-min soak with a direction change every 5th swipe) scroll into view before they are rendered, showing a white placeholder briefly; forward scrolling misses none. | Reduced from 7.2% (C2 fix + 2 pages behind). Remaining option: a low-resolution thumbnail layer (as react-native-pdf does), not implemented. |
| M5 | Remote downloads are cached per process only; no cache-control/ETag, no size limit on `Caches/BetterPdf`. The OS may purge the directory; the app cannot. | Fixed: session copies are deleted on the next launch; persistent copies (`cache: 'persistent'`, not purged, not backed up), `maxAgeMs` revalidation with `ETag` / `Last-Modified`, and `PdfCache` (`download`, `getEntry`, `remove`, `clear`, `getByteSize`). No automatic size limit: copies are app-managed (api-design "Caching and offline"). JVM tests + `viewer-offline` e2e on both platforms. |
| M6 | `source` key collision: a URI containing the separator could equal a URI+headers key and skip a reload. | Fixed, unit tested. |
| M8 | `onPageChange` meant different things per platform: iOS relayed PDFKit's `currentPage`, which never reaches a last page shorter than the viewport (`goToPage(last)` reported 4/5 on the `mixed-sizes` fixture). | Fixed: both platforms report the page under the viewport centre, or the last page at the end; caught by the `viewer-mixed-sizes` flow on iOS. |
| M9 | Android reports links only from API 35 (or 12–14 with SDK extension 13) and, among internal links, only GoTo actions with XYZ destinations (`PdfRenderer` returns nothing for `/Dest` arrays and `/Fit` destinations — measured with four link forms). iOS (PDFKit) handles all forms. | Fixed: links are read from the file by the library's parser on every Android version, every link form (`PdfLinkReaderTest`, `viewer-links` on an API 34 emulator without SDK extension 13). Password-protected documents keep the renderer's links (the parser does not decrypt). |
| M7 | iOS memory behaviour under memory warnings and while the view is off-window is delegated to PDFKit and has **not** been measured on a device. | Open; needs Instruments on hardware. |

## Low

| # | Finding | Status |
| --- | --- | --- |
| L1 | `onPageChange` fires once per page crossed (~6/s in fast flings). Semantic and opt-in, but an app that re-renders a large tree on it pays per page. | By design; documented. |
| L2 | Per-frame small allocations on the Android UI thread (`IntRange`, planner list, request objects). No GC pressure or jank attributable to them was observed. | Left as is (not measurable → not optimized). |
| L3 | `getStats()` fields are `-1` on iOS (PDFKit exposes no cache metrics). | Documented. |
| L4 | Double-tap zoom target is fixed (`max(2, 2 × minScale)`); not configurable. | Open. |
| L5 | Example app: Maestro's text input accumulates in secure fields on Android; the password flow uses buttons instead. | Test-harness limitation, documented in the flow. |

## Checklist

| Item | Result |
| --- | --- |
| JS work in hot paths | None. No Nitro call during scroll/zoom; `onPageChange` only per page (measured: ~50 events in 8 s of flinging, identical to react-native-pdf). Guarded by `nitroBoundary.test.ts`. |
| Unnecessary serialization | None found. No PDF bytes, bitmaps or page arrays cross JS↔native. |
| Memory leaks | Android: renderer, FD and pages are closed on the worker thread (`use {}`), session close is idempotent, pixels are released on detach and drop. iOS: C3 fixed; residual ~0.3 MB per closed viewer until JS GC. |
| Bitmap explosions | Fixed (C1, C2, H2); 5-minute soak on the 1000-page scan stays within 124–149 MB PSS (react-native-pdf: ~900 MB). |
| Main-thread blocking | Android: document open, downloads and rendering are off-main; the main thread does O(log n) lookups and draws. iOS: `PDFDocument(url:)` off-main. |
| Race conditions | Session callbacks are dropped after `close()`; stale tile generations discarded; render queue is lock-protected and unit tested; `getStats` reads volatile/atomic snapshots. |
| Unnecessary dependencies | Library: `react-native-nitro-modules` (peer) only. No blob-util, no bundled PDFium, no WebView. |
| Outdated Nitro APIs | Built against Nitro 0.37.1 (latest at the time); code generated by Nitrogen 0.37.1. |
| Outdated RN APIs | No `NativeModules`, `RCTBridgeModule`, `RCTEventEmitter`, `requireNativeComponent`. New Architecture only. |
| Expo integration | Works through standard autolinking in development builds (verified with Expo SDK 57); no config plugin required; Expo Go unsupported by design. |
| Android lifecycle | Detach drops pixels; `onDropView` closes the session; memory-pressure callbacks trim to visible pages and pause prefetch for 30 s. |
| iOS lifecycle | `onDropView` cancels loads, drops the scroll-offset observation and releases the document. Off-window/memory-warning behaviour: see M7. |

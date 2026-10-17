# Performance results

Every number here was produced by `benchmarks/run-android.mjs` (see
[../benchmarks.md](../benchmarks.md) for the method) or by the named probe.
Medians of 3 runs unless stated. Raw JSON: `benchmarks/results/` (gitignored;
file names below).

**Environment**: Android emulator `sdk_gphone64_arm64`, API 35, 1080×2400,
release build of the example app, host Apple Silicon Mac under other load.
Emulator timings are not device timings; use them for before/after and
library-vs-library comparisons only. Differences below ~1 percentage point of
janky frames or ~10% CPU are within run-to-run noise here.

## 1. Baseline (before optimization)

`android-2026-09-29T14-33-32-528Z.json`

| Fixture | Library | Load (ms) | Jump (ms) | Peak PSS (MB) | Janky % | p90 frame (ms) | JS thread CPU % | Total CPU % |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| small | better | 160 | 1 | 122.5 | 3.7 | 26 | 2.1 | 36.8 |
| small | react-native-pdf | 190 | 49 | 88.9 | 3.4 | 27 | 2.1 | 65.6 |
| medium | better | 323 | 0 | 150.9 | 4.3 | 29 | 2.7 | 62.2 |
| medium | react-native-pdf | 433 | 34 | 130.7 | 2.6 | 28 | 2.7 | 123.5 |
| large | better | 800 | 1 | 351.0 | 3.0 | 29 | 2.7 | 70.0 |
| large | react-native-pdf | 769 | 51 | 544.4 | 8.9 | 36 | 2.1 | 117.9 |
| extreme | better | 1182 | 1 | 532.2 | 3.5 | 28 | 2.3 | 85.9 |
| extreme | react-native-pdf | 2118 | 34 | 898.5 | 4.8 | 29 | 2.4 | 121.5 |

## 2. B1 — page-size measurement retained the whole file in PDFium

**Evidence** (temporary probe logging `Debug.getNativeHeapAllocatedSize()`
around `PdfRenderer(...)` and around the loop that opened every page to read
its size; debug build; probe removed afterwards):

| Pages | File | Constructor: Δ native / time | + size loop: Δ native / time |
| ---: | ---: | --- | --- |
| 10 | 0.8 MB | 0 MB / 27 ms | 0 MB / 45 ms |
| 100 | 19 MB | 0 MB / 3 ms | 19 MB / 123 ms |
| 500 | 225 MB | 0 MB / 6 ms | 226 MB / 796 ms |
| 1000 | 395 MB | 0 MB / 3 ms | 400 MB / 1692 ms |

A second probe (open `extreme`, then render ~200 pages via jumps) showed the
native heap jump happening at open and then staying flat while rendering —
ruling out the render cache.

**Change**: lazy page sizes (page 0 at load, others on first render, layout
re-anchored on correction) + re-create the renderer every 48 page opens.

**Result** (`android-2026-09-29T14-43-44-561Z.json`, better only):

| Fixture | Load before → after | Peak PSS before → after | Janky % before → after | Total CPU % before → after |
| --- | --- | --- | --- | --- |
| small | 160 → 122 ms | 122.5 → 122.7 MB | 3.7 → 4.3 | 36.8 → 38.9 |
| medium | 323 → 118 ms (−63%) | 150.9 → 128.4 MB (−15%) | 4.3 → 5.1 | 62.2 → 68.8 |
| large | 800 → 112 ms (−86%) | 351.0 → 136.1 MB (−61%) | 3.0 → 4.3 | 70.0 → 74.3 |
| extreme | 1182 → 113 ms (−90%) | 532.2 → 142.1 MB (−73%) | 3.5 → 5.1 | 85.9 → 92.7 |

Cost: parsing moved from load to first render. Mean render time on
`extreme` went from 74–85 ms to 88–121 ms per page and render-thread CPU
from ~48% to ~55% while scrolling. Cache hit rate unchanged (71–78%).

## 3. B2 — evicted bitmaps left to the GC

Applied before the baseline, so it has no clean before/after in the table
above. Stress-flow memory sampling (debug build, `stress-open-close` +
`stress-rapid`, B1 still present): peak native heap 611 → 578 MB. The effect
is small because B1 dominated; kept because it removes GC-timing dependence
from pixel memory.

## 4. B3 — bitmap pool size

**Hypothesis**: the pool kept up to `max(16 MB, 2 viewports)` of free bitmaps.

**Change**: `max(8 MB, 1 viewport)`.

**Result** (`android-2026-09-29T14-49-59-890Z.json`, better only):

| Fixture | Peak PSS before → after | Bitmap allocations / run before → after | Janky % | CPU % |
| --- | --- | --- | --- | --- |
| small | 122.7 → 116.3 MB (−5%) | ~11 → ~16 | 4.3 → 4.4 | 38.9 → 37.1 |
| extreme | 142.1 → 138.1 MB (−3%) | ~10 → ~25 (of ~95 renders) | 5.1 → 3.3 | 92.7 → 91.4 |

Kept: bounded memory is the primary goal and ~15 extra 6.6 MB allocations
per minute of scrolling showed no measurable CPU or frame-time cost.

## 5. B4/C2 — cache misses on direction changes, and plan/budget thrashing

**Symptom** (first 5-minute soak): 121 of 1688 pages (7.2%) were not rendered
when they scrolled into view.

**Diagnosis** (60 s scroll windows on `extreme`, 2 runs each,
`--reverse-every 0` vs `5`):

| Scrolling | Misses | Renders | Page crossings |
| --- | ---: | ---: | ---: |
| forward only | 14 / 14 (4.1%) | 366 / 362 | 328 / 323 |
| every 5th swipe reversed | 39 / 35 (11%) | 466 / 456 | 326 / 323 |

Forward-only misses equal the page jumps + first page: sequential scrolling
never misses, so render throughput is *not* the bottleneck and a
low-resolution fling mode would not help. Misses and ~95 extra renders come
from reversals: pages just scrolled past had been evicted. The budget (4
viewports ≈ 32 MB ≈ 4.8 page bitmaps) was smaller than the render plan
(2–3 visible + 2 ahead + 1 behind).

**Failed attempt — reverted**: `behind = 2` with a 5-viewport budget (36 MB).
The plan (~7 pages ≈ 46 MB) exceeded the budget and the cache thrashed —
every render evicted another planned page:

| Scrolling | Misses | Renders | Total CPU % |
| --- | ---: | ---: | ---: |
| reversed every 5th | 190 / 206 (57–61%) | 728 / 723 | 121 |
| forward only | 190 / 238 (56–70%) | 886 / 754 | 120 |

This exposed a latent bug (C2): any configuration where the plan exceeds the
budget thrashes — tablets and landscape are likely candidates.

**Fix**: `PrefetchPlanner.fitToBudget` — the cache budget grows to cover the
whole plan (up to 128 MB); beyond that the lowest-priority planned pages are
dropped. Unit tested. Then `behind = 2` was re-tested on top of it.

| Variant (reversed every 5th) | Misses | Renders | Peak PSS | CPU % |
| --- | ---: | ---: | ---: | ---: |
| before | 37 (11%) | 461 | ~140 MB | ~80 |
| budget covers plan, behind = 1 | 27 / 27 (8.2%) | 460 / 470 | 138.5 / 141.3 MB | 78.5 / 81.3 |
| budget covers plan, behind = 2 | 27 / 17 (6.5%) | 376 / 367 | 147.4 / 145.3 MB | 81.0 / 78.8 |

Forward-only was unchanged by both (14 misses). Kept: budget fit +
`behind = 2` — about +6 MB for 19% fewer renders and fewer placeholders.

## 6. Final comparison

Better PDF: `android-2026-09-29T16-11-47-624Z.json` (all changes applied).
react-native-pdf: `android-2026-09-29T15-14-47-441Z.json` (same session, same
emulator; its code did not change between the two runs).

| Fixture | Library | Load (ms) | Jump (ms) | Peak PSS (MB) | Janky % | p90 frame (ms) | JS thread CPU % | Total CPU % | JS callbacks while scrolling (8 s) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| small | better | **109** | 1 | 116.5 | 4.7 | 27 | 2.2 | **37.4** | 29 |
| small | react-native-pdf | 136 | 37 | **90.7** | 3.6 | 28 | 2.8 | 77.9 | 27 |
| medium | better | **123** | 1 | **126.6** | 4.1 | 29 | 2.2 | **56.7** | 48 |
| medium | react-native-pdf | 467 | 34 | 131.3 | 3.2 | 29 | 2.6 | 124.5 | 51 |
| large | better | **116** | 0 | **135.8** | 4.1 | 29 | 2.3 | **63.7** | 48 |
| large | react-native-pdf | 1841 | 41 | 546.1 | 4.2 | 28 | 2.5 | 122.2 | 48 |
| extreme | better | **134** | 1 | **143.7** | 3.9 | 27 | 2.4 | **78.5** | 49 |
| extreme | react-native-pdf | 2371 | 34 | 897.8 | 7.6 | 29 | 2.6 | 120.8 | 45 |

### Before → after (Better PDF)

| Scenario | Before | After | Change |
| --- | ---: | ---: | ---: |
| Load, 1000 pages | 1182 ms | 134 ms | −89% |
| Load, 500 pages | 800 ms | 116 ms | −86% |
| Peak PSS, 1000 pages | 532.2 MB | 143.7 MB | −73% |
| Peak PSS, 500 pages | 351.0 MB | 135.8 MB | −61% |
| Peak PSS, 10 pages | 122.5 MB | 116.5 MB | −5% |
| Total CPU while scrolling, 1000 pages | 85.9% | 78.5% | −9% |
| Cache misses, 5-min soak | 7.2% | 2.6% | −64% |
| Renders per page crossed, 5-min soak | 1.33 | 1.17 | −12% |
| Janky frames while scrolling | 3.0–4.3% | 3.9–4.7% | within noise |
| Page jump (to page-change event) | 1 ms | 1 ms | — |

### Where react-native-pdf is better

- **Memory on small documents**: 90.7 vs 116.5 MB on the 10-page fixture.
  Our cache holds the prefetch window as full-resolution ARGB page bitmaps
  (≈ 31.5 MB at budget). react-native-pdf (AndroidPdfViewer 4.0.1) renders
  256-px parts in `RGB_565` unless `useBestQuality` is set (react-native-pdf
  does not set it) — half the bytes per pixel, visibly lower quality in
  images — plus 0.3× thumbnails. Not copied: halving via RGB_565 would lower
  quality, and shrinking the window would bring back misses (§5).
- Janky frames on `small`/`medium`: equal within noise.

### What the numbers do not say

- "Jump" is time to the page-change event, not to pixels (see methodology).
  react-native-pdf's ~35–50 ms includes its JS `setNativeProps` round trip.
- JS thread CPU (~2.5%) is the benchmark's own `requestAnimationFrame`
  monitor for both libraries; neither library does JS work while scrolling.
- JS callbacks while scrolling are `onPageChange` events, one per page
  boundary crossed, identical for both libraries by design.

## 7. Structural guarantees (unit tests)

| Guarantee | Test |
| --- | --- |
| No per-frame JS events; boundary exactly the reviewed props/events | `package/src/nitroBoundary.test.ts` |
| Re-render with equal inline source/callbacks sends nothing to native | `package/src/PdfView.test.tsx` |
| Work for pages the user left is never started | `RenderQueueTest` |
| In-flight request never duplicated; queue ≤ latest plan | `RenderQueueTest` |
| Render plan does not grow with document size | `PrefetchPlannerTest` |
| Cache budget always covers the render plan (no thrashing); only visible pages under memory pressure | `PrefetchPlannerTest` |
| Cache never exceeds its budget except for visible pages | `LruByteCacheTest` |

## 8. Memory soak

`extreme` (1000 pages, 395 MB), 5 min 20 s of continuous host-driven swipes
(every 5th reversed), one run per variant. PSS sampled every 500 ms.

| Library / version | Before scroll | After 1 min | After 5 min | End | Peak | Page crossings | Misses | Renders | Bitmap allocations | Total CPU % |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| better, after B1 (`…15-28-32…`) | 122.3 | 118.9 | 131.5 | 123.1 | 143.6 | 1693 | 121 (7.2%) | 2253 | 378 | 88.8 |
| better, final (`…16-20-01…`) | 123.9 | 136.1 | 142.6 | 134.8 | 148.6 | 1469 | 38 (2.6%) | 1722 | 186 | 74.7 |
| react-native-pdf (`…15-28-32…`) | 877.7 | 901.7 | 901.3 | 894.3 | 903.3 | 1691 | – | – | – | 118.8 |

Stress flows (`stress-open-close` + `stress-rapid`, sampled every second,
Android emulator): peak PSS **768 MB** before the changes
(`android-stress-memory.csv`, debug build) → **170 MB** after
(`android-final-stress-memory.csv`, release build), returning to 87 MB on the
document list.

Memory stays flat over five minutes: no growth attributable to the document
(PDFium object cache bounded by renderer recycling, bitmap cache bounded by
budget). The final version holds ~10 MB more than the post-B1 version because
of the larger plan (§5).

## 9. iOS (simulator)

> **Correction (2026-10-04):** every iOS row for `large` before §12 measured
> blank pages: the fixture declared its scans as DeviceGray while their
> JPEGs have three components, which PDFKit does not draw. See §12.

**Caveat**: iOS Simulator on an Apple Silicon Mac. Memory is the host
process's physical footprint (`footprint -p`) of the simulated app — useful
for relative comparisons and leak detection, not a device number. One run per
cell. Script: `benchmarks/run-ios-sim.sh`. Both libraries use PDFKit on iOS,
so this compares wrappers around the same engine.

| Fixture | Library | Load (ms) | Peak footprint (MB) | Footprint after run (MB) | JS callbacks while scrolling |
| --- | --- | ---: | ---: | ---: | ---: |
| small | better | 22 | 278 | 180 | 7 |
| small | react-native-pdf | 25 | 343 | 332 | 4 |
| medium | better | 50 | 320 | 189 | 22 |
| medium | react-native-pdf | 93 | 393 | 245 | 9 |
| large | better | 40 | 174 | 68 | 22 |
| large | react-native-pdf | 125 | 176 | 65 | 9 |
| extreme | better | 226 | 204 | 90 | 21 |
| extreme | react-native-pdf | 226 | 203 | 67 | 9 |

- Load and memory are close for scanned documents, as expected with the same
  engine; the text-heavy fixtures peak higher than the scans for both
  libraries (PDFKit tiles dense vector text at full resolution — unverified
  without Instruments on a device).
- Better PDF emits more `onPageChange` events because it reports the page
  under the viewport centre continuously (the same rule as Android, see audit
  M8); react-native-pdf relays PDFKit's coarser `PDFViewPageChanged`.

## 10. iOS leak — hybrid objects outliving their views

**Found by**: repeating `stress-open-close` (10× open/close of the 1000- and
500-page scans) in one process and reading the footprint after each round.

**Evidence**: temporary `NSLog` probes in `present`, `onDropView` and `deinit`
of `HybridBetterPdfView`: `onDropView` ran for every close but `deinit`
never did. With `hybridRef` removed from the JS wrapper, `deinit` ran after
every close — JS kept the hybrid object (and its `PDFView`) alive until a
Hermes GC, which a PDF viewer's small JS heap rarely triggers.

**Change**: `onDropView` destroys the `PDFView` (not just its document), so a
lingering hybrid object is an empty `UIView`. Android: `onDropView` also
empties the bitmap pool.

| Footprint after each round of 10 open/close cycles | Before | After |
| --- | ---: | ---: |
| start | 34 MB | 34 MB |
| round 1 | 231 MB | 76 MB |
| round 2 | 361 MB | 82 MB |
| round 3 | 512 MB | 88 MB |
| growth per round | ~150 MB | ~6 MB |

The remaining ~0.3 MB per opened viewer is the empty native shells and their
JS wrappers until the JS garbage collector runs. (Not so: a reference cycle
through a GC root kept them for the life of the runtime; see §14.)

## 11. Re-run after text selection (2026-09-30)

Same method, emulator and fixtures as §6; release builds that include text
selection (render queue with an urgent slot, selection controller) and
`initialScale` / `filePath`. react-native-pdf 7.0.5 unchanged.

**Android** (`android-2026-09-30T12-51-30-492Z.json`, medians of 3):

| Fixture | Library | Load (ms) | Jump (ms) | Peak PSS (MB) | Janky % | p90 frame (ms) | JS thread CPU % | Total CPU % | JS callbacks while scrolling |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| small | better | **122** | 0 | 118.2 | 4.3 | 23 | 1.8 | **30.8** | 27 |
| small | react-native-pdf | 162 | 50 | **91.0** | 2.1 | 29 | 1.7 | 60.2 | 27 |
| medium | better | **90** | 0 | **126.8** | 3.2 | 24 | 1.8 | **47.6** | 49 |
| medium | react-native-pdf | 288 | 34 | 132.1 | 1.0 | 25 | 1.9 | 119.8 | 49 |
| large | better | **91** | 0 | **141.2** | 3.7 | 20 | 1.9 | **58.0** | 48 |
| large | react-native-pdf | 900 | 34 | 546.7 | 1.2 | 24 | 1.8 | 116.0 | 48 |
| extreme | better | **91** | 1 | **146.8** | 4.3 | 24 | 1.9 | **72.4** | 46 |
| extreme | react-native-pdf | 1207 | 50 | 900.5 | 1.5 | 23 | 1.8 | 116.4 | 47 |

- Load, memory, CPU and jump latency reproduce §6. No regression from text
  selection: Better PDF's janky frames (3.2–4.3%) are in §6's range
  (3.9–4.7%).
- react-native-pdf's janky frames were lower in this run (1.0–2.1%) than in
  §6 (3.2–7.6%). Janky-frame percentages on this emulator vary by more than
  the difference between the libraries; they need a device to compare.
- JS-thread dropped frames (`requestAnimationFrame`, medians): zoom 1–3 vs
  1–4, page jumps 1–5 vs 1–6 — equal. While scrolling, Better PDF dropped
  11–17 vs 8–12 JS frames, with equal JS-thread CPU (1.8–1.9%).

**iOS simulator** (one run per cell; `ios-sim-20260930T144205.txt`; the
`large` rows measured blank pages, see §12):

| Fixture | Library | Load (ms) | Peak footprint (MB) | Footprint after run (MB) | JS callbacks while scrolling |
| --- | --- | ---: | ---: | ---: | ---: |
| small | better | 31 | 286 | 171 | 7 |
| small | react-native-pdf | 28 | 344 | 279 | 4 |
| medium | better | 56 | 318 | 214 | 22 |
| medium | react-native-pdf | 99 | 353 | 290 | 9 |
| large | better | 99 | 175 | 65 | 22 |
| large | react-native-pdf | 134 | 175 | 65 | 9 |
| extreme | better | 229 | 204 | 75 | 21 |
| extreme | react-native-pdf | 246 | 234 | 67 | 9 |

**Remote documents** (new: `--source remote` / `SOURCE=remote`; the fixture
is opened from `http://localhost:8090` with a fresh URL per run, so the time
to load includes each library's download; react-native-pdf with
`cache: false`):

iOS simulator (`ios-sim-20260930T145031.txt`, one run per cell; the `large`
row measured blank pages, see §12):

| Fixture | Library | Load incl. download (ms) | Peak footprint (MB) |
| --- | --- | ---: | ---: |
| small | better | 54 | 282 |
| small | react-native-pdf | 48 | 346 |
| medium | better | 80 | 321 |
| medium | react-native-pdf | 94 | 393 |
| large | better | 376 | 175 |
| large | react-native-pdf | 374 | 182 |

On iOS both download natively (URLSession; react-native-blob-util's native
side), so times are equal within one run's noise.

Android: with its default `trustAllCerts: true`, react-native-pdf 7.0.5
failed to load every remote document (`android-2026-09-30T13-25-01-233Z.json`,
9 of 9 runs; the host script reports them as load timeouts): react-native-blob-util
0.25.1 throws `IllegalStateException: Use of own trust manager but none
defined` in `getUnsafeOkHttpClient`, and `onError` receives "ReactNativeBlobUtil
request error". The benchmark now passes `trustAllCerts={false}`.

Android emulator with `trustAllCerts={false}` (`android-2026-09-30T13-33-31-232Z.json`,
medians of 3; the fixture server runs on the host, reached through
`adb reverse`):

| Fixture | Library | Load incl. download (ms) | Peak PSS (MB) | Janky % | Total CPU % while scrolling |
| --- | --- | ---: | ---: | ---: | ---: |
| small (0.76 MB) | better | **145** | 118.3 | 1.9 | **32.7** |
| small | react-native-pdf | 332 | **100.1** | 2.1 | 72.3 |
| medium (19 MB) | better | **298** | **133.7** | 2.7 | **55.8** |
| medium | react-native-pdf | 666 | 141.1 | 1.8 | 119.2 |
| large (225 MB) | better | **2028** | **137.1** | 2.2 | **64.1** |
| large | react-native-pdf | 4057 | 784.2 | 2.1 | 117.3 |

Better PDF downloads on its document thread straight into the file it
opens; react-native-pdf downloads through react-native-blob-util, whose
completion reaches JS, which then mounts the native view with the path.
Janky frames were equal in this run (1.9–2.7% vs 1.8–2.1%), which supports
reading the local run's difference as emulator noise.

## 12. iOS `large` re-measured (2026-10-04)

`large` (500 pages, 230 MB of grayscale-looking scans) drew as blank pages
on iOS: `benchmarks/generate-fixtures.mjs` declared its images
`/ColorSpace /DeviceGray`, but `jpeg-js` writes three-component (YCbCr)
JPEGs only. PDFium (Android) decodes them anyway; CoreGraphics (iOS, macOS)
checks the component count and draws nothing. Found while measuring PDFKit
writing a filled-in form, which dropped those pages' images (now kept,
compared pixel for pixel). The generator now declares the scans as
DeviceRGB; the pixels are the same gray, so Android's numbers stand (its
renderer drew the images before and after). Only `large.pdf` changes;
`medium`, `extreme` and the other fixtures were already DeviceRGB.

iOS simulator, Release build, one run per cell (`ios-sim-20261004T213423.txt`;
all four fixtures re-run together for comparison):

| Fixture | Library | Load (ms) | Peak footprint (MB) | Footprint after run (MB) | JS callbacks while scrolling |
| --- | --- | ---: | ---: | ---: | ---: |
| small | better | 48 | 283 | 204 | 7 |
| small | react-native-pdf | 30 | 345 | 284 | 4 |
| medium | better | 59 | 320 | 153 | 22 |
| medium | react-native-pdf | 115 | 365 | 281 | 9 |
| large | better | 56 | 197 | 67 | 21 |
| large | react-native-pdf | 48 | 207 | 68 | 9 |
| extreme | better | 197 | 204 | 67 | 21 |
| extreme | react-native-pdf | 234 | 210 | 68 | 9 |

Remote (`SOURCE=remote`, `ios-sim-20261004T214249.txt`): `large` better
280 ms / 189 MB peak, react-native-pdf 246 ms / 207 MB.

- With its images drawn, `large` peaks ~20–30 MB higher than measured
  before (174 → 197 MB, react-native-pdf 176 → 207 MB) and loads in the
  same time for both libraries (56 vs 48 ms; the earlier 40 vs 125 ms was
  blank pages and one run's noise). Both use PDFKit, as for the other
  fixtures.
- The other fixtures are within one run's noise of §9 and §11.
- The `stress-open-close` flow, which opens `large` and `extreme`
  repeatedly, passes on iOS with the images drawn.


## 13. Re-run before 1.0 (2026-10-07)

The 1.0 release candidate (forms, overlays, `PdfDocument`, the link parser
and the library built with hidden symbols since §11) against
react-native-pdf 7.0.5, unchanged. Same method and fixtures as §11, but the
API 35 emulator image is gone: `sdk_gphone64_arm64`, **API 37**, 1080×2400,
release builds, arm64 only, unminified.

**Android** (`android-2026-10-07T07-18-59-703Z.json`, medians of 3):

| Fixture | Library | Load (ms) | Jump (ms) | Peak PSS (MB) | Janky % | p90 frame (ms) | JS thread CPU % | Total CPU % | JS callbacks while scrolling |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| small | better | **55** | 0 | 118 | 51.8 | 19 | 0.7 | **15.1** | 25 |
| small | react-native-pdf | 82 | 48 | **89.6** | 40.9 | 19 | 0.6 | 22.4 | 27 |
| medium | better | **64** | 0 | **130.1** | 35.7 | 19 | 0.7 | **24.4** | 44 |
| medium | react-native-pdf | 198 | 35 | 131.1 | 15.4 | 19 | 0.7 | 49.9 | 43 |
| large | better | **64** | 0 | **138.4** | 52.4 | 18 | 0.6 | **27.7** | 44 |
| large | react-native-pdf | 482 | 34 | 551.1 | 0.4 | 18 | 0.9 | 113.1 | 45 |
| extreme | better | **91** | 0 | **143.7** | 51.8 | 19 | 0.6 | **27.4** | 42 |
| extreme | react-native-pdf | 746 | 34 | 897 | 0.2 | 18 | 0.7 | 114.2 | 41 |

Remote (`--source remote`, `android-2026-10-07T07-28-20-053Z.json`,
medians of 3):

| Fixture | Library | Load incl. download (ms) | Peak PSS (MB) | Total CPU % while scrolling |
| --- | --- | ---: | ---: | ---: |
| small | better | **72** | 123.4 | **14.1** |
| small | react-native-pdf | 113 | **104.8** | 23.4 |
| medium | better | **128** | **139.4** | **20.7** |
| medium | react-native-pdf | 262 | 146.1 | 61.4 |
| large | better | **585** | **138.3** | **24** |
| large | react-native-pdf | 965 | 564.8 | 108 |

- Load, memory, CPU and jump latency keep §11's picture. Loads and HTTP
  loads are faster for both libraries than on the API 35 image (e.g.
  react-native-pdf `extreme` 1207 → 746 ms), so compare libraries within a
  run, not across sections.
- **Janky frames are not comparable on this image.** Runs split into two
  modes, for both libraries: ~490 frames with 0.2% janky, or ~310 frames
  with ~50% janky, at the same frame times (p50 17 ms). `dumpsys gfxinfo`
  shows the cause: frames miss the FrameTimeline deadline ("Slow issue draw
  commands") with a GPU p50 of 16 ms for Better PDF and 12–13 ms for
  react-native-pdf — at the 16.7 ms budget of the emulated GPU; the legacy
  janky count of the same run is 1.5% (react-native-pdf 7.8%).
  react-native-pdf draws RGB_565 page parts (half the bytes per pixel, see
  §6), likely the GPU difference (not measured separately).
- The ~38-fps mode draws fewer frames, so it costs less CPU: Better PDF's
  CPU medians above come mostly from that mode. Its runs at ~490 frames
  used 23–38% (`extreme` 37.7%) against react-native-pdf's 108–115% on
  `large` and `extreme`: 3× less CPU, not the medians' 4×.
- Driver fix after this run: `input swipe` can inject a swipe whose moves
  arrive too late, which the viewer sees as a tap; two at one spot are a
  double tap, which zooms the viewer for the rest of the scroll window
  (seen once while recording a video of a run, at 1.76×). Consecutive
  flings now alternate between two columns. Page changes per run do not
  drop as a 1.76× zoom would make them, so the runs above were not zoomed.
- **Not a regression**: the code §11 measured (`9d9cef5`), built and run
  on this emulator, shows the same modes (21–47% janky,
  `android-2026-10-07T07-41-11-460Z-bed0738.json`), with p99 frame times of
  48–125 ms and 64–69% CPU on `large` and `extreme`, against 20–32 ms and
  ~27% now. Frame pacing needs a device to compare (§6).

**iOS simulator** (Release build, one run per cell, two passes:
`ios-sim-20261007T094433.txt`, `ios-sim-20261007T095306.txt`):

| Fixture | Library | Load (ms), pass 1 / 2 | Peak footprint (MB), pass 1 / 2 | Footprint after run (MB), pass 1 / 2 |
| --- | --- | ---: | ---: | ---: |
| small | better | 49 / 28 | 277 / 277 | 166 / 201 |
| small | react-native-pdf | 83 / 24 | 338 / 347 | 324 / 279 |
| medium | better | 52 / 57 | 325 / 322 | 159 / 185 |
| medium | react-native-pdf | 51 / 95 | 359 / 377 | 248 / 240 |
| large | better | 57 / 54 | 209 / 204 | 67 / 67 |
| large | react-native-pdf | 65 / 65 | 212 / 236 | 68 / 67 |
| extreme | better | 216 / 184 | 215 / 198 | 68 / 76 |
| extreme | react-native-pdf | 249 / 250 | 211 / 214 | 68 / 68 |

Remote (`SOURCE=remote`, `ios-sim-20261007T100126.txt`, one run per cell):
small 87 vs 93 ms, medium 152 vs 92 ms, large 304 vs 300 ms (better vs
react-native-pdf; peaks 280 / 325 / 197 vs 351 / 353 / 220 MB).

- Both libraries render with PDFKit, so the numbers are close and single
  runs are noisy (react-native-pdf `small`: 83 then 24 ms). Better PDF
  loads local files as fast or faster in most cells and peaks at the same
  or lower footprint; after a run its footprint is lower for `small` and
  `medium`. Remote loads are within one run's noise except `medium`, where
  this run was slower.

## 14. Closed viewers never released (2026-10-14)

**Found by**: a code audit (not measured then). Native stores each `on*`
callback as a JS function, which Nitro keeps as a GC root. The callback's
closure shares an environment with the `PdfView` ref that `hybridRef`
fills, and that hybrid object owns the native view, which owns the
callbacks: a cycle the garbage collector cannot break. Separately, iOS's
search and form kept the displayed `PDFDocument` after `onDropView`.

**Method**: debug builds of the example (RN 0.86.3, Nitro 0.37.1) with
temporary probes: a live-instance count of `HybridBetterPdfView` and of
the displayed `ColorSchemeDocument` (iOS: `init` / `deinit`; Android: weak
references, counted at each `init`), logged. Maestro opens and closes the
Viewer screen's bundled sample (which sets `onLoad`) 20 times. Garbage is
then collected by force: Hermes through Metro's inspector
(`HeapProfiler.collectGarbage`), Android's ART with `am dumpheap`, in the
order ART, Hermes, ART, twice. Instances are counted independently with
`heap <pid>` (iOS simulator) and from the heap dump (Android). iOS
simulator "iPhone 17 Pro" (iOS 26.5) and Android emulator
`sdk_gphone64_arm64` (API 37).

**Change**: `onDropView` sets every `on*` callback to `nil` / `null`
before it tears down, which also stops events into JS for a dropped view.
iOS: `PdfSearchController.clear()` releases its document, and `onDropView`
and `reload()` clear the search and the form. Clearing the search on a
source change (as Android already does) also stops the old document's
search from reporting while the next one loads.

| Alive after closing | Before | After |
| --- | ---: | ---: |
| iOS viewers, after forced GC | 12 of 12 | 0 of 20 |
| Android viewers, after forced GC | 20 of 20 | 0 of 20 |
| iOS `PDFDocument`s of searched viewers, no GC | 6 of 6 | 0 of 5 |

- iOS before: `onDropView` ran for every close, `deinit` never, also after
  five forced collections; `heap` listed 12 `HybridBetterPdfView` and 12
  `PdfContainerView`. After: 16 of 20 were released by Hermes's own
  collections during the loop, the rest by the forced one. The final code,
  without the probes, again left none (`heap`).
- Android before: the heap dump held 21 `HybridBetterPdfView`,
  `PdfDocumentView`, `DocumentContainer` and `Func_void_PdfLoadEvent_cxx`
  (the 20 closed and one more opened to log the count); `dumpsys meminfo`
  counted 204 views on the document list. After: the view opened to log
  the count saw only itself; 164 views; the dump held one instance, the
  last closed (the one closed before it was gone once it had opened).
- Each closed viewer's `PDFDocument` went about 15 ms after `onDropView`
  even while the viewer itself waited for a collection; before, every
  searched one stayed with its viewer.
- In debug builds React DevTools keeps host components' props: in the
  example, `SearchBar`'s `pdf` (the `PdfView` ref) kept each viewer that
  had shown the search bar, now an empty shell without a document. Release
  builds have no DevTools; not measured there.
- On React Native 0.81, iOS never calls `onDropView` (Nitro's generated
  view calls it from `invalidate`, which 0.81 does not call for views that
  are not recycled), so none of this runs there and closed viewers stay as
  before.

## 15. iOS search highlights (2026-10-16)

**Found by**: issue #29. While a search ran, every new match could hand
PDFKit's `highlightedSelections` the full list of matches again (at most
every 100 ms), and next, previous and colour changes did it too.

**Method**: the harness that became
`benchmarks/ios-search-highlights/run.sh` (same phases and checks; see its
README; "before" is `REV` at the commit before the change), on the iOS
26.5 simulator (Apple Silicon Mac, quiet host): `PdfSearchController.swift`
and real PDFKit in a simulator process, a continuous `PDFView` (402×874 pt)
in an off-screen `UIWindow`, no app. `outline-large` (1,000 text pages),
searched from page 1. Stall: the longest wait of a main-queue ping sent in
a loop from another thread while searching. Colour change: setting
`matchColor`. Next / previous: the call, scroll included. Drag: 60 steps
of 200 pt to the scroll view's offset, with the view's per-frame hook.
Each run of the new code also checked that on every visible page the
highlighted ranges cover each occurrence of the query and nothing else,
and that PDFKit drew one `PDFSelectionLayer` per match, the current one in
its own colour. Not measured: a device, and Time Profiler on the example
app.

**Finding**: PDFKit's setter costs time per selection object on the
visible pages, not per match: 1,602 matches on three pages took 870–990 ms
as separate selections and 3–5 ms as one selection per page. Both draw the
same layers, one per match (`bounds(for:)` of all 1,602 took 0.3 ms). A
list of only the visible pages' matches, as separate selections, still
took 1.2 s at 528,449 matches.

**Change**: `PdfSearchController` hands PDFKit one selection per page,
holding the page's matches except the current one, for the visible pages
and one page either side, plus the current match. A page's selection is
kept until a match arrives on the page or the current match moves to or
from it. On every scroll frame the view checks whether the pages at its
corners left that range (~2 µs; `visiblePages` takes ~0.1 ms), and once
the viewport settles, whether any visible page did.

| | `or`, 102,528 matches: before | after | `e`, 528,449 matches: before | after |
| --- | ---: | ---: | ---: | ---: |
| Colour change | 72–76 ms | 1.2–1.6 ms | 1.25–1.30 s | 9–11 ms |
| Next / previous | 71–85 ms | 1.4–2.5 ms | 1.23–1.34 s | 10–19 ms |
| Longest main-thread stall while searching | 134 ms | 10 ms | 2.15 s | 20 ms |
| Stalls over 16 ms while searching | 41 | 0 | 48 | 1 |
| Search | 7.8 s | 3.9 s | 84 s | 5.1 s |
| Drag, mean / longest step | 6.2 / 21 ms | 0.8 / 7.9 ms | 127 / 542 ms | 3.1 / 25 ms |
| Jump to page 700 | 96 ms | 6.6 ms | 1.84 s | 29 ms |

- Before, a drag assigned nothing; its cost is PDFKit drawing the
  separate selections of the pages that come into view.
- The search is now mostly PDFKit's own find (`lorem`, 14,740 matches:
  3.4 s). In `viewer-search`, Maestro could no longer see a search of `l`
  on `outline-large` still running before clearing it (each view hierarchy
  snapshot of that document takes ~4 s), so that check was removed; the
  clear still comes ~1 s into the ~4 s search.

## 16. Android view reads in a burst (2026-10-16)

**Found by**: issue #24. The view's `renderPage`, `getOutline`,
`getPageSizes` and `exportDocument` each ran on a thread of its own
(Nitro's `Promise.parallel`), and each page image opened its own renderer
and bitmap: nothing bounded how many ran at once.

**Method**: `example/e2e/probe-render-burst.yaml` (a long press on "Image"
in the viewer: 200 `renderPage(i % pageCount, { width: 300 })` calls at
once, PNG) on `large` (500 pages), debug builds (arm64), the API 37
emulator (4 cores) on a host shared with other emulators, so times swing
up to 4× between runs. Builds alternated, seven runs each; the first
pair with `benchmarks/sample-android-threads.sh` and
`benchmarks/sample-android-memory.sh` running, the last two also timing
images 1–10.

**Change**: each open document has a `BetterPdf-read` thread (exits when
idle) for the view's reads, one at a time in call order, as `PdfDocument`
and iOS do, and a `BetterPdf-write` thread for exports, which take
seconds for long documents (iOS: `com.betterpdf.write`). Work still
queued when the document closes rejects.

| | before | after |
| --- | ---: | ---: |
| Order of resolution | random | call order |
| Threads at peak, first pair (all / `Thread-N` / `BetterPdf-read`) | 251 / 199 / 0 | 54 / 0 / 1 |
| PSS at peak, first pair (≈ 230 MB at start) | 481 MB | 272 MB |
| Images 1–10 all there (last two pairs) | 6.7–10.9 s | 0.3–0.7 s |
| All 200 | 3.0–14.0 s | 5.9–26.9 s |
| All 200, after ÷ before in the same pair | | 1.3–2.1× |

- The whole burst takes longer: PDFium renders one page at a time in the
  process, but opening renderers, PNG encoding and file writes ran in
  parallel before. A list's first screenful arrives 9–35× sooner instead,
  since those images no longer wait behind the rest.

## 17. Android zoomed out below the fitted width (2026-10-16)

**Found by**: issue #30. With `minScale` below 1, every visible page was
still rendered and kept at fit width whatever the zoom, and the cache
keeps visible pages beyond its cap, so their memory grew with 1 / zoom².

**Method**: `run-android.mjs --zoom 0.05 --fixtures extreme --libraries
better` (new: `minScale` 0.05, `zoomTo(0.05)` before the scroll window,
stats read 3 s after it), release builds (arm64) with the same JS, the
API 37 emulator (1080×2400; the bench viewer is ~1080×1800). Three runs
of each build, then three interleaved pairs. Other emulators loaded the
host for all but the first "before" set (95–100% janky frames in both
builds, also at zoom 1; 21% in that set), so only counts and PSS are
compared, and render times only within pairs. Zoom 1:
`--fixtures medium,extreme`, three runs of each build. Raw JSON:
`android-2026-10-16T17-24-28-711Z.json` (before) and
`android-2026-10-16T17-36-10-405Z.json` (after), the pairs from
`android-2026-10-16T17-42-16-509Z.json` on.

**Change**: `BaseResolution` renders the base layer at half the fitted
size at zoom ½ and below, and at a quarter at ¼ and below, never with
fewer pixels than the screen shows. A cached page of another width renders
again, as after a rotation. Above ½ nothing changes.

| Zoom 0.05, `extreme` | before | after |
| --- | ---: | ---: |
| Pages cached 3 s after scrolling | 23–24 at fit width | 81 at a quarter |
| `cacheBytes` (cap 128 MB) | 145–151 MB | 32 MB |
| Peak PSS | 238–246 MB | 144–155 MB |
| Pages rendered in the window (pairs) | 98–136 | 156–200 |
| Mean render (pairs) | 117–181 ms | 87–101 ms |
| Bitmap allocations | 34–104 | 98–102 |
| Page changes while scrolling (pairs) | 38 / 88 / 87 | 46 / 92 / 86 |

| Zoom 1 | `medium` before | after | `extreme` before | after |
| --- | ---: | ---: | ---: | ---: |
| Peak PSS | 131–132 MB | 128–133 MB | 145–150 MB | 139–148 MB |
| Pages rendered | 59–63 | 56–60 | 56–59 | 54–67 |
| Bitmap allocations | 20–23 | 19–20 | 17–20 | 15–19 |
| `cacheBytes` | 38–44 MB | 38 MB | 38 MB | 38 MB |

- More allocations, each 1/16 the size: 38–39 MB allocated per run in the
  pairs instead of 214–252 MB. The cache's budget (at least 4 viewports)
  now holds ~100 pages, and the allocations are it filling up.
- More pages render with the same render-thread CPU (62–75% of a core in
  both builds).
- On screen: at 0.05 every visible page was drawn; a double tap back to
  zoom 2 was sharp within 3 s.
- Below ¼ the visible pages' memory grows with 1 / zoom again. It stays
  under 128 MB down to zoom 0.01 on phones and tablets
  (`BaseResolutionTest`); at 0.005 a tablet shows 225 pages (194 MB).

## 18. Base64 sources (2026-10-15)

**Question**: how large may a `{ base64 }` document be before it costs
visibly more than a file? The string crosses into native code with the
`source` prop, on the main thread, and is decoded on the loading thread.

**Method**: the example app with temporary probes (not committed): the
time from setting the source state in JS to `onLoad`, the native `source`
prop setter including Nitro's conversion of the string (iOS: the generated
component's `setSource`, on the main thread; Android: the generated state
updater's `setSource`, on the UI thread, which builds the Java string), and
the decoding on the loading thread (iOS: decode + write the pin; Android:
decode + write + open the descriptor + delete). Documents of 1, 5 and
20 MB (5, 25 and 100 pages of the `medium` fixture's kind), each opened
3 times as a file (`{ uri }`) and as base64, in a new viewer each time;
Android 6 times for base64 (two rounds). Reading the file into a string
(`File.base64()`) is the app's cost and not timed. iOS simulator "iPhone 17
Pro" (iOS 26.5), **debug** build (the library's Swift unoptimized); Android
emulator `sdk_gphone64_arm64` (API 37), **release** build; the host was
under other load (load average about 3), so single runs vary a lot.

| | 1 MB | 5 MB | 20 MB |
| --- | ---: | ---: | ---: |
| iOS: file → `onLoad` (ms) | 20–63 | 22–51 | 23–43 |
| iOS: base64 → `onLoad` (ms) | 24–46 | 31–77 | 100–116 |
| iOS: `source` prop setter, main thread (ms) | 0.5–0.7 | 2.7–6.7 | 12–39 |
| iOS: decode + write (ms) | 1–2 | 3–5 | 12–14 |
| Android: file → `onLoad` (ms) | 22–65 | 24–177 | 23–188 |
| Android: base64 → `onLoad` (ms) | 25–113 | 82–420 | 297–1157 |
| Android: `source` prop setter, UI thread (ms) | 2–8 | 8–31 | 31–39, once 177 |
| Android: decode + write + open (ms) | 16–66 | 62–277 | 232–866 |

- Up to a few MB base64 costs little: on iOS a few milliseconds more than
  the file; on Android tens of milliseconds, and the UI thread is held for
  under 10 ms at 1 MB.
- At 20 MB the Android UI thread is held for over 30 ms (two frames or
  more) while the prop is set, and opening takes about 0.3 s more than the
  file; one run paused 177 ms on the UI thread and 866 ms in decoding, as a
  garbage collection would. That design would have needed "up to a few MB"
  in the documents; the next section removed the cost.
- Android's cost is the string, not the file: the Java string is built on
  the UI thread, and the decoder is a Kotlin loop (10–20× iOS's time, where
  padded base64 goes through Foundation's decoder; see api-design
  "Source"). In the debug build the same 20 MB took 0.4–1.3 s to decode and
  43–177 ms on the UI thread. Passing the text without a Java string removes
  both; see the next section.
- Once loaded, a base64 document is read like a file: page sizes, search,
  page images and export work from the pin (iOS) or the descriptor
  (Android), see `viewer-base64`.

### The text in a native buffer (2026-10-16)

**Change**: `NativePdfSource.base64` is no longer a string but the text in a
native-owned `ArrayBuffer`, filled in JS once per distinct text. Android
decodes it in C++ straight into the file; iOS reads the same buffer (see
api-design "Source").

**Method**: as above (the same probes, plus the JS copy into the buffer),
same simulator and emulator, same builds (iOS debug, Android release), host
load average 4–15. The tables above are the string design; "→" gives the
new range.

| | 1 MB | 5 MB | 20 MB |
| --- | ---: | ---: | ---: |
| iOS: base64 → `onLoad` (ms) | 24–46 → 24–40 | 31–77 → 25–49 | 100–116 → 36–63 |
| iOS: `source` prop setter, main thread (ms) | 0.5–0.7 → ≤ 0.02 | 2.7–6.7 → ≤ 0.02 | 12–39 → ≤ 0.02 |
| iOS: decode + write (ms) | 1–2 → 1–2 | 3–5 → 3–5 | 12–14 → 9–13 |
| iOS: JS copy into the buffer (ms) | – → 0.1–0.6 | – → 0.7–21 | – → 6–11 |
| Android: base64 → `onLoad` (ms) | 25–113 → 39–107 | 82–420 → 57–185 | 297–1157 → 46–73 |
| Android: `source` prop setter, UI thread (ms) | 2–8 → 0.06–0.22 | 8–31 → 0.05–0.24 | 31–177 → 0.01–0.04 |
| Android: decode + write + open (ms) | 16–66 → 9–18 | 62–277 → 21–32 | 232–866 → 26–35 |
| Android: JS copy into the buffer (ms) | – → 0.2–2.6 | – → 9–17 | – → 5–6 |

The same files opened in 21–64, 25–66 and 22–50 ms on iOS and in 38–170,
71–183 and 24–205 ms on Android.

- Neither thread is held any more: the setter only passes a pointer. At
  20 MB a base64 document now opens within tens of milliseconds of the same
  file on both platforms, so the size advice is about memory, not time:
  while the document is shown, the app's string and the native buffer each
  hold the text.
- The JS copy (`TextEncoder.encodeInto`, Hermes) runs once per text; its
  spread at 5 MB (up to 21 ms, more than at 20 MB) came with the host load.
- Android now decodes in C++ at about 1 ms per MB of base64, opening the
  file included (release build), with no Java string or byte array of the
  document's size for the garbage collector (the string design paused once
  for 177 ms on the UI thread). The two decode rows do not compare the
  platforms: iOS's is a debug build and does not include opening.

## 19. iOS forms on long documents (2026-10-16)

**Found by**: issue #28. `DocumentForm` found a field's widgets by
loading the annotations of every page with widgets, on the main thread:
for `getFormFields`, for every reader's edit while `onFormChange` was set,
for `setFormFieldValues`, and to copy every widget's value before
`exportDocument`, `print` and `renderPage` of a changed form.

**Method**: `benchmarks/ios-forms/run.sh` (see its README; "before" is
`REV` at the commit before the change), on the iOS 26.5 simulator (Apple
Silicon Mac, quiet host): the form sources and real PDFKit in a simulator
process, a freshly loaded document in a continuous `PDFView` (402×874 pt)
in an off-screen `UIWindow` for every row, no app; the view's main-thread
calls are replayed, not run. `forms-long`: the `forms` fixture's two
pages, then a parent field with four text fields on each further page
(1,000 pages, 4,005 fields; 5,000 pages, 20,005 fields, generated with a
larger `pageCount`). 1,000 pages: two runs of each version, alternating
(ranges below); 5,000 pages: one run each. Then Time Profiler with the
Hangs instrument on the example app (release build, same simulator):
`forms-long` open, Fields, a tap into the first field and typing, Fields,
Export, with taps sent to the simulator (Maestro's view hierarchy reads
would add their own main-thread work). Not measured: a device.

**Change**: the form reads, from the page dictionaries, which pages each
field's widgets are on (with the pages that have widgets, which it read
before), and loads only those. It keeps the widgets changed by the reader
or by code: the snapshot for exports, printing and page images holds
those fields only, and is nil while nothing changed (formerly a change
counter, which a rejected `setFormFieldValues` also raised).
`getFormFields` reads a separate instance of the file on the read queue,
filled in with that snapshot, as page images are.

| Main thread | 1,000 pages: before | after | 5,000 pages: before | after |
| --- | ---: | ---: | ---: | ---: |
| The form, on first use | 10.5–10.7 ms | 12.8–13.0 ms | 51 ms | 65 ms |
| First edit, page 1 (form created, `onFormChange` field) | 167–189 ms | 13.4–14.0 ms | 1.05 s | 70 ms |
| First edit, last page | 176–217 ms | 13.7–14.0 ms | 1.38 s | 64 ms |
| `getFormFields` after an edit | 256–370 ms | 0.1 ms | 3.24 s | 0.0 ms |
| Snapshot for `exportDocument`, `print`, `renderPage` (two fields changed) | 256–285 ms | 0.1 ms | 3.09 s | 0.1 ms |
| `setFormFieldValues`, one field on the last page (form created) | 179–205 ms | 12.8–14.6 ms | 1.42 s | 69 ms |

| Example app, main thread in the view's form code (Time Profiler) | before | after |
| --- | ---: | ---: |
| Fields, as loaded | 226 ms | 1 ms |
| Tap into the first field, typing (form created, `onFormChange`) | 5 ms (pages loaded by Fields) | 8 ms |
| Fields after the edit | 89 ms | 15 ms |
| Export | 88 ms | 8 ms |
| Hangs reported | one, 295 ms | one, 275 ms |

- The one hang in each trace is PDFKit creating its text field and the
  keyboard taking it (`-[PDFKitTextView initWithAnnotation:…]`,
  `becomeFirstResponder`) on the tap into the field, not the library's
  code. The view's form work before the change stayed under the
  instrument's 250 ms threshold in the app, but held the main thread for
  up to 226 ms.
- What is left on the main thread is the form's first use: walking the
  page dictionaries, 13 µs per page.
- `getFormFields` resolves after 261–272 ms at 1,000 pages (3.6 s at
  5,000), on the read queue; the returned fields are the same in both
  versions (hash in the run's output) and equal the displayed document's
  own.
- Exports, page images and printing of a changed form fill in only the
  changed fields of the copy; the others are as the file has them. The
  export's fields equal the displayed document's (checked for the five
  form fixtures); the write itself (2.3–2.9 s at 1,000 pages) was already
  off the main thread.
- `viewer-forms`, `viewer-base64`, `viewer-print` and `viewer-page-image`
  pass on iOS.

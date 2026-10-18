# Benchmarks

`react-native-better-pdf` vs `react-native-pdf` 7.0.5.

No number in this document is estimated. Every figure comes from a run of the
scripts below; the raw JSON for each run is in `benchmarks/results/`
(gitignored — regenerate locally). Where a number could not be measured on
the available hardware, it says so.

## Fixtures

Generated deterministically by `node benchmarks/generate-fixtures.mjs`
(seeded PRNG, no external inputs):

| Fixture   | Pages | Size    | Content                                                                 |
| --------- | ----: | ------: | ----------------------------------------------------------------------- |
| `small`   |    10 | 0.76 MB | dense text, per-character kerned runs, uncompressed streams             |
| `medium`  |   100 | 19 MB   | text + one 900×600 colour JPEG per page                                 |
| `large`   |   500 | 225 MB  | full-page 150 dpi greyscale scans (1240×1754 JPEG), one image object per page |
| `extreme` |  1000 | 395 MB  | full-page 150 dpi colour scans (1240×1754 JPEG), one image object per page |

Every page has its own image object, so neither renderer can reuse a decoded
image across pages. Additional fixtures for correctness tests: `mixed-sizes`
(portrait, landscape, a 226×3000 pt receipt, square), `mixed-colour` (100
pages alternating text and text with a colour photo), `encrypted` (RC4-40,
password `secret`), and malformed files (empty, not a PDF, truncated,
corrupt xref).

## Harness

The example app's **Bench** tab runs the same scenario for either
library (`example/bench/scenario.ts`). Both viewers are configured as
equivalently as their APIs allow: vertical continuous scroll, fit width,
`minScale 1`, `maxScale 4`, 8 pt spacing; react-native-pdf with
`cache: false` and `trustAllCerts={false}` (its default breaks HTTP loads on
Android, see results §11). By default both open the same local `file://`
copy and **no network time is included**; the remote variant opens the
fixture from the fixture server under a fresh URL per run, so the load time
includes each library's download.

Scenario, per run (fresh mount, app cold-started by the host driver):

1. **Load** — mount → `onLoad` / `onLoadComplete`.
2. **Idle** — 1.5 s, JS frame monitor running.
3. **Page jumps** — 5 non-animated jumps (50%, 90%, 10%, 75%, 25% of the
   document), each timed from the call to the page-change event for that
   page; 0.7 s dwell after each.
4. **Zoom** — to 2.5× and back to 1× (react-native-pdf: via its `scale`
   prop, the only zoom API it has), JS frame monitor running.
5. **Scroll window** — 10 s during which the host performs swipes
   (Android: `adb shell input swipe`, 80% → 30% of the screen, 120 ms,
   every fifth swipe reversed).

### What each metric means

| Metric | How | Caveat |
| --- | --- | --- |
| Time to load | JS: mount → load event | Not "first pixel". Better PDF emits `onLoad` once the document is open (Android: after page 0's size, before the first render; iOS: after `PDFDocument` parsing). react-native-pdf emits after its own load. |
| Page jump | JS: `goToPage`/`setPage` → matching page-change event | Measures navigation + event delivery, **not** time until the target page's pixels are on screen. On iOS PDFKit posts the page-change notification synchronously inside `go(to:)`. |
| JS dropped frames | `requestAnimationFrame` intervals > 1.5× median | Measures JS-thread availability, not UI smoothness |
| JS thread CPU | Android: `/proc/<pid>/task/<tid>/stat` of `mqt_v_js`/`mqt_js` during the scroll window | percent of one core |
| Total / render-thread CPU | same source, all threads / `BetterPdf-render` | |
| Janky frames, p90 frame time | Android: `dumpsys gfxinfo` reset at scroll start, read at end | Emulator GPU is not representative of devices |
| Peak PSS | Android: `dumpsys meminfo` every 500 ms over the whole run | Includes the React Native runtime and the example app |
| Native render time | Android: Perfetto `BetterPdf#renderPage` trace sections; mean also in `getStats().meanRenderMs` | react-native-pdf has no equivalent markers |
| Cache hit rate, bitmap allocations, cancelled renders | `getStats()` at the end of the run (Better PDF, Android only) | "Hit" = page was already rendered when it scrolled into view |

## Running

```sh
bun run fixtures
node benchmarks/serve-fixtures.mjs &
```

**Android** (release build, one device connected or `ANDROID_SERIAL` set):

```sh
adb reverse tcp:8090 tcp:8090
cd example/android && ./gradlew :app:installRelease -PreactNativeArchitectures=arm64-v8a
cd ../.. && node benchmarks/run-android.mjs --runs 3
```

Each run copies its fixture onto the device first if needed; the copy happens
before the viewer mounts and is not part of any measurement.

Useful switches: `--libraries better`, `--fixtures extreme`,
`--scroll-seconds 320` (memory soak with PSS at 1 and 5 minutes),
`--reverse-every 0` (scroll in one direction only), `--source remote`
(open from the fixture server; load includes the download), `--zoom 0.05`
(scroll window zoomed out: `minScale` lowered to it, `zoomTo` it before the
swipes, and the stats read 3 s after them, once the dozens of visible pages
have rendered).

**iOS simulator** (Release build installed):

```sh
benchmarks/run-ios-sim.sh <simulator-udid> [fixtures…]
SOURCE=remote benchmarks/run-ios-sim.sh <simulator-udid> small medium large
```

Drives the same scenario through Maestro and reads the simulated app's
physical footprint with `footprint`. That is a host-process proxy — fine
for comparisons and leak detection, not a device number. Device memory and
CPU need Instruments (Allocations, Leaks, Time Profiler) on hardware.

**iOS search highlights** (no app; a simulator, booted if needed):

```sh
node benchmarks/generate-fixtures.mjs outline-large
benchmarks/ios-search-highlights/run.sh <simulator-udid>
REV=<commit> benchmarks/ios-search-highlights/run.sh <simulator-udid>   # the controller as of <commit>
```

Runs the real `PdfSearchController` and PDFKit in a simulator process and
times a search's main-thread stalls, colour changes, next/previous, a drag
and a jump, checking the highlights after each; see
[its README](../benchmarks/ios-search-highlights/README.md). Run it before
a release that touches iOS search.

**Native traces (Android)**:

```sh
adb shell perfetto -o /data/misc/perfetto-traces/pdf.pftrace -t 20s \
  -a software.gmi.betterpdf.example gfx view sched
```

Look for `BetterPdf#open`, `BetterPdf#renderPage` and `BetterPdf#recycleRenderer` slices.

## Size

Measured on release builds of the example app, which contains both
libraries, by attributing each file and each linked object to its library
(Android 2026-10-07, the 1.0 release candidate; iOS 2026-09-30, after text
selection was added, not measured again since). KB are 1,024 bytes.
react-native-pdf includes the
dependencies it requires (AndroidPdfViewer, pdfium-android, Gson,
react-native-blob-util). react-native-nitro-modules is listed separately: it
is shared by every Nitro module in an app.

**Android** (`app-release.apk`, arm64-v8a only; `.so` files are stored
uncompressed, so the stored size is the installed size; "gzip" estimates the
download; dex sizes are unminified, the example builds without R8):

| | react-native-better-pdf | react-native-pdf 7.0.5 |
| --- | ---: | ---: |
| Native code, per ABI | `libNitroBetterPdf.so` 1,850 KB (gzip 438 KB) | `libpdfium.so` 4,660 KB + `libpdfiumandroid.so` 422 KB = 5,082 KB (gzip 2,546 KB) |
| Dex | `com.margelo.nitro.betterpdf` 277 KB | `org.wonday.pdf` 8 KB + AndroidPdfViewer 44 KB + pdfium-android 75 KB + Gson 119 KB + blob-util 51 KB = 297 KB |
| **Total, one ABI** | **≈ 2.08 MB** | **≈ 5.25 MB** |
| Shared prerequisite | react-native-nitro-modules: `libNitroModules.so` 958 KB + 14 KB dex | — |

Native code is per ABI: an APK with all four ABIs carries PDFium four times
(≈ 20 MB); an app bundle delivers one ABI per device. On 2026-09-30
`libNitroBetterPdf.so` was 1,308 KB; what was added since (reading
position, page images, `PdfCache`, printing, `PdfDocument`, overlays,
forms) and its generated bindings grew it to 2,763 KB, of which `.dynstr` +
`.dynsym` + `.gnu.hash` for 4,774 exported symbols took 921 KB. The library
is now built with hidden visibility (`-fvisibility=hidden`: only
`JNI_OnLoad` and 78 type-information and vtable symbols from the C++
standard library and folly headers are exported; everything else is registered by address), which brought it to 1,701 KB.
Since `{ base64 }` (1.1) types keep default visibility
(`-fvisibility-ms-compat`), so that Nitro's native buffers are recognized
across libraries (see api-design "Source"): 1,850 KB with the base64 decoder,
of which the exported type information takes 126 KB.

**iOS** (Release, simulator arm64 slice; linker map of the app binary; both
libraries render with the system's PDFKit, which costs the app nothing):

| | react-native-better-pdf | react-native-pdf 7.0.5 |
| --- | ---: | ---: |
| Linked code | `NitroBetterPdf` 526 KB: 147 KB hand-written Swift, 378 KB Nitrogen-generated bindings | `react-native-pdf` 42 KB + `react-native-blob-util` 120 KB = 161 KB |
| Shared prerequisite | `NitroModules` 226 KB | — |

On iOS this library is larger: its generated JSI/Swift bindings outweigh
react-native-pdf's Objective-C view.

**npm** (unpacked): react-native-better-pdf 1.7 MB in 734 files (tarball
323 kB, npm's figures; 2026-10-06) + react-native-nitro-modules 581 KB; react-native-pdf 303 KB +
react-native-blob-util 893 KB + crypto-js 487 KB +
deprecated-react-native-prop-types 46 KB.

## Results

Full tables, baselines, every experiment (including the ones that were
reverted) and raw file names: [performance/results.md](performance/results.md).

Summary, Android emulator (API 37), release builds, medians of 3 runs, both
libraries in one session (2026-10-18, results §22):

| Fixture | Load: Better / rn-pdf | Load from HTTP (incl. download): Better / rn-pdf | Peak PSS: Better / rn-pdf | Peak PSS over HTTP | Total CPU while scrolling: Better / rn-pdf |
| --- | --- | --- | --- | --- | --- |
| small (10 p) | 57 / 67 ms | 74 / 131 ms | 85.3 / 90.2 MB | 86.3 / 105.1 MB | 16.4 / 32.6% |
| medium (100 p) | 43 / 165 ms | 104 / 283 ms | 127.6 / 129.7 MB | 132.7 / 145.5 MB | 25.5 / 59% |
| large (500 p) | 47 / 417 ms | 592 / 1202 ms | 104.2 / 549.6 MB | 104.8 / 564.1 MB | 28.5 / 110.9% |
| extreme (1000 p) | 56 / 622 ms | — | 142.1 / 897.9 MB | — | 33.8 / 110.7% |

Page jumps: 0 ms vs 34–50 ms to the page-change event. JS-thread CPU
while scrolling is equal (under 1%) and so is the number of JS callbacks.
Janky-frame percentages on this emulator image say nothing about either
library (results §13); they need a device. With its default
`trustAllCerts: true`, react-native-pdf could not open any HTTP document on
Android (results §11); the numbers above use `false`.

On iOS both libraries render with PDFKit, so the numbers are close: Better
PDF loads local files as fast or faster in most runs and peaks at the same
or lower footprint; HTTP loads are within one run's noise (both download
natively); see results §9, §12 and §13.

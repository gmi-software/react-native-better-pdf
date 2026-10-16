# Contributing

## Repository layout

- `package/` — the published library (`react-native-better-pdf`)
- `example/` — Expo SDK 57 development-build app: a viewer, a benchmark
  screen, and Maestro end-to-end flows (`example/e2e/`)
- `benchmarks/` — fixture generator, fixture server, Android benchmark driver
- `docs/` — guides, API reference, design, research, benchmarks, audit

Bun workspaces with `linker = "hoisted"` (see `bunfig.toml`): react-native-pdf,
used as the benchmark baseline, expects a flat `node_modules`.

## Setup

```sh
bun install
bun run fixtures          # generates benchmarks/fixtures/*.pdf (~650 MB, gitignored)
```

Toolchain: Node 24, Bun 1.3, Xcode 27, JDK 17 (JDK 25 breaks the CMake
configure step of some React Native dependencies), Android SDK 36, NDK 27.1,
Maestro (e2e). With several Android devices attached, set `ANDROID_SERIAL`.

## Everyday commands

```sh
bun run typecheck                       # library + example
bun run lint
bun run test                            # JS unit tests (bun test)
cd package && bun run specs             # regenerate Nitro bindings after editing *.nitro.ts
```

Android JVM unit tests (layout, prefetch planner, LRU, download cache against
a local HTTP server):

```sh
cd example/android && ./gradlew :react-native-better-pdf:testDebugUnitTest
```

## Running the example

```sh
node benchmarks/serve-fixtures.mjs      # serves fixtures on :8090
adb reverse tcp:8090 tcp:8090           # Android only
cd example && bunx expo run:ios         # or run:android
```

## End-to-end tests (Maestro)

Flows live in `example/e2e/` and run on both platforms:

```sh
example/e2e/run.sh <simulator-udid | emulator-serial>             # functional flows
example/e2e/run.sh <device> stress-open-close stress-rapid        # stress flows
```

| Flow | Covers |
| --- | --- |
| `viewer-bundled` | `require()` asset, page count, `goToPage` |
| `viewer-scroll-zoom` | native scroll changes pages, zoom, navigation while zoomed, stats |
| `viewer-errors` | `file-not-found`, `http-error`, `invalid-pdf` for empty / non-PDF / truncated / corrupt-xref files |
| `viewer-password` | `password-required` → `invalid-password` → unlock |
| `stress-open-close` | 10× open/close of the 1000- and 500-page scans |
| `stress-close-during-work` | documents closed while their work runs — renders in a fling, a search, a page image, a download, the outline, a form fill and export, `PdfDocument` page images — 5 rounds; watch logcat (`adb logcat -b crash`) and the iOS crash reports while it runs |
| `stress-rapid` | rapid flings, direction changes, jumps and zooms on 1000 pages |
| `viewer-links` | page tap coordinates in PDF points, URL link reported (not opened), internal link followed, double tap not reported as a tap; a `/Dest` with `/Fit` and a name-tree destination, and a JavaScript action reported as a page tap (`links-all`). Run it on an Android 14 emulator without SDK extension 13 too: links there come from the library's parser alone |
| `viewer-load-progress` | download progress of a remote document (fixture server throttled via `?bytesPerSecond=`) |
| `viewer-offline` | `PdfCache` and cache options: persistent vs session copies across an app restart, 304 revalidation, stale copy while the fixture server is "offline" (`/__control/offline`), removal, cancellation, `clear()` |
| `viewer-print` | print dialog previews the bundled sample and the unlocked protected fixture; closing resolves `isSubmitted: false`; Android saves the protected document as PDF (`isSubmitted: true`) |
| `viewer-dark` | the Embedded card switches to dark pages and back while shown (screenshot for review: Maestro cannot check colours) |
| `viewer-inset` | `contentInset` under translucent bars on 100 pages: the first page and `goToPage` below the header (also after zooming back), the end above the toolbar, facing pages, a fitted page's height between the bars (screenshot) |
| `viewer-forms` | the `forms` fixture filled in by touch (text when editing ends, checkbox, radio, Android's choice list, a field above the keyboard) and from code (all or none), `onFormChange` for the reader's changes only, filling turned off, export and the reopened copy |
| `viewer-overlays` | `PdfPageOverlay`s tapped by testID on 100 pages: at fit, after their page scrolls in, after a far jump and back (hidden meanwhile), after a resize, zoomed 2.5× (also paged), in paged RTL and in pairs (screenshots for review) |
| `viewer-spreads` | facing pages (`pairs`, `book`) on 100 pages: the indicator names a spread's first page, the last page at the end; switching layouts keeps the reader's page |
| `viewer-short-document` | a one-page document starts at the top (also after zooming in and out); rounded card on the Embedded screen (screenshot) |
| `viewer-mixed-sizes` | estimated page sizes corrected on render (portrait, landscape, receipt, square) |
| `viewer-text-selection` | long press selects a word, one event per handle drag, tap clears, `enableTextSelection={false}` in continuous and paged layouts |
| `probe-memory-growth` | diagnostic: ~100 distinct pages rendered via jumps; run with the memory sampler |
| `probe-render-burst` | diagnostic: 200 `renderPage` calls at once on 500 pages, with `getPageSizes` after the first, resolve in call order; on Android run with the thread and memory samplers |

On iOS, `Pressable` rows expose one combined accessibility label, so flows
match list rows as `"title.*"`.

While a stress flow runs, `benchmarks/sample-android-memory.sh` records
memory once per second, and `benchmarks/sample-android-threads.sh` the
app's thread count about five times per second.

## Benchmarks

See [docs/benchmarks.md](docs/benchmarks.md). Always benchmark **release**
builds.

## Change guidelines

- Keep the hot path native. A change that adds per-frame JS work, a new
  per-gesture event, or a React-managed page needs a design note first.
- Anything that allocates bitmaps must say who frees them and when.
- For public API changes, update `docs/api.md`, the feature's guide in
  `docs/guides/` and `docs/api-design.md`; for new features or platform
  support, also the README's feature list and capability matrix. Update the
  [agent skill](https://github.com/gmi-software/better-skills/tree/main/skills/react-native-better-pdf)
  in gmi-software/better-skills when they are released.
- Validate both iOS and Android for native changes.
- Performance claims need a benchmark run attached.

## Commit messages

This project uses [Conventional Commits](https://www.conventionalcommits.org/):
the release notes are generated from them. Commit messages are validated
locally by a Husky hook (installed by `bun install`) and on pull requests in
CI.

```text
<type>[optional scope]: <description>
```

Common types: `feat`, `fix`, `perf`, `docs`, `refactor`, `test`, `build`,
`ci`, `chore`. Scopes in use: `ios`, `android`, `example`, `bench`, `e2e`.

```text
feat: add contentInset for translucent bars
fix(ios): write form exports off the main thread
perf(android): build the library with hidden symbols
```

## Pull requests

`main` is protected: every change lands through a reviewed pull request, and
the `quality` and `commitlint` checks must pass. Native changes also run the
iOS and Android compile jobs (`.github/workflows/native.yml`).

## Releasing

Maintainers cut releases from CI — see [RELEASING.md](RELEASING.md).

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By taking
part you are expected to uphold it; report unacceptable behavior to
security@gmi.software.

## Security

Do not report vulnerabilities through issues or pull requests. See
[SECURITY.md](SECURITY.md) for the private reporting channel.

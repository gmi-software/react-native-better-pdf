# iOS search highlights

What `search()` costs the main thread on iOS: the real `PdfSearchController`
(`package/ios/PdfSearchController.swift`) and PDFKit, in a process spawned on a
simulator. No app and no React Native. Written for #29; the numbers are in
[`docs/performance/results.md`](../../docs/performance/results.md), section 15.

```sh
node benchmarks/generate-fixtures.mjs outline-large
benchmarks/ios-search-highlights/run.sh <simulator-udid>              # queries `or` and `e`
benchmarks/ios-search-highlights/run.sh <simulator-udid> lorem re     # other queries
REV=<commit> benchmarks/ios-search-highlights/run.sh <simulator-udid> # the controller as of <commit>
```

The script compiles the controller with `swiftc -O` for the simulator, boots
the simulator if needed, and searches `outline-large` (1,000 text pages) from
page 1 in an iPhone-sized (402×874 pt) continuous `PDFView` in an off-screen
window. It prints one table row per query, keeps the full output in
`benchmarks/results/` (gitignored), and exits 1 if a highlight check failed.

| Column | How |
| --- | --- |
| Colour change | Setting `matchColor` three times; the shown highlights are recoloured. |
| Next / previous | Five `next()` and three `previous()` calls, scroll included. |
| Longest stall while searching, stalls over 16 ms | Another thread pings the main queue in a loop (1 ms between pings) and records how long each ping waited. |
| Search | `search()` to the event with `isComplete`. |
| Drag | 60 steps of 200 pt to the scroll view's offset, about six pages, each timed with the viewport hook below. |
| Jump | `go(to:)` to page 700, with the hook. |
| Highlights | After every phase: on each visible page, every occurrence of the query is highlighted once and nothing else is (except the newline PDFKit joins into one range with matches either side of a line break); one selection has the current colour, and PDFKit drew it (a `PDFSelectionLayer` in that colour) when its page is visible. |

`HybridBetterPdfView` is replaced by the two calls it makes: the controller's
`viewportDidChange()` on every scroll offset change, and
`viewportDidSettle()` once the viewport settles. A controller from before
#29 has neither and runs without them.

Caveats:

- The simulator runs on the host CPU: compare before and after on one machine
  in one sitting (`REV=`), and don't read the numbers as device numbers.
  Stalls move most with host load: the longest for `or` was 10 ms on a quiet
  host, 34 ms with Android emulators running, and 133 ms right after the
  simulator booted (run twice after a boot).
- PDFKit adds its highlight layers as in the app (the checks read them), but
  the window is never on screen: no tiles are drawn.
- Only `PdfSearchController.swift` follows `REV`; the rest is the working tree.

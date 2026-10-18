# iOS forms

What the viewer's form work costs the main thread on iOS: the real
`DocumentForm`, `FormSnapshot` and `ColorSchemeDocument` (`package/ios/`) and
PDFKit, in a process spawned on a simulator. No app and no React Native.
Written for #28; the numbers are in
[`docs/performance/results.md`](../../docs/performance/results.md), section 19.

```sh
node benchmarks/generate-fixtures.mjs forms-long forms forms-encrypted forms-compressed forms-boxes
benchmarks/ios-forms/run.sh <simulator-udid>                  # all five
benchmarks/ios-forms/run.sh <simulator-udid> forms-long       # one
REV=<commit> benchmarks/ios-forms/run.sh <simulator-udid>     # the form sources as of <commit>
```

The script compiles the form sources with `swiftc -O` for the simulator
(the Nitro types are stand-ins in `main.swift`), boots the simulator if
needed, and runs each fixture in its own process. Every operation starts
from a freshly loaded document in an iPhone-sized (402×874 pt) continuous
`PDFView` in an off-screen window, so no page's annotations are loaded but
those the view or the reader touched. It prints one table per fixture,
keeps the full output in `benchmarks/results/` (gitignored), and exits 1 if
a check failed.

| Row | What the view does on the main thread |
| --- | --- |
| Open the form | `DocumentForm(document:)`, on first use. |
| First edit | The reader's change to a field on a page they see: the form created, the change noted, and the field built for `onFormChange` (in the view, a main-queue turn later). |
| `getFormFields` | After an edit on page 1: before #28, every field read from the displayed document; after, the snapshot of the changed fields, then the fields read from a filled-in copy on another thread ("Total" is until the result is back on main). |
| `exportDocument` | After edits on the first and last pages: the snapshot of the values to copy ("Total" adds filling in and writing a copy, off the main thread; `renderPage` and `print` take the same snapshot). |
| `setFormFieldValues` | One text field on the last page, the form created on first use. |

Main thread is the call's own time; longest stall is the longest wait of a
main-queue ping sent in a loop from another thread. Checks: the fields
`getFormFields` returns equal the displayed document's own (read on main),
the export's fields equal them too (in any order: PDFKit writes the field
tree in its own), the edited value is reported, and `widgets(named:)` finds
every field's widgets that a walk over every page finds. The note's hash
covers the returned fields: it should not change between versions.

Caveats:

- The simulator runs on the host CPU: compare before and after on one
  machine in one sitting (`REV=`), and don't read the numbers as device
  numbers. Run twice after the simulator boots.
- The view's calls are replayed here, not run: `HybridBetterPdfView` needs
  React Native. The example app's `forms-long` entry is the place for Time
  Profiler.
- `forms-long` has 1,000 pages; for 5,000, change its `pageCount` in the
  generator and the last field in `run.sh` (`p5000.line4`).

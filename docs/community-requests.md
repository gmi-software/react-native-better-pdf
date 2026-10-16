# What the react-native-pdf community asks for

Research for the roadmap, 2026-09-30. Issue numbers refer to
[wonday/react-native-pdf](https://github.com/wonday/react-native-pdf/issues).
"better-pdf" marks the state of this library at that date. Counts are
reactions (r) and comments (c) summed over a cluster of related issues.

Source: `gh api` on wonday/react-native-pdf (1,814 stars; 821 issues, 383 open; 216 PRs, 4 open), Stack Overflow tag (44 q), npm (react-native-pdf 7.0.5, ~691k/week; react-native-blob-util ~964k/week). Discussions disabled; Reddit not readable. r = reactions, c = comments. #555 votes look inflated (12 bare +1s same day).

## Feature requests (ranked by cluster reactions+comments)
1. Full-text search + highlights — 7 issues, 40 r / 40 c, 2018→2026, 5 open: #555 (31r 21c), #243, #524, #825, #525, #1038. COVERED (`search`, highlights; Android 15+).
2. Scroll/viewport events, save/restore position, read-to-end — 11 issues, 37 r / 24 c: #492 (8r), #224 (7r), #427, #484, #439, #905, #275, #869. COVERED (`onViewportChange`, `initialViewport`, `goToViewport`, `isAtEnd`).
3. Working link taps on Android / singlePage — 12 issues, 22 r / 81 c: #983, #464, #384, #533, #594, iOS #841/#877/#839. COVERED (links read from the file on every Android version, every link form; password-protected documents keep the renderer's links: API 35+ or SDK extension 13).
4. Layout: padding, top alignment, border radius — 6 issues, 21 r / 34 c: #520, #920, #909, #513, #994, PR #993 unmerged. COVERED (top alignment, `borderRadius`, no iOS safe-area offset, `pageSpacing={0}`, `contentInset`; see api-design "Short documents, rounded corners, safe area" and "Content inset").
5. Scroll indicators — 6 issues, 22 r: #569, #828, #412, #421, #813. covered.
6. Annotation/signature fidelity on Android; editing — 10 issues, 9 r / 41 c: #848, #975, #930, #863, #862, #687, #314, PR #1042. partly: forms are filled in, read and exported (see api-design "Forms"); annotations are not edited.
7. Large PDFs: fast first page, low memory — 6 issues, 10 r / 24 c: #659, #799, #482; OOM #519/#579/#682; lag #638. partly (measured advantage).
8. Tap coordinates in PDF space; React overlays on page — #523 (6r), #616, #722, PR #618; overlays #350, #254, #449, #484. COVERED (taps; overlays as `PdfPageOverlay`, see api-design "Page overlays").
9. Two-page spread — 4 issues, 7 r / 12 c: #415, #921, #666, #200. partly: vertical continuous (`spreadMode`); paged spreads are not possible with PDFKit (see api-design "Facing pages").
10. Text selection + copy — 5 issues, 3 r / 13 c: #459, #419, #925, #690 (+ half of #555). COVERED.
11. Cache control: purge, per-URL invalidation, prefetch, offline — 11 issues, 2 r / 21 c: #952, #964, #572, #566, #689, #696, #246. COVERED (cache options on the source, `PdfCache`; see api-design "Caching and offline").
12. Per-page dimensions — 4 issues, 2 r / 24 c: #302, #346, #898. COVERED (`getPageSizes`).
13. Password detection / prompt — 7 issues: #26, #895, #860, #992, #223. partly (stable codes exist; Android 15+).
14. Accessibility (screen readers) — #503 (6r), #483, #937, #939. COVERED.
15. Dark mode / inversion — #325, #715, #996, #1038. COVERED (`colorScheme`; on iOS annotations keep their colours, see api-design "Dark pages").
Also: base64 (#739, #705, #835, #199; covered: `{ base64 }` in 1.1, see api-design "Source"), custom certs/pinning (#409, #433, #932), forms fill/save (#748, #763, #342, #362; covered: forms, see api-design "Forms"), thumbnail image export (#998, #43, #457, #811; covered: `renderPage`), TOC (#542, #247; covered), decrypt-on-read (#1016), print/share (#378, #303, #371; print covered: `print`), rotation (#556), reload (#717), tvOS (#916), Windows (#683, #642). Covered already: zoom control (#528, #236), goToPage on Android (#985, #917), progress not stuck (#413, #622, #995).

## Recurring bugs / maintenance (selling points)
- 16 KB page size — 7 issues, 119 r / 109 c, 2025: #960 (51r, open), #965, #970, #947, #948, #934, #962. better-pdf .so verified 16 KB aligned (0x4000).
- New Architecture / Fabric — 10 issues, 96 r / 70 c: #878 (35r), #942, #907, #901, #663, #726, #831, #815, #1021.
- react-native-blob-util — 11 issues, 96 r / 82 c: #605, #624, #575, #796 (cancel crash, 35c), #773, #728, #1018; PR #870 unmerged.
- Release cadence — 6 issues, 70 r / 42 c: #963, #967, #991, #1026, #585, #590.
- Contributions rarely land: open PRs #1044, #1040, #1039, #1033; closed unmerged #618, #993, #1042, #870, #1010.
- Android "Already closed" crash — 6 issues, 48 r / 119 c: #830, #882, #976, #959, #989, #987, #1041 (partly fixed in 7.0.5). better-pdf: audited and stress-tested, see api-design "Closing while work runs".
- SIGSEGV in libpdfium — #847, #1024, #1041, #979; PR #1033.
- iOS blank after RN/Expo upgrade — 5 issues, 32 r / 51 c: #958, #969, #919, #966, #988.
- Expo — #834, #716, #856, #977.
- onLoadComplete dropped on Android under Fabric — #899, #754, #949; root cause #1009, fix PR #1010 closed.
- Rendering accuracy — #951, #975, blur #551/#376/#765/#634.
- Nested gestures (ScrollView/Modal) — #693, #845, #954, #1043, #448.
- URI/path handling — #990 (PR #1040), #488, #646, #844, #837, #1030.
- Security scanners — #779 (crypto-js), #570, #794, #997.

## Recommended additions (from research)
1. Search with highlights (PDFKit findString; PdfRenderer.searchText API 35+). Done: `search`.
2. onViewportChange + get/restoreViewport (+ page-to-view conversion for overlays). Done: `onViewportChange`, `initialViewport`, `goToViewport`; overlays as `PdfPageOverlay`.
3. Cache & download control (persistent cache, clearCache, prefetch, POST/body). Done except POST/body (downloads are GET only): source cache options, `PdfCache`.
4. Two-page spread + dark/inverted mode. Done: `spreadMode` (vertical continuous), `colorScheme`.
5. Android annotation/link fidelity; PdfRendererPreV (API 31–34 via SDK ext 13) for links/passwords/selection below 15 (to verify). PdfRendererPreV done (passwords, selection, search and forms on Android 12–14 with SDK extension 13; links there only for password-protected documents, other documents' links are read from the file on every version); fidelity stays the platform renderer's.
Cheap extras: per-page sizes (done: `getPageSizes`), contentInset/top alignment (done: top alignment, `contentInset`), renderThumbnail(page,width)→file (done: `renderPage`), README table mapping rn-pdf pain points to how better-pdf avoids them (open; docs/migrating-from-react-native-pdf.md maps the API).

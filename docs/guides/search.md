# Search

```tsx
const [search, setSearch] = useState<PdfSearchChangeEvent>();

<PdfView ref={pdf} source={source} onSearchChange={setSearch} />;

pdf.current?.search(query);   // new query: restarts; '' clears
pdf.current?.searchNext();    // or searchPrevious(); both wrap around

// "3 / 17", or "3 / 17…" while the rest of the document is searched
const label = search?.isSupported === false
  ? 'Search unavailable'
  : `${(search?.currentMatch ?? -1) + 1} / ${search?.matchCount ?? 0}${search?.isComplete ? '' : '…'}`;
```

- Search is native and runs in the background: matches are highlighted as
  pages are searched, starting at the current page, and the first match at
  or after it becomes current and is scrolled into view. Case-insensitive.
- `onSearchChange` fires at most 10 times per second while pages are
  searched, and immediately for the first match, `searchNext`/`searchPrevious`,
  completion and clearing. `currentMatch` counts matches in document order.
- Matches are highlighted in `searchHighlightColor` (translucent yellow)
  and the current one in `searchCurrentHighlightColor` (orange). Any React
  Native colour works except `PlatformColor` / `DynamicColorIOS`, which
  fall back to the defaults.
- Loading another document ends the search (with an event whose `query` is
  `''`).
- Android searches with the platform renderer's text API (Android 15+, or
  12–14 with SDK extension 13); elsewhere the search ends at once with
  `isSupported: false`.

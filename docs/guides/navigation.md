# Navigation and reading position

## Links and taps

```tsx
<PdfView
  source={source}
  onLinkPress={({ url }) => {
    if (url.startsWith('https://')) Linking.openURL(url);
  }}
  onPageTap={({ page, x, y }) => toggleToolbar()}
/>
```

URL links are reported, never opened by the viewer. Links to other pages
are followed natively. `onPageTap` reports taps outside links in PDF points
from the top-left of the page as displayed.

## Reading position

Reopen a document where the reader left off, and know what they have read:

```tsx
const saved = storage.get(documentId); // a PdfViewport, or undefined

<PdfView
  source={source}
  initialViewport={saved}
  onViewportChange={({ viewport, isAtEnd }) => {
    storage.set(documentId, viewport);
    if (isAtEnd) markAsRead(documentId);
  }}
/>;
```

- A `PdfViewport` is `{ page, x, y, scale }`: the point of `page` (in PDF
  points from its top-left corner) at the view's top-left corner, and the
  zoom. PDF points do not depend on the screen, so a position survives
  rotation, another device or another layout mode. `x` / `y` are negative
  when the corner is in the margin or gap before the page.
- `onViewportChange` fires once after loading and then whenever the view
  stops moving (scroll, fling, zoom, page turn, rotation, `goToPage`…),
  only when the position changed. `firstVisiblePage` / `lastVisiblePage`
  give the pages on screen; `isAtEnd` is true while the end of the last
  page is on screen.
- `ref.goToViewport(viewport)` jumps back to a position without animation,
  e.g. for "back to where I was" after following a table-of-contents entry.
- A saved page outside the document (a shorter new version, a corrupted
  value) opens the nearest page from its top.
- Paged layouts (`enablePaging`, `singlePage`) restore the page and zoom;
  on iOS the position within a zoomed page is not restored.

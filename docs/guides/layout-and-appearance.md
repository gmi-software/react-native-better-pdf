# Layout and appearance

## Facing pages

```tsx
const { width, height } = useWindowDimensions();
<PdfView source={source} spreadMode={width > height ? 'book' : 'none'} />;
```

- `'pairs'` shows pages 1–2, 3–4, … side by side; `'book'` shows the first
  page alone, like a cover, then 2–3, 4–5, …, like a magazine.
- Each page fits half the view's width; a page without a partner (the
  cover, an odd last page) is centred. Rows scroll vertically.
- `onPageChange` reports the first page of the spread under the viewport
  centre (the last page at the end). Switching `spreadMode` or the layout
  keeps the page the reader was on.
- Vertical continuous layouts only: ignored with `horizontal`,
  `enablePaging` and `singlePage` (iOS's PDFKit lays out facing pages only
  there). Facing pages run left to right.

## Translucent bars

```tsx
<View style={{ flex: 1 }}>
  <PdfView
    source={source}
    style={{ flex: 1 }}
    contentInset={{ top: headerHeight, bottom: toolbarHeight }}
  />
  <View style={[StyleSheet.absoluteFill, { bottom: undefined, height: headerHeight, backgroundColor: 'rgba(255, 255, 255, 0.85)' }]} />
</View>
```

- Pages scroll under the bars, but the first page starts below `top` and
  the document ends above `bottom`, as with a `ScrollView`'s
  `contentInset`. Values are dp / pt and do not scale with zoom.
- Everything that names a position refers to the area between the bars:
  `goToPage` puts the page's top below the header, `initialViewport`,
  `goToViewport` and `onViewportChange` use its top-left corner, the
  current page is the one at its centre, `isAtEnd` means the last page's
  bottom is above the toolbar, search results scroll into it, and
  `fitPolicy` `'height'` / `'page'` fit pages between the bars.
- Vertical continuous layouts only (facing pages included); horizontal,
  paged and single-page layouts ignore it, like `spreadMode`.

## Dark pages

```tsx
const scheme = useColorScheme();
<PdfView
  source={source}
  colorScheme={scheme} // 'dark' turns pages dark; anything else keeps them
  style={{ flex: 1, backgroundColor: scheme === 'dark' ? '#27272A' : '#E4E4E7' }}
/>;
```

- Lightness inverted, hues kept: white paper turns black, black text
  white, and a red heading stays red (lighter). Photos are inverted too.
- The area around pages is the view's `backgroundColor`; set a dark one.
- Search and selection highlights keep their colours. Page images
  (`renderPage`) and printing keep the document's colours.
- Annotations: Android darkens whatever its renderer draws. On iOS,
  PDFKit draws annotations apart from the page, so they keep their own
  colours; highlights, underlines and strike-outs are lightened onto the
  dark page instead of disappearing.

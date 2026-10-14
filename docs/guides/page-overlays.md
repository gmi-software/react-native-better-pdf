# Page overlays

```tsx
import { PdfPageOverlay, PdfView } from 'react-native-better-pdf';

<PdfView source={source} style={{ flex: 1 }}>
  {fields.map((field) => (
    <PdfPageOverlay
      key={field.id}
      page={field.page} // zero-based
      x={field.x} // PDF points from the page's top-left,
      y={field.y} // as onPageTap reports them
      width={180}
      height={40}
    >
      <Pressable style={{ flex: 1 }} onPress={() => sign(field)}>
        <Text>Sign here</Text>
      </Pressable>
    </PdfPageOverlay>
  ))}
</PdfView>;
```

- Coordinates are those of `onPageTap`: PDF points from the top-left
  corner of the page as displayed (crop box, rotation applied), so an
  overlay can go where the reader tapped.
- The overlay is laid out at `width` × `height` dp / pt and scaled with
  the page, as if printed on it. `scalesWithZoom={false}` keeps its size
  (a pin, a badge), its top-left corner on the point.
- Native code moves overlays in the same frame as their pages — scrolling,
  zooming, paging, resizing — without JS. An overlay is hidden while its
  page is off screen (also from screen readers) and while no document is
  shown.
- Between overlays, touches reach the document.
- `style` cannot move or resize an overlay, and a `transform` on it is
  overwritten; transform a view inside it instead.
- Meant for tens of overlays: each is moved every frame the document
  moves (23 overlays: ~0.1 ms per frame on iOS, ~0.2 ms on Android, debug
  builds).

# Page images and documents without a view

## Page images

```tsx
const image = await pdf.current!.renderPage(page, {
  width: 160 * PixelRatio.get(), // pixels; the height follows the page
  format: 'jpeg',                // default 'png'
  quality: 0.8,                  // JPEG only, default 0.9
});
<Image source={{ uri: image.uri }} style={{ width: 160, aspectRatio: image.width / image.height }} />;
```

- The page as displayed (crop box, rotation), on white, with the
  annotations the viewer draws. `width` is 1–4096 pixels, and an image is
  at most 4096 × 4096 pixels' worth (a very tall page needs a smaller
  width).
- Rendered off the main thread on a separate renderer, so the view keeps
  scrolling and its caches are untouched.
- The file is written to the app's cache directory (`BetterPdf/renders`)
  and is yours: move it to keep it, delete it when done. The OS may clear
  cache files when storage is low.
- For thumbnails that stay on screen, a `singlePage` `PdfView` (see the
  example's thumbnail strip) needs no files; `renderPage` is for images
  you pass elsewhere — `<Image>` lists, sharing, uploads.
- Without a view, `PdfDocument` renders the same images (next section).

## Documents without a view

`PdfDocument` opens a document without showing it — for a document list
with titles, page counts and covers, or to check a password before opening
the viewer:

```ts
import { PdfDocument, PdfOpenError } from 'react-native-better-pdf';

try {
  const document = await PdfDocument.open(source, { password });
  try {
    document.pageCount;                          // 12
    const { title, author, createdAtMs } = await document.getMetadata();
    const cover = await document.renderPage(0, { width: 300 });
    const sizes = await document.getPageSizes(); // as in the view's ref
    const toc = await document.getOutline();     // as in the view's ref
  } finally {
    document.close();
  }
} catch (error) {
  if (error instanceof PdfOpenError && error.code === 'password-required') askForPassword();
}
```

- `open` takes every source `PdfView` takes and opens it the same way:
  remote documents are downloaded (or taken from the stored copy, see
  [Offline reading and caching](offline-and-caching.md)), with
  `onProgress` and an `AbortSignal` as options. It fails with a
  `PdfOpenError` whose `code` is one of the view's error codes. Offline, a
  remote document opens from its outdated copy and `isStale` is `true`.
- `getMetadata()` reads the document information dictionary: `title`,
  `author`, `subject`, `keywords`, `creator`, `producer`, and `createdAtMs`
  / `modifiedAtMs` (milliseconds since the epoch; `new Date(createdAtMs)`).
  Missing or empty entries are `undefined`.
- `getPageSizes`, `getOutline` and `renderPage` work like the view's ref
  methods. Calls run one after another, so requesting twenty thumbnails at
  once renders them one at a time.
- **Call `close()`** when done: an open document keeps its file open until
  then (or until the JS garbage collector releases it, which can take
  long). Calls after `close()` reject.

# Sources and loading

## Sources

```ts
type PdfSource =
  | PdfUriSource        // { uri, headers? } — https://, http://, file://, content:// (Android)
  | { filePath: string } // absolute path
  | PdfBase64Source      // { base64 } — the document's bytes as a base64 string
  | number;              // require('./file.pdf')
```

Remote files are downloaded natively and reused for the rest of the app
session — or kept for offline reading; see
[Offline reading and caching](offline-and-caching.md). An inline
`source={{ uri }}` object is fine: the source is compared by value, so
re-renders never reload the document.

`headers` are sent with the download. Each name must be an HTTP token
(letters, digits and ``!#$%&'*+-.^_`|~``) and no value may contain a line
break or another control character (tab is fine): such a source fails with
`invalid-source` on both platforms, before anything is sent.

## Base64 documents

A document you have as a base64 string — made with pdfmake or pdf-lib, or
received from an API — opens as `{ base64 }`, in `PdfView` and in
`PdfDocument.open`:

```tsx
import { PDFDocument } from 'pdf-lib';

const pdf = await PDFDocument.create();
pdf.addPage().drawText('Hello');
setSource({ base64: await pdf.saveAsBase64() });

<PdfView source={source} />;
```

- The string is standard base64 (RFC 4648). Line breaks, spaces and tabs are
  ignored, and the `=` padding may be left out. Anything else fails with
  `invalid-source`, as does an empty string; bytes that are not a PDF fail
  with `invalid-pdf`.
- Pass only the base64, not a `data:` URI: remove the
  `data:application/pdf;base64,` prefix. URL-safe base64 (with `-` and `_`)
  needs converting: `value.replace(/-/g, '+').replace(/_/g, '/')`.
- The string is decoded natively, off the main thread. `onLoad`'s
  `filePath` is `undefined`, as there is no file to hand to the app: iOS
  keeps the decoded document in a temporary file in the app's caches while
  it is shown (deleted when it closes, or at the next launch after a
  crash), and Android deletes its file as soon as the document is open. To
  share or upload the document, export a copy with `ref.exportDocument()`.
- The view reloads when the string's content changes, not when an equal
  string arrives in a new source object. Comparing two equal strings costs
  time in proportion to their length on every render (unless they are the
  very same string value), so keep the string in state instead of building
  it while rendering.
- `PdfCache` does not take base64 sources: there is nothing to download.

Decoding costs little: a 20 MB document opens within tens of milliseconds of
the same file, and neither the JS nor the main thread is held for long (see
[Performance](../performance/results.md#18-base64-sources-2026-10-15)).
Memory is the cost. While the document is shown, its text is held twice, as
your string and as the native copy made from it, each a third larger than
the document. For large documents, or many at once, write them to a file.

### Large documents

Write a large document to a file once and open the file:

```tsx
// Expo: expo-file-system
import { File, Paths } from 'expo-file-system';

const file = new File(Paths.cache, `${id}.pdf`);
file.write(base64, { encoding: 'base64' });

<PdfView source={{ uri: file.uri }} />;
```

```tsx
// Bare React Native: @dr.pogodin/react-native-fs (the react-native-fs API)
import { CachesDirectoryPath, writeFile } from '@dr.pogodin/react-native-fs';

const path = `${CachesDirectoryPath}/${id}.pdf`;
await writeFile(path, base64, 'base64');

<PdfView source={{ filePath: path }} />;
```

Give each document its own file name: a source with the same path does not
reload the view, even after the file is rewritten.

When the base64 comes from your own API (a PDF wrapped in JSON), consider
returning the PDF itself and passing its URL with `headers`: it is then
downloaded natively, with progress, caching and offline reading (see
[Offline reading and caching](offline-and-caching.md)), and is a quarter
smaller than its base64 form.

## Loading state

There is no built-in spinner. Show your own until `onLoad` or `onError`;
for remote documents `onLoadProgress` reports the download:

```tsx
onLoadProgress={({ loadedBytes, totalBytes }) =>
  setProgress(totalBytes === undefined ? undefined : loadedBytes / totalBytes)
}
```

`totalBytes` is `undefined` when the server does not report a size.

See the [API reference](../api.md#errors) for the error codes.

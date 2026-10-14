# Sources and loading

## Sources

```ts
type PdfSource =
  | PdfUriSource        // { uri, headers? } — https://, http://, file://, content:// (Android)
  | { filePath: string } // absolute path
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

`source` does not take base64 or `data:` URIs: the viewer reads files
(Android's `PdfRenderer` needs one), and a document-sized string in a prop
would be processed again on the JS thread on every render. Write the data to
a file once and open the file:

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

When the base64 comes from your own API (a PDF wrapped in JSON), return the
PDF itself and pass its URL with `headers` instead: it is then downloaded
natively, with progress, caching and offline reading (see
[Offline reading and caching](offline-and-caching.md)), and is a
quarter smaller than its base64 form.

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

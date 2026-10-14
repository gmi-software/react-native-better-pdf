# Offline reading and caching

Remote documents are downloaded natively and stored. The source says for
how long, and how often the server is asked for a newer version:

```tsx
const source: PdfUriSource = {
  uri: `https://example.com/documents/${id}.pdf`,
  headers: { Authorization: `Bearer ${token}` },
  cache: 'persistent',        // keep across launches (default 'session')
  cacheKey: `document-${id}`, // the token changes: identify the copy by id
  maxAgeMs: 0,                // ask the server on every open (default: never)
};

<PdfView source={source} onLoad={({ isStale }) => setOfflineBanner(isStale)} />;
```

- **`cache`**: `'session'` keeps the download until the app exits; opening
  the document again does not download it. `'persistent'` keeps it across
  launches where the OS does not purge it and backups do not include it,
  until you remove it.
- **`maxAgeMs`**: once a copy is older, opening the document asks the
  server whether it changed (`If-None-Match` / `If-Modified-Since`). An
  unchanged document is not downloaded again; a changed one replaces the
  copy.
- **Offline**: when the server cannot be reached (no connection, no answer
  within 10 seconds, or a 5xx response), the view shows the stored copy and
  `onLoad` reports `isStale: true`. Without a copy it fails with
  `network-error`. A 4xx response is an error even with a copy.
- **`cacheKey`** identifies the copy instead of `uri` and `headers`. Set it
  when they change between opens — signed URLs, rotating tokens — or the
  copy is not found again. Sources with the same `cacheKey` are the same
  document: a new URL or token does not reload the view.
- A response that is not a PDF (a login page, a captive portal) fails with
  `invalid-pdf` and is never stored.

`PdfCache` manages the copies without a view, with the same source object:

```ts
import { PdfCache, PdfDownloadError } from 'react-native-better-pdf';

// "Download for offline reading", with progress and cancellation.
const controller = new AbortController();
try {
  const entry = await PdfCache.download(source, {
    onProgress: ({ loadedBytes, totalBytes }) => setProgress({ loadedBytes, totalBytes }),
    signal: controller.signal,
  }); // { filePath, byteSize, cache, validatedAtMs }
} catch (error) {
  if (error instanceof PdfDownloadError && error.httpStatus === 401) await refreshToken();
}

await PdfCache.getEntry(source); // the stored copy, or undefined; no network
await PdfCache.remove(source);   // true when there was a copy
await PdfCache.getByteSize();    // all stored copies, in bytes
await PdfCache.clear();          // e.g. on sign-out
```

- `download` resolves at once when the stored copy is fresh enough, and
  joins a download of the same copy already in progress (e.g. by a
  `PdfView`): the file is downloaded once. Unlike the view, it never falls
  back to an old copy — it rejects (`PdfDownloadError` with a `code`, or an
  `AbortError` when cancelled) and the old copy stays.
- Cancelling stops the download unless a `PdfView` is waiting for it too.
- `remove` and `clear` do not disturb documents on screen and do not stop
  downloads in progress (those still store their result). Page images from
  `renderPage` are not affected.
- Session copies from the previous launch are deleted on first use.
- There is no list of stored copies (it would mean keeping URLs and
  headers on disk) and no size limit: keep track of what you download and
  remove what is no longer needed. Downloads run while the app runs; there
  are no background transfers.

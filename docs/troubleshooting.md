# Troubleshooting

| Problem | Solution |
| --- | --- |
| The viewer does not appear in Expo Go | Use a development build (`npx expo run:ios` / `run:android`); Expo Go cannot load native modules. |
| An error about `BetterPdfView` right after installing | Rebuild the native app: `pod install` and a new build on iOS, a new build on Android. |
| Errors about the legacy architecture | Enable the New Architecture; Nitro Views need Fabric. |
| A password-protected document fails with `unsupported-pdf` on Android | The device is below Android 15 without SDK extension 13, where the platform renderer cannot open protected files. |
| Search reports `isSupported: false`, long presses select nothing | Same devices as above; see the [capability matrix](../README.md#capability-matrix). |
| On Android, `onError` arrives while the document is shown | It has `error.page`: one page could not be rendered after `onLoad` (`page-render-failed`, or `out-of-memory`), and the document stays open. Keep the viewer on screen; only `onError` without `page` ends the load. See [Errors](api.md#errors). |
| A remote document fails with `invalid-pdf` | The server answered with something else, often a login page: check the URL and `headers`. |
| A remote document fails with `invalid-source` | Check that the URL is `http(s)://` and the `headers`: often a token read with a trailing newline. `error.message` names the header. |
| A remote document fails with `http-error` and a 3xx `httpStatus` | The server redirects from https to http, which is not followed. Use a URL that stays on https. See [Redirects](guides/sources-and-loading.md#redirects). |
| A remote document fails with `http-error` 401 or 403 after a redirect | The server redirects to another origin (another host or port, or http to https), and `headers` are not sent there. Use the final URL, or one the target accepts without your headers, such as a signed URL. |
| A signed URL or rotating token downloads the document on every open | Set `cacheKey` on the source to identify the stored copy. |
| The document is a base64 string | Pass `source={{ base64 }}` (1.1+); for large documents write it to a file. See [Base64 documents](guides/sources-and-loading.md#base64-documents). |
| On Android, a `content://` document fails with `invalid-source` ("The PDF source is not seekable"), or with `file-not-found` and "Not a whole file" | The provider streams the document or hands out part of a file, which 1.1 and earlier cannot open. Later versions copy it first (see [`content://` URIs on Android](guides/sources-and-loading.md#content-uris-on-android)); on 1.1, copy it to a file and open that. |
| A base64 source fails with `invalid-source` | `error.message` says why: a `data:` URI prefix to remove, URL-safe base64 (`-`, `_`) to convert, or another character that is not base64. |
| Tapping a URL link does nothing | The viewer never opens URLs: open them in `onLinkPress`. |
| `onPageTap` arrives about 300 ms after the tap | It waits for a possible double tap; `enableDoubleTapZoom={false}` reports taps at once. |
| The first page sits under the status bar or a header | The viewer adds no safe-area insets: place it in a `SafeAreaView`, or use `contentInset` for translucent bars. |

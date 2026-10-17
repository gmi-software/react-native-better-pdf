# Troubleshooting

| Problem | Solution |
| --- | --- |
| The viewer does not appear in Expo Go | Use a development build (`npx expo run:ios` / `run:android`); Expo Go cannot load native modules. |
| An error about `BetterPdfView` right after installing | Rebuild the native app: `pod install` and a new build on iOS, a new build on Android. |
| Errors about the legacy architecture | Enable the New Architecture; Nitro Views need Fabric. |
| A password-protected document fails with `unsupported-pdf` on Android | The device is below Android 15 without SDK extension 13, where the platform renderer cannot open protected files. |
| Search reports `isSupported: false`, long presses select nothing | Same devices as above; see the [capability matrix](../README.md#capability-matrix). |
| A remote document fails with `invalid-pdf` | The server answered with something else, often a login page: check the URL and `headers`. |
| A remote document fails with `invalid-source` | Check that the URL is `http(s)://` and the `headers`: often a token read with a trailing newline. `error.message` names the header. |
| A signed URL or rotating token downloads the document on every open | Set `cacheKey` on the source to identify the stored copy. |
| The document is a base64 string | Pass `source={{ base64 }}` (1.1+); for large documents write it to a file. See [Base64 documents](guides/sources-and-loading.md#base64-documents). |
| A base64 source fails with `invalid-source` | `error.message` says why: a `data:` URI prefix to remove, URL-safe base64 (`-`, `_`) to convert, or another character that is not base64. |
| Tapping a URL link does nothing | The viewer never opens URLs: open them in `onLinkPress`. |
| `onPageTap` arrives about 300 ms after the tap | It waits for a possible double tap; `enableDoubleTapZoom={false}` reports taps at once. |
| The first page sits under the status bar or a header | The viewer adds no safe-area insets: place it in a `SafeAreaView`, or use `contentInset` for translucent bars. |

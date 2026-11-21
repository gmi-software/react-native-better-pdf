# Printing

```tsx
const result = await pdf.current?.print({ jobName: 'Invoice 7' });
if (result?.isSubmitted) showToast('Sent to the printer');
```

- Opens the system print dialog for the document as loaded and resolves
  when it closes: `isSubmitted` is `true` when the user printed or saved
  as PDF, `false` when they closed the dialog.
- Prints password-protected documents too: the print system opens files
  without a password, so it gets an unlocked copy that never touches disk
  (in memory on iOS; written straight to the print spooler on Android).
  Works for `content://` sources, which have no file path.
- `jobName` names the job in the print queue and the file when saving as
  PDF; by default the source's file name, or `'Document'`.
- Rejects with a `PdfMethodError` before `onLoad` (`not-loaded`), while
  the view's print dialog is open or, on Android, when the app is not in
  the foreground (`busy`), when the view closes or its `source` changes
  before the dialog opens (`closed`; a filled-in form, and on iOS a
  protected document, is prepared first: seconds when the document is
  long), when the device cannot print (`not-supported`), and on iOS when
  the document's permissions forbid printing (`not-permitted`; Android has
  no API to read them and prints anyway). Also rejects for a view showing
  several sources (`not-supported`, see
  [Several documents as one](sources-and-loading.md#several-documents-as-one)).
  On iPad the dialog opens as a popover from the view.
- On Android a device cannot print when it has no print service (Android
  TV, for example) or when the system opens no dialog, as it does when a
  device policy disables printing. The app is in the foreground while
  `AppState` is `active`; the result of a dialog arrives once the app is
  there again (or 2 s after the dialog closed), so a `print()` called
  from it opens the next dialog. When the system destroys the Activity
  under the dialog, the dialog closes with it and the promise resolves,
  with `isSubmitted: false` unless the job was already submitted.
- Printing a file the app already has needs no viewer: `onLoad`'s
  `filePath` works with `expo-print` or `react-native-print`. This method
  adds what they cannot: protected documents, `content://` sources, and the
  dialog anchored to the viewer on iPad.

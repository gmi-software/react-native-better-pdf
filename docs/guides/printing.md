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
- Rejects before `onLoad`, while the view's print dialog is open, when the
  app is not in the foreground, and on iOS when the device cannot print or
  the document's permissions forbid printing (Android has no API to read
  them and prints anyway). On iPad the dialog opens as a popover from the
  view.
- Printing a file the app already has needs no viewer: `onLoad`'s
  `filePath` works with `expo-print` or `react-native-print`. This method
  adds what they cannot: protected documents, `content://` sources, and the
  dialog anchored to the viewer on iPad.

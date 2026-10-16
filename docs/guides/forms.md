# Forms

```tsx
<PdfView
  ref={pdf}
  source={source}
  onFormChange={({ field }) => console.log(field.name, field)}
/>;

const fields = await pdf.current!.getFormFields();
// [{ name: 'name', type: 'text', value: '', isRequired: true, widgets: [{ page: 0, x, y, width, height }] },
//  { name: 'agree', type: 'checkbox', isChecked: false, … },
//  { name: 'plan', type: 'radio', value: 'basic', options: ['basic', 'pro'], … }, …]

await pdf.current!.setFormFieldValues({
  name: 'Anna', // text, or a combo box's option
  agree: true, // checkbox
  plan: 'pro', // radio: the option
  colours: ['Blue'], // list box: the options
  'address.city': 'Kraków', // fully qualified names
});
const { filePath } = await pdf.current!.exportDocument(); // share or upload it
```

- Text fields, checkboxes, radio buttons, combo and list boxes are filled
  in by touch: iOS with PDFKit's own editing, Android with a text field
  over the form field (kept above the keyboard) and a list to pick
  choices from. `enableFormFilling={false}` leaves them as they are.
- `onFormChange` reports the reader's changes only — at once for a toggle
  or choice, when editing ends for text, never per keystroke — not
  `setFormFieldValues`.
- `getFormFields()` returns every field with its current value, typed by
  `type` (`PdfFormField`); `widgets` are where it is shown, in the same
  coordinates as `onPageTap` — e.g. to place a `PdfPageOverlay` on a
  signature field. Choices are their displayed labels.
- `setFormFieldValues()` sets fields by name, all or none: it rejects,
  changing nothing, for an unknown or read-only field or a value its type
  does not take.
- `exportDocument()` writes the document as shown to a new file (the
  cache directory by default, or `{ filePath }`, which must name a file,
  not a directory), keeping its password protection, off the main thread;
  the source file is never changed. A remote document's `onLoad`
  `filePath` is the library's downloaded copy: exporting over it, or
  anywhere else in that cache, is rejected.
  Printing and `renderPage` show the form as filled in too.
- Password protection of an export: the password that opened the document
  opens the export, with the same permissions (printing, copying, form
  filling), and a document that opens without a password still does.
  Android keeps the document's own encryption. On iOS a filled-in export
  is encrypted anew (AES-128) with the passwords the app knows: its owner
  password is one nobody knows, so its restrictions can no longer be
  lifted, and when the owner password opened the document, only that
  password opens the export (the user password is not known). An export
  without changes is the file as it is on both platforms.
- `getFormFields`, `setFormFieldValues`, `exportDocument`, `print` and
  `renderPage` first end text editing, so what the reader is typing counts.
- Edits live only with the open document: reopening the source shows the
  document as stored, without them. Export to keep them.

import type { PdfCapabilities } from '../PdfCapabilities';
import type { PdfViewProps, PdfViewRef } from '../PdfView';

/**
 * The features this device supports, as returned by
 * {@linkcode PdfCapabilities.get}. All `true` on iOS. On Android all four
 * come from the platform renderer: `true` on Android 15+ and on Android
 * 12–14 with SDK extension 13, `false` on other versions.
 *
 * Viewing, links, the outline, page images and printing work on every
 * device and are not listed. Which annotations are drawn, and whether
 * screen readers read page text, also differ by Android version; see
 * docs/compatibility.md.
 */
export interface PdfDeviceCapabilities {
  /**
   * {@linkcode PdfViewRef.search} can find text. When `false`, every search
   * finds nothing and {@linkcode PdfViewProps.onSearchChange} reports
   * `isSupported: false`: hide search UI.
   */
  search: boolean;
  /**
   * A long press selects text. When `false`, long presses are ignored even
   * with `enableTextSelection` and `onTextSelectionChange` never fires.
   */
  textSelection: boolean;
  /**
   * Forms can be filled in. When `false`, form fields are shown but the
   * reader cannot edit them and `onFormChange` never fires,
   * {@linkcode PdfViewRef.setFormFieldValues} rejects with `not-supported`,
   * and `exportDocument()` copies the file unchanged.
   * {@linkcode PdfViewRef.getFormFields} still works. When `true`, a
   * document without a form, or one shown from several sources, still
   * cannot be filled in.
   */
  formFilling: boolean;
  /**
   * Password-protected documents can be opened. When `false`, they fail
   * with `unsupported-pdf` (never `password-required`), in `PdfView` and
   * `PdfDocument.open` alike, so asking for a password does not help.
   * Documents without protection open as usual.
   */
  passwords: boolean;
}

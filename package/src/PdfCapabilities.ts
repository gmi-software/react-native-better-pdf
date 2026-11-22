import { NitroModules } from 'react-native-nitro-modules';
import type { BetterPdfCache } from './specs/BetterPdfCache.nitro';
import type { PdfDeviceCapabilities } from './types/PdfDeviceCapabilities';

let capabilities: Readonly<PdfDeviceCapabilities> | undefined;

/**
 * Which features this device supports, known before any document is
 * opened: decide whether to show search, text selection, form filling or a
 * password prompt before showing them.
 *
 * @example
 * ```tsx
 * const { search } = PdfCapabilities.get();
 * {search && <SearchButton />}
 * ```
 */
export const PdfCapabilities = {
  /**
   * The features this device supports; see
   * {@linkcode PdfDeviceCapabilities} for what each one gates. Synchronous,
   * needs no `PdfView`, and returns the same frozen object on every call:
   * the answer cannot change while the app runs.
   */
  get(): Readonly<PdfDeviceCapabilities> {
    if (capabilities === undefined) {
      const native =
        NitroModules.createHybridObject<BetterPdfCache>('BetterPdfCache');
      const { search, textSelection, formFilling, passwords } =
        native.getCapabilities();
      capabilities = Object.freeze({
        search,
        textSelection,
        formFilling,
        passwords,
      });
    }
    return capabilities;
  },
};

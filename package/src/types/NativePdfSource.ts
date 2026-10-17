import type { PdfCacheMode } from './PdfCacheMode';

/**
 * The document source as received by the native view. The public
 * `PdfSource` union accepted by `PdfView` is normalized into this shape, so
 * native code handles a single representation.
 *
 * `uri` schemes understood natively:
 * - `http://`, `https://` — downloaded natively into the app's cache (see
 *   `cache`, `maxAgeMs` and `cacheKey`).
 * - `file://` or an absolute path — opened in place.
 * - `content://` (Android) — opened through the ContentResolver.
 * - a bare resource name (Android release builds of `require('./x.pdf')`) —
 *   resolved from `res/raw`.
 */
export interface NativePdfSource {
  /** Location of the document; see the supported schemes above. */
  uri: string;
  /** A `{ base64 }` source's text in a native-owned buffer; `uri` is `''` then. */
  base64?: ArrayBuffer;
  /** Request headers for remote sources. Ignored for local sources. */
  headers?: Record<string, string>;
  /** Remote sources: how long the downloaded copy is kept. Default `'session'`. */
  cache?: PdfCacheMode;
  /** Remote sources: age after which the copy is revalidated. Default: never. */
  maxAgeMs?: number;
  /** Remote sources: identifies the downloaded copy instead of `uri` and `headers`. */
  cacheKey?: string;
}

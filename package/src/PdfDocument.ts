import { Image } from 'react-native';
import { NitroModules } from 'react-native-nitro-modules';
import { abortable, abortReason, untilAborted } from './abort';
import { buildOutlineTree } from './outline';
import { toNativeSource } from './nativeSource';
import { PdfOpenError } from './PdfOpenError';
import { normalizeSource, sourcePassword, type PdfSource } from './source';
import type { BetterPdfDocument } from './specs/BetterPdfDocument.nitro';
import type { PdfDocumentMetadata } from './types/PdfDocumentMetadata';
import type { PdfOpenOptions } from './types/PdfOpenOptions';
import type { PdfOpenedDocument, PdfOpenResult } from './types/PdfOpenResult';
import type { PdfOutlineItem } from './types/PdfOutlineItem';
import type { PdfPageImage } from './types/PdfPageImage';
import type { PdfPageSize } from './types/PdfPageSize';
import type { PdfRenderPageOptions } from './types/PdfRenderPageOptions';

/**
 * A PDF opened without a view: its page count, metadata, page sizes and
 * outline, and page images — e.g. for a document list with covers, or
 * thumbnails. Opens sources like `PdfView` (downloads, cache options,
 * passwords) and reads them with the same code as the view's ref.
 *
 * Call {@linkcode PdfDocument.close} when done: the document holds the file
 * open until then (or until JS garbage-collects it, which can take long).
 *
 * @example
 * ```ts
 * const document = await PdfDocument.open({ uri, cache: 'persistent' });
 * try {
 *   const { title } = await document.getMetadata();
 *   const cover = await document.renderPage(0, { width: 300 });
 *   setItem({ title, pageCount: document.pageCount, cover: cover.uri });
 * } finally {
 *   document.close();
 * }
 * ```
 */
export class PdfDocument {
  /** Number of pages. */
  readonly pageCount: number;
  /**
   * Absolute path of the opened file, as in `PdfView`'s `onLoad`: the
   * downloaded copy for remote sources; `undefined` for `{ base64 }` and
   * Android `content://` sources.
   */
  readonly filePath?: string;
  /**
   * `true` when a remote document was opened from a downloaded copy older
   * than its `maxAgeMs` because the server could not be reached, as in
   * `PdfView`'s `onLoad`.
   */
  readonly isStale: boolean;
  private native: BetterPdfDocument | undefined;

  private constructor(native: BetterPdfDocument, opened: PdfOpenedDocument) {
    this.native = native;
    this.pageCount = opened.pageCount;
    if (opened.filePath !== undefined) this.filePath = opened.filePath;
    this.isStale = opened.isStale;
  }

  /**
   * Opens `source` — any one source `PdfView` accepts — off the JS thread,
   * downloading a remote document (or using its stored copy) like the view.
   *
   * @throws {PdfOpenError} when the document cannot be opened; match on
   *   `code`, e.g. `'password-required'`.
   */
  static async open(
    source: PdfSource,
    options: PdfOpenOptions = {},
  ): Promise<PdfDocument> {
    const { password, onProgress, signal } = options;
    if (signal?.aborted) throw abortReason(signal);
    const native =
      NitroModules.createHybridObject<BetterPdfDocument>('BetterPdfDocument');
    // Async, so that a synchronous throw becomes a rejection too.
    const result = (async () =>
      native.open(
        toNativeSource(normalizeSource(source, Image.resolveAssetSource)),
        sourcePassword(source) ?? password,
        untilAborted(onProgress, signal),
      ))();
    const settle = (value: PdfOpenResult): PdfDocument => {
      if (value.document !== undefined) {
        return new PdfDocument(native, value.document);
      }
      native.close();
      if (value.error !== undefined) throw new PdfOpenError(value.error);
      throw new Error('Opening ended without a result');
    };
    // Also closes the native object on an abort.
    const fail = (error: unknown): never => {
      native.close();
      throw error;
    };
    return abortable(result, signal).then(settle, fail);
  }

  /**
   * The document's title, author, dates and other information. Read on
   * first call, then cached. Rejects on Android for encrypted documents
   * (their metadata is encrypted), and after {@linkcode close}.
   */
  getMetadata(): Promise<PdfDocumentMetadata> {
    return this.call((native) => native.getMetadata());
  }

  /**
   * Every page's size as displayed, in PDF points, indexed by zero-based
   * page; see `PdfViewRef.getPageSizes`. Cached. Rejects after
   * {@linkcode close}.
   */
  getPageSizes(): Promise<PdfPageSize[]> {
    return this.call((native) => native.getPageSizes());
  }

  /**
   * The table of contents, or `[]` when there is none; see
   * `PdfViewRef.getOutline`. Cached. Rejects on Android for encrypted
   * documents, and after {@linkcode close}.
   */
  async getOutline(): Promise<PdfOutlineItem[]> {
    return buildOutlineTree(await this.call((native) => native.getOutline()));
  }

  /**
   * Renders zero-based `page` to a PNG or JPEG file; see
   * `PdfViewRef.renderPage`. Calls run one after another. Rejects for a page
   * outside the document, a `width` outside 1–4096 px, and after
   * {@linkcode close}.
   */
  renderPage(
    page: number,
    options: PdfRenderPageOptions,
  ): Promise<PdfPageImage> {
    return this.call((native) => native.renderPage(page, options));
  }

  /**
   * Releases the document. Calls already running finish; later calls
   * reject. Safe to call more than once.
   */
  close(): void {
    const native = this.native;
    this.native = undefined;
    native?.close();
  }

  private async call<T>(
    read: (native: BetterPdfDocument) => Promise<T>,
  ): Promise<T> {
    const native = this.native;
    if (native === undefined) throw new Error('The document is closed');
    return read(native);
  }
}

import type { PdfLoadedSource } from './PdfLoadedSource';

/**
 * Delivered to `PdfView`'s `onLoad` once a document is open and ready to
 * display.
 */
export interface PdfLoadEvent {
  /** Number of pages in the document: of every source together. */
  pageCount: number;
  /** Width of the first page in PDF points (1/72"). */
  firstPageWidth: number;
  /** Height of the first page in PDF points (1/72"). */
  firstPageHeight: number;
  /**
   * The source's {@linkcode PdfLoadedSource.filePath}; `undefined` with
   * several sources, which no one file holds.
   */
  filePath?: string;
  /** `true` when any source is {@linkcode PdfLoadedSource.isStale}. */
  isStale: boolean;
  /**
   * Every source in `source` order, with where its pages are in the
   * document: one entry for a single source.
   */
  sources: PdfLoadedSource[];
}

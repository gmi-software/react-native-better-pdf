import type { PdfViewProps, PdfViewRef } from '../PdfView';

/**
 * The state of the text search started with {@linkcode PdfViewRef.search}.
 *
 * @see {@linkcode PdfViewProps.onSearchChange}
 */
export interface PdfSearchChangeEvent {
  /** The query being searched; `''` after the search was cleared. */
  query: string;
  /** Matches found so far (all of them once `isComplete`). */
  matchCount: number;
  /**
   * Zero-based index of the current (emphasised) match among all matches,
   * in document order. `undefined` while none has been found.
   */
  currentMatch?: number;
  /** Zero-based page of the current match. */
  currentPage?: number;
  /** The whole document has been searched. */
  isComplete: boolean;
  /**
   * `false` where the device cannot search: Android before 15, except
   * Android 12–14 with SDK extension 13. The same for every search on a
   * device: read `PdfCapabilities.get().search` to hide search UI before a
   * search starts.
   */
  isSupported: boolean;
}

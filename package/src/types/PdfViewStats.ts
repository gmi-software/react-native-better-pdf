/**
 * Diagnostic counters returned by `PdfViewRef.getStats()`. Cheap to read,
 * intended for benchmarks and tests, not for driving UI. Fields that a
 * platform does not track are `-1`.
 */
export interface PdfViewStats {
  /** Number of pages in the open document, 0 before load. */
  pageCount: number;
  /** Pages currently holding a rendered bitmap (Android); -1 on iOS. */
  residentPages: number;
  /** Bytes held by the native render cache (Android); -1 on iOS. */
  cacheBytes: number;
  /**
   * Current cache budget in bytes (Android); -1 on iOS. From Android 8 it
   * counts every page at 4 bytes per pixel and grey pages take a quarter of
   * that, so `cacheBytes` can stay well below it; before Android 8 it counts
   * the bytes pages take, a quarter the budget for grey documents.
   */
  cacheBudgetBytes: number;
  /**
   * Pages that were already rendered when they scrolled into view
   * (Android); -1 on iOS.
   */
  cacheHits: number;
  /**
   * Pages that were not rendered yet when they scrolled into view
   * (Android); -1 on iOS.
   */
  cacheMisses: number;
  /**
   * Bitmaps allocated (not reused) since the document was loaded
   * (Android); -1 on iOS.
   */
  bitmapAllocations: number;
  /** Page renders completed since the document was loaded (Android); -1 on iOS. */
  pagesRendered: number;
  /**
   * Render requests dropped before execution because the page scrolled away
   * (Android); -1 on iOS.
   */
  rendersCancelled: number;
  /** Mean wall time of a native page render in milliseconds, -1 if not tracked. */
  meanRenderMs: number;
}

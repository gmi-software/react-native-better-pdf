import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules';
import type { NativePdfFormChangeEvent } from '../types/NativePdfFormChangeEvent';
import type { NativePdfFormField } from '../types/NativePdfFormField';
import type { NativePdfFormValue } from '../types/NativePdfFormValue';
import type { NativeContentInset } from '../types/NativeContentInset';
import type { NativePdfSource } from '../types/NativePdfSource';
import type { PdfColorScheme } from '../types/PdfColorScheme';
import type { PdfError } from '../types/PdfError';
import type { PdfExportResult } from '../types/PdfExportResult';
import type { PdfFitPolicy } from '../types/PdfFitPolicy';
import type { PdfInitialPosition } from '../types/PdfInitialPosition';
import type { PdfLinkPressEvent } from '../types/PdfLinkPressEvent';
import type { PdfLoadProgressEvent } from '../types/PdfLoadProgressEvent';
import type { PdfLoadEvent } from '../types/PdfLoadEvent';
import type { PdfOutlineEntry } from '../types/PdfOutlineEntry';
import type { PdfOverlayPlacement } from '../types/PdfOverlayPlacement';
import type { PdfPageChangeEvent } from '../types/PdfPageChangeEvent';
import type { PdfPageImage } from '../types/PdfPageImage';
import type { PdfPageSize } from '../types/PdfPageSize';
import type { PdfPageTapEvent } from '../types/PdfPageTapEvent';
import type { PdfPrintResult } from '../types/PdfPrintResult';
import type { PdfRenderPageOptions } from '../types/PdfRenderPageOptions';
import type { PdfScaleChangeEvent } from '../types/PdfScaleChangeEvent';
import type { PdfSearchChangeEvent } from '../types/PdfSearchChangeEvent';
import type { PdfSpreadMode } from '../types/PdfSpreadMode';
import type { PdfTextSelectionChangeEvent } from '../types/PdfTextSelectionChangeEvent';
import type { PdfViewport } from '../types/PdfViewport';
import type { PdfViewportChangeEvent } from '../types/PdfViewportChangeEvent';
import type { PdfViewStats } from '../types/PdfViewStats';

/**
 * Props of the native `BetterPdfView`. Apps use the `PdfView` component,
 * which memoizes the source and keeps callbacks identity-stable.
 */
export interface PdfViewProps extends HybridViewProps {
  /** The documents shown, one after the other, each with its password ('' for none). */
  sources?: NativePdfSource[];
  /** Position applied once a document finishes loading. */
  initialPosition?: PdfInitialPosition;
  /** Relative to the fitted page. Default 1. */
  minScale?: number;
  /** Relative to the fitted page. Default 4. */
  maxScale?: number;
  /** Gap between pages in dp / pt. Default 8. */
  pageSpacing?: number;
  /** Pages follow each other left to right. Default false. */
  horizontal?: boolean;
  /** Snap to one page at a time. Default false. */
  enablePaging?: boolean;
  /** Horizontal layouts run right to left (first page on the right); iOS: paged only. Default false. */
  enableRTL?: boolean;
  /** Show only the page at `initialPage` / `goToPage`, without touch handling. Default false. */
  singlePage?: boolean;
  /** Default 'width' when vertical and continuous, otherwise 'page'. */
  fitPolicy?: PdfFitPolicy;
  /** Facing pages side by side; vertical continuous layouts only. Default 'none'. */
  spreadMode?: PdfSpreadMode;
  /** 'dark' shows pages with lightness inverted and hues kept. Default 'light'. */
  colorScheme?: PdfColorScheme;
  /** Space in dp / pt pages scroll under, top and bottom; vertical continuous layouts only. */
  contentInset?: NativeContentInset;
  /** `nativeID` of the React view holding `PdfPageOverlay`s, a sibling above this view. */
  overlayLayerId?: string;
  /** Overlay views in that layer, placed on their pages natively every frame. */
  overlays?: PdfOverlayPlacement[];
  /** Double tap toggles zoom; when false, taps are reported without waiting for a second tap. Default true. */
  enableDoubleTapZoom?: boolean;
  /** Drags scroll the document (else they go to parent views). Default true. */
  scrollEnabled?: boolean;
  /** Screen-reader page label; {page} (1-based) and {pageCount} are replaced. Default 'Page {page} of {pageCount}'. */
  pageAccessibilityLabel?: string;
  /** Default true. */
  showsVerticalScrollIndicator?: boolean;
  /** Default true. Only visible while zoomed wider than the view. */
  showsHorizontalScrollIndicator?: boolean;
  /** Long press selects text (Android: API 35+). Default true. */
  enableTextSelection?: boolean;
  /** Form fields can be filled in by touch (Android: API 35+ or SDK extension 13). Default true. */
  enableFormFilling?: boolean;
  /** Highlight of search matches, ARGB (`processColor` output). */
  searchHighlightColor?: number;
  /** Highlight of the current search match, ARGB (`processColor` output). */
  searchCurrentHighlightColor?: number;
  onLoad?: (event: PdfLoadEvent) => void;
  onLoadProgress?: (event: PdfLoadProgressEvent) => void;
  onError?: (error: PdfError) => void;
  onPageChange?: (event: PdfPageChangeEvent) => void;
  onLinkPress?: (event: PdfLinkPressEvent) => void;
  onPageTap?: (event: PdfPageTapEvent) => void;
  /** Once per settled zoom change (gesture end, zoomTo), never per frame. */
  onScaleChange?: (event: PdfScaleChangeEvent) => void;
  /** Search progress and navigation; at most 10 per second while searching, plus one on completion. */
  onSearchChange?: (event: PdfSearchChangeEvent) => void;
  /** Once per settled scroll or zoom (and once after loading), never per frame. */
  onViewportChange?: (event: PdfViewportChangeEvent) => void;
  /** Once per change the reader makes to a form field: a toggle or choice, or text when editing ends. */
  onFormChange?: (event: NativePdfFormChangeEvent) => void;
  /** Once per settled selection change (gesture end), never per handle move. */
  onTextSelectionChange?: (event: PdfTextSelectionChangeEvent) => void;
}

/** Imperative methods of the native `BetterPdfView`. */
export interface PdfViewMethods extends HybridViewMethods {
  /** Scrolls so that `page` (zero-based, clamped) is at the top of the viewport. */
  goToPage(page: number, animated: boolean): void;
  /** Zooms around the viewport center. `scale` is relative to the fitted page and clamped to [minScale, maxScale]. */
  zoomTo(scale: number, animated: boolean): void;
  getStats(): PdfViewStats;
  /** Jumps (without animation) so `viewport`'s page point is at the top-left corner, at its zoom. */
  goToViewport(viewport: PdfViewport): void;
  /**
   * The document outline, flattened depth-first. Parsed off the main thread
   * on first call and cached. Rejects before a document has loaded, and on
   * Android for encrypted documents.
   */
  getOutline(): Promise<PdfOutlineEntry[]>;
  /**
   * Every page's displayed size, by zero-based page. Read off the main
   * thread on first call and cached. Rejects before a document has loaded.
   */
  getPageSizes(): Promise<PdfPageSize[]>;
  /**
   * Renders zero-based `page` to an image file off the main thread. Rejects
   * before a document has loaded, and for a page or width out of range.
   */
  renderPage(
    page: number,
    options: PdfRenderPageOptions,
  ): Promise<PdfPageImage>;
  /**
   * Opens the system print dialog for the loaded document (unlocked when it
   * has a password) and resolves when the dialog closes. Rejects before a
   * document has loaded and while a print dialog of this view is open.
   */
  print(jobName: string): Promise<PdfPrintResult>;
  /**
   * Every form field with its current value, in the document's field order.
   * Rejects before a document has loaded.
   */
  getFormFields(): Promise<NativePdfFormField[]>;
  /**
   * Sets form fields' values; all or none: rejects without changing any
   * for an unknown or read-only field or a value its type does not take.
   * Rejects before a document has loaded and where filling is unsupported.
   */
  setFormFieldValues(values: NativePdfFormValue[]): Promise<void>;
  /**
   * Writes the document with its form as filled in to `filePath` ('' for a
   * new file in the cache directory). Rejects before a document has loaded.
   */
  exportDocument(filePath: string): Promise<PdfExportResult>;
  /** Starts a case-insensitive text search, replacing any previous one; '' clears it. */
  search(query: string): void;
  /** Moves to the next match (wrapping) and scrolls it into view. */
  searchNext(): void;
  /** Moves to the previous match (wrapping) and scrolls it into view. */
  searchPrevious(): void;
  /** Ends the search and removes its highlights. */
  clearSearch(): void;
}

export type BetterPdfView = HybridView<PdfViewProps, PdfViewMethods>;

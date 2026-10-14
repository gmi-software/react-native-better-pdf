import {
  useId,
  useImperativeHandle,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type Ref,
} from 'react';
import {
  Image,
  StyleSheet,
  View,
  type ColorValue,
  type ViewProps,
} from 'react-native';
import {
  callback,
  getHostComponent,
  type HybridRef,
} from 'react-native-nitro-modules';
import BetterPdfViewConfig from '../nitrogen/generated/shared/json/BetterPdfViewConfig.json';
import {
  toFormField,
  toNativeFilePath,
  toNativeFormValues,
} from './formFields';
import { nativeColor } from './nativeColor';
import { OverlayRegistryContext, type OverlayRegistry } from './PdfPageOverlay';
import { buildOutlineTree } from './outline';
import { defaultPrintJobName } from './printJobName';
import { normalizeSource, sourceKey, type PdfSource } from './source';
import type {
  PdfViewMethods,
  PdfViewProps as NativeProps,
} from './specs/BetterPdfView.nitro';
import type { NativeContentInset } from './types/NativeContentInset';
import type { NativePdfFormChangeEvent } from './types/NativePdfFormChangeEvent';
import type { NativePdfSource } from './types/NativePdfSource';
import type { PdfColorScheme } from './types/PdfColorScheme';
import type { PdfContentInset } from './types/PdfContentInset';
import type { PdfError } from './types/PdfError';
import type { PdfExportOptions } from './types/PdfExportOptions';
import type { PdfExportResult } from './types/PdfExportResult';
import type { PdfFitPolicy } from './types/PdfFitPolicy';
import type { PdfFormChangeEvent } from './types/PdfFormChangeEvent';
import type { PdfFormField } from './types/PdfFormField';
import type { PdfFormFieldValue } from './types/PdfFormFieldValue';
import type { PdfInitialPosition } from './types/PdfInitialPosition';
import type { PdfLinkPressEvent } from './types/PdfLinkPressEvent';
import type { PdfLoadEvent } from './types/PdfLoadEvent';
import type { PdfLoadProgressEvent } from './types/PdfLoadProgressEvent';
import type { PdfOutlineItem } from './types/PdfOutlineItem';
import type { PdfOverlayPlacement } from './types/PdfOverlayPlacement';
import type { PdfPageChangeEvent } from './types/PdfPageChangeEvent';
import type { PdfPageImage } from './types/PdfPageImage';
import type { PdfPageSize } from './types/PdfPageSize';
import type { PdfPageTapEvent } from './types/PdfPageTapEvent';
import type { PdfPrintOptions } from './types/PdfPrintOptions';
import type { PdfPrintResult } from './types/PdfPrintResult';
import type { PdfRenderPageOptions } from './types/PdfRenderPageOptions';
import type { PdfScaleChangeEvent } from './types/PdfScaleChangeEvent';
import type { PdfSearchChangeEvent } from './types/PdfSearchChangeEvent';
import type { PdfSpreadMode } from './types/PdfSpreadMode';
import type { PdfTextSelectionChangeEvent } from './types/PdfTextSelectionChangeEvent';
import type { PdfViewport } from './types/PdfViewport';
import type { PdfViewportChangeEvent } from './types/PdfViewportChangeEvent';
import type { PdfViewStats } from './types/PdfViewStats';

const NativeBetterPdfView = getHostComponent<NativeProps, PdfViewMethods>(
  'BetterPdfView',
  () => BetterPdfViewConfig,
);

type NativeRef = HybridRef<NativeProps, PdfViewMethods>;

/** `rgba(255, 214, 0, 0.4)` and `rgba(255, 140, 0, 0.6)` as ARGB. */
const DEFAULT_SEARCH_HIGHLIGHT_COLOR = 0x66ffd600;
const DEFAULT_SEARCH_CURRENT_HIGHLIGHT_COLOR = 0x99ff8c00;

export interface NavigationOptions {
  /** Default `true`. */
  animated?: boolean;
}

export interface PdfViewRef {
  /** Scrolls so `page` (zero-based, clamped) is at the top of the viewport. */
  goToPage(page: number, options?: NavigationOptions): void;
  /** Zooms around the viewport center. Relative to the fitted page, clamped to [minScale, maxScale]. */
  zoomTo(scale: number, options?: NavigationOptions): void;
  /**
   * Jumps, without animation, to a position saved from
   * {@linkcode PdfViewProps.onViewportChange}: its page point at the view's
   * top-left corner, at its zoom. The page is clamped to the document and
   * the position to the scrollable area. Paged layouts settle on the page
   * unless zoomed in. Ignored before {@linkcode PdfViewProps.onLoad}.
   */
  goToViewport(viewport: PdfViewport): void;
  /** Native diagnostics. `null` before the native view has mounted. */
  getStats(): PdfViewStats | null;
  /**
   * The document's table of contents (outline / bookmarks), or `[]` when it
   * has none. Read off the main thread on first call, then cached. Rejects
   * before {@linkcode PdfViewProps.onLoad} and, on Android, for encrypted
   * documents (their titles are encrypted).
   */
  getOutline(): Promise<PdfOutlineItem[]>;
  /**
   * Every page's size as displayed (crop box, rotation applied) in PDF
   * points, indexed by zero-based page — e.g. to lay out overlays or
   * thumbnails of a document with mixed page sizes. Read off the main thread
   * on first call, then cached; cheap even for long documents (no page is
   * rendered). Rejects before {@linkcode PdfViewProps.onLoad}.
   */
  getPageSizes(): Promise<PdfPageSize[]>;
  /**
   * Renders zero-based `page` to a PNG or JPEG file — for thumbnails, share
   * previews or covers — on a white background, with the annotations the
   * viewer draws. Runs off the main thread on a separate renderer, so the
   * view keeps scrolling. Rejects before {@linkcode PdfViewProps.onLoad},
   * for a page outside the document, and for a `width` outside 1–4096 px.
   */
  renderPage(
    page: number,
    options: PdfRenderPageOptions,
  ): Promise<PdfPageImage>;
  /**
   * Opens the system print dialog for the loaded document and resolves when
   * it closes, with whether the user printed (or saved as PDF). Prints the
   * document as loaded: unlocked when it was opened with a
   * {@linkcode PdfViewProps.password}, the downloaded copy of a remote
   * source. Rejects before {@linkcode PdfViewProps.onLoad}, while a print
   * dialog of this view is open, when the device cannot print or the app is
   * not in the foreground, and on iOS when the document does not permit
   * printing.
   */
  print(options?: PdfPrintOptions): Promise<PdfPrintResult>;
  /**
   * Every form (AcroForm) field with its current value, filled in by the
   * reader or `setFormFieldValues`, in the document's field order; `[]`
   * without a form. Rejects before {@linkcode PdfViewProps.onLoad}.
   */
  getFormFields(): Promise<PdfFormField[]>;
  /**
   * Fills form fields in, by name: `{ name: 'Anna', agree: true }` (see
   * {@linkcode PdfFormFieldValue}). All or none: rejects without changing a
   * field for an unknown or read-only field, a value its type does not
   * take or an option it does not have. Does not send
   * {@linkcode PdfViewProps.onFormChange}. Rejects before
   * {@linkcode PdfViewProps.onLoad} and where filling is unsupported
   * (Android below 15 without SDK extension 13).
   */
  setFormFieldValues(
    values: Readonly<Record<string, PdfFormFieldValue>>,
  ): Promise<void>;
  /**
   * Writes the document as shown — its form filled in — to a new PDF file,
   * e.g. to share or upload it; the source file is never changed (a
   * `filePath` naming it, or a directory, is rejected). Keeps the
   * document's password protection. Rejects before
   * {@linkcode PdfViewProps.onLoad}.
   */
  exportDocument(options?: PdfExportOptions): Promise<PdfExportResult>;
  /**
   * Searches the document's text (case-insensitive), replacing any previous
   * search; `''` clears it. Results arrive through
   * {@linkcode PdfViewProps.onSearchChange} as pages are searched. All
   * matches are highlighted; the first match at or after the current page
   * becomes current and is scrolled into view.
   */
  search(query: string): void;
  /** Makes the next match current (wrapping to the first) and scrolls it into view. */
  searchNext(): void;
  /** Makes the previous match current (wrapping to the last) and scrolls it into view. */
  searchPrevious(): void;
  /** Ends the search and removes its highlights. */
  clearSearch(): void;
}

export interface PdfViewProps extends ViewProps {
  source: PdfSource;
  password?: string;
  /** Zero-based page shown when a document finishes loading. Default `0`. */
  initialPage?: number;
  /**
   * Zoom applied once when a document finishes loading, relative to the
   * fitted page and clamped to [`minScale`, `maxScale`]; like
   * {@linkcode PdfViewProps.initialPage}, later changes have no effect (use
   * `ref.zoomTo()`). Not reported through
   * {@linkcode PdfViewProps.onScaleChange}. Default `1`.
   */
  initialScale?: number;
  /**
   * Position shown when a document finishes loading, e.g. where the reader
   * left off (from {@linkcode PdfViewProps.onViewportChange}). Takes
   * precedence over {@linkcode PdfViewProps.initialPage} and
   * {@linkcode PdfViewProps.initialScale}; like them, later changes have no
   * effect (use `ref.goToViewport()`).
   */
  initialViewport?: PdfViewport;
  /** Relative to the fitted page (see {@linkcode PdfViewProps.fitPolicy}). Default `1`. */
  minScale?: number;
  /** Relative to the fitted page (see {@linkcode PdfViewProps.fitPolicy}). Default `4`. */
  maxScale?: number;
  /** Gap between pages in dp / pt. Default `8`. */
  pageSpacing?: number;
  /**
   * Lay pages out left to right and scroll horizontally. Default `false`.
   */
  horizontal?: boolean;
  /**
   * Show one page at a time: swipes snap to the previous or next page. A
   * page longer than the view scrolls freely before moving on. While zoomed
   * in, panning is free; zooming back out snaps to a page again. Swipes
   * follow {@linkcode PdfViewProps.horizontal}: vertical by default.
   * Default `false`.
   */
  enablePaging?: boolean;
  /**
   * Right-to-left reading order for horizontal layouts (Arabic, Hebrew,
   * manga): the first page is on the right and reading continues to the
   * left, with or without {@linkcode PdfViewProps.enablePaging}. Page
   * numbers keep document order. No effect on vertical layouts or
   * {@linkcode PdfViewProps.singlePage}. Default `false`.
   *
   * iOS: PDFKit honours right-to-left only with
   * {@linkcode PdfViewProps.enablePaging}; a continuous horizontal layout
   * stays left to right.
   */
  enableRTL?: boolean;
  /**
   * Show only one page, as a static image — for thumbnails and previews.
   * The page is {@linkcode PdfViewProps.initialPage}; `ref.goToPage()`
   * switches it. The view ignores touches, so it can sit inside a
   * `Pressable` or a scrolling list: no scrolling, zooming,
   * {@linkcode PdfViewProps.onPageTap} or {@linkcode PdfViewProps.onLinkPress}.
   * Other pages are never rendered. Takes precedence over
   * {@linkcode PdfViewProps.horizontal} and
   * {@linkcode PdfViewProps.enablePaging}. Default `false`.
   */
  singlePage?: boolean;
  /**
   * How pages are scaled to the view at zoom `1`; `minScale`, `maxScale` and
   * `zoomTo` are relative to this fit. Default `'width'` for the vertical
   * continuous layout, `'page'` with {@linkcode PdfViewProps.horizontal},
   * {@linkcode PdfViewProps.enablePaging} or
   * {@linkcode PdfViewProps.singlePage}.
   *
   * Android fits every page individually. iOS (PDFKit) applies one scale to
   * the whole continuous document: `'width'` fits the widest page when
   * scrolling vertically; otherwise the first page is fitted.
   */
  fitPolicy?: PdfFitPolicy;
  /**
   * Shows facing pages side by side, e.g. on a tablet in landscape (pass it
   * from `useWindowDimensions()` to switch with the orientation): each page
   * fits half the view's width, and a page without a partner (the cover in
   * `'book'`, an odd page at the end) is centred. Vertical continuous
   * layouts only: ignored with {@linkcode PdfViewProps.horizontal},
   * {@linkcode PdfViewProps.enablePaging} and
   * {@linkcode PdfViewProps.singlePage}. Default `'none'`.
   *
   * {@linkcode PdfViewProps.onPageChange} reports the first page of the
   * spread under the viewport centre, or the last page once scrolled to the
   * end.
   */
  spreadMode?: PdfSpreadMode;
  /**
   * Shows pages in dark colours for reading in the dark: lightness
   * inverted, hues kept (white paper turns black, black text white, a red
   * heading stays red; photos are inverted too). The area around pages is
   * the view's `backgroundColor`. Page images (`renderPage`) and printing
   * keep the document's colours. On iOS annotations keep their own colours
   * (PDFKit draws them apart from the page); highlights, underlines and
   * strike-outs are lightened onto the dark page.
   *
   * Takes `useColorScheme()`'s value as it is, to follow the system:
   * anything but `'dark'` (also `'unspecified'` and `null`) shows the
   * document's colours. Default `'light'`.
   */
  colorScheme?: PdfColorScheme | 'unspecified' | null;
  /**
   * Space in dp / pt kept clear at the top and bottom, e.g. for a
   * translucent header or toolbar over the viewer: pages scroll under it,
   * but at rest the first page starts below `top` and the document ends
   * above `bottom`. Pages, positions (`goToPage`, `initialViewport`,
   * {@linkcode PdfViewProps.onViewportChange}), the current page and the
   * end of the document all refer to the area between the insets, and
   * `fitPolicy` `'height'` / `'page'` fit pages into it. Not scaled by zoom.
   * Vertical continuous layouts only (facing pages included): ignored with
   * {@linkcode PdfViewProps.horizontal}, {@linkcode PdfViewProps.enablePaging}
   * and {@linkcode PdfViewProps.singlePage}. Default none.
   */
  contentInset?: PdfContentInset;
  /**
   * Double tap toggles zoom (Android: fitted page ↔ 2×; iOS: PDFKit's own
   * zoom step, e.g. 1.7× for an A4 page on an iPhone). When `false`,
   * double taps do nothing and {@linkcode PdfViewProps.onPageTap} fires
   * right away instead of after the double-tap window (~300 ms). Pinch zoom
   * and `zoomTo()` are unaffected. Default `true`.
   */
  enableDoubleTapZoom?: boolean;
  /**
   * When `false`, dragging does not scroll the document: the drag goes to
   * parent views instead (e.g. a surrounding `ScrollView` scrolls). Pinch
   * and double-tap zoom, {@linkcode PdfViewProps.onPageTap},
   * {@linkcode PdfViewProps.onLinkPress} and `ref.goToPage()` / `zoomTo()`
   * keep working. Same name and default as React Native's `ScrollView`.
   * Default `true`.
   */
  scrollEnabled?: boolean;
  /**
   * How screen readers (VoiceOver, TalkBack) name a page, e.g. when it comes
   * into view: `{page}` is replaced by the 1-based page number and
   * `{pageCount}` by the number of pages. Localise it for your app's
   * language. Default `'Page {page} of {pageCount}'`.
   */
  pageAccessibilityLabel?: string;
  /** Show the vertical scroll indicator while scrolling. Default `true`. */
  showsVerticalScrollIndicator?: boolean;
  /**
   * Show the horizontal scroll indicator while scrolling a page that is
   * zoomed wider than the view. Default `true`.
   */
  showsHorizontalScrollIndicator?: boolean;
  /**
   * Let the user select text, as in a read-only text field: a long press
   * selects a word, handles adjust the selection and the system menu offers
   * Copy and Select all. `false` turns selection off entirely. Default
   * `true`.
   *
   * iOS uses PDFKit's selection, which can span pages. Android draws its
   * own in the app theme's colours; a selection stays on one page, and
   * copying ends it. Android: needs Android 15+, or Android 12–14 with SDK
   * extension 13; other devices ignore long presses.
   */
  enableTextSelection?: boolean;
  /**
   * Form fields (text fields, checkboxes, radio buttons, choices) can be
   * filled in by touch; `false` shows them as they are. Read and set values
   * with `ref.getFormFields()` / `ref.setFormFieldValues()` either way.
   * Android: needs Android 15+, or Android 12–14 with SDK extension 13.
   * Default `true`.
   */
  enableFormFilling?: boolean;
  /**
   * Background of every search match. Use a translucent colour so the text
   * stays readable. `PlatformColor` and `DynamicColorIOS` are not supported
   * (the default is used). Default `'rgba(255, 214, 0, 0.4)'` (yellow).
   */
  searchHighlightColor?: ColorValue;
  /**
   * Background of the current search match (the one `searchNext()` /
   * `searchPrevious()` moved to), drawn instead of
   * {@linkcode PdfViewProps.searchHighlightColor}. Default
   * `'rgba(255, 140, 0, 0.6)'` (orange).
   */
  searchCurrentHighlightColor?: ColorValue;
  onLoad?: (event: PdfLoadEvent) => void;
  /**
   * Download progress of a remote document, at most every 100 ms plus once on
   * completion (before {@linkcode PdfViewProps.onLoad}). Not called for local
   * files, or when a downloaded copy is used or confirmed unchanged by the
   * server (see `PdfUriSource.cache` and `maxAgeMs`).
   */
  onLoadProgress?: (event: PdfLoadProgressEvent) => void;
  onError?: (error: PdfError) => void;
  onPageChange?: (event: PdfPageChangeEvent) => void;
  /**
   * The user tapped a link to a URL. Links to other pages of the document
   * are followed natively and not reported here. The viewer never opens URLs
   * itself — without this handler, URL links do nothing.
   *
   * Android: links in password-protected documents need Android 15+, or
   * Android 12–14 with SDK extension 13; other devices report taps on them
   * through {@linkcode PdfViewProps.onPageTap} instead.
   */
  onLinkPress?: (event: PdfLinkPressEvent) => void;
  /**
   * The user tapped a page outside of any link. Delivered after the
   * double-tap-to-zoom window has passed.
   */
  onPageTap?: (event: PdfPageTapEvent) => void;
  /**
   * The zoom settled at a new value — once per change, after a pinch or
   * double-tap ends or `ref.zoomTo()` finishes; never during a gesture.
   * Not called when the value is unchanged (e.g. rotation keeps the relative
   * zoom).
   */
  onScaleChange?: (event: PdfScaleChangeEvent) => void;
  /**
   * The text selection settled at different text — after a long press, a
   * handle drag or Select all ends, never while a handle moves — or was
   * cleared (`text` is `''`), e.g. by a tap elsewhere or loading another
   * document.
   */
  onTextSelectionChange?: (event: PdfTextSelectionChangeEvent) => void;
  /**
   * The search started with `ref.search()` changed: more matches found, the
   * current match moved, or it completed or was cleared. At most 10 events
   * per second while searching, plus one on completion.
   */
  onSearchChange?: (event: PdfSearchChangeEvent) => void;
  /**
   * The viewport settled at a new position: once after loading, then after
   * each scroll, fling, zoom, page turn, rotation or `ref` navigation ends —
   * never while it moves. Save `event.viewport` to reopen the document where
   * the reader left off; `isAtEnd` tells that the end was reached.
   */
  onViewportChange?: (event: PdfViewportChangeEvent) => void;
  /**
   * The reader changed a form field: at once for a checkbox, radio button
   * or choice, when editing ends for text — never per keystroke. Not sent
   * for `ref.setFormFieldValues()`.
   */
  onFormChange?: (event: PdfFormChangeEvent) => void;
  ref?: Ref<PdfViewRef>;
}

type Handlers = Pick<
  PdfViewProps,
  | 'onLoad'
  | 'onLoadProgress'
  | 'onError'
  | 'onPageChange'
  | 'onLinkPress'
  | 'onPageTap'
  | 'onScaleChange'
  | 'onTextSelectionChange'
  | 'onSearchChange'
  | 'onViewportChange'
  | 'onFormChange'
>;

/**
 * The native initial position, stable while its values are unchanged
 * (Nitro diffs props by identity).
 */
function useInitialPosition(
  initialPage: number | undefined,
  initialScale: number | undefined,
  initialViewport: PdfViewport | undefined,
): PdfInitialPosition {
  const page = initialViewport?.page ?? initialPage ?? 0;
  const scale = initialViewport?.scale ?? initialScale ?? 1;
  const x = initialViewport?.x;
  const y = initialViewport?.y;
  return useMemo(
    () =>
      x === undefined || y === undefined
        ? { page, scale }
        : { page, scale, x, y },
    [page, scale, x, y],
  );
}

/**
 * Keeps the native source prop referentially stable while its value is
 * unchanged. Nitro diffs props by JS identity, so without this an inline
 * `source={{ uri }}` would re-send the source to native on every render.
 */
function useStableSource(source: PdfSource): NativePdfSource {
  const normalized = normalizeSource(source, Image.resolveAssetSource);
  const key = sourceKey(normalized);
  const cached = useRef<{ key: string; value: NativePdfSource } | null>(null);
  if (cached.current?.key !== key) {
    cached.current = { key, value: normalized };
  }
  return cached.current.value;
}

/**
 * A native, virtualized PDF viewer. Scrolling, zooming, rendering and
 * caching all happen natively; JS only sends configuration and receives
 * load / page-change / error events.
 *
 * `style` and the other {@linkcode ViewProps} apply to the viewer's box, which
 * clips the document (`overflow: 'hidden'`): `borderRadius` rounds it on both
 * platforms, e.g. for a card. Clipping hides an iOS shadow; draw one on a
 * surrounding view.
 */
export function PdfView({
  source,
  password,
  initialPage,
  initialScale,
  initialViewport,
  minScale,
  maxScale,
  pageSpacing,
  horizontal,
  enablePaging,
  enableRTL,
  singlePage,
  fitPolicy,
  spreadMode,
  colorScheme,
  contentInset,
  enableDoubleTapZoom,
  scrollEnabled,
  pageAccessibilityLabel,
  showsVerticalScrollIndicator,
  showsHorizontalScrollIndicator,
  enableTextSelection,
  enableFormFilling,
  searchHighlightColor,
  searchCurrentHighlightColor,
  onLoad,
  onLoadProgress,
  onError,
  onPageChange,
  onLinkPress,
  onPageTap,
  onScaleChange,
  onTextSelectionChange,
  onSearchChange,
  onViewportChange,
  onFormChange,
  ref,
  style,
  children,
  ...viewProps
}: PdfViewProps) {
  const nativeSource = useStableSource(source);
  const nativeContentInset = useContentInset(contentInset);
  const { overlays, overlayRegistry } = useOverlays();
  const overlayLayerId = `pdf-overlays${useId()}`;
  // For print's default job name; read when called, like the handlers.
  const latestSource = useRef(nativeSource);
  useLayoutEffect(() => {
    latestSource.current = nativeSource;
  });
  const initialPosition = useInitialPosition(
    initialPage,
    initialScale,
    initialViewport,
  );

  // Callbacks read the latest handler through a ref so the wrapped functions
  // handed to native never change identity after mount.
  const renderedHandlers: Handlers = {
    onLoad,
    onLoadProgress,
    onError,
    onPageChange,
    onLinkPress,
    onPageTap,
    onScaleChange,
    onTextSelectionChange,
    onSearchChange,
    onViewportChange,
    onFormChange,
  };
  const handlers = useRef(renderedHandlers);
  useLayoutEffect(() => {
    handlers.current = renderedHandlers;
  });
  const nativeCallbacks = useMemo(
    () => ({
      onLoad: callback((event: PdfLoadEvent) =>
        handlers.current.onLoad?.(event),
      ),
      onLoadProgress: callback((event: PdfLoadProgressEvent) =>
        handlers.current.onLoadProgress?.(event),
      ),
      onError: callback((error: PdfError) => handlers.current.onError?.(error)),
      onPageChange: callback((event: PdfPageChangeEvent) =>
        handlers.current.onPageChange?.(event),
      ),
      onLinkPress: callback((event: PdfLinkPressEvent) =>
        handlers.current.onLinkPress?.(event),
      ),
      onPageTap: callback((event: PdfPageTapEvent) =>
        handlers.current.onPageTap?.(event),
      ),
      onScaleChange: callback((event: PdfScaleChangeEvent) =>
        handlers.current.onScaleChange?.(event),
      ),
      onTextSelectionChange: callback((event: PdfTextSelectionChangeEvent) =>
        handlers.current.onTextSelectionChange?.(event),
      ),
      onSearchChange: callback((event: PdfSearchChangeEvent) =>
        handlers.current.onSearchChange?.(event),
      ),
      onViewportChange: callback((event: PdfViewportChangeEvent) =>
        handlers.current.onViewportChange?.(event),
      ),
      onFormChange: callback((event: NativePdfFormChangeEvent) =>
        handlers.current.onFormChange?.({ field: toFormField(event.field) }),
      ),
    }),
    [],
  );

  const nativeRef = useRef<NativeRef | null>(null);
  const hybridRef = useMemo(
    () =>
      callback((instance: NativeRef) => {
        nativeRef.current = instance;
      }),
    [],
  );

  useImperativeHandle(
    ref,
    () => ({
      goToPage(page, options) {
        nativeRef.current?.goToPage(page, options?.animated ?? true);
      },
      zoomTo(scale, options) {
        nativeRef.current?.zoomTo(scale, options?.animated ?? true);
      },
      async getOutline() {
        const native = nativeRef.current;
        if (native == null) throw new Error('The PDF view is not mounted');
        return buildOutlineTree(await native.getOutline());
      },
      goToViewport(viewport) {
        const { page, x, y, scale } = viewport;
        nativeRef.current?.goToViewport({ page, x, y, scale });
      },
      async getPageSizes() {
        const native = nativeRef.current;
        if (native == null) throw new Error('The PDF view is not mounted');
        return native.getPageSizes();
      },
      async renderPage(page, options) {
        const native = nativeRef.current;
        if (native == null) throw new Error('The PDF view is not mounted');
        return native.renderPage(page, options);
      },
      async print(options) {
        const native = nativeRef.current;
        if (native == null) throw new Error('The PDF view is not mounted');
        const jobName = options?.jobName?.trim() ?? '';
        return native.print(
          jobName === '' ? defaultPrintJobName(latestSource.current) : jobName,
        );
      },
      async getFormFields() {
        const native = nativeRef.current;
        if (native == null) throw new Error('The PDF view is not mounted');
        return (await native.getFormFields()).map(toFormField);
      },
      async setFormFieldValues(values) {
        const native = nativeRef.current;
        if (native == null) throw new Error('The PDF view is not mounted');
        await native.setFormFieldValues(toNativeFormValues(values));
      },
      async exportDocument(options) {
        const native = nativeRef.current;
        if (native == null) throw new Error('The PDF view is not mounted');
        return native.exportDocument(toNativeFilePath(options?.filePath));
      },
      getStats() {
        return nativeRef.current?.getStats() ?? null;
      },
      search(query) {
        nativeRef.current?.search(query);
      },
      searchNext() {
        nativeRef.current?.searchNext();
      },
      searchPrevious() {
        nativeRef.current?.searchPrevious();
      },
      clearSearch() {
        nativeRef.current?.clearSearch();
      },
    }),
    [],
  );

  // Only subscribe to events the app listens to, so native skips the JS hop
  // entirely for the others. Once subscribed, an event stays subscribed (the
  // handler ref makes a removed handler a no-op): unsubscribing would send
  // `null`, which Nitro rejects.
  const subscribed = useRef(new Set<keyof Handlers>());
  const subscription = <K extends keyof Handlers>(name: K) => {
    if (renderedHandlers[name] !== undefined) subscribed.current.add(name);
    return subscribed.current.has(name) ? nativeCallbacks[name] : undefined;
  };

  // The native view fills a plain View that takes the app's style: Android
  // ignores `borderRadius` on custom native views, and clipping a scrolling
  // native view to rounded corners is what `overflow: 'hidden'` does on both
  // platforms. `collapsable={false}`: the box must exist (clip, testID).
  return (
    <View {...viewProps} style={[styles.box, style]} collapsable={false}>
      <NativeBetterPdfView
        style={styles.fill}
        source={nativeSource}
        // Every optional prop is sent as a concrete value: when a prop is
        // removed, Fabric sends `null`, which Nitro's optional converters reject.
        // An empty password means "none" on both platforms.
        password={password ?? ''}
        initialPosition={initialPosition}
        minScale={minScale ?? 1}
        maxScale={maxScale ?? 4}
        pageSpacing={pageSpacing ?? 8}
        horizontal={horizontal ?? false}
        enablePaging={enablePaging ?? false}
        enableRTL={enableRTL ?? false}
        singlePage={singlePage ?? false}
        fitPolicy={
          fitPolicy ??
          (horizontal === true || enablePaging === true || singlePage === true
            ? 'page'
            : 'width')
        }
        spreadMode={spreadMode ?? 'none'}
        colorScheme={colorScheme === 'dark' ? 'dark' : 'light'}
        contentInset={nativeContentInset}
        enableDoubleTapZoom={enableDoubleTapZoom ?? true}
        scrollEnabled={scrollEnabled ?? true}
        pageAccessibilityLabel={
          pageAccessibilityLabel ?? 'Page {page} of {pageCount}'
        }
        showsVerticalScrollIndicator={showsVerticalScrollIndicator ?? true}
        showsHorizontalScrollIndicator={showsHorizontalScrollIndicator ?? true}
        enableTextSelection={enableTextSelection ?? true}
        enableFormFilling={enableFormFilling ?? true}
        searchHighlightColor={nativeColor(
          searchHighlightColor,
          DEFAULT_SEARCH_HIGHLIGHT_COLOR,
        )}
        searchCurrentHighlightColor={nativeColor(
          searchCurrentHighlightColor,
          DEFAULT_SEARCH_CURRENT_HIGHLIGHT_COLOR,
        )}
        onLoad={subscription('onLoad')}
        onLoadProgress={subscription('onLoadProgress')}
        onError={subscription('onError')}
        onPageChange={subscription('onPageChange')}
        onLinkPress={subscription('onLinkPress')}
        onPageTap={subscription('onPageTap')}
        onScaleChange={subscription('onScaleChange')}
        onTextSelectionChange={subscription('onTextSelectionChange')}
        onSearchChange={subscription('onSearchChange')}
        onViewportChange={subscription('onViewportChange')}
        onFormChange={subscription('onFormChange')}
        overlayLayerId={overlayLayerId}
        overlays={overlays}
        hybridRef={hybridRef}
      />
      {children != null && (
        // Above the document, passing touches through between overlays;
        // native code moves the PdfPageOverlays in it with their pages.
        <OverlayRegistryContext.Provider value={overlayRegistry}>
          <View
            nativeID={overlayLayerId}
            collapsable={false}
            pointerEvents="box-none"
            style={StyleSheet.absoluteFill}
          >
            {children}
          </View>
        </OverlayRegistryContext.Provider>
      )}
    </View>
  );
}

/**
 * The native content inset: concrete, non-negative values (Fabric sends
 * `null` for a removed prop, which Nitro rejects), stable while they are
 * unchanged (Nitro diffs props by identity).
 */
function useContentInset(
  inset: PdfContentInset | undefined,
): NativeContentInset {
  const top = clearance(inset?.top);
  const bottom = clearance(inset?.bottom);
  return useMemo(() => ({ top, bottom }), [top, bottom]);
}

function clearance(value: number | undefined): number {
  return value != null && Number.isFinite(value) && value > 0 ? value : 0;
}

/** The placements of the `PdfPageOverlay`s rendered inside a `PdfView`. */
function useOverlays(): {
  overlays: PdfOverlayPlacement[];
  overlayRegistry: OverlayRegistry;
} {
  const placements = useRef(new Map<string, PdfOverlayPlacement>());
  const [overlays, setOverlays] = useState<PdfOverlayPlacement[]>([]);
  const overlayRegistry = useMemo<OverlayRegistry>(
    () => ({
      set(placement) {
        const previous = placements.current.get(placement.id);
        if (previous != null && samePlacement(previous, placement)) return;
        placements.current.set(placement.id, placement);
        setOverlays([...placements.current.values()]);
      },
      remove(id) {
        if (placements.current.delete(id)) {
          setOverlays([...placements.current.values()]);
        }
      },
    }),
    [],
  );
  return { overlays, overlayRegistry };
}

function samePlacement(a: PdfOverlayPlacement, b: PdfOverlayPlacement) {
  return (
    a.page === b.page &&
    a.x === b.x &&
    a.y === b.y &&
    a.width === b.width &&
    a.height === b.height &&
    a.scalesWithZoom === b.scalesWithZoom
  );
}

const styles = StyleSheet.create({
  box: { overflow: 'hidden' },
  fill: { flex: 1 },
});

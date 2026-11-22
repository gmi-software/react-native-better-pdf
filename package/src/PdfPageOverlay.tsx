import {
  createContext,
  useContext,
  useId,
  useLayoutEffect,
  type ReactNode,
} from 'react';
import { StyleSheet, View, type ViewProps } from 'react-native';
import type { PdfOverlayPlacement } from './types/PdfOverlayPlacement';

/** How `PdfPageOverlay`s announce themselves to their `PdfView`. */
export interface OverlayRegistry {
  set(placement: PdfOverlayPlacement): void;
  remove(id: string): void;
}

export const OverlayRegistryContext = createContext<OverlayRegistry | null>(
  null,
);

/**
 * Props of {@linkcode PdfPageOverlay}. The rectangle is in PDF points from
 * the page's top-left corner, as `PdfView`'s `onPageTap` reports them.
 */
export interface PdfPageOverlayProps extends ViewProps {
  /** Zero-based page the overlay sits on. */
  page: number;
  /** Left edge, in PDF points from the page's left edge. */
  x: number;
  /** Top edge, in PDF points from the page's top edge. */
  y: number;
  /** Width in PDF points; also the overlay's layout width. */
  width: number;
  /** Height in PDF points; also the overlay's layout height. */
  height: number;
  /**
   * `true` (default): the overlay is scaled with the page, like content
   * printed on it. `false`: it keeps its size and its top-left corner stays
   * on the page point — for badges and handles.
   */
  scalesWithZoom?: boolean | undefined;
  children?: ReactNode | undefined;
}

/**
 * A React view attached to a point of a page, rendered as a child of
 * `PdfView`: it moves with the page while scrolling and zooming, placed
 * natively every frame (JS is not involved while the document moves).
 * Lay its content out at `width` × `height` as if PDF points were dp;
 * it is scaled to the page's zoom. Hidden while its page is off screen.
 *
 * Meant for tens of overlays — form fields, signatures, markers — not one
 * per page of a long document.
 */
export function PdfPageOverlay({
  page,
  x,
  y,
  width,
  height,
  scalesWithZoom = true,
  style,
  ...viewProps
}: PdfPageOverlayProps) {
  const registry = useContext(OverlayRegistryContext);
  const id = `pdf-overlay${useId()}`;
  useLayoutEffect(() => {
    registry?.set({ id, page, x, y, width, height, scalesWithZoom });
  }, [registry, id, page, x, y, width, height, scalesWithZoom]);
  useLayoutEffect(() => () => registry?.remove(id), [registry, id]);
  if (registry == null) {
    throw new Error('PdfPageOverlay must be rendered inside a PdfView');
  }
  // Off screen until native code places it on its page; the app's style
  // cannot move or resize it.
  return (
    <View
      {...viewProps}
      nativeID={id}
      collapsable={false}
      style={[style, styles.overlay, { width, height }]}
    />
  );
}

const styles = StyleSheet.create({
  overlay: { position: 'absolute', left: -100000, top: 0 },
});

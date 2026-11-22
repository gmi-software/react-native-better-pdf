import type { PdfViewProps } from '../PdfView';

/**
 * Space kept clear at the top and bottom of a vertically scrolling
 * document, in dp / pt (not scaled by zoom), e.g. for a translucent header
 * or toolbar over the viewer: pages scroll under it, but the first page
 * starts below `top` and the document ends above `bottom`.
 *
 * @see {@linkcode PdfViewProps.contentInset}
 */
export interface PdfContentInset {
  top?: number | undefined;
  bottom?: number | undefined;
}

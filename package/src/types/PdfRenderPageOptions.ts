import type { PdfImageFormat } from './PdfImageFormat';

/** How `renderPage` renders a page to an image file. */
export interface PdfRenderPageOptions {
  /**
   * Image width in pixels (1–4096); the height follows the page's aspect
   * ratio as displayed. For an image shown at `w` points, pass
   * `w * PixelRatio.get()`.
   */
  width: number;
  /** Default `'png'`. */
  format?: PdfImageFormat | undefined;
  /** JPEG quality from 0 to 1; ignored for PNG. Default `0.9`. */
  quality?: number | undefined;
}

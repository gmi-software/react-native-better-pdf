import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

interface ViewConfig {
  validAttributes: Record<string, unknown>;
  bubblingEventTypes: Record<string, unknown>;
  directEventTypes: Record<string, unknown>;
}

// Read from disk rather than imported: other test files mock this module.
const BetterPdfViewConfig: ViewConfig = JSON.parse(
  readFileSync(
    join(
      import.meta.dir,
      '../nitrogen/generated/shared/json/BetterPdfViewConfig.json',
    ),
    'utf8',
  ),
);

/**
 * Structural guard for the JS ↔ native boundary. Everything that can cross
 * into the native view is listed in the Nitrogen-generated view config, so
 * this fails as soon as a spec change adds a prop or event — in particular a
 * per-frame one (onScroll, onZoom, …) that would put JS on the hot path.
 * Changing this list must come with a design note in docs/api-design.md.
 */
describe('Nitro boundary', () => {
  const attributes = Object.keys(BetterPdfViewConfig.validAttributes).sort();

  test('exposes exactly the reviewed props and events', () => {
    expect(attributes).toEqual(
      [
        'colorScheme',
        'contentInset',
        'enableDoubleTapZoom',
        'enableFormFilling',
        'enableTextSelection',
        'enablePaging',
        'enableRTL',
        'fitPolicy',
        'horizontal',
        'hybridRef',
        'initialPosition',
        'maxScale',
        'minScale',
        'onError',
        // Once per change the reader makes, text when editing ends (see
        // docs/api-design.md "Forms").
        'onFormChange',
        'onLinkPress',
        'onLoad',
        'onLoadProgress',
        'onPageChange',
        'onPageTap',
        'onScaleChange',
        'onSearchChange',
        'onTextSelectionChange',
        'onViewportChange',
        // Placements of PdfPageOverlay views; native code moves the views
        // every frame without JS (see docs/api-design.md "Page overlays").
        'overlayLayerId',
        'overlays',
        'pageAccessibilityLabel',
        'pageSpacing',
        'scrollEnabled',
        'searchCurrentHighlightColor',
        'searchHighlightColor',
        'showsHorizontalScrollIndicator',
        'showsVerticalScrollIndicator',
        'singlePage',
        'sources',
        'spreadMode',
      ].sort(),
    );
  });

  test('has no per-frame events', () => {
    // Events whose names suggest high frequency but are bounded natively.
    const reviewed = new Set([
      // Throttled to one event per 100 ms + one on completion (see the
      // PdfLoadProgressEvent docs and ProgressThrottle on both platforms).
      'onLoadProgress',
      // Once per settled zoom change, never during a gesture (native settle
      // detection on both platforms; see docs/api-design.md "Scale change").
      'onScaleChange',
      // Once per settled selection, never per handle move (react-native-pdf
      // sent one per move; see docs/api-design.md "Text selection").
      'onTextSelectionChange',
      // Once per settled scroll / zoom and once after loading, never per
      // frame (see docs/api-design.md "Viewport").
      'onViewportChange',
    ]);
    const perFrame = attributes.filter(
      (name) =>
        /scroll|zoom|scale|frame|progress|render|visible/i.test(name) &&
        name.startsWith('on') &&
        !reviewed.has(name),
    );
    expect(perFrame).toEqual([]);
  });

  test('uses no legacy bubbling/direct event channels', () => {
    expect(BetterPdfViewConfig.bubblingEventTypes).toEqual({});
    expect(BetterPdfViewConfig.directEventTypes).toEqual({});
  });
});

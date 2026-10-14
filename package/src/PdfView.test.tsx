import { beforeEach, describe, expect, mock, test } from 'bun:test';
import { createRef, type ReactNode } from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';

(
  globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
).IS_REACT_ACT_ENVIRONMENT = true;

// --- Native boundary mocks -------------------------------------------------

type HostProps = Record<string, unknown>;
const hostRenders: HostProps[] = [];
const boxRenders: HostProps[] = [];
const nativeCalls: string[] = [];

const stats: PdfViewStats = {
  pageCount: 3,
  residentPages: 1,
  cacheBytes: 0,
  cacheBudgetBytes: 0,
  cacheHits: 0,
  cacheMisses: 0,
  bitmapAllocations: 0,
  pagesRendered: 0,
  rendersCancelled: 0,
  meanRenderMs: -1,
};

const nativeCheckbox = {
  name: 'agree',
  type: 'checkbox',
  isReadOnly: false,
  isRequired: true,
  widgets: [{ page: 0, x: 200, y: 270, width: 20, height: 20 }],
  text: '',
  isChecked: false,
  options: [],
  selectedOptions: [],
  isMultiline: false,
  maxLength: 0,
  isMultiSelect: false,
  isEditable: false,
  isSigned: false,
};

const nativeInstance = {
  goToPage: (page: number, animated: boolean) =>
    nativeCalls.push(`goToPage(${page}, ${animated})`),
  zoomTo: (scale: number, animated: boolean) =>
    nativeCalls.push(`zoomTo(${scale}, ${animated})`),
  getStats: () => stats,
  search: (query: string) => nativeCalls.push(`search(${query})`),
  searchNext: () => nativeCalls.push('searchNext()'),
  searchPrevious: () => nativeCalls.push('searchPrevious()'),
  clearSearch: () => nativeCalls.push('clearSearch()'),
  getPageSizes: () => Promise.resolve([{ width: 595, height: 842 }]),
  renderPage: (page: number, options: { width: number }) =>
    Promise.resolve({
      uri: `file:///page-${page}.png`,
      width: options.width,
      height: 2 * options.width,
    }),
  print: (jobName: string) => {
    nativeCalls.push(`print(${jobName})`);
    return Promise.resolve({ isSubmitted: true });
  },
  goToViewport: (viewport: object) =>
    nativeCalls.push(`goToViewport(${JSON.stringify(viewport)})`),
  getFormFields: () =>
    Promise.resolve([{ ...nativeCheckbox, isChecked: true }]),
  setFormFieldValues: (values: object[]) => {
    nativeCalls.push(`setFormFieldValues(${JSON.stringify(values)})`);
    return Promise.resolve();
  },
  exportDocument: (filePath: string) => {
    nativeCalls.push(`exportDocument(${filePath})`);
    return Promise.resolve({ filePath: filePath || '/cache/form.pdf' });
  },
  getOutline: () =>
    Promise.resolve([
      { title: 'Chapter', page: 0, depth: 0 },
      { title: 'Section', page: 2, depth: 1 },
    ]),
};

function FakeHost(props: HostProps) {
  hostRenders.push(props);
  return null;
}

function FakeView({
  children,
  ...props
}: HostProps & { children?: ReactNode }) {
  boxRenders.push(props);
  return children;
}

mock.module('react-native', () => ({
  View: FakeView,
  StyleSheet: { create: <T,>(styles: T) => styles },
  Image: {
    resolveAssetSource: (asset: number) => ({ uri: `asset://${asset}` }),
  },
  // Like React Native: a number for plain colours, an object for
  // PlatformColor, null for invalid strings.
  processColor: (color: unknown) =>
    color === 'blue'
      ? 0xff0000ff
      : typeof color === 'object'
        ? { semantic: ['label'] }
        : null,
}));
// Module mocks are shared by all test files: export everything the package
// imports from Nitro (PdfCache.test.ts needs NitroModules).
mock.module('react-native-nitro-modules', () => ({
  callback: <T,>(f: T) => ({ f }),
  getHostComponent: () => FakeHost,
  NitroModules: {
    createHybridObject: () => {
      throw new Error('Not available in PdfView tests');
    },
  },
}));
mock.module(
  '../nitrogen/generated/shared/json/BetterPdfViewConfig.json',
  () => ({ default: {} }),
);

const { PdfView } = await import('./PdfView');
const { PdfPageOverlay } = await import('./PdfPageOverlay');
import type { PdfViewRef } from './PdfView';
import type { PdfViewStats } from './types/PdfViewStats';

// ---------------------------------------------------------------------------

const lastBox = () => {
  const props = boxRenders[boxRenders.length - 1];
  if (props == null) throw new Error('box not rendered');
  return props;
};

const lastHost = () => {
  const props = hostRenders[hostRenders.length - 1];
  if (props == null) throw new Error('host not rendered');
  return props;
};

function render(element: React.ReactElement): ReactTestRenderer {
  let renderer: ReactTestRenderer | undefined;
  act(() => {
    renderer = create(element);
  });
  if (renderer == null) throw new Error('render failed');
  return renderer;
}

beforeEach(() => {
  hostRenders.length = 0;
  boxRenders.length = 0;
  nativeCalls.length = 0;
});

describe('PdfView', () => {
  test('keeps the native source identity when an equal inline source is re-rendered', () => {
    const renderer = render(
      <PdfView source={{ uri: 'file:///a.pdf', headers: { A: '1' } }} />,
    );
    const first = lastHost().source;
    act(() =>
      renderer.update(
        <PdfView source={{ uri: 'file:///a.pdf', headers: { A: '1' } }} />,
      ),
    );
    expect(lastHost().source).toBe(first);
  });

  test('sends a new source object when the value changes', () => {
    const renderer = render(<PdfView source={{ uri: 'file:///a.pdf' }} />);
    const first = lastHost().source;
    act(() => renderer.update(<PdfView source={{ uri: 'file:///b.pdf' }} />));
    expect(lastHost().source).not.toBe(first);
    expect(lastHost().source).toEqual({ uri: 'file:///b.pdf' });
  });

  test('resolves require() assets', () => {
    render(<PdfView source={12} />);
    expect(lastHost().source).toEqual({ uri: 'asset://12' });
  });

  test('forwards link presses and page taps to the latest handlers through stable callbacks', () => {
    const received: string[] = [];
    const renderer = render(
      <PdfView
        source={{ uri: 'x' }}
        onLinkPress={(event) => received.push(`old link ${event.url}`)}
        onPageTap={(event) => received.push(`old tap ${event.page}`)}
      />,
    );
    const link = lastHost().onLinkPress as {
      f: (event: { url: string }) => void;
    };
    const tap = lastHost().onPageTap as {
      f: (event: { page: number; x: number; y: number }) => void;
    };
    act(() =>
      renderer.update(
        <PdfView
          source={{ uri: 'x' }}
          onLinkPress={(event) => received.push(`link ${event.url}`)}
          onPageTap={(event) =>
            received.push(`tap ${event.page} ${event.x} ${event.y}`)
          }
        />,
      ),
    );
    expect(lastHost().onLinkPress).toBe(link);
    expect(lastHost().onPageTap).toBe(tap);
    link.f({ url: 'https://example.com' });
    tap.f({ page: 2, x: 10, y: 20 });
    expect(received).toEqual(['link https://example.com', 'tap 2 10 20']);
  });

  test('forwards load progress through a stable callback', () => {
    const received: Array<[number, number | undefined]> = [];
    const renderer = render(
      <PdfView source={{ uri: 'x' }} onLoadProgress={() => {}} />,
    );
    const progress = lastHost().onLoadProgress as {
      f: (event: { loadedBytes: number; totalBytes?: number }) => void;
    };
    act(() =>
      renderer.update(
        <PdfView
          source={{ uri: 'x' }}
          onLoadProgress={(event) =>
            received.push([event.loadedBytes, event.totalBytes])
          }
        />,
      ),
    );
    expect(lastHost().onLoadProgress).toBe(progress);
    progress.f({ loadedBytes: 10, totalBytes: 100 });
    progress.f({ loadedBytes: 20 });
    expect(received).toEqual([
      [10, 100],
      [20, undefined],
    ]);
  });

  test('passes no native callback for events the app does not listen to', () => {
    render(<PdfView source={{ uri: 'x' }} onLoad={() => {}} />);
    expect(lastHost().onLoad).toBeDefined();
    expect(lastHost().onPageChange).toBeUndefined();
    expect(lastHost().onError).toBeUndefined();
    expect(lastHost().onLoadProgress).toBeUndefined();
    expect(lastHost().onLinkPress).toBeUndefined();
    expect(lastHost().onPageTap).toBeUndefined();
    expect(lastHost().onScaleChange).toBeUndefined();
    expect(lastHost().onTextSelectionChange).toBeUndefined();
  });

  test('forwards text selection changes through a stable callback', () => {
    const texts: string[] = [];
    render(
      <PdfView
        source={{ uri: 'x' }}
        onTextSelectionChange={(event) => texts.push(event.text)}
      />,
    );
    const wrapped = lastHost().onTextSelectionChange as {
      f: (event: { text: string }) => void;
    };
    wrapped.f({ text: 'brown fox' });
    wrapped.f({ text: '' });
    expect(texts).toEqual(['brown fox', '']);
  });

  test('forwards scale changes through a stable callback', () => {
    const scales: number[] = [];
    render(
      <PdfView
        source={{ uri: 'x' }}
        onScaleChange={(event) => scales.push(event.scale)}
      />,
    );
    const wrapped = lastHost().onScaleChange as {
      f: (event: { scale: number }) => void;
    };
    wrapped.f({ scale: 2.5 });
    expect(scales).toEqual([2.5]);
  });

  test('keeps callback identity stable while calling the latest handler', () => {
    const calls: string[] = [];
    const renderer = render(
      <PdfView
        source={{ uri: 'x' }}
        onPageChange={() => calls.push('first')}
      />,
    );
    const wrapped = lastHost().onPageChange as { f: (event: unknown) => void };
    act(() =>
      renderer.update(
        <PdfView
          source={{ uri: 'x' }}
          onPageChange={() => calls.push('second')}
        />,
      ),
    );
    expect(lastHost().onPageChange).toBe(wrapped);
    wrapped.f({ page: 1, pageCount: 2 });
    expect(calls).toEqual(['second']);
  });

  test('never sends undefined for a prop that had a value', () => {
    // Fabric turns a removed prop into `null`, which Nitro rejects; the
    // wrapper must send a concrete value instead.
    const calls: string[] = [];
    const renderer = render(
      <PdfView
        source={{ uri: 'x' }}
        password="p"
        minScale={2}
        fitPolicy="height"
        spreadMode="pairs"
        horizontal
        enableTextSelection={false}
        searchHighlightColor="blue"
        onPageTap={() => calls.push('tap')}
      />,
    );
    const tap = lastHost().onPageTap as { f: (event: unknown) => void };
    act(() => renderer.update(<PdfView source={{ uri: 'x' }} />));
    expect(lastHost()).toMatchObject({
      password: '',
      initialPosition: { page: 0, scale: 1 },
      minScale: 1,
      maxScale: 4,
      pageSpacing: 8,
      horizontal: false,
      enablePaging: false,
      fitPolicy: 'width',
      spreadMode: 'none',
      showsVerticalScrollIndicator: true,
      showsHorizontalScrollIndicator: true,
      singlePage: false,
      enableRTL: false,
      scrollEnabled: true,
      enableDoubleTapZoom: true,
      pageAccessibilityLabel: 'Page {page} of {pageCount}',
      enableTextSelection: true,
      searchHighlightColor: 0x66ffd600,
      searchCurrentHighlightColor: 0x99ff8c00,
    });
    // Still subscribed, but the removed handler is no longer called.
    expect(lastHost().onPageTap).toBe(tap);
    tap.f({ page: 0, x: 1, y: 2 });
    expect(calls).toEqual([]);
  });

  test('sends search highlight colours as ARGB, falling back to the defaults', () => {
    render(
      <PdfView
        source={{ uri: 'x' }}
        searchHighlightColor="blue"
        searchCurrentHighlightColor="not-a-colour"
      />,
    );
    expect(lastHost()).toMatchObject({
      searchHighlightColor: 0xff0000ff,
      searchCurrentHighlightColor: 0x99ff8c00,
    });
    render(
      <PdfView
        source={{ uri: 'x' }}
        searchHighlightColor={{ semantic: ['label'] } as never}
      />,
    );
    expect(lastHost().searchHighlightColor).toBe(0x66ffd600);
  });

  test('defaults fitPolicy to the whole page in horizontal and paged layouts', () => {
    render(<PdfView source={{ uri: 'x' }} enablePaging />);
    expect(lastHost().fitPolicy).toBe('page');
    render(<PdfView source={{ uri: 'x' }} horizontal />);
    expect(lastHost().fitPolicy).toBe('page');
    render(<PdfView source={{ uri: 'x' }} singlePage />);
    expect(lastHost().fitPolicy).toBe('page');
  });

  test('forwards plain props', () => {
    render(
      <PdfView
        source={{ uri: 'x' }}
        initialPage={4}
        minScale={0.5}
        maxScale={6}
        pageSpacing={0}
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator
        horizontal
        enablePaging
        fitPolicy="height"
        spreadMode="book"
        password="p"
        enableTextSelection={false}
      />,
    );
    expect(lastHost()).toMatchObject({
      initialPosition: { page: 4, scale: 1 },
      minScale: 0.5,
      maxScale: 6,
      pageSpacing: 0,
      showsVerticalScrollIndicator: false,
      showsHorizontalScrollIndicator: true,
      horizontal: true,
      enablePaging: true,
      fitPolicy: 'height',
      spreadMode: 'book',
      password: 'p',
      enableTextSelection: false,
    });
  });

  test('puts style and view props on a clipping box the native view fills', () => {
    render(
      <PdfView
        source={{ uri: 'x' }}
        testID="pdf"
        style={{ flex: 1, borderRadius: 12 }}
      />,
    );
    expect(lastBox()).toMatchObject({
      testID: 'pdf',
      collapsable: false,
      style: [{ overflow: 'hidden' }, { flex: 1, borderRadius: 12 }],
    });
    expect(lastHost().style).toEqual({ flex: 1 });
    expect(lastHost().testID).toBeUndefined();
  });

  test("takes useColorScheme()'s values and sends light or dark", () => {
    const renderer = render(
      <PdfView source={{ uri: 'x' }} colorScheme="dark" />,
    );
    expect(lastHost().colorScheme).toBe('dark');
    act(() =>
      renderer.update(
        <PdfView source={{ uri: 'x' }} colorScheme="unspecified" />,
      ),
    );
    expect(lastHost().colorScheme).toBe('light');
    act(() =>
      renderer.update(<PdfView source={{ uri: 'x' }} colorScheme={null} />),
    );
    expect(lastHost().colorScheme).toBe('light');
    act(() => renderer.update(<PdfView source={{ uri: 'x' }} />));
    expect(lastHost().colorScheme).toBe('light');
  });

  test('ref methods default to animated and map options to native arguments', () => {
    const ref = createRef<PdfViewRef>();
    render(<PdfView ref={ref} source={{ uri: 'x' }} />);
    expect(ref.current?.getStats()).toBeNull();

    const hybridRef = lastHost().hybridRef as {
      f: (instance: typeof nativeInstance) => void;
    };
    hybridRef.f(nativeInstance);

    ref.current?.goToPage(3);
    ref.current?.goToPage(5, { animated: false });
    ref.current?.zoomTo(2);
    expect(nativeCalls).toEqual([
      'goToPage(3, true)',
      'goToPage(5, false)',
      'zoomTo(2, true)',
    ]);
    expect(ref.current?.getStats()).toBe(stats);
  });

  test('navigation ignores numbers that are not finite', () => {
    const ref = createRef<PdfViewRef>();
    render(<PdfView ref={ref} source={{ uri: 'x' }} />);
    const hybridRef = lastHost().hybridRef as {
      f: (instance: typeof nativeInstance) => void;
    };
    hybridRef.f(nativeInstance);

    const viewport = { page: 2, x: 0, y: 10, scale: 1 };
    for (const value of [NaN, Infinity, -Infinity]) {
      ref.current?.goToPage(value);
      ref.current?.zoomTo(value);
      ref.current?.goToViewport({ ...viewport, page: value });
      ref.current?.goToViewport({ ...viewport, x: value });
      ref.current?.goToViewport({ ...viewport, y: value });
      ref.current?.goToViewport({ ...viewport, scale: value });
    }
    ref.current?.goToViewport({ ...viewport, scale: 0 });
    expect(nativeCalls).toEqual([]);

    // Finite values outside the document are clamped natively.
    ref.current?.goToPage(1e20);
    ref.current?.goToPage(-1);
    ref.current?.goToViewport({ ...viewport, page: -1e20 });
    expect(nativeCalls).toEqual([
      'goToPage(100000000000000000000, true)',
      'goToPage(-1, true)',
      'goToViewport({"page":-100000000000000000000,"x":0,"y":10,"scale":1})',
    ]);
  });

  test('initialViewport takes precedence and keeps the native prop stable', () => {
    const viewport = { page: 7, x: -4, y: 120.5, scale: 2 };
    const renderer = render(
      <PdfView
        source={{ uri: 'x' }}
        initialPage={3}
        initialScale={1.5}
        initialViewport={viewport}
      />,
    );
    const position = lastHost().initialPosition;
    expect(position).toEqual({ page: 7, scale: 2, x: -4, y: 120.5 });
    act(() =>
      renderer.update(
        <PdfView
          source={{ uri: 'x' }}
          initialPage={3}
          initialScale={1.5}
          initialViewport={{ ...viewport }}
        />,
      ),
    );
    expect(lastHost().initialPosition).toBe(position);
    act(() =>
      renderer.update(
        <PdfView source={{ uri: 'x' }} initialPage={3} initialScale={1.5} />,
      ),
    );
    expect(lastHost().initialPosition).toEqual({ page: 3, scale: 1.5 });
  });

  test('goToViewport sends only the position fields', () => {
    const ref = createRef<PdfViewRef>();
    render(<PdfView ref={ref} source={{ uri: 'x' }} />);
    const hybridRef = lastHost().hybridRef as {
      f: (instance: typeof nativeInstance) => void;
    };
    hybridRef.f(nativeInstance);
    // e.g. a whole saved event spread into the viewport.
    const saved = { page: 2, x: 0, y: 10, scale: 1, isAtEnd: false };
    ref.current?.goToViewport(saved);
    expect(nativeCalls).toEqual([
      'goToViewport({"page":2,"x":0,"y":10,"scale":1})',
    ]);
  });

  test('forwards viewport changes through a stable callback', () => {
    const events: unknown[] = [];
    render(
      <PdfView
        source={{ uri: 'x' }}
        onViewportChange={(e) => events.push(e)}
      />,
    );
    const event = {
      viewport: { page: 1, x: 0, y: 3, scale: 1 },
      firstVisiblePage: 1,
      lastVisiblePage: 2,
      isAtEnd: false,
    };
    (lastHost().onViewportChange as { f: (e: unknown) => void }).f(event);
    expect(events).toEqual([event]);
  });

  test("print rejects before mount and names the job after the source's file", async () => {
    const ref = createRef<PdfViewRef>();
    render(
      <PdfView ref={ref} source={{ uri: 'https://x.test/files/q3.pdf?t=1' }} />,
    );
    await expect(ref.current!.print()).rejects.toThrow('not mounted');
    const hybridRef = lastHost().hybridRef as {
      f: (instance: typeof nativeInstance) => void;
    };
    hybridRef.f(nativeInstance);
    expect(await ref.current!.print()).toEqual({ isSubmitted: true });
    await ref.current!.print({ jobName: ' Invoice 7 ' });
    await ref.current!.print({ jobName: '  ' });
    expect(nativeCalls).toEqual([
      'print(q3.pdf)',
      'print(Invoice 7)',
      'print(q3.pdf)',
    ]);
  });

  test('getPageSizes rejects before mount and forwards after', async () => {
    const ref = createRef<PdfViewRef>();
    render(<PdfView ref={ref} source={{ uri: 'x' }} />);
    await expect(ref.current!.getPageSizes()).rejects.toThrow('not mounted');
    const hybridRef = lastHost().hybridRef as {
      f: (instance: typeof nativeInstance) => void;
    };
    hybridRef.f(nativeInstance);
    expect(await ref.current!.getPageSizes()).toEqual([
      { width: 595, height: 842 },
    ]);
  });

  test('renderPage rejects before mount and forwards after', async () => {
    const ref = createRef<PdfViewRef>();
    render(<PdfView ref={ref} source={{ uri: 'x' }} />);
    await expect(ref.current!.renderPage(0, { width: 100 })).rejects.toThrow(
      'not mounted',
    );
    const hybridRef = lastHost().hybridRef as {
      f: (instance: typeof nativeInstance) => void;
    };
    hybridRef.f(nativeInstance);
    expect(await ref.current!.renderPage(3, { width: 100 })).toEqual({
      uri: 'file:///page-3.png',
      width: 100,
      height: 200,
    });
  });

  test('search methods forward to the native view', () => {
    const ref = createRef<PdfViewRef>();
    const events: number[] = [];
    render(
      <PdfView
        ref={ref}
        source={{ uri: 'x' }}
        onSearchChange={(event) => events.push(event.matchCount)}
      />,
    );
    const hybridRef = lastHost().hybridRef as {
      f: (instance: typeof nativeInstance) => void;
    };
    hybridRef.f(nativeInstance);
    ref.current?.search('lorem');
    ref.current?.searchNext();
    ref.current?.searchPrevious();
    ref.current?.clearSearch();
    expect(nativeCalls).toEqual([
      'search(lorem)',
      'searchNext()',
      'searchPrevious()',
      'clearSearch()',
    ]);
    const wrapped = lastHost().onSearchChange as {
      f: (event: { matchCount: number }) => void;
    };
    wrapped.f({ matchCount: 3 });
    expect(events).toEqual([3]);
  });

  test('places PdfPageOverlays through native props, in the layer it names', () => {
    const overlays = (page: number, x: number, withPin = true) => (
      <PdfView source={{ uri: 'x' }}>
        <PdfPageOverlay page={page} x={x} y={20} width={100} height={40} />
        {withPin && (
          <PdfPageOverlay
            page={1}
            x={5}
            y={6}
            width={24}
            height={24}
            scalesWithZoom={false}
            style={{ left: 0, width: 10, opacity: 0.5 }}
          />
        )}
      </PdfView>
    );
    const renderer = render(overlays(0, 10));
    const layer = boxRenders.find(
      (props) => props.nativeID === lastHost().overlayLayerId,
    );
    expect(layer?.pointerEvents).toBe('box-none');
    const placed = lastHost().overlays as Array<{ id: string }>;
    expect(placed.map(({ id: _, ...placement }) => placement)).toEqual([
      { page: 0, x: 10, y: 20, width: 100, height: 40, scalesWithZoom: true },
      { page: 1, x: 5, y: 6, width: 24, height: 24, scalesWithZoom: false },
    ]);
    // Each placement names its view; the app's style cannot move or resize it.
    const pin = boxRenders.findLast(
      (props) => props.nativeID === placed[1]?.id,
    );
    expect(pin?.style).toEqual([
      { left: 0, width: 10, opacity: 0.5 },
      { position: 'absolute', left: -100000, top: 0 },
      { width: 24, height: 24 },
    ]);

    // Re-rendered unchanged: the same array, nothing new for native code.
    act(() => renderer.update(overlays(0, 10)));
    expect(lastHost().overlays).toBe(placed);

    act(() => renderer.update(overlays(2, 30)));
    expect(lastHost().overlays).toEqual([
      { ...placed[0], page: 2, x: 30 },
      placed[1],
    ]);

    act(() => renderer.update(overlays(2, 30, false)));
    expect(lastHost().overlays).toEqual([{ ...placed[0], page: 2, x: 30 }]);
  });

  test('sends no overlays and renders no layer without children', () => {
    render(<PdfView source={{ uri: 'x' }} />);
    expect(lastHost().overlays).toEqual([]);
    expect(boxRenders.some((props) => props.pointerEvents != null)).toBe(false);
  });

  test('PdfPageOverlay outside a PdfView throws', () => {
    const error = console.error;
    console.error = () => {};
    try {
      expect(() =>
        render(<PdfPageOverlay page={0} x={0} y={0} width={10} height={10} />),
      ).toThrow('PdfPageOverlay must be rendered inside a PdfView');
    } finally {
      console.error = error;
    }
  });

  test('sends contentInset as concrete, non-negative values, stable while unchanged', () => {
    const renderer = render(<PdfView source={{ uri: 'x' }} />);
    expect(lastHost().contentInset).toEqual({ top: 0, bottom: 0 });
    act(() =>
      renderer.update(
        <PdfView
          source={{ uri: 'x' }}
          contentInset={{ top: 80, bottom: 60 }}
        />,
      ),
    );
    const inset = lastHost().contentInset;
    expect(inset).toEqual({ top: 80, bottom: 60 });
    act(() =>
      renderer.update(
        <PdfView
          source={{ uri: 'x' }}
          contentInset={{ top: 80, bottom: 60 }}
        />,
      ),
    );
    expect(lastHost().contentInset).toBe(inset);
    act(() =>
      renderer.update(
        <PdfView
          source={{ uri: 'x' }}
          contentInset={{ top: -5, bottom: NaN }}
        />,
      ),
    );
    expect(lastHost().contentInset).toEqual({ top: 0, bottom: 0 });
  });

  test('form methods reject before mount and convert at the boundary after', async () => {
    const ref = createRef<PdfViewRef>();
    render(<PdfView ref={ref} source={{ uri: 'x' }} />);
    await expect(ref.current!.getFormFields()).rejects.toThrow('not mounted');
    const hybridRef = lastHost().hybridRef as {
      f: (instance: typeof nativeInstance) => void;
    };
    hybridRef.f(nativeInstance);
    expect(await ref.current!.getFormFields()).toEqual([
      {
        name: 'agree',
        type: 'checkbox',
        isChecked: true,
        isReadOnly: false,
        isRequired: true,
        widgets: [{ page: 0, x: 200, y: 270, width: 20, height: 20 }],
      },
    ]);
    await ref.current!.setFormFieldValues({ name: 'Anna', agree: true });
    expect(await ref.current!.exportDocument()).toEqual({
      filePath: '/cache/form.pdf',
    });
    await ref.current!.exportDocument({
      filePath: 'file:///tmp/my%20form.pdf',
    });
    expect(nativeCalls).toEqual([
      'setFormFieldValues([{"name":"name","text":"Anna"},{"name":"agree","isChecked":true}])',
      'exportDocument()',
      'exportDocument(/tmp/my form.pdf)',
    ]);
  });

  test('forwards form changes as typed fields, filling enabled by default', () => {
    const changes: unknown[] = [];
    render(
      <PdfView
        source={{ uri: 'x' }}
        onFormChange={(event) => changes.push(event.field)}
      />,
    );
    expect(lastHost().enableFormFilling).toBe(true);
    const wrapped = lastHost().onFormChange as {
      f: (event: { field: typeof nativeCheckbox }) => void;
    };
    wrapped.f({ field: nativeCheckbox });
    expect(changes).toEqual([
      {
        name: 'agree',
        type: 'checkbox',
        isChecked: false,
        isReadOnly: false,
        isRequired: true,
        widgets: nativeCheckbox.widgets,
      },
    ]);
  });

  test('getOutline rejects before mount and returns a tree after', async () => {
    const ref = createRef<PdfViewRef>();
    render(<PdfView ref={ref} source={{ uri: 'x' }} />);
    await expect(ref.current!.getOutline()).rejects.toThrow('not mounted');
    const hybridRef = lastHost().hybridRef as {
      f: (instance: typeof nativeInstance) => void;
    };
    hybridRef.f(nativeInstance);
    expect(await ref.current!.getOutline()).toEqual([
      {
        title: 'Chapter',
        page: 0,
        children: [{ title: 'Section', page: 2, children: [] }],
      },
    ]);
  });
});

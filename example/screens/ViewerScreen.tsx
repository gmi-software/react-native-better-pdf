import { useCallback, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  PdfPageOverlay,
  PdfView,
  type PdfError,
  type PdfLoadProgressEvent,
  type PdfOutlineItem,
  type PdfPageImage,
  type PdfSearchChangeEvent,
  type PdfSource,
  type PdfViewport,
  type PdfViewportChangeEvent,
  type PdfViewRef,
  type PdfViewStats,
} from 'react-native-better-pdf';
import {
  DOCUMENTS,
  type DocumentEntry,
  type DocumentSource,
} from '../documents';
import { describeField, FormBar } from './FormBar';
import { OutlinePanel } from './OutlinePanel';
import { SearchBar } from './SearchBar';
import { ThumbnailStrip } from './ThumbnailStrip';
import { Button, colors } from '../ui';

/** Layout modes cycled by the toolbar button, labelled by their title. */
const LAYOUTS = [
  { title: 'Vertical', horizontal: false, enablePaging: false },
  { title: 'Horizontal', horizontal: true, enablePaging: false },
  { title: 'Paged', horizontal: false, enablePaging: true },
  { title: 'Paged H', horizontal: true, enablePaging: true },
  { title: 'RTL', horizontal: true, enablePaging: false, enableRTL: true },
  { title: 'Paged RTL', horizontal: true, enablePaging: true, enableRTL: true },
  // Facing pages: vertical continuous only.
  {
    title: 'Pairs',
    horizontal: false,
    enablePaging: false,
    spreadMode: 'pairs',
  },
  { title: 'Book', horizontal: false, enablePaging: false, spreadMode: 'book' },
] as const;

/** `fitPolicy` values cycled by the toolbar button; `undefined` = default. */
const FITS = [
  { title: 'Fit auto', fitPolicy: undefined },
  { title: 'Fit W', fitPolicy: 'width' },
  { title: 'Fit H', fitPolicy: 'height' },
  { title: 'Fit P', fitPolicy: 'page' },
] as const;

/**
 * Where each document was left, by entry id, for "Resume" in the list. In
 * memory only; a real app would persist it (e.g. AsyncStorage / MMKV).
 */
const savedViewports = new Map<string, PdfViewport>();

/** Page images a long press on "Image" requests at once. */
const BURST_IMAGES = 200;

type LoadState =
  | { kind: 'idle' }
  | { kind: 'resolving' }
  | { kind: 'loading'; source: DocumentSource }
  | { kind: 'loaded'; source: DocumentSource; pageCount: number }
  | { kind: 'failed'; source: DocumentSource | null; error: PdfError | string };

export function ViewerScreen() {
  const ref = useRef<PdfViewRef>(null);
  const [entry, setEntry] = useState<DocumentEntry | null>(null);
  const [state, setState] = useState<LoadState>({ kind: 'idle' });
  const [page, setPage] = useState(0);
  const [password, setPassword] = useState<string | undefined>(undefined);
  const [passwordDraft, setPasswordDraft] = useState('');
  // Remounts the secure input per attempt; resetting a controlled secure
  // TextInput to '' does not always clear the native Android field.
  const [attempt, setAttempt] = useState(0);
  const [stats, setStats] = useState<PdfViewStats | null>(null);
  /** Last link press / page tap, shown under the toolbar. */
  const [lastEvent, setLastEvent] = useState('');
  const [showIndicators, setShowIndicators] = useState(true);
  const [layoutIndex, setLayoutIndex] = useState(0);
  const layout = LAYOUTS[layoutIndex] ?? LAYOUTS[0];
  const [fitIndex, setFitIndex] = useState(0);
  const [showThumbnails, setShowThumbnails] = useState(false);
  const [scrollEnabled, setScrollEnabled] = useState(true);
  const [showSearch, setShowSearch] = useState(false);
  const [search, setSearch] = useState<PdfSearchChangeEvent | null>(null);
  const [customSearchColor, setCustomSearchColor] = useState(false);
  /** Opened with "Resume": the saved position, applied on load. */
  const [resumeViewport, setResumeViewport] = useState<PdfViewport>();
  const [viewport, setViewport] = useState<PdfViewportChangeEvent | null>(null);
  /** Last ref.renderPage result, previewed over the document. */
  const [pageImage, setPageImage] = useState<PdfPageImage | null>(null);
  /** "Image" alternates PNG and JPEG, starting with PNG per document. */
  const renders = useRef(0);
  /** Set by "Mark", restored by "Return" (ref.goToViewport). */
  const mark = useRef<PdfViewport | null>(null);
  const [doubleTapZoom, setDoubleTapZoom] = useState(true);
  const [textSelection, setTextSelection] = useState(true);
  const [formFilling, setFormFilling] = useState(true);
  /** The displayed file, from onLoad. */
  const [loadedFilePath, setLoadedFilePath] = useState<string>();
  /** onLoad events since the entry was opened: each one is a (re)load. */
  const [loadCount, setLoadCount] = useState(0);
  /** Showing the entry's `alternate` source ("Swap"). */
  const isAlternate = useRef(false);
  /** The latest open or "Swap": only its source, or its failure, is applied. */
  const sourceRequest = useRef(0);
  const [isCollapsed, setCollapsed] = useState(false);
  /** Counts onTextSelectionChange events: one per settled selection. */
  const selectionEvents = useRef(0);
  /** Counts onScaleChange events: one per settled zoom, never per frame. */
  const scaleEvents = useRef(0);
  const [outline, setOutline] = useState<PdfOutlineItem[] | null>(null);
  const fit = FITS[fitIndex] ?? FITS[0];
  const [progress, setProgress] = useState<PdfLoadProgressEvent | null>(null);

  const open = useCallback(
    async (next: DocumentEntry, resume?: PdfViewport) => {
      setEntry(next);
      setResumeViewport(resume);
      setCollapsed(next.onLoadAtZeroWidth != null);
      setViewport(null);
      setPageImage(null);
      renders.current = 0;
      mark.current = null;
      setPassword(undefined);
      setPasswordDraft('');
      setAttempt((value) => value + 1);
      setPage(0);
      setStats(null);
      setLastEvent('');
      setProgress(null);
      setOutline(null);
      setSearch(null);
      setShowSearch(false);
      setLoadCount(0);
      isAlternate.current = false;
      sourceRequest.current += 1;
      const request = sourceRequest.current;
      scaleEvents.current = 0;
      selectionEvents.current = 0;
      setState({ kind: 'resolving' });
      try {
        const source = await next.resolve();
        if (request === sourceRequest.current) {
          setState({ kind: 'loading', source });
        }
      } catch (error) {
        if (request === sourceRequest.current) {
          setState({ kind: 'failed', source: null, error: String(error) });
        }
      }
    },
    [],
  );

  const swap = async (target: DocumentEntry) => {
    isAlternate.current = !isAlternate.current;
    sourceRequest.current += 1;
    const request = sourceRequest.current;
    const resolve = isAlternate.current ? target.alternate : target.resolve;
    if (resolve == null) return;
    try {
      const source = await resolve();
      if (request === sourceRequest.current) {
        setState({ kind: 'loading', source });
      }
    } catch (error) {
      if (request === sourceRequest.current) {
        setState({ kind: 'failed', source: null, error: String(error) });
      }
    }
  };

  if (entry == null) {
    return (
      <ScrollView contentContainerStyle={styles.list}>
        {DOCUMENTS.map((document) => {
          const saved = savedViewports.get(document.id);
          return (
            <View key={document.id} style={styles.rowWithAction}>
              <Pressable style={styles.rowMain} onPress={() => open(document)}>
                <Text style={styles.rowTitle}>{document.title}</Text>
                <Text style={styles.rowSubtitle}>{document.subtitle}</Text>
              </Pressable>
              {saved != null && (
                <Button
                  title={`Resume p. ${saved.page + 1}`}
                  onPress={() => open(document, saved)}
                />
              )}
            </View>
          );
        })}
      </ScrollView>
    );
  }

  const unlock = (value: string) => {
    if (state.kind !== 'failed' || state.source == null) return;
    setState({ kind: 'loading', source: state.source });
    setPassword(value);
    setPasswordDraft('');
    setAttempt((current) => current + 1);
  };

  const source =
    state.kind === 'loading' ||
    state.kind === 'loaded' ||
    state.kind === 'failed'
      ? state.source
      : null;
  const pageCount = state.kind === 'loaded' ? state.pageCount : 0;
  const error = state.kind === 'failed' ? state.error : null;

  return (
    <View style={styles.fill}>
      <View style={styles.toolbar}>
        <Button title="‹ Back" onPress={() => setEntry(null)} />
        <Text style={styles.pageLabel}>
          {pageCount > 0 ? `${page + 1} / ${pageCount}` : entry.title}
        </Text>
        <Button
          title="TOC"
          onPress={() => {
            const started = performance.now();
            ref.current
              ?.getOutline()
              .then((items) => {
                const ms = Math.round(performance.now() - started);
                setLastEvent(`outline: ${items.length} top-level in ${ms} ms`);
                setOutline(items);
              })
              .catch((error: unknown) =>
                setLastEvent(`outline: ${String(error)}`),
              );
          }}
        />
        <Button
          title={doubleTapZoom ? '2×tap on' : '2×tap off'}
          onPress={() => setDoubleTapZoom((value) => !value)}
        />
        <Button
          title="Stats"
          onPress={() => setStats(ref.current?.getStats() ?? null)}
        />
      </View>
      <View style={[styles.toolbar, styles.wrap]}>
        <Button
          title={showIndicators ? 'Bars on' : 'Bars off'}
          onPress={() => setShowIndicators((value) => !value)}
        />
        <Button
          title={layout.title}
          onPress={() =>
            setLayoutIndex((index) => (index + 1) % LAYOUTS.length)
          }
        />
        <Button
          title={fit.title}
          onPress={() => setFitIndex((index) => (index + 1) % FITS.length)}
        />
        <Button
          title={scrollEnabled ? 'Drag on' : 'Drag off'}
          onPress={() => setScrollEnabled((value) => !value)}
        />
        <Button
          title={showThumbnails ? 'Thumbs on' : 'Thumbs off'}
          onPress={() => setShowThumbnails((value) => !value)}
        />
        <Button
          title="Find"
          onPress={() => {
            if (showSearch) ref.current?.clearSearch();
            setShowSearch((value) => !value);
          }}
        />
        <Button
          title="Sizes"
          onPress={() => {
            const started = performance.now();
            ref.current
              ?.getPageSizes()
              .then((sizes) => {
                const ms = Math.round(performance.now() - started);
                const list = sizes
                  .slice(0, 8)
                  .map(
                    (size) =>
                      `${Math.round(size.width)}x${Math.round(size.height)}`,
                  )
                  .join(' ');
                setLastEvent(`sizes ${sizes.length}: ${list} (${ms} ms)`);
              })
              .catch((error: unknown) =>
                setLastEvent(`sizes: ${String(error)}`),
              );
          }}
        />
        <Button
          title="Image"
          onPress={() => {
            const started = performance.now();
            const format = renders.current++ % 2 === 0 ? 'png' : 'jpeg';
            ref.current
              ?.renderPage(page, { width: 300, format })
              .then((image) => {
                const ms = Math.round(performance.now() - started);
                const file = image.uri.split('.').pop();
                setLastEvent(
                  `image p${page + 1}: ${image.width}x${image.height} ${file} (${ms} ms)`,
                );
                setPageImage(image);
              })
              .catch((error: unknown) =>
                setLastEvent(`image: ${String(error)}`),
              );
          }}
          onLongPress={() => {
            // A list's images requested at once (probe-render-burst), with
            // the page sizes asked for right after the first one.
            const view = ref.current;
            if (view == null || pageCount === 0) return;
            const started = performance.now();
            // When the first screenful of a list (images 1–10) is there.
            let firstTenMs = 0;
            let firstTenLeft = 10;
            const arrived: string[] = [];
            const image = (index: number) =>
              view.renderPage(index % pageCount, { width: 300 }).then(() => {
                arrived.push(`image ${index}`);
                if (index < 10 && --firstTenLeft === 0) {
                  firstTenMs = Math.round(performance.now() - started);
                }
              });
            const calls = [
              'image 0',
              'sizes',
              ...Array.from(
                { length: BURST_IMAGES - 1 },
                (_, i) => `image ${i + 1}`,
              ),
            ];
            setLastEvent(`burst ${BURST_IMAGES}: rendering`);
            Promise.all([
              image(0),
              view.getPageSizes().then(() => arrived.push('sizes')),
              ...Array.from({ length: BURST_IMAGES - 1 }, (_, i) =>
                image(i + 1),
              ),
            ])
              .then(() => {
                const ms = Math.round(performance.now() - started);
                const inOrder = arrived.every(
                  (call, index) => call === calls[index],
                );
                const sizesAt = arrived.indexOf('sizes') + 1;
                setLastEvent(
                  `burst ${BURST_IMAGES}: ${inOrder ? 'in call order' : 'out of order'} (sizes ${sizesAt}/${arrived.length}, first 10 ${firstTenMs} ms, all ${ms} ms)`,
                );
              })
              .catch((error: unknown) =>
                setLastEvent(`burst: ${String(error)}`),
              );
          }}
        />
        <Button
          title="Mark"
          onPress={() => {
            mark.current = viewport?.viewport ?? null;
          }}
        />
        <Button
          title="Return"
          onPress={() => {
            if (mark.current != null) ref.current?.goToViewport(mark.current);
          }}
        />
        <Button
          title="Print"
          onPress={() => {
            ref.current
              ?.print()
              .then((result) =>
                setLastEvent(
                  `print: ${result.isSubmitted ? 'submitted' : 'cancelled'}`,
                ),
              )
              .catch((error: unknown) =>
                setLastEvent(`print: ${String(error)}`),
              );
          }}
        />
      </View>

      {entry.form === true && (
        <FormBar
          pdf={ref}
          sourcePath={loadedFilePath}
          isDownloaded={
            typeof source === 'object' &&
            source != null &&
            'uri' in source &&
            /^https?:/i.test(source.uri)
          }
          isFillingEnabled={formFilling}
          onToggleFilling={() => setFormFilling((value) => !value)}
          onEvent={setLastEvent}
          onOpen={(filePath) =>
            void open({
              id: 'exported-form',
              title: 'Exported form',
              subtitle: filePath,
              resolve: async () => ({ uri: `file://${filePath}` }),
              form: true,
            })
          }
        />
      )}

      {entry.alternate != null && (
        <View style={styles.toolbar}>
          <Button title="Swap" onPress={() => swap(entry)} />
          <Button
            title="Same"
            // An equal source in a new object: PdfView must not reload.
            onPress={() =>
              setState((current) =>
                current.kind === 'loaded'
                  ? { ...current, source: copySource(current.source) }
                  : current,
              )
            }
          />
          <Text testID="load-count" style={styles.pageLabel}>
            {`loads: ${loadCount}`}
          </Text>
        </View>
      )}

      {showSearch && (
        <SearchBar
          pdf={ref}
          state={search}
          isCustomColor={customSearchColor}
          onToggleColor={() => setCustomSearchColor((value) => !value)}
        />
      )}

      {showThumbnails && source != null && pageCount > 0 && (
        <ThumbnailStrip
          source={source}
          pageCount={pageCount}
          currentPage={page}
          onSelect={(target) => ref.current?.goToPage(target)}
        />
      )}

      {/* Always rendered: a row appearing mid-gesture would resize the viewer. */}
      <View style={styles.eventRow}>
        <Text style={styles.lastEvent} numberOfLines={1}>
          {lastEvent === '' ? ' ' : lastEvent}
        </Text>
        <Text testID="viewport-status" style={styles.viewport}>
          {formatViewport(viewport)}
        </Text>
      </View>

      <View style={styles.fill}>
        {source != null && (
          <PdfView
            ref={ref}
            style={[styles.pdf, isCollapsed && styles.collapsed]}
            source={source}
            password={password}
            showsVerticalScrollIndicator={showIndicators}
            showsHorizontalScrollIndicator={showIndicators}
            horizontal={layout.horizontal}
            enablePaging={layout.enablePaging}
            enableRTL={'enableRTL' in layout && layout.enableRTL}
            spreadMode={'spreadMode' in layout ? layout.spreadMode : 'none'}
            fitPolicy={fit.fitPolicy}
            scrollEnabled={scrollEnabled}
            enableDoubleTapZoom={doubleTapZoom}
            enableTextSelection={textSelection}
            enableFormFilling={formFilling}
            contentInset={entry.contentInset}
            onFormChange={(event) =>
              setLastEvent(`form: ${describeField(event.field)}`)
            }
            onLoadProgress={setProgress}
            initialPage={entry.initialPage}
            initialScale={entry.initialScale}
            initialViewport={resumeViewport}
            onViewportChange={(event) => {
              setViewport(event);
              savedViewports.set(entry.id, event.viewport);
            }}
            onLoad={(event) => {
              setState({ kind: 'loaded', source, pageCount: event.pageCount });
              setLoadCount((count) => count + 1);
              setLoadedFilePath(event.filePath);
              // The local file behind the document, e.g. for sharing; with
              // several sources, where each one's pages start.
              setLastEvent(
                event.sources.length > 1
                  ? `sources: ${event.sources.map((item) => `p${item.firstPage + 1}+${item.pageCount}`).join(' ')}`
                  : `file: ${event.filePath?.split('/').slice(-2).join('/') ?? 'none'}`,
              );
              if (entry.onLoadAtZeroWidth != null) {
                if (ref.current != null) entry.onLoadAtZeroWidth(ref.current);
                setTimeout(() => setCollapsed(false), 500);
              }
            }}
            onPageChange={(event) => setPage(event.page)}
            // A real app would validate the scheme and call Linking.openURL.
            onLinkPress={(event) => setLastEvent(`link: ${event.url}`)}
            onSearchChange={setSearch}
            searchHighlightColor={
              customSearchColor ? 'rgba(0, 122, 255, 0.3)' : undefined
            }
            searchCurrentHighlightColor={
              customSearchColor ? 'rgba(0, 122, 255, 0.6)' : undefined
            }
            onScaleChange={(event) => {
              scaleEvents.current += 1;
              setLastEvent(
                `scale: ${event.scale.toFixed(2)} (#${scaleEvents.current})`,
              );
            }}
            onTextSelectionChange={(event) => {
              selectionEvents.current += 1;
              setLastEvent(
                `selection #${selectionEvents.current}: "${event.text.replace(/\s+/g, ' ').trim()}"`,
              );
            }}
            onPageTap={(event) =>
              setLastEvent(
                `tap: page ${event.page + 1} at ${Math.round(event.x)}, ${Math.round(event.y)} pt`,
              )
            }
            onError={(pdfError) =>
              setState({ kind: 'failed', source, error: pdfError })
            }
          >
            {entry.overlays === true && (
              <DemoOverlays
                onPress={(name) => setLastEvent(`overlay: ${name}`)}
              />
            )}
          </PdfView>
        )}
        {entry.contentInset != null && (
          <>
            <View
              pointerEvents="none"
              style={[
                styles.insetBar,
                { top: 0, height: entry.contentInset.top },
              ]}
            >
              <Text style={styles.insetText}>Header</Text>
            </View>
            <View
              pointerEvents="none"
              style={[
                styles.insetBar,
                { bottom: 0, height: entry.contentInset.bottom },
              ]}
            >
              <Text style={styles.insetText}>Toolbar</Text>
            </View>
          </>
        )}
        {pageImage != null && (
          <Pressable
            testID="page-image"
            style={styles.pageImage}
            onPress={() => setPageImage(null)}
          >
            <Image
              source={{ uri: pageImage.uri }}
              style={{
                width: 120,
                height: (120 * pageImage.height) / pageImage.width,
              }}
            />
          </Pressable>
        )}
        {(state.kind === 'resolving' || state.kind === 'loading') && (
          <View style={styles.overlay} pointerEvents="none">
            <ActivityIndicator size="large" />
            {progress != null && (
              <Text style={styles.progress}>{formatProgress(progress)}</Text>
            )}
          </View>
        )}
        {error != null && (
          <View style={styles.overlay}>
            <View style={styles.errorCard}>
              {typeof error === 'string' ? (
                <Text style={styles.errorText}>{error}</Text>
              ) : (
                <>
                  <Text style={styles.errorCode}>{error.code}</Text>
                  {Array.isArray(source) && error.sourceIndex != null && (
                    <Text style={styles.errorText}>
                      {`source ${error.sourceIndex + 1} of ${source.length}`}
                    </Text>
                  )}
                  <Text style={styles.errorText}>{error.message}</Text>
                  {error.nativeDescription != null && (
                    <Text style={styles.errorNative}>
                      {error.nativeDescription}
                    </Text>
                  )}
                  {(error.code === 'password-required' ||
                    error.code === 'invalid-password') && (
                    <View style={styles.passwordRow}>
                      <Button
                        title="Try wrong"
                        onPress={() => unlock('wrong')}
                      />
                      <Button
                        title="Try fixture"
                        onPress={() => unlock('secret')}
                      />
                    </View>
                  )}
                  {(error.code === 'password-required' ||
                    error.code === 'invalid-password') && (
                    <View style={styles.passwordRow}>
                      <TextInput
                        key={attempt}
                        style={styles.input}
                        placeholder="Password (fixture: secret)"
                        secureTextEntry
                        autoCapitalize="none"
                        value={passwordDraft}
                        onChangeText={setPasswordDraft}
                      />
                      <Button
                        title="Unlock"
                        onPress={() => unlock(passwordDraft)}
                      />
                    </View>
                  )}
                </>
              )}
            </View>
          </View>
        )}
        {outline != null && (
          <OutlinePanel
            items={outline}
            onClose={() => setOutline(null)}
            onSelect={(target) => {
              setOutline(null);
              ref.current?.goToPage(target, { animated: false });
            }}
          />
        )}
        {stats != null && (
          <Pressable style={styles.stats} onPress={() => setStats(null)}>
            <Text style={styles.statsText}>{formatStats(stats)}</Text>
          </Pressable>
        )}
      </View>

      <View style={styles.toolbar}>
        <Button title="First" onPress={() => ref.current?.goToPage(0)} />
        <Button
          title="−10"
          onPress={() => ref.current?.goToPage(Math.max(0, page - 10))}
        />
        <Button title="+10" onPress={() => ref.current?.goToPage(page + 10)} />
        <Button
          title="Last"
          onPress={() =>
            ref.current?.goToPage(pageCount - 1, { animated: false })
          }
        />
        <Button title="1×" onPress={() => ref.current?.zoomTo(1)} />
        <Button title="2.5×" onPress={() => ref.current?.zoomTo(2.5)} />
        {/* Bottom row: a top-row button would push "Stats" under the dev-client menu button. */}
        <Button
          title={textSelection ? 'Sel on' : 'Sel off'}
          onPress={() => setTextSelection((value) => !value)}
        />
      </View>
    </View>
  );
}

/** An equal source in new objects (bundled assets are numbers). */
function copySource(source: DocumentSource): DocumentSource {
  const copy = (item: PdfSource) =>
    typeof item === 'object' ? { ...item } : item;
  return Array.isArray(source) ? source.map(copy) : copy(source as PdfSource);
}

/** "p 3 · y 120 · 1.00x", plus "· end" once the end is on screen. */
function formatViewport(event: PdfViewportChangeEvent | null): string {
  if (event == null) return '';
  const { page, y, scale } = event.viewport;
  const end = event.isAtEnd ? ' · end' : '';
  return `p ${page + 1} · y ${Math.round(y)} · ${scale.toFixed(2)}x${end}`;
}

function formatProgress({
  loadedBytes,
  totalBytes,
}: PdfLoadProgressEvent): string {
  const mb = (bytes: number) => (bytes / 1024 / 1024).toFixed(1);
  if (totalBytes === undefined) return `Downloading ${mb(loadedBytes)} MB`;
  const percent = Math.floor((loadedBytes / totalBytes) * 100);
  return `Downloading ${percent}% (${mb(loadedBytes)} / ${mb(totalBytes)} MB)`;
}

function formatStats(stats: PdfViewStats): string {
  const count = (value: number) => (value < 0 ? 'n/a' : `${value}`);
  const mb = (bytes: number) =>
    bytes < 0 ? 'n/a' : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return [
    `pages ${stats.pageCount}  resident ${count(stats.residentPages)}`,
    `cache ${mb(stats.cacheBytes)} / ${mb(stats.cacheBudgetBytes)}`,
    `hits ${count(stats.cacheHits)}  misses ${count(stats.cacheMisses)}`,
    `rendered ${count(stats.pagesRendered)}  cancelled ${count(stats.rendersCancelled)}`,
    `bitmap allocs ${count(stats.bitmapAllocations)}  mean ${stats.meanRenderMs < 0 ? 'n/a' : `${stats.meanRenderMs.toFixed(1)} ms`}`,
  ].join('\n');
}

/**
 * Overlays in PDF points from a page's top-left: a signature field that
 * scales with the page, a pin that keeps its size, and a note on page 3.
 */
function DemoOverlays({ onPress }: { onPress: (name: string) => void }) {
  return (
    <>
      <PdfPageOverlay page={0} x={50} y={110} width={220} height={44}>
        <Pressable
          testID="overlay-sign"
          style={styles.overlayField}
          onPress={() => onPress('sign here')}
        >
          <Text style={styles.overlayText}>Sign here</Text>
        </Pressable>
      </PdfPageOverlay>
      <PdfPageOverlay
        page={0}
        x={480}
        y={110}
        width={28}
        height={28}
        scalesWithZoom={false}
      >
        <Pressable
          testID="overlay-pin"
          style={styles.overlayPin}
          onPress={() => onPress('pin')}
        />
      </PdfPageOverlay>
      <PdfPageOverlay page={2} x={50} y={110} width={220} height={44}>
        <Pressable
          testID="overlay-note"
          style={styles.overlayField}
          onPress={() => onPress('page 3 note')}
        >
          <Text style={styles.overlayText}>Page 3 note</Text>
        </Pressable>
      </PdfPageOverlay>
    </>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  pdf: { flex: 1, backgroundColor: colors.canvas },
  collapsed: { width: 0 },
  overlayField: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 2,
    borderRadius: 4,
    borderColor: colors.accent,
    backgroundColor: 'rgba(37, 99, 235, 0.12)',
  },
  overlayText: { color: colors.accent, fontSize: 16, fontWeight: '600' },
  insetBar: {
    position: 'absolute',
    left: 0,
    right: 0,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(37, 99, 235, 0.35)',
  },
  insetText: { color: '#FFFFFF', fontWeight: '600' },
  overlayPin: {
    flex: 1,
    borderRadius: 14,
    borderWidth: 2,
    borderColor: '#FFFFFF',
    backgroundColor: colors.danger,
  },
  list: { padding: 12, gap: 8 },
  rowWithAction: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: 10,
    backgroundColor: colors.card,
    paddingRight: 8,
  },
  rowMain: { flex: 1, padding: 14 },
  rowTitle: { fontSize: 16, fontWeight: '600', color: colors.text },
  rowSubtitle: { fontSize: 13, color: colors.muted, marginTop: 2 },
  // Many toggles: wrap on narrow screens rather than push the last off-screen.
  wrap: { flexWrap: 'wrap', justifyContent: 'flex-start' },
  toolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 8,
    paddingVertical: 6,
    gap: 4,
    backgroundColor: colors.card,
  },
  progress: {
    marginTop: 12,
    fontSize: 14,
    color: colors.text,
    fontVariant: ['tabular-nums'],
  },
  eventRow: {
    flexDirection: 'row',
    paddingHorizontal: 12,
    paddingVertical: 4,
    gap: 8,
    backgroundColor: colors.card,
  },
  lastEvent: { flex: 1, fontSize: 13, color: colors.muted },
  viewport: {
    fontSize: 13,
    color: colors.muted,
    fontVariant: ['tabular-nums'],
  },
  pageLabel: {
    fontSize: 15,
    fontWeight: '600',
    color: colors.text,
    fontVariant: ['tabular-nums'],
  },
  pageImage: {
    position: 'absolute',
    top: 8,
    right: 8,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.muted,
    backgroundColor: colors.card,
  },
  overlay: {
    ...StyleSheet.absoluteFill,
    alignItems: 'center',
    justifyContent: 'center',
  },
  errorCard: {
    margin: 24,
    padding: 16,
    borderRadius: 12,
    backgroundColor: colors.card,
    gap: 6,
  },
  errorCode: { fontSize: 16, fontWeight: '700', color: colors.danger },
  errorText: { fontSize: 14, color: colors.text },
  errorNative: { fontSize: 12, color: colors.muted },
  passwordRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 8,
    alignItems: 'center',
  },
  input: {
    flex: 1,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.muted,
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 8,
    color: colors.text,
  },
  stats: {
    position: 'absolute',
    top: 8,
    right: 8,
    padding: 10,
    borderRadius: 8,
    backgroundColor: 'rgba(0,0,0,0.75)',
  },
  statsText: {
    color: 'white',
    fontSize: 12,
    fontFamily: 'Courier',
    lineHeight: 16,
  },
});

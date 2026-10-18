import { useCallback, useEffect, useRef, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import {
  PdfCache,
  PdfDownloadError,
  PdfView,
  type PdfCacheEntry,
  type PdfError,
  type PdfUriSource,
} from 'react-native-better-pdf';
import { FIXTURE_SERVER } from '../documents';
import { Button, colors } from '../ui';

interface OfflineDocument {
  id: string;
  title: string;
  source: PdfUriSource;
}

/**
 * Documents kept for offline reading. Every open asks the server whether
 * the stored copy is still current (`maxAgeMs: 0`); `cacheKey` keeps the copy
 * when the URL changes (here: the throttling query).
 */
const OFFLINE_DOCUMENTS: OfflineDocument[] = [
  {
    id: 'report',
    title: 'Report',
    source: {
      uri: `${FIXTURE_SERVER}/small.pdf`,
      cache: 'persistent',
      cacheKey: 'offline-report',
      maxAgeMs: 0,
    },
  },
  {
    id: 'manual',
    title: 'Manual (slow)',
    source: {
      uri: `${FIXTURE_SERVER}/medium.pdf?bytesPerSecond=2000000`,
      cache: 'persistent',
      cacheKey: 'offline-manual',
      maxAgeMs: 0,
    },
  },
  {
    id: 'brochure',
    title: 'Brochure (session)',
    source: {
      uri: `${FIXTURE_SERVER}/mixed-sizes.pdf`,
      cache: 'session',
      cacheKey: 'offline-brochure',
      maxAgeMs: 0,
    },
  },
];

type RowState =
  | { kind: 'idle' }
  | { kind: 'downloading'; percent?: number; controller: AbortController }
  | { kind: 'failed'; message: string };

const mb = (bytes: number) => (bytes / 1_000_000).toFixed(1);
const time = (ms: number) => new Date(ms).toLocaleTimeString();

/** Simulates losing the network: the fixture server drops connections. */
async function setServerOnline(online: boolean) {
  await fetch(`${FIXTURE_SERVER}/__control/${online ? 'online' : 'offline'}`);
}

export function OfflineScreen() {
  const [entries, setEntries] = useState<
    Record<string, PdfCacheEntry | undefined>
  >({});
  const [rows, setRows] = useState<Record<string, RowState>>({});
  const [byteSize, setByteSize] = useState(0);
  const [online, setOnline] = useState(true);
  const [opened, setOpened] = useState<OfflineDocument | null>(null);

  const refresh = useCallback(async () => {
    const found = await Promise.all(
      OFFLINE_DOCUMENTS.map(
        async (document) =>
          [document.id, await PdfCache.getEntry(document.source)] as const,
      ),
    );
    setEntries(Object.fromEntries(found));
    setByteSize(await PdfCache.getByteSize());
  }, []);

  useEffect(() => {
    setServerOnline(true).catch(() => undefined);
    // Not synchronous: `refresh` sets state only after an `await`, which the
    // rule does not look past.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refresh();
  }, [refresh]);

  const setRow = (id: string, state: RowState) =>
    setRows((current) => ({ ...current, [id]: state }));

  const download = async (document: OfflineDocument) => {
    const controller = new AbortController();
    setRow(document.id, { kind: 'downloading', controller });
    try {
      await PdfCache.download(document.source, {
        signal: controller.signal,
        onProgress: ({ loadedBytes, totalBytes }) =>
          setRow(document.id, {
            kind: 'downloading',
            controller,
            percent:
              totalBytes === undefined
                ? undefined
                : Math.round((loadedBytes / totalBytes) * 100),
          }),
      });
      setRow(document.id, { kind: 'idle' });
    } catch (error) {
      const message =
        error instanceof PdfDownloadError
          ? error.code
          : error instanceof Error && error.name === 'AbortError'
            ? 'cancelled'
            : String(error);
      setRow(document.id, { kind: 'failed', message });
    }
    await refresh();
  };

  const remove = async (document: OfflineDocument) => {
    await PdfCache.remove(document.source);
    setRow(document.id, { kind: 'idle' });
    await refresh();
  };

  if (opened != null) {
    return (
      <OfflineReader
        document={opened}
        onClose={() => {
          setOpened(null);
          refresh();
        }}
      />
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.list}>
      {/* Buttons on the left: the dev client's floating button sits top right. */}
      <View style={styles.listToolbar}>
        <Button
          title={online ? 'Go offline' : 'Go online'}
          onPress={async () => {
            await setServerOnline(!online);
            setOnline(!online);
          }}
        />
        <Text testID="offline-server" style={styles.label}>
          {online ? 'Server online' : 'Server offline'}
        </Text>
      </View>
      <View style={styles.listToolbar}>
        <Button
          title="Clear all"
          onPress={async () => {
            await PdfCache.clear();
            setRows({});
            await refresh();
          }}
        />
        <Text testID="offline-storage" style={styles.label}>
          Storage: {mb(byteSize)} MB
        </Text>
      </View>
      {OFFLINE_DOCUMENTS.map((document) => {
        const entry = entries[document.id];
        const row = rows[document.id] ?? { kind: 'idle' };
        const status =
          row.kind === 'downloading'
            ? `Downloading ${row.percent ?? '…'}%`
            : row.kind === 'failed'
              ? `Failed: ${row.message}`
              : entry === undefined
                ? 'Not downloaded'
                : `Saved (${entry.cache}) ${mb(entry.byteSize)} MB, checked ${time(entry.validatedAtMs)}`;
        return (
          <View key={document.id} style={styles.row}>
            <Text style={styles.title}>{document.title}</Text>
            <Text testID={`${document.id}-status`} style={styles.status}>
              {status}
            </Text>
            <View style={styles.actions}>
              {row.kind === 'downloading' ? (
                <Button
                  title={`Cancel ${document.title}`}
                  onPress={() => row.controller.abort()}
                />
              ) : (
                <Button
                  title={`Download ${document.title}`}
                  onPress={() => download(document)}
                />
              )}
              <Button
                title={`Remove ${document.title}`}
                onPress={() => remove(document)}
              />
              <Button
                title={`Open ${document.title}`}
                onPress={() => setOpened(document)}
              />
            </View>
          </View>
        );
      })}
    </ScrollView>
  );
}

function OfflineReader({
  document,
  onClose,
}: {
  document: OfflineDocument;
  onClose: () => void;
}) {
  const [page, setPage] = useState(0);
  const [pageCount, setPageCount] = useState(0);
  const [isStale, setIsStale] = useState(false);
  const [error, setError] = useState<PdfError | null>(null);
  /** Whether this open downloaded the document, or used the stored copy. */
  const downloaded = useRef(false);
  const [origin, setOrigin] = useState('');

  return (
    <View style={styles.fill}>
      <View style={styles.toolbar}>
        <Button title="‹ Back" onPress={onClose} />
        <Text style={styles.label}>
          {pageCount > 0 ? `${page + 1} / ${pageCount}` : ''}
        </Text>
      </View>
      {isStale && (
        <Text testID="offline-stale" style={styles.banner}>
          Offline copy, may be outdated
        </Text>
      )}
      {origin !== '' && <Text style={styles.status}>{origin}</Text>}
      {error != null && <Text style={styles.error}>{error.code}</Text>}
      <PdfView
        style={styles.fill}
        source={document.source}
        onLoadProgress={() => {
          downloaded.current = true;
        }}
        onLoad={(event) => {
          setPageCount(event.pageCount);
          setIsStale(event.isStale);
          setOrigin(
            downloaded.current ? 'Downloaded' : 'Opened from the stored copy',
          );
        }}
        onPageChange={(event) => setPage(event.page)}
        onError={setError}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: colors.canvas },
  list: { padding: 12, gap: 12 },
  listToolbar: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  toolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    paddingHorizontal: 8,
  },
  label: { color: colors.text, fontSize: 14, fontWeight: '600' },
  row: { backgroundColor: colors.card, borderRadius: 10, padding: 12, gap: 4 },
  title: { color: colors.text, fontSize: 16, fontWeight: '600' },
  status: { color: colors.muted, fontSize: 13, paddingHorizontal: 8 },
  actions: { flexDirection: 'row', flexWrap: 'wrap' },
  banner: {
    backgroundColor: '#FEF3C7',
    color: colors.text,
    padding: 8,
    textAlign: 'center',
  },
  error: { color: colors.danger, padding: 8, fontWeight: '600' },
});

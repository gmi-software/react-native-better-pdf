import { useEffect, useRef, useState } from 'react';
import { Image, ScrollView, StyleSheet, Text, View } from 'react-native';
import {
  PdfDocument,
  PdfOpenError,
  type PdfDocumentMetadata,
  type PdfOutlineItem,
  type PdfPageImage,
  type PdfSource,
} from 'react-native-better-pdf';
import { ensureFixture, FIXTURE_SERVER, type FixtureName } from '../documents';
import { Button, colors } from '../ui';

interface Target {
  title: string;
  resolve: () => Promise<PdfSource>;
}

const fixture = (name: FixtureName): Target => ({
  title: name,
  resolve: async () => ({ uri: (await ensureFixture(name)).uri }),
});

/** Documents opened with `PdfDocument`, without a view. */
const TARGETS: Target[] = [
  {
    title: 'sample',
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    resolve: async () => require('../assets/sample.pdf') as number,
  },
  fixture('metadata'),
  {
    title: 'metadata (remote)',
    resolve: async () => ({ uri: `${FIXTURE_SERVER}/metadata.pdf` }),
  },
  {
    title: 'metadata (base64)',
    resolve: async () => ({
      base64: await (await ensureFixture('metadata')).base64(),
    }),
  },
  fixture('metadata-encrypted'),
  fixture('page-boxes'),
  fixture('outline'),
  fixture('malformed-not-a-pdf'),
  // For timing: 100 pages with photos, and a 1000-page scan (414 MB).
  fixture('medium'),
  fixture('extreme'),
];

const MAX_THUMBNAILS = 12;

/** An open error's code, else the message's first line (on Android the stack follows). */
function describeError(error: unknown): string {
  if (error instanceof PdfOpenError) return error.code;
  const message = error instanceof Error ? error.message : String(error);
  return message.split('\n')[0] ?? message;
}

const date = (ms: number | undefined) =>
  ms === undefined ? '-' : new Date(ms).toISOString();

function metadataLines(metadata: PdfDocumentMetadata): string {
  return [
    `title: ${metadata.title ?? '-'}`,
    `author: ${metadata.author ?? '-'}`,
    `subject: ${metadata.subject ?? '-'}`,
    `keywords: ${metadata.keywords ?? '-'}`,
    `creator: ${metadata.creator ?? '-'}`,
    `producer: ${metadata.producer ?? '-'}`,
    `created: ${date(metadata.createdAtMs)}`,
    `modified: ${date(metadata.modifiedAtMs)}`,
  ].join('\n');
}

interface Timed<T> {
  outcome: { ok: true; value: T } | { ok: false; error: unknown };
  ms: number;
}

/** Runs `call`, timing it; never rejects. */
async function timed<T>(call: () => Promise<T>): Promise<Timed<T>> {
  const started = Date.now();
  try {
    const value = await call();
    return { outcome: { ok: true, value }, ms: Date.now() - started };
  } catch (error) {
    return { outcome: { ok: false, error }, ms: Date.now() - started };
  }
}

const countItems = (items: PdfOutlineItem[]): number =>
  items.reduce((sum, item) => sum + 1 + countItems(item.children), 0);

export function DocumentsScreen() {
  const document = useRef<PdfDocument | null>(null);
  const [target, setTarget] = useState<Target | null>(null);
  const [status, setStatus] = useState('Pick a document');
  const [lines, setLines] = useState<Record<string, string>>({});
  const [cover, setCover] = useState<PdfPageImage | null>(null);
  const [thumbnails, setThumbnails] = useState<PdfPageImage[]>([]);

  useEffect(() => () => document.current?.close(), []);

  const show = (key: string, text: string) =>
    setLines((current) => ({ ...current, [key]: text }));

  /** Shows a call's formatted value under `key`, or why it failed. */
  const showResult = <T,>(
    key: string,
    { outcome }: Timed<T>,
    format: (value: T) => string,
  ) =>
    show(
      key,
      outcome.ok
        ? format(outcome.value)
        : `${key} failed: ${describeError(outcome.error)}`,
    );

  const open = async (next: Target, password?: string) => {
    document.current?.close();
    document.current = null;
    setTarget(next);
    setLines({});
    setCover(null);
    setThumbnails([]);
    setStatus('Opening…');
    const source = await next.resolve();
    const opening = await timed(() => PdfDocument.open(source, { password }));
    if (!opening.outcome.ok) {
      setStatus(`error: ${describeError(opening.outcome.error)}`);
      return;
    }
    const opened = opening.outcome.value;
    document.current = opened;
    // Everything is read before the screen updates, so re-renders on the JS
    // thread do not count towards the timings.
    const metadata = await timed(() => opened.getMetadata());
    const sizes = await timed(() => opened.getPageSizes());
    const outline = await timed(() => opened.getOutline());
    const coverImage = await timed(() => opened.renderPage(0, { width: 300 }));
    if (document.current !== opened) return;
    setStatus(`opened in ${opening.ms} ms`);
    show(
      'summary',
      `pages ${opened.pageCount}, stale ${opened.isStale ? 'yes' : 'no'}, file ${opened.filePath === undefined ? 'no' : 'yes'}`,
    );
    showResult('metadata', metadata, metadataLines);
    showResult('sizes', sizes, (pageSizes) => {
      const shown = pageSizes
        .slice(0, MAX_THUMBNAILS)
        .map(({ width, height }) => `${width}x${height}`);
      const rest = pageSizes.length - shown.length;
      return `sizes: ${shown.join(' ')}${rest > 0 ? ` and ${rest} more` : ''}`;
    });
    showResult(
      'outline',
      outline,
      (items) =>
        `outline: ${items.length} top-level, ${countItems(items)} in all`,
    );
    showResult('cover', coverImage, (image) => {
      setCover(image);
      return `cover: ${image.width}x${image.height}`;
    });
    show(
      'timing',
      `reads: metadata ${metadata.ms}, sizes ${sizes.ms}, outline ${outline.ms}, cover ${coverImage.ms} ms`,
    );
  };

  const renderThumbnails = async () => {
    const opened = document.current;
    if (opened == null) return;
    const count = Math.min(opened.pageCount, MAX_THUMBNAILS);
    // Requested at once; the document renders them one after another.
    const images = await timed(() =>
      Promise.all(
        Array.from({ length: count }, (_, page) =>
          opened.renderPage(page, { width: 150, format: 'jpeg' }),
        ),
      ),
    );
    showResult('thumbnails', images, (rendered) => {
      setThumbnails(rendered);
      return `thumbnails: ${rendered.length} in ${images.ms} ms`;
    });
  };

  const close = async () => {
    const opened = document.current;
    if (opened == null) return;
    opened.close();
    opened.close();
    try {
      await opened.getMetadata();
      show('closed', 'after close: resolved');
    } catch (error) {
      show('closed', `after close: ${describeError(error)}`);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.targets}>
        {TARGETS.map((item) => (
          <Button
            key={item.title}
            title={item.title}
            selected={item === target}
            onPress={() => open(item)}
          />
        ))}
      </View>
      {target?.title === 'metadata-encrypted' && (
        <View style={styles.actions}>
          <Button title="Password" onPress={() => open(target, 'secret')} />
          <Button title="Wrong password" onPress={() => open(target, 'nope')} />
        </View>
      )}
      <Text testID="doc-status" style={styles.status}>
        {status}
      </Text>
      {['summary', 'metadata', 'sizes', 'outline', 'cover', 'timing'].map(
        (key) => (
          <Lines key={key} id={key} text={lines[key]} />
        ),
      )}
      {cover != null && (
        <Image
          testID="doc-cover-image"
          source={{ uri: cover.uri }}
          style={[styles.cover, { aspectRatio: cover.width / cover.height }]}
        />
      )}
      {document.current != null && (
        <View style={styles.actions}>
          <Button title="Thumbnails" onPress={renderThumbnails} />
          <Button title="Close" onPress={close} />
        </View>
      )}
      {['thumbnails', 'closed'].map((key) => (
        <Lines key={key} id={key} text={lines[key]} />
      ))}
      <View style={styles.thumbnails}>
        {thumbnails.map((image) => (
          <Image
            key={image.uri}
            source={{ uri: image.uri }}
            style={[
              styles.thumbnail,
              { aspectRatio: image.width / image.height },
            ]}
          />
        ))}
      </View>
    </ScrollView>
  );
}

/** One text element per line, so e2e flows can match each line. */
function Lines({ id, text }: { id: string; text: string | undefined }) {
  if (text === undefined) return null;
  return (
    <View testID={`doc-${id}`}>
      {text.split('\n').map((line) => (
        <Text key={line} style={styles.line}>
          {line}
        </Text>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  content: { padding: 12, gap: 8 },
  targets: { flexDirection: 'row', flexWrap: 'wrap', gap: 4 },
  actions: { flexDirection: 'row', gap: 8 },
  status: { color: colors.text, fontSize: 15, fontWeight: '600' },
  line: { color: colors.text, fontSize: 13, fontVariant: ['tabular-nums'] },
  cover: {
    width: 150,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.muted,
  },
  thumbnails: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    // Stretching would override the thumbnails' aspect ratio.
    alignItems: 'flex-start',
    gap: 6,
  },
  thumbnail: {
    width: 56,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.muted,
  },
});

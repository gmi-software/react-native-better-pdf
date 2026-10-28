import { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { PdfView, type PdfSource } from 'react-native-better-pdf';
import { ensureFixture, FIXTURE_SERVER } from '../documents';
import { colors } from '../ui';

export type ReopenSource = 'bundled' | 'file' | 'remote';

/** A load that takes longer than this counts as stalled. */
const STALL_MS = 10_000;

async function resolveSource(source: ReopenSource): Promise<PdfSource> {
  switch (source) {
    case 'bundled':
      // Metro bundles PDF assets through require().
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      return require('../assets/sample.pdf') as number;
    case 'file':
      return { uri: (await ensureFixture('small')).uri };
    case 'remote':
      return { uri: `${FIXTURE_SERVER}/small.pdf` };
  }
}

/**
 * Opens a document `count` times in a row, closing each viewer right after
 * its load event, the way a list of documents is browsed quickly. On iOS 26,
 * each viewer closed while PDFKit still analyzes its page leaves a thread
 * blocked for good; the viewer must keep loading documents regardless.
 */
export function ReopenScreen({
  count,
  source,
}: {
  count: number;
  source: ReopenSource;
}) {
  const [resolved, setResolved] = useState<PdfSource | null>(null);
  const [opened, setOpened] = useState(0);
  const [stalled, setStalled] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    resolveSource(source).then(setResolved, (e: Error) => setError(e.message));
  }, [source]);

  const isDone = opened >= count;
  useEffect(() => {
    if (resolved == null || isDone || error != null) return;
    const timer = setTimeout(() => setStalled(true), STALL_MS);
    return () => clearTimeout(timer);
  }, [resolved, opened, isDone, error]);

  let status = `Reopening ${opened} / ${count}`;
  if (isDone) status = `Reopened ${count} / ${count}`;
  else if (error != null) status = `Failed at ${opened + 1}: ${error}`;
  else if (stalled) status = `Stalled: open ${opened + 1} never loaded`;

  return (
    <View style={styles.fill}>
      <Text style={styles.status} testID="reopen-status">
        {status}
      </Text>
      {resolved != null && !isDone && !stalled && error == null && (
        <PdfView
          key={opened}
          source={resolved}
          style={styles.pdf}
          onLoad={() => setOpened((n) => n + 1)}
          onError={(pdfError) => setError(pdfError.code)}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: colors.card },
  status: {
    padding: 16,
    fontSize: 16,
    color: colors.text,
    fontVariant: ['tabular-nums'],
  },
  pdf: { flex: 1, backgroundColor: colors.canvas },
});

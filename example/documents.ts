import { Directory, File, Paths } from 'expo-file-system';
import type { PdfSource } from 'react-native-better-pdf';

/** Served by `node benchmarks/serve-fixtures.mjs` (Android: `adb reverse tcp:8090 tcp:8090`). */
export const FIXTURE_SERVER = 'http://localhost:8090';

export const FIXTURES = [
  'small',
  'medium',
  'large',
  'extreme',
  'mixed-sizes',
  'single-page',
  'links',
  'links-all',
  'outline',
  'outline-compressed',
  'outline-large',
  'annotations',
  'forms',
  'forms-encrypted',
  'page-boxes',
  'page-boxes-encrypted',
  'metadata',
  'metadata-encrypted',
  'encrypted',
  'aes-encrypted',
  'scanned-encrypted',
  'malformed-not-a-pdf',
  'malformed-empty',
  'malformed-truncated',
  'malformed-garbage-xref',
] as const;
export type FixtureName = (typeof FIXTURES)[number];

export const BENCHMARK_FIXTURES = [
  'small',
  'medium',
  'large',
  'extreme',
] as const satisfies readonly FixtureName[];

export interface DocumentEntry {
  id: string;
  title: string;
  subtitle: string;
  /** Resolves the source; fixtures are copied onto the device first. */
  resolve: () => Promise<PdfSource>;
  initialPage?: number;
  initialScale?: number;
  /** Shows the example's PdfPageOverlays (pages 1 and 3). */
  overlays?: boolean;
  /** Shows the form buttons (fill, read, export). */
  form?: boolean;
  /** Translucent bars over the viewer, which pages scroll under (`contentInset`). */
  contentInset?: { top: number; bottom: number };
  /** Another source for "Swap"; also shows "Same" and the onLoad count. */
  alternate?: () => Promise<PdfSource>;
}

const fixtureDirectory = () => {
  const directory = new Directory(Paths.document, 'fixtures');
  if (!directory.exists) directory.create();
  return directory;
};

/** Copies a fixture from the fixture server once and returns its local file. */
export async function ensureFixture(name: FixtureName): Promise<File> {
  const file = new File(fixtureDirectory(), `${name}.pdf`);
  if (file.exists && file.size > 0) return file;
  if (file.exists) file.delete();
  return File.downloadFileAsync(`${FIXTURE_SERVER}/${name}.pdf`, file);
}

async function base64Fixture(name: FixtureName): Promise<PdfSource> {
  return { base64: await (await ensureFixture(name)).base64() };
}

export const DOCUMENTS: DocumentEntry[] = [
  {
    id: 'bundled',
    title: 'Bundled sample',
    subtitle: "require('./assets/sample.pdf')",
    resolve: async () => require('./assets/sample.pdf') as number,
  },
  {
    id: 'remote',
    title: 'Remote (HTTP)',
    subtitle: 'Downloaded natively from the fixture server',
    resolve: async () => ({ uri: `${FIXTURE_SERVER}/medium.pdf` }),
  },
  {
    id: 'remote-slow',
    title: 'Remote (slow)',
    subtitle: 'Throttled to 4 MB/s to show download progress',
    // A unique query per open bypasses the per-session download cache.
    resolve: async () => ({
      uri: `${FIXTURE_SERVER}/medium.pdf?bytesPerSecond=4000000&open=${Date.now()}`,
    }),
  },
  {
    id: 'remote-404',
    title: 'Remote 404',
    subtitle: 'Expect http-error',
    resolve: async () => ({ uri: `${FIXTURE_SERVER}/does-not-exist.pdf` }),
  },
  {
    id: 'remote-forms',
    title: 'Remote form',
    subtitle: 'The forms fixture, downloaded natively',
    resolve: async () => ({ uri: `${FIXTURE_SERVER}/forms.pdf` }),
    form: true,
  },
  {
    id: 'missing-file',
    title: 'Missing file',
    subtitle: 'Expect file-not-found',
    resolve: async () => ({ filePath: '/definitely/not/here.pdf' }),
  },
  {
    id: 'medium-page5-2x',
    title: 'Start at page 5, 2×',
    subtitle: 'initialPage={4} initialScale={2}',
    resolve: async () => ({ uri: (await ensureFixture('medium')).uri }),
    initialPage: 4,
    initialScale: 2,
  },
  {
    id: 'medium-inset',
    title: 'Inset',
    subtitle: 'contentInset under translucent bars',
    resolve: async () => ({ uri: (await ensureFixture('medium')).uri }),
    contentInset: { top: 80, bottom: 60 },
  },
  {
    id: 'medium-inset-last',
    title: 'Inset, last page',
    subtitle: 'initialPage={99}, bars 80 / 60',
    resolve: async () => ({ uri: (await ensureFixture('medium')).uri }),
    initialPage: 99,
    contentInset: { top: 80, bottom: 60 },
  },
  {
    id: 'medium-inset-tall-last',
    title: 'Inset tall, last page',
    subtitle: 'initialPage={99}, bars 0 / 400',
    resolve: async () => ({ uri: (await ensureFixture('medium')).uri }),
    initialPage: 99,
    contentInset: { top: 0, bottom: 400 },
  },
  {
    id: 'medium-overlays',
    title: 'Overlays',
    subtitle: 'PdfPageOverlay on pages 1 and 3',
    resolve: async () => ({ uri: (await ensureFixture('medium')).uri }),
    overlays: true,
  },
  ...FIXTURES.map<DocumentEntry>((name) => ({
    id: `fixture-${name}`,
    title: name,
    subtitle: 'Fixture copied to the device',
    resolve: async () => ({ uri: (await ensureFixture(name)).uri }),
    form: name.startsWith('forms'),
  })),
  {
    id: 'base64',
    title: 'Base64',
    subtitle: '{ base64 } of outline; Swap to single-page',
    resolve: () => base64Fixture('outline'),
    alternate: () => base64Fixture('single-page'),
  },
  {
    id: 'base64-encrypted',
    title: 'Base64 encrypted',
    subtitle: '{ base64 } of encrypted, password "secret"',
    resolve: () => base64Fixture('encrypted'),
  },
  {
    id: 'base64-form',
    title: 'Base64 form',
    subtitle: '{ base64 } of forms; exports as document.pdf',
    resolve: () => base64Fixture('forms'),
    form: true,
  },
  {
    id: 'base64-invalid',
    title: 'Base64 not base64',
    subtitle: 'Expect invalid-source',
    resolve: async () => ({ base64: 'JVBERi0xLjQK!' }),
  },
  {
    id: 'base64-data-uri',
    title: 'Base64 data: URI',
    subtitle: 'Expect invalid-source',
    resolve: async () => ({
      base64: 'data:application/pdf;base64,JVBERi0xLjQK',
    }),
  },
  {
    id: 'base64-not-a-pdf',
    title: 'Base64 not a PDF',
    subtitle: 'Expect invalid-pdf',
    // "Hello, world!"
    resolve: async () => ({ base64: 'SGVsbG8sIHdvcmxkIQ==' }),
  },
];

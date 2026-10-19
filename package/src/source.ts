import type { NativePdfSource } from './types/NativePdfSource';
import type { PdfCacheMode } from './types/PdfCacheMode';

/**
 * A document addressed by URI: `http(s)://` (downloaded natively), `file://`,
 * or `content://` on Android. The same object is accepted by `PdfView` and
 * by `PdfCache`, so both refer to the same downloaded copy.
 *
 * `cache`, `maxAgeMs` and `cacheKey` apply to `http(s)://` documents only.
 *
 * @example
 * ```ts
 * // Readable offline; checks for a newer version on every open when online.
 * const source: PdfUriSource = {
 *   uri: `https://example.com/documents/${id}.pdf`,
 *   headers: { Authorization: `Bearer ${token}` },
 *   cache: 'persistent',
 *   cacheKey: `document-${id}`,
 *   maxAgeMs: 0,
 * };
 * ```
 */
export interface PdfUriSource {
  /** `http(s)://`, `file://`, or `content://` (Android). */
  uri: string;
  /**
   * Request headers for `http(s)://` documents. Each name must be an HTTP
   * token (letters, digits and ``!#$%&'*+-.^_`|~``) and no value may contain
   * a line break or another control character (tab is fine); otherwise
   * loading fails with `invalid-source` before anything is sent. Keep values
   * ASCII (encode anything else yourself): Android sends other characters
   * as UTF-8, iOS as Latin-1 and cuts the value at the first character
   * outside it.
   */
  headers?: Readonly<Record<string, string>>;
  /**
   * How long the downloaded copy is kept: for the app session, or across
   * launches for offline reading. While a copy is kept, opening the document
   * again does not download it (but see
   * {@linkcode PdfUriSource.maxAgeMs}). A persistent copy is also used when
   * `cache` is `'session'`. Default `'session'`.
   */
  cache?: PdfCacheMode;
  /**
   * How old, in milliseconds, a downloaded copy may be before it is checked
   * with the server again when the document is opened or downloaded. The
   * check is a conditional request (`ETag` / `Last-Modified`): an unchanged
   * document is not downloaded again. `0` checks on every open. When the
   * server cannot be reached (no connection, no answer within 10 seconds, or
   * a 5xx response), `PdfView` shows the old copy and reports
   * `isStale` in `onLoad`. Default: never checked again.
   */
  maxAgeMs?: number;
  /**
   * Identifies the downloaded copy instead of `uri` and `headers`. Set it
   * when they change between opens of the same document — signed URLs,
   * rotating access tokens — or the copy is not found again. Two sources
   * with the same `cacheKey` are the same document: `PdfView` does not
   * reload when only their `uri` or `headers` differ.
   */
  cacheKey?: string;
  /**
   * Opens a password-protected document. Takes precedence over
   * `PdfView`'s `password` prop and `PdfDocument.open`'s `password` option,
   * so each of several sources can have its own. `PdfCache` ignores it.
   */
  password?: string;
}

/** A document at an absolute path on the device. */
export interface PdfFileSource {
  /** Absolute path of the file. */
  filePath: string;
  /** As {@linkcode PdfUriSource.password}. */
  password?: string;
}

/**
 * A document as a base64 string, e.g. from pdfmake, pdf-lib or an API. Meant
 * for documents of up to a few MB; keep the string in state.
 */
export interface PdfBase64Source {
  /** Standard base64 without a `data:` prefix; whitespace and missing padding are fine. */
  base64: string;
  /** As {@linkcode PdfUriSource.password}. */
  password?: string;
}

/**
 * Where to load a PDF from.
 *
 * - {@linkcode PdfUriSource} — `http(s)://` (downloaded natively into the
 *   app's cache), `file://`, or `content://` on Android.
 * - {@linkcode PdfFileSource} — `{ filePath }`, an absolute path on the
 *   device.
 * - {@linkcode PdfBase64Source} — the document's bytes as a base64 string.
 * - `number` — a bundled asset, i.e. `require('./document.pdf')`.
 *   `pdf` is one of Metro's default asset extensions, so no config is needed.
 */
export type PdfSource = PdfUriSource | PdfFileSource | PdfBase64Source | number;

/** Resolves `require()` asset ids. Injected so this module stays pure. */
export type AssetResolver = (asset: number) => { uri: string } | null;

export type NormalizedSource = Omit<NativePdfSource, 'base64'> & {
  base64?: string;
};

/**
 * The native shape of a URI source. Copies `headers` so later mutation of
 * the caller's object cannot leak into native code, and leaves absent
 * options absent.
 */
export function normalizeUriSource(
  source: PdfUriSource,
): Omit<NativePdfSource, 'base64'> {
  const normalized: Omit<NativePdfSource, 'base64'> = { uri: source.uri };
  if (source.headers != null) normalized.headers = { ...source.headers };
  if (source.cache != null) normalized.cache = source.cache;
  if (source.maxAgeMs != null) normalized.maxAgeMs = source.maxAgeMs;
  if (source.cacheKey != null) normalized.cacheKey = source.cacheKey;
  return normalized;
}

export function normalizeSource(
  source: PdfSource,
  resolveAsset: AssetResolver,
): NormalizedSource {
  if (typeof source === 'number') {
    return { uri: resolveAsset(source)?.uri ?? '' };
  }
  if ('filePath' in source) {
    return { uri: source.filePath };
  }
  if ('base64' in source) {
    return { uri: '', base64: source.base64 };
  }
  return normalizeUriSource(source);
}

/** The source's own password, if it has one (bundled assets cannot). */
export function sourcePassword(source: PdfSource): string | undefined {
  return typeof source === 'number' ? undefined : source.password;
}

/**
 * The native sources of `PdfView`'s `source`, one or several, each with its
 * password: its own, else `password` (the prop), else `''` for none.
 */
export function normalizeSources(
  source: PdfSource | readonly PdfSource[],
  password: string | undefined,
  resolveAsset: AssetResolver,
): NormalizedSource[] {
  const sources: readonly PdfSource[] = isSourceList(source)
    ? source
    : [source];
  return sources.map((item) => ({
    ...normalizeSource(item, resolveAsset),
    password: sourcePassword(item) ?? password ?? '',
  }));
}

function isSourceList(
  source: PdfSource | readonly PdfSource[],
): source is readonly PdfSource[] {
  return Array.isArray(source);
}

/**
 * Value identity of a normalized source. Equal keys mean equal values, so
 * the native view never receives the same source twice. (Whether a changed
 * source reloads the document is decided natively: a changed `uri` or
 * `headers` does not when the `cacheKey` is the same.)
 */
export function sourceKey(source: NormalizedSource): string {
  const headers = Object.keys(source.headers ?? {})
    .sort()
    .map((name) => [name, source.headers?.[name]]);
  return JSON.stringify([
    source.uri,
    headers,
    source.cache ?? null,
    source.maxAgeMs ?? null,
    source.cacheKey ?? null,
    source.base64 !== undefined,
    source.password ?? null,
  ]);
}

/** Value identity of several normalized sources, in order. */
export function sourcesKey(sources: readonly NormalizedSource[]): string {
  return JSON.stringify(sources.map(sourceKey));
}

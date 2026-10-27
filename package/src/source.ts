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
   *
   * They go to the URL's origin only: after a redirect to another scheme,
   * host or port (http to https included), the rest of the download is
   * sent without them.
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

/** Describes an arbitrary runtime value without invoking unsafe serialization. */
function describeValue(value: unknown): string {
  if (value === null) return 'null';
  switch (typeof value) {
    case 'undefined':
      return 'undefined';
    case 'string':
      return 'a string';
    case 'number':
      return Number.isNaN(value) ? 'NaN' : String(value);
    case 'bigint':
      return `${value}n`;
    case 'symbol':
      return value.toString();
    case 'function':
      return 'a function';
    case 'object':
      return Array.isArray(value) ? 'an array' : 'an object';
    default:
      return typeof value;
  }
}

/**
 * Describes the runtime shape of a source for a validation error.
 *
 * `normalizeUriSource` passes `uriOnly` because that helper accepts only URI
 * sources, while the other callers accept `uri`, `filePath`, or `base64`.
 */
function describeSourceType(source: unknown, uriOnly = false): string {
  if (source === null) return 'null';
  if (source === undefined) return 'undefined';
  if (Array.isArray(source)) return 'an array';
  const type = typeof source;
  if (type === 'string') return 'a string';
  if (type === 'number') return 'a number';
  if (type === 'boolean') return 'a boolean';
  if (type === 'object') {
    const obj = source as Record<string, unknown>;
    const keys = Object.keys(obj);
    if (keys.length === 0) return '{}';
    if ('uri' in obj && typeof obj.uri !== 'string') {
      return `{ uri: ${describeValue(obj.uri)} }`;
    }
    if ('filePath' in obj && typeof obj.filePath !== 'string') {
      return `{ filePath: ${describeValue(obj.filePath)} }`;
    }
    if ('base64' in obj && typeof obj.base64 !== 'string') {
      return `{ base64: ${describeValue(obj.base64)} }`;
    }
    if (uriOnly) {
      if (typeof obj.filePath === 'string') return 'an object with filePath';
      if (typeof obj.base64 === 'string') return 'an object with base64';
      return 'an object without uri';
    }
    return 'an object without uri, filePath or base64';
  }
  return type;
}

/** Validates and normalizes one runtime source value without throwing. */
export function validateSourceItem(
  source: unknown,
  resolveAsset: AssetResolver,
): { ok: true; source: NormalizedSource } | { ok: false; error: string } {
  if (typeof source === 'number') {
    return { ok: true, source: { uri: resolveAsset(source)?.uri ?? '' } };
  }
  if (source == null || typeof source !== 'object' || Array.isArray(source)) {
    return {
      ok: false,
      error: `source must be a require() result or an object with uri, filePath or base64; got ${describeSourceType(source)}`,
    };
  }
  const obj = source as Record<string, unknown>;
  if ('filePath' in obj) {
    if (typeof obj.filePath === 'string') {
      return { ok: true, source: { uri: obj.filePath } };
    }
    return {
      ok: false,
      error: `source must be a require() result or an object with uri, filePath or base64; got ${describeSourceType(source)}`,
    };
  }
  if ('base64' in obj) {
    if (typeof obj.base64 === 'string') {
      return { ok: true, source: { uri: '', base64: obj.base64 } };
    }
    return {
      ok: false,
      error: `source must be a require() result or an object with uri, filePath or base64; got ${describeSourceType(source)}`,
    };
  }
  if ('uri' in obj) {
    if (typeof obj.uri === 'string') {
      return { ok: true, source: normalizeUriSource(source as PdfUriSource) };
    }
    return {
      ok: false,
      error: `source must be a require() result or an object with uri, filePath or base64; got ${describeSourceType(source)}`,
    };
  }
  return {
    ok: false,
    error: `source must be a require() result or an object with uri, filePath or base64; got ${describeSourceType(source)}`,
  };
}

/**
 * The native shape of a URI source. Copies `headers` so later mutation of
 * the caller's object cannot leak into native code, and leaves absent
 * options absent.
 */
export function normalizeUriSource(
  source: PdfUriSource,
): Omit<NativePdfSource, 'base64'> {
  if (
    source == null ||
    typeof source !== 'object' ||
    typeof (source as { uri?: unknown }).uri !== 'string'
  ) {
    throw new Error(
      `source must be an object with uri; got ${describeSourceType(source, true)}`,
    );
  }
  const normalized: Omit<NativePdfSource, 'base64'> = { uri: source.uri };
  if (source.headers != null) normalized.headers = { ...source.headers };
  if (source.cache != null) normalized.cache = source.cache;
  if (source.maxAgeMs != null) normalized.maxAgeMs = source.maxAgeMs;
  if (source.cacheKey != null) normalized.cacheKey = source.cacheKey;
  return normalized;
}

/** The source's own password, if it has one (bundled assets cannot). */
export function sourcePassword(source: unknown): string | undefined {
  if (source != null && typeof source === 'object' && 'password' in source) {
    const pwd = (source as { password?: unknown }).password;
    return typeof pwd === 'string' ? pwd : undefined;
  }
  return undefined;
}

export type SourceInspection =
  | { status: 'empty' }
  | { status: 'valid'; sources: NormalizedSource[] }
  | { status: 'invalid'; message: string; sourceIndex?: number };

/**
 * Validates and inspects `source`, detecting whether it is empty (null/undefined),
 * valid, or malformed with an explanatory message.
 */
export function inspectSources(
  source: unknown,
  password: string | undefined,
  resolveAsset: AssetResolver,
): SourceInspection {
  if (source == null) {
    return { status: 'empty' };
  }
  const isList = Array.isArray(source);
  const items = isList ? source : [source];
  if (items.length === 0) {
    return { status: 'valid', sources: [] };
  }
  const normalized: NormalizedSource[] = [];
  for (let index = 0; index < items.length; index++) {
    const item = items[index];
    const validation = validateSourceItem(item, resolveAsset);
    if (!validation.ok) {
      return {
        status: 'invalid',
        message: validation.error,
        sourceIndex: index,
      };
    }
    normalized.push({
      ...validation.source,
      password: sourcePassword(item) ?? password ?? '',
    });
  }
  return { status: 'valid', sources: normalized };
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

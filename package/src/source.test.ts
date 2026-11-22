import { afterEach, beforeEach, describe, expect, test } from 'bun:test';
import {
  inspectSources,
  normalizeUriSource,
  resetIgnoredKeyWarnings,
  sourceKey,
  validateSourceItem,
} from './source';

const resolveAsset = (asset: number) =>
  asset === 7 ? { uri: 'http://localhost:8081/assets/doc.pdf' } : null;

const validatedSource = (source: unknown) => {
  const result = validateSourceItem(source, resolveAsset);
  if (!result.ok) throw new Error(result.error);
  return result.source;
};

describe('validateSourceItem', () => {
  test('passes uri sources through', () => {
    expect(validatedSource({ uri: 'https://x.test/a.pdf' })).toEqual({
      uri: 'https://x.test/a.pdf',
    });
  });

  test('copies headers so later mutation of the caller object cannot leak in', () => {
    const headers: Record<string, string> = { Authorization: 'Bearer 1' };
    const normalized = validatedSource({
      uri: 'https://x.test/a.pdf',
      headers,
    });
    headers.Authorization = 'Bearer 2';
    expect(normalized.headers).toEqual({ Authorization: 'Bearer 1' });
  });

  test('maps filePath to a plain path uri', () => {
    expect(validatedSource({ filePath: '/data/doc.pdf' })).toEqual({
      uri: '/data/doc.pdf',
    });
  });

  test('describes a filePath when a URI-only source receives one', () => {
    expect(() => normalizeUriSource({ filePath: '/a.pdf' } as never)).toThrow(
      'source must be an object with uri; got an object with filePath',
    );
  });

  test('resolves required assets', () => {
    expect(validatedSource(7)).toEqual({
      uri: 'http://localhost:8081/assets/doc.pdf',
    });
  });

  test('passes cache options through and leaves absent ones absent', () => {
    expect(
      validatedSource({
        uri: 'https://x.test/a.pdf',
        cache: 'persistent',
        maxAgeMs: 0,
        cacheKey: 'doc',
      }),
    ).toEqual({
      uri: 'https://x.test/a.pdf',
      cache: 'persistent',
      maxAgeMs: 0,
      cacheKey: 'doc',
    });
    expect(
      Object.keys(validatedSource({ uri: 'https://x.test/a.pdf' })),
    ).toEqual(['uri']);
  });

  test('passes base64 through with an empty uri, without copying it', () => {
    const base64 = 'JVBERi0xLjQK';
    const normalized = validatedSource({ base64 });
    expect(normalized).toEqual({ uri: '', base64 });
    expect(Object.keys(normalized)).toEqual(['uri', 'base64']);
  });

  test('passes an empty base64 string on, for native code to reject', () => {
    expect(validatedSource({ base64: '' })).toEqual({
      uri: '',
      base64: '',
    });
  });

  test('unresolvable assets become an empty uri, which native reports as invalid-source', () => {
    expect(validatedSource(99)).toEqual({ uri: '' });
  });

  test('null options are absent', () => {
    expect(
      validatedSource({
        uri: 'https://x.test/a.pdf',
        headers: null,
        cache: null,
        maxAgeMs: null,
        cacheKey: null,
      }),
    ).toEqual({ uri: 'https://x.test/a.pdf' });
  });

  test('reports options of the wrong type, naming the option', () => {
    const uri = 'https://x.test/a.pdf';
    const cases: [Record<string, unknown>, string][] = [
      [{ uri, cacheKey: 42 }, 'source.cacheKey must be a string; got 42'],
      [
        { uri, headers: 'Authorization: x' },
        'source.headers must be an object of strings; got a string',
      ],
      [
        { uri, headers: ['x'] },
        'source.headers must be an object of strings; got an array',
      ],
      [
        { uri, headers: new Map([['Authorization', 'x']]) },
        'source.headers must be an object of strings; got a Map',
      ],
      [
        { uri, headers: { 'X-User': 7 } },
        'source.headers["X-User"] must be a string; got 7',
      ],
      [
        { uri, headers: { Authorization: undefined } },
        'source.headers["Authorization"] must be a string; got undefined',
      ],
      [
        { uri, headers: { Authorization: null } },
        'source.headers["Authorization"] must be a string; got null',
      ],
      [
        { uri, cache: 'Persistent' },
        "source.cache must be 'session' or 'persistent'; got a string",
      ],
      [
        { uri, cache: true },
        "source.cache must be 'session' or 'persistent'; got true",
      ],
      [
        { uri, maxAgeMs: '0' },
        'source.maxAgeMs must be a finite number of at least 0; got a string',
      ],
      [
        { uri, maxAgeMs: -1 },
        'source.maxAgeMs must be a finite number of at least 0; got -1',
      ],
      [
        { uri, maxAgeMs: Number.NaN },
        'source.maxAgeMs must be a finite number of at least 0; got NaN',
      ],
      [
        { uri, maxAgeMs: Number.POSITIVE_INFINITY },
        'source.maxAgeMs must be a finite number of at least 0; got Infinity',
      ],
    ];
    for (const [source, error] of cases) {
      expect(validateSourceItem(source, resolveAsset)).toEqual({
        ok: false,
        error,
      });
      expect(() => normalizeUriSource(source as never)).toThrow(error);
    }
  });

  test('reads each option once, so a getter cannot change it after the check', () => {
    let reads = 0;
    const headers = {
      get Authorization() {
        reads += 1;
        return reads === 1 ? 'Bearer 1' : (7 as never);
      },
    };
    let headersReads = 0;
    const source = {
      uri: 'https://x.test/a.pdf',
      get headers() {
        headersReads += 1;
        return headersReads === 1 ? headers : (7 as never);
      },
    };
    expect(validateSourceItem(source, resolveAsset)).toEqual({
      ok: true,
      source: {
        uri: 'https://x.test/a.pdf',
        headers: { Authorization: 'Bearer 1' },
      },
    });
  });
});

describe('sourceKey', () => {
  test('is equal for equal values', () => {
    expect(sourceKey({ uri: 'a' })).toBe(sourceKey({ uri: 'a' }));
  });

  test('ignores header order', () => {
    expect(sourceKey({ uri: 'a', headers: { A: '1', B: '2' } })).toBe(
      sourceKey({ uri: 'a', headers: { B: '2', A: '1' } }),
    );
  });

  test('differs when the uri or any header differs', () => {
    const base = sourceKey({ uri: 'a', headers: { A: '1' } });
    expect(sourceKey({ uri: 'b', headers: { A: '1' } })).not.toBe(base);
    expect(sourceKey({ uri: 'a', headers: { A: '2' } })).not.toBe(base);
    expect(sourceKey({ uri: 'a' })).not.toBe(base);
  });

  test('differs when a cache option differs, so native always gets the latest source', () => {
    const base = sourceKey({ uri: 'a', cacheKey: 'doc' });
    expect(sourceKey({ uri: 'a', cacheKey: 'other' })).not.toBe(base);
    expect(sourceKey({ uri: 'a', cacheKey: 'doc', maxAgeMs: 0 })).not.toBe(
      base,
    );
    expect(
      sourceKey({ uri: 'a', cacheKey: 'doc', cache: 'persistent' }),
    ).not.toBe(base);
    expect(sourceKey({ uri: 'b', cacheKey: 'doc' })).not.toBe(base);
  });

  test('marks base64 sources without their content', () => {
    const key = sourceKey({ uri: '', base64: 'JVBERi0xLjQK' });
    expect(key).not.toContain('JVBERi0xLjQK');
    // The content is compared separately (see useStableSources).
    expect(sourceKey({ uri: '', base64: 'b3RoZXI=' })).toBe(key);
    expect(sourceKey({ uri: '' })).not.toBe(key);
  });

  test('depends on the password', () => {
    expect(sourceKey({ uri: 'a', password: 'p' })).not.toBe(
      sourceKey({ uri: 'a' }),
    );
  });

  test('cannot be spoofed by a uri containing the separator', () => {
    expect(sourceKey({ uri: 'a\n[["A","1"]]' })).not.toBe(
      sourceKey({ uri: 'a', headers: { A: '1' } }),
    );
  });
});

describe('inspectSources', () => {
  test('a single source becomes a list of one, with its password', () => {
    expect(
      inspectSources({ uri: 'https://x.test/a.pdf' }, undefined, resolveAsset),
    ).toEqual({
      status: 'valid',
      sources: [{ uri: 'https://x.test/a.pdf', password: '' }],
    });
  });

  test("each source keeps its own password, else takes the prop's", () => {
    expect(
      inspectSources(
        [
          { uri: 'a', password: 'own' },
          { filePath: '/b', password: '' },
          { uri: 'c' },
          7,
        ],
        'prop',
        resolveAsset,
      ),
    ).toEqual({
      status: 'valid',
      sources: [
        { uri: 'a', password: 'own' },
        { uri: '/b', password: '' },
        { uri: 'c', password: 'prop' },
        { uri: 'http://localhost:8081/assets/doc.pdf', password: 'prop' },
      ],
    });
  });

  test('a base64 source keeps its text and its own password', () => {
    expect(
      inspectSources(
        [
          { base64: 'JVBERi0xLjQK', password: 'own' },
          { base64: 'JVBERi0xLjcK' },
        ],
        'prop',
        resolveAsset,
      ),
    ).toEqual({
      status: 'valid',
      sources: [
        { uri: '', base64: 'JVBERi0xLjQK', password: 'own' },
        { uri: '', base64: 'JVBERi0xLjcK', password: 'prop' },
      ],
    });
  });

  test('an empty list stays empty (native reports invalid-source)', () => {
    expect(inspectSources([], 'prop', resolveAsset)).toEqual({
      status: 'valid',
      sources: [],
    });
  });
});

describe('invalid and empty sources', () => {
  test('validateSourceItem reports malformed values without throwing', () => {
    for (const source of [
      null,
      undefined,
      'https://example.com/a.pdf',
      {},
      { uri: 42 as never },
      { uri: 42n } as never,
    ]) {
      expect(validateSourceItem(source, resolveAsset).ok).toBe(false);
    }
  });

  test('inspectSources classifies null and undefined as empty', () => {
    expect(inspectSources(null, undefined, resolveAsset)).toEqual({
      status: 'empty',
    });
    expect(inspectSources(undefined, undefined, resolveAsset)).toEqual({
      status: 'empty',
    });
  });

  test('inspectSources reports malformed single sources as invalid with descriptive message', () => {
    expect(
      inspectSources('https://example.com/a.pdf', undefined, resolveAsset),
    ).toEqual({
      status: 'invalid',
      message:
        'source must be a require() result or an object with uri, filePath or base64; got a string',
      sourceIndex: 0,
    });
    expect(inspectSources({}, undefined, resolveAsset)).toEqual({
      status: 'invalid',
      message:
        'source must be a require() result or an object with uri, filePath or base64; got {}',
      sourceIndex: 0,
    });
    expect(
      inspectSources({ uri: 42 as never }, undefined, resolveAsset),
    ).toEqual({
      status: 'invalid',
      message:
        'source must be a require() result or an object with uri, filePath or base64; got { uri: 42 }',
      sourceIndex: 0,
    });
  });

  test('inspectSources reports malformed list items with sourceIndex', () => {
    expect(inspectSources([null as never], undefined, resolveAsset)).toEqual({
      status: 'invalid',
      message:
        'source must be a require() result or an object with uri, filePath or base64; got null',
      sourceIndex: 0,
    });
    expect(
      inspectSources(
        [{ uri: 'https://x.test/a.pdf' }, 'bad' as never],
        undefined,
        resolveAsset,
      ),
    ).toEqual({
      status: 'invalid',
      message:
        'source must be a require() result or an object with uri, filePath or base64; got a string',
      sourceIndex: 1,
    });
  });
});

describe('explicit undefined', () => {
  const uri = 'https://x.test/a.pdf';
  const undefinedOptions = {
    headers: undefined,
    cache: undefined,
    maxAgeMs: undefined,
    cacheKey: undefined,
    password: undefined,
  };

  test('is absent for every optional field of a uri source', () => {
    expect(validatedSource({ uri, ...undefinedOptions })).toStrictEqual({
      uri,
    });
    expect(normalizeUriSource({ uri, ...undefinedOptions })).toStrictEqual({
      uri,
    });
    expect(
      inspectSources({ uri, ...undefinedOptions }, 'prop', resolveAsset),
    ).toStrictEqual(inspectSources({ uri }, 'prop', resolveAsset));
  });

  test('is absent for the password of file and base64 sources', () => {
    for (const source of [{ filePath: '/a.pdf' }, { base64: 'JVBERi0xLjQK' }]) {
      expect(
        inspectSources(
          { ...source, password: undefined },
          'prop',
          resolveAsset,
        ),
      ).toStrictEqual(inspectSources(source, 'prop', resolveAsset));
    }
  });

  test('is absent for the password prop', () => {
    expect(inspectSources({ uri }, undefined, resolveAsset)).toStrictEqual({
      status: 'valid',
      sources: [{ uri, password: '' }],
    });
  });

  test('does not select a source kind', () => {
    expect(
      validatedSource({ uri, filePath: undefined, base64: undefined }),
    ).toStrictEqual({ uri });
    expect(
      validatedSource({ filePath: undefined, base64: 'JVBERi0xLjQK' }),
    ).toStrictEqual({ uri: '', base64: 'JVBERi0xLjQK' });
    expect(
      validatedSource({ uri: undefined, filePath: '/a.pdf' }),
    ).toStrictEqual({ uri: '/a.pdf' });
  });

  test('alone still reports the key', () => {
    const message = (source: unknown) => {
      const result = validateSourceItem(source, resolveAsset);
      return result.ok ? undefined : result.error;
    };
    const prefix =
      'source must be a require() result or an object with uri, filePath or base64; got ';
    expect(message({ filePath: undefined })).toBe(
      `${prefix}{ filePath: undefined }`,
    );
    expect(message({ base64: undefined })).toBe(
      `${prefix}{ base64: undefined }`,
    );
    expect(message({ uri: undefined })).toBe(`${prefix}{ uri: undefined }`);
  });
});

describe('ignored source keys', () => {
  const globals = globalThis as { __DEV__?: boolean };
  const warn = console.warn;
  let warnings: string[];

  beforeEach(() => {
    warnings = [];
    console.warn = (...args: unknown[]) => warnings.push(args.join(' '));
    resetIgnoredKeyWarnings();
    globals.__DEV__ = true;
  });

  afterEach(() => {
    console.warn = warn;
    delete globals.__DEV__;
  });

  test('warns, naming the keys that are ignored', () => {
    validatedSource({ uri: 'https://x.test/a.pdf', filePath: '/a.pdf' });
    validatedSource({
      filePath: '/a.pdf',
      base64: 'JVBERi0xLjQK',
      cache: 'persistent',
      cacheKey: 'secret-key',
    });
    validatedSource({
      base64: 'JVBERi0xLjQK',
      uri: 'https://x.test/a.pdf',
      headers: { Authorization: 'Bearer token' },
      maxAgeMs: 0,
    });
    expect(warnings).toEqual([
      '[react-native-better-pdf] A source with filePath ignores uri. Pass one of uri, filePath or base64; headers, cache, maxAgeMs and cacheKey apply to uri sources only.',
      '[react-native-better-pdf] A source with filePath ignores base64, cache, cacheKey. Pass one of uri, filePath or base64; headers, cache, maxAgeMs and cacheKey apply to uri sources only.',
      '[react-native-better-pdf] A source with base64 ignores uri, headers, maxAgeMs. Pass one of uri, filePath or base64; headers, cache, maxAgeMs and cacheKey apply to uri sources only.',
    ]);
  });

  test('names keys only, never their values', () => {
    validatedSource({
      filePath: '/private/a.pdf',
      uri: 'https://x.test/a.pdf?token=secret',
      headers: { Authorization: 'Bearer secret' },
    });
    expect(warnings).toHaveLength(1);
    expect(warnings[0]).not.toContain('secret');
    expect(warnings[0]).not.toContain('/private');
  });

  test('warns once per combination of keys, however often it is normalized', () => {
    for (let render = 0; render < 3; render++) {
      inspectSources(
        [
          { filePath: '/a.pdf', cache: 'persistent' },
          { filePath: '/b.pdf', cache: 'session' },
          { base64: 'JVBERi0xLjQK', cache: 'persistent' },
        ],
        undefined,
        resolveAsset,
      );
    }
    expect(warnings).toEqual([
      expect.stringContaining('A source with filePath ignores cache.'),
      expect.stringContaining('A source with base64 ignores cache.'),
    ]);
  });

  test('does not warn about consistent sources or undefined keys', () => {
    validatedSource({
      uri: 'https://x.test/a.pdf',
      headers: { A: '1' },
      cache: 'persistent',
      maxAgeMs: 0,
      cacheKey: 'doc',
      password: 'pw',
      filePath: undefined,
      base64: undefined,
    });
    validatedSource({
      filePath: '/a.pdf',
      password: 'pw',
      uri: undefined,
      cache: undefined,
    });
    validatedSource({ base64: 'JVBERi0xLjQK', password: 'pw' });
    validatedSource({
      filePath: '/a.pdf',
      headers: null,
      cache: null,
      maxAgeMs: null,
      cacheKey: null,
    });
    validatedSource(7);
    expect(warnings).toEqual([]);
  });

  test('stays silent outside development', () => {
    for (const dev of [false, undefined]) {
      if (dev === undefined) delete globals.__DEV__;
      else globals.__DEV__ = dev;
      validatedSource({ uri: 'https://x.test/a.pdf', filePath: '/a.pdf' });
    }
    expect(warnings).toEqual([]);
  });
});

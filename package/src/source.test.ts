import { describe, expect, test } from 'bun:test';
import {
  inspectSources,
  normalizeUriSource,
  sourceKey,
  sourcesKey,
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
    // The content is compared separately (see useStableSource).
    expect(sourceKey({ uri: '', base64: 'b3RoZXI=' })).toBe(key);
    expect(sourceKey({ uri: '' })).not.toBe(key);
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

describe('sourcesKey', () => {
  test('leaves base64 text out', () => {
    const key = sourcesKey([{ uri: '', base64: 'JVBERi0xLjQK' }]);
    expect(key).not.toContain('JVBERi0xLjQK');
    expect(sourcesKey([{ uri: '', base64: 'JVBERi0xLjcK' }])).toBe(key);
  });

  test('differs when the order, a source or a password differs', () => {
    const base = sourcesKey([{ uri: 'a' }, { uri: 'b' }]);
    expect(sourcesKey([{ uri: 'a' }, { uri: 'b' }])).toBe(base);
    expect(sourcesKey([{ uri: 'b' }, { uri: 'a' }])).not.toBe(base);
    expect(sourcesKey([{ uri: 'a' }])).not.toBe(base);
    expect(sourcesKey([{ uri: 'a' }, { uri: 'b', password: 'p' }])).not.toBe(
      base,
    );
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

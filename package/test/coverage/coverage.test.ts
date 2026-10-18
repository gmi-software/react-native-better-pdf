// Android's grey-page conversion (PageCoverage.cpp), built for the host.
import { expect, test } from 'bun:test';
import { spawnSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const packageDir = join(import.meta.dir, '../..');
const binary = join(mkdtempSync(join(tmpdir(), 'better-pdf-coverage-')), 'cpp');

const build = spawnSync(
  'c++',
  [
    '-std=c++20',
    '-O2',
    '-I',
    join(packageDir, 'android/src/main/cpp'),
    join(import.meta.dir, 'harness.cpp'),
    join(packageDir, 'android/src/main/cpp/PageCoverage.cpp'),
    '-o',
    binary,
  ],
  { encoding: 'utf8' },
);
if (build.status !== 0) {
  throw new Error(`c++ failed:\n${build.stderr || build.error}`);
}

test('grey pixels become coverage and back, any other pixel is reported', () => {
  const result = spawnSync(binary, { encoding: 'utf8' });
  expect(result.stdout.trim()).toBe('ok');
  expect(result.status).toBe(0);
});

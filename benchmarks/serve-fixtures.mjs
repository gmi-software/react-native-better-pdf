#!/usr/bin/env node
// Serves benchmarks/fixtures over HTTP so the example app can copy them onto
// the device once. Android: run `adb reverse tcp:8090 tcp:8090` first.
//
//   node benchmarks/serve-fixtures.mjs [port]
//
// `?bytesPerSecond=N` streams the file at roughly N bytes per second, to make
// download progress observable on a local network.
//
// Files carry `ETag` / `Last-Modified`; a matching `If-None-Match` (or a
// not-older `If-Modified-Since`) is answered with 304, like a CDN.
//
// `GET /__control/offline` makes the server drop every other connection
// without answering (a device without network, as far as the app can tell)
// until `GET /__control/online`.
//
// Redirects: `/__redirect?to=<url>` answers 302 to `<url>`, and
// `/__redirect-loop` redirects to itself. `?require=a,b` answers 401 unless
// the request has all of these headers, `?forbid=a,b` 403 if it has any.

import { createReadStream, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { basename, dirname, join } from 'node:path';
import { Transform } from 'node:stream';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), 'fixtures');
const port = Number(process.argv[2] ?? 8090);

function throttle(bytesPerSecond) {
  const chunkBytes = Math.max(1024, Math.floor(bytesPerSecond / 10));
  return new Transform({
    highWaterMark: chunkBytes,
    transform(chunk, _encoding, done) {
      let offset = 0;
      const push = () => {
        if (offset >= chunk.length) return done();
        this.push(chunk.subarray(offset, offset + chunkBytes));
        offset += chunkBytes;
        setTimeout(push, 100);
      };
      push();
    },
  });
}

let offline = false;

createServer((request, response) => {
  const url = new URL(request.url ?? '/', 'http://x');
  if (url.pathname.startsWith('/__control/')) {
    offline = url.pathname === '/__control/offline';
    console.log(
      `${request.method} ${url.pathname} → ${offline ? 'offline' : 'online'}`,
    );
    response
      .writeHead(200, { 'Content-Type': 'text/plain' })
      .end(offline ? 'offline' : 'online');
    return;
  }
  if (offline) {
    console.log(
      `${request.method} ${url.pathname}${url.search} → dropped (offline)`,
    );
    request.socket.destroy();
    return;
  }
  if (url.pathname === '/__redirect' || url.pathname === '/__redirect-loop') {
    const location = url.searchParams.get('to') ?? url.pathname;
    response.writeHead(302, { Location: location }).end();
    console.log(`${request.method} ${url.pathname}${url.search} → ${location}`);
    return;
  }
  const names = (key) =>
    (url.searchParams.get(key) ?? '').split(',').filter(Boolean);
  const status = names('require').some((n) => !(n in request.headers))
    ? 401
    : names('forbid').some((n) => n in request.headers)
      ? 403
      : null;
  if (status !== null) {
    response.writeHead(status).end();
    console.log(`${request.method} ${url.pathname}${url.search} → ${status}`);
    return;
  }
  const name = basename(decodeURIComponent(url.pathname));
  const file = join(root, name);
  try {
    const { size, mtime } = statSync(file);
    const etag = `"${size.toString(16)}-${mtime.getTime().toString(16)}"`;
    const lastModified = mtime.toUTCString();
    const since = Date.parse(request.headers['if-modified-since'] ?? '');
    const notModified =
      request.headers['if-none-match'] !== undefined
        ? request.headers['if-none-match'] === etag
        : !Number.isNaN(since) &&
          Math.floor(mtime.getTime() / 1000) * 1000 <= since;
    if (notModified) {
      response
        .writeHead(304, { ETag: etag, 'Last-Modified': lastModified })
        .end();
      console.log(`${request.method} ${url.pathname}${url.search} → 304`);
      return;
    }
    response.writeHead(200, {
      'Content-Type': 'application/pdf',
      'Content-Length': size,
      ETag: etag,
      'Last-Modified': lastModified,
    });
    const bytesPerSecond = Number(url.searchParams.get('bytesPerSecond'));
    const stream = createReadStream(file);
    if (bytesPerSecond > 0)
      stream.pipe(throttle(bytesPerSecond)).pipe(response);
    else stream.pipe(response);
  } catch {
    response.writeHead(404).end();
  }
  console.log(`${request.method} ${url.pathname}${url.search}`);
}).listen(port, () =>
  console.log(`Serving ${root} on http://localhost:${port}`),
);

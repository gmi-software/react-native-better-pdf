#!/usr/bin/env node
// Generates deterministic PDF fixtures for benchmarks and stress tests.
//
//   node benchmarks/generate-fixtures.mjs            # all fixtures
//   node benchmarks/generate-fixtures.mjs small medium
//
// Output: benchmarks/fixtures/ (gitignored). Fixtures are synthetic but
// structurally realistic: text pages use many positioned TJ runs like real
// PDF producers, scanned pages embed one full-page JPEG per page (each page
// has its own image object, so renderers cannot share decoded images).
// Output is deterministic (seeded PRNG) so runs are comparable.

import { createCipheriv, createHash } from 'node:crypto';
import { mkdirSync, writeFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';
import jpeg from 'jpeg-js';

const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), 'fixtures');
const A4 = { width: 595, height: 842 };

// ---------------------------------------------------------------- utilities

function prng(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const WORDS =
  'lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua enim ad minim veniam quis nostrud exercitation ullamco laboris nisi aliquip ex ea commodo consequat duis aute irure in reprehenderit voluptate velit esse cillum fugiat nulla pariatur excepteur sint occaecat cupidatat non proident sunt culpa qui officia deserunt mollit anim id est laborum'.split(
    ' ',
  );

function sentence(random, words) {
  const out = [];
  for (let i = 0; i < words; i++)
    out.push(WORDS[Math.floor(random() * WORDS.length)]);
  return out.join(' ');
}

function rc4(key, data) {
  const s = new Uint8Array(256);
  for (let i = 0; i < 256; i++) s[i] = i;
  for (let i = 0, j = 0; i < 256; i++) {
    j = (j + s[i] + key[i % key.length]) & 255;
    [s[i], s[j]] = [s[j], s[i]];
  }
  const out = Buffer.alloc(data.length);
  for (let k = 0, i = 0, j = 0; k < data.length; k++) {
    i = (i + 1) & 255;
    j = (j + s[i]) & 255;
    [s[i], s[j]] = [s[j], s[i]];
    out[k] = data[k] ^ s[(s[i] + s[j]) & 255];
  }
  return out;
}

const md5 = (...parts) =>
  createHash('md5').update(Buffer.concat(parts)).digest();

// PDF Standard Security Handler, revision 2 (40-bit RC4). Old, but every
// renderer supports it, and it is enough to exercise the password flows.
const PASSWORD_PAD = Buffer.from(
  '28bf4e5e4e758a4164004e56fffa01082e2e00b6d0683e802f0ca9fe6453697a',
  'hex',
);
const padPassword = (password) =>
  Buffer.concat([Buffer.from(password, 'latin1'), PASSWORD_PAD]).subarray(
    0,
    32,
  );

function standardSecurity(userPassword, ownerPassword, fileId) {
  const permissions = -4;
  const ownerKey = md5(padPassword(ownerPassword)).subarray(0, 5);
  const O = rc4(ownerKey, padPassword(userPassword));
  const P = Buffer.alloc(4);
  P.writeInt32LE(permissions);
  const fileKey = md5(padPassword(userPassword), O, P, fileId).subarray(0, 5);
  const U = rc4(fileKey, PASSWORD_PAD);
  return {
    dictionary: `<< /Filter /Standard /V 1 /R 2 /O <${O.toString('hex')}> /U <${U.toString('hex')}> /P ${permissions} >>`,
    encrypt(objectNumber, data) {
      const suffix = Buffer.from([
        objectNumber & 255,
        (objectNumber >> 8) & 255,
        (objectNumber >> 16) & 255,
        0,
        0,
      ]);
      return rc4(md5(fileKey, suffix).subarray(0, 10), data);
    },
  };
}

// Revision 4, 128-bit AES (AESV2).
function aesSecurity(userPassword, ownerPassword, fileId) {
  const permissions = -4;
  const rehash = (digest) => {
    for (let i = 0; i < 50; i++) digest = md5(digest);
    return digest;
  };
  const rc4Passes = (key, data) => {
    let out = rc4(key, data);
    for (let i = 1; i <= 19; i++) {
      const passKey = key.map((byte) => byte ^ i);
      out = rc4(passKey, out);
    }
    return out;
  };
  const O = rc4Passes(
    rehash(md5(padPassword(ownerPassword))),
    padPassword(userPassword),
  );
  const P = Buffer.alloc(4);
  P.writeInt32LE(permissions);
  const fileKey = rehash(md5(padPassword(userPassword), O, P, fileId));
  const U = Buffer.concat([
    rc4Passes(fileKey, md5(PASSWORD_PAD, fileId)),
    Buffer.alloc(16),
  ]);
  return {
    dictionary:
      '<< /Filter /Standard /V 4 /R 4 /Length 128 ' +
      '/CF << /StdCF << /CFM /AESV2 /AuthEvent /DocOpen /Length 16 >> >> /StmF /StdCF /StrF /StdCF ' +
      `/O <${O.toString('hex')}> /U <${U.toString('hex')}> /P ${permissions} >>`,
    encrypt(objectNumber, data) {
      const suffix = Buffer.from([
        objectNumber & 255,
        (objectNumber >> 8) & 255,
        (objectNumber >> 16) & 255,
        0,
        0,
      ]);
      const key = md5(fileKey, suffix, Buffer.from('sAlT', 'latin1'));
      const iv = md5(key, data);
      const cipher = createCipheriv('aes-128-cbc', key, iv);
      return Buffer.concat([iv, cipher.update(data), cipher.final()]);
    },
  };
}

// ---------------------------------------------------------------- PDF writer

class PdfWriter {
  objects = []; // index = object number - 1

  reserve() {
    this.objects.push(null);
    return this.objects.length;
  }

  set(number, dictionary, stream) {
    this.objects[number - 1] = { dictionary, stream };
  }

  add(dictionary, stream) {
    const number = this.reserve();
    this.set(number, dictionary, stream);
    return number;
  }

  build({ root, password, cipher = 'rc4', objectStreams = false, info }) {
    if (objectStreams) return this.buildWithObjectStreams({ root, info });
    const fileId = createHash('md5')
      .update(String(this.objects.length))
      .digest();
    const handler = cipher === 'aes' ? aesSecurity : standardSecurity;
    const security = password
      ? handler(password, `${password}-owner`, fileId)
      : null;
    const encryptNumber = security ? this.add(security.dictionary) : null;
    // Strings outside streams are encrypted too, with the object's key.
    const infoNumber = info ? this.reserve() : null;
    if (info) {
      this.set(
        infoNumber,
        infoDictionary(info, (bytes) => security?.encrypt(infoNumber, bytes)),
      );
    }

    const chunks = [Buffer.from('%PDF-1.7\n%\xe2\xe3\xcf\xd3\n', 'latin1')];
    let offset = chunks[0].length;
    const offsets = [];
    this.objects.forEach((object, index) => {
      const number = index + 1;
      offsets.push(offset);
      let body;
      if (object.stream) {
        const data =
          security && number !== encryptNumber
            ? security.encrypt(number, object.stream)
            : object.stream;
        const dictionary = object.dictionary.replace(
          />>\s*$/,
          ` /Length ${data.length} >>`,
        );
        body = Buffer.concat([
          Buffer.from(`${number} 0 obj\n${dictionary}\nstream\n`, 'latin1'),
          data,
          Buffer.from('\nendstream\nendobj\n', 'latin1'),
        ]);
      } else {
        const dictionary =
          security && number !== encryptNumber && number !== infoNumber
            ? encryptStrings(object.dictionary, (bytes) => security.encrypt(number, bytes))
            : object.dictionary;
        body = Buffer.from(`${number} 0 obj\n${dictionary}\nendobj\n`, 'latin1');
      }
      chunks.push(body);
      offset += body.length;
    });

    const xref = [`xref\n0 ${this.objects.length + 1}\n0000000000 65535 f \n`];
    for (const value of offsets)
      xref.push(`${String(value).padStart(10, '0')} 00000 n \n`);
    const id = fileId.toString('hex');
    xref.push(
      `trailer\n<< /Size ${this.objects.length + 1} /Root ${root} 0 R` +
        (encryptNumber ? ` /Encrypt ${encryptNumber} 0 R` : '') +
        (infoNumber ? ` /Info ${infoNumber} 0 R` : '') +
        ` /ID [<${id}> <${id}>] >>\nstartxref\n${offset}\n%%EOF\n`,
    );
    chunks.push(Buffer.from(xref.join(''), 'latin1'));
    return Buffer.concat(chunks);
  }
}

/**
 * Replaces the literal strings in `text` (an object's dictionary) with hex
 * strings of their bytes through `encrypt`, as an encrypted document stores
 * strings; names and the rest stay.
 */
function encryptStrings(text, encrypt) {
  let out = '';
  let index = 0;
  while (index < text.length) {
    const char = text[index];
    if (char !== '(') {
      out += char;
      index++;
      continue;
    }
    const bytes = [];
    let depth = 1;
    index++;
    while (index < text.length && depth > 0) {
      let c = text[index++];
      if (c === '\\') {
        const next = text[index++];
        const escapes = { n: 10, r: 13, t: 9, b: 8, f: 12 };
        if (next in escapes) bytes.push(escapes[next]);
        else if (/[0-7]/.test(next)) {
          let octal = next;
          while (octal.length < 3 && /[0-7]/.test(text[index])) octal += text[index++];
          bytes.push(parseInt(octal, 8) & 0xff);
        } else bytes.push(next.charCodeAt(0));
        continue;
      }
      if (c === '(') depth++;
      if (c === ')' && --depth === 0) break;
      bytes.push(c.charCodeAt(0) & 0xff);
    }
    out += `<${encrypt(Buffer.from(bytes)).toString('hex')}>`;
  }
  return out;
}

/**
 * PDF 1.5 layout used by most modern producers: every non-stream object in
 * one object stream, and a cross-reference *stream* (Flate, PNG "Up"
 * predictor) instead of a classic xref table.
 */
PdfWriter.prototype.buildWithObjectStreams = function ({ root, info }) {
  const infoNumber = info ? this.add(infoDictionary(info)) : null;
  const fileId = createHash('md5').update(String(this.objects.length)).digest();
  const packed = [];
  const header = [];
  let body = '';
  this.objects.forEach((object, index) => {
    if (object.stream) return;
    header.push(`${index + 1} ${Buffer.byteLength(body, 'latin1')}`);
    body += `${object.dictionary}\n`;
    packed.push(index + 1);
  });
  const headerText = `${header.join(' ')}\n`;
  const objStmNumber = this.objects.length + 1;
  const xrefNumber = objStmNumber + 1;
  const objStmData = deflateSync(Buffer.from(headerText + body, 'latin1'));

  const chunks = [Buffer.from('%PDF-1.7\n%\xe2\xe3\xcf\xd3\n', 'latin1')];
  let offset = chunks[0].length;
  const entries = new Map(); // object number -> [type, field2, field3]
  const push = (buffer) => {
    chunks.push(buffer);
    offset += buffer.length;
  };
  const writeStream = (number, dictionary, data) => {
    entries.set(number, [1, offset, 0]);
    push(Buffer.concat([
      Buffer.from(`${number} 0 obj\n${dictionary.replace(/>>\s*$/, ` /Length ${data.length} >>`)}\nstream\n`, 'latin1'),
      data,
      Buffer.from('\nendstream\nendobj\n', 'latin1'),
    ]));
  };
  this.objects.forEach((object, index) => {
    if (object.stream) writeStream(index + 1, object.dictionary, object.stream);
  });
  writeStream(
    objStmNumber,
    `<< /Type /ObjStm /N ${packed.length} /First ${Buffer.byteLength(headerText, 'latin1')} /Filter /FlateDecode >>`,
    objStmData,
  );
  packed.forEach((number, position) => entries.set(number, [2, objStmNumber, position]));

  // The xref stream describes itself too.
  const size = xrefNumber + 1;
  entries.set(xrefNumber, [1, offset, 0]);
  const widths = [1, 4, 2];
  const rowLength = widths.reduce((a, b) => a + b, 0);
  const rows = Buffer.alloc(size * rowLength);
  for (let number = 0; number < size; number++) {
    const [type, a, b] = number === 0 ? [0, 0, 65535] : (entries.get(number) ?? [0, 0, 0]);
    const row = number * rowLength;
    rows.writeUInt8(type, row);
    rows.writeUInt32BE(a, row + 1);
    rows.writeUInt16BE(b, row + 5);
  }
  // PNG "Up" predictor: each row is prefixed with filter type 2 and stores
  // the difference to the previous row.
  const predicted = Buffer.alloc(size * (rowLength + 1));
  for (let number = 0; number < size; number++) {
    predicted[number * (rowLength + 1)] = 2;
    for (let column = 0; column < rowLength; column++) {
      const current = rows[number * rowLength + column];
      const above = number > 0 ? rows[(number - 1) * rowLength + column] : 0;
      predicted[number * (rowLength + 1) + 1 + column] = (current - above) & 0xff;
    }
  }
  const id = fileId.toString('hex');
  const xrefData = deflateSync(predicted);
  push(Buffer.concat([
    Buffer.from(
      `${xrefNumber} 0 obj\n<< /Type /XRef /Size ${size} /W [${widths.join(' ')}] /Root ${root} 0 R ` +
        (infoNumber ? `/Info ${infoNumber} 0 R ` : '') +
        `/ID [<${id}> <${id}>] /Filter /FlateDecode /DecodeParms << /Predictor 12 /Columns ${rowLength} >> ` +
        `/Length ${xrefData.length} >>\nstream\n`,
      'latin1',
    ),
    xrefData,
    Buffer.from('\nendstream\nendobj\n', 'latin1'),
  ]));
  const startxref = entries.get(xrefNumber)[1];
  push(Buffer.from(`startxref\n${startxref}\n%%EOF\n`, 'latin1'));
  return Buffer.concat(chunks);
};

/**
 * Builds a document. `pageContent(index)` returns
 * `{ content: string, images?: { name, jpeg, width, height }[] }`.
 */
function buildDocument({
  pageCount,
  pageContent,
  pageSize = () => A4,
  compress = true,
  password,
  cipher,
  catalogEntries,
  objectStreams = false,
  // Replaces a page's `/MediaBox [...]`, e.g. to add /CropBox and /Rotate or
  // leave the box to inheritance; `pagesEntries` goes into the /Pages node.
  pageBoxes,
  pagesEntries = '',
  // The trailer's /Info dictionary: `{ Title: 'text', ... }`.
  info,
}) {
  const writer = new PdfWriter();
  const catalog = writer.reserve();
  const pages = writer.reserve();
  const font = writer.add(
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  );
  // Reserved up front so link annotations can target any page.
  const pageRefs = Array.from({ length: pageCount }, () => writer.reserve());

  for (let index = 0; index < pageCount; index++) {
    const { content, images = [], annotations } = pageContent(index);
    const annotationRefs = (annotations?.(pageRefs) ?? []).map((dictionary) =>
      writer.add(dictionary),
    );
    const xobjects = images.map((image) => {
      const number = writer.add(
        `<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} ` +
          // jpeg-js writes three-component (YCbCr) JPEGs only, gray pixels
          // included: declared as DeviceRGB to match. DeviceGray drew
          // nothing on iOS (CoreGraphics checks the component count; PDFium
          // does not).
          '/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode >>',
        image.jpeg,
      );
      return `/${image.name} ${number} 0 R`;
    });
    const raw = Buffer.from(content, 'latin1');
    const stream = compress
      ? writer.add('<< /Filter /FlateDecode >>', deflateSync(raw))
      : writer.add('<< >>', raw);
    const size = pageSize(index);
    const boxes = pageBoxes?.(index) ?? `/MediaBox [0 0 ${size.width} ${size.height}]`;
    const annots = annotationRefs.length
      ? ` /Annots [${annotationRefs.map((ref) => `${ref} 0 R`).join(' ')}]`
      : '';
    writer.set(
      pageRefs[index],
      `<< /Type /Page /Parent ${pages} 0 R ${boxes} ` +
        `/Resources << /Font << /F1 ${font} 0 R >> /XObject << ${xobjects.join(' ')} >> >> ` +
        `/Contents ${stream} 0 R${annots} >>`,
    );
  }

  writer.set(
    pages,
    `<< /Type /Pages /Kids [${pageRefs.map((r) => `${r} 0 R`).join(' ')}] /Count ${pageCount}${pagesEntries ? ` ${pagesEntries}` : ''} >>`,
  );
  const extra = catalogEntries ? ` ${catalogEntries(writer, pageRefs)}` : '';
  // One `{ page, refs }`, or one per page.
  for (const { page, refs } of [writer.pendingAnnots ?? []].flat()) {
    const object = writer.objects[page - 1];
    object.dictionary = object.dictionary.replace(
      />>\s*$/,
      ` /Annots [${refs.map((ref) => `${ref} 0 R`).join(' ')}] >>`,
    );
  }
  writer.set(catalog, `<< /Type /Catalog /Pages ${pages} 0 R${extra} >>`);
  return writer.build({ root: catalog, password, cipher, objectStreams, info });
}

/** Bézier approximation of an ellipse inscribed in [2, 2, w - 2, h - 2]. */
function ellipse(w, h) {
  const k = 0.5523;
  const cx = w / 2;
  const cy = h / 2;
  const rx = w / 2 - 2;
  const ry = h / 2 - 2;
  return [
    `${cx + rx} ${cy} m`,
    `${cx + rx} ${cy + ry * k} ${cx + rx * k} ${cy + ry} ${cx} ${cy + ry} c`,
    `${cx - rx * k} ${cy + ry} ${cx - rx} ${cy + ry * k} ${cx - rx} ${cy} c`,
    `${cx - rx} ${cy - ry * k} ${cx - rx * k} ${cy - ry} ${cx} ${cy - ry} c`,
    `${cx + rx * k} ${cy - ry} ${cx + rx} ${cy - ry * k} ${cx + rx} ${cy} c`,
  ].join(' ');
}

/** A PDF text string: printable ASCII as a literal, anything else as UTF-16BE. */
function pdfText(text) {
  if (/^[\x20-\x7e]*$/.test(text)) {
    return `(${text.replace(/[\\()]/g, (c) => `\\${c}`)})`;
  }
  const utf16 = Buffer.from(text, 'utf16le').swap16();
  return `<FEFF${utf16.toString('hex').toUpperCase()}>`;
}

/**
 * An information dictionary from `{ key: text }`. Text that is not
 * printable ASCII is written as UTF-16BE; `encrypt(bytes)`, when it returns
 * bytes, encrypts each string (as hex).
 */
function infoDictionary(info, encrypt = () => undefined) {
  const entries = Object.entries(info).map(([key, text]) => {
    const bytes = /^[\x20-\x7e]*$/.test(text)
      ? Buffer.from(text, 'latin1')
      : Buffer.concat([
          Buffer.from([0xfe, 0xff]),
          Buffer.from(text, 'utf16le').swap16(),
        ]);
    const encrypted = encrypt(bytes);
    const value = encrypted
      ? `<${encrypted.toString('hex')}>`
      : bytes[0] === 0xfe
        ? `<${bytes.toString('hex').toUpperCase()}>`
        : pdfText(text);
    return `/${key} ${value}`;
  });
  return `<< ${entries.join(' ')} >>`;
}

/**
 * Adds a document outline (bookmarks). Items: `{ title | rawTitle, target?,
 * children? }`; `target(pageRefs)` returns the raw `/Dest ...` or `/A ...`
 * entry. Returns the catalog entry.
 */
function outlineEntry(writer, pageRefs, items) {
  const root = writer.reserve();
  const build = (list, parent) => {
    const numbers = list.map(() => writer.reserve());
    list.forEach((item, index) => {
      const children = item.children?.length
        ? build(item.children, numbers[index])
        : null;
      const parts = [
        `/Title ${item.rawTitle ?? pdfText(item.title)}`,
        `/Parent ${parent} 0 R`,
      ];
      if (index > 0) parts.push(`/Prev ${numbers[index - 1]} 0 R`);
      if (index < list.length - 1) parts.push(`/Next ${numbers[index + 1]} 0 R`);
      if (children) {
        parts.push(
          `/First ${children.first} 0 R /Last ${children.last} 0 R /Count ${children.count}`,
        );
      }
      if (item.target) parts.push(item.target(pageRefs));
      writer.set(numbers[index], `<< ${parts.join(' ')} >>`);
    });
    return { first: numbers[0], last: numbers.at(-1), count: list.length };
  };
  const top = build(items, root);
  writer.set(
    root,
    `<< /Type /Outlines /First ${top.first} 0 R /Last ${top.last} 0 R /Count ${top.count} >>`,
  );
  return `/Outlines ${root} 0 R /PageMode /UseOutlines`;
}

/**
 * Every destination form found in real documents. The expected flattened
 * result is asserted by the Android unit tests (PdfOutlineReaderTest) and
 * the viewer-outline e2e flow.
 */
const OUTLINE_ITEMS = [
  {
    // PDFDocEncoding: 0x80 = bullet, 0x84 = em dash.
    rawTitle: '(\\200 Introduction \\204 overview)',
    target: (r) => `/Dest [${r[0]} 0 R /XYZ null null null]`,
  },
  {
    title: 'Rozdział 1: Zażółć gęślą jaźń',
    target: (r) => `/A << /S /GoTo /D [${r[2]} 0 R /Fit] >>`,
    children: [
      { title: '1.1 Named (name tree)', target: () => '/Dest (sec-1-1)' },
      {
        title: '1.2 Named (catalog Dests)',
        target: () => '/Dest /sec12',
        children: [
          { title: 'Głęboko', target: (r) => `/Dest [${r[5]} 0 R /FitH 700]` },
        ],
      },
      { title: '1.3 Named dictionary', target: () => '/A << /S /GoTo /D (zzz) >>' },
    ],
  },
  { title: 'الفصل الثاني', target: (r) => `/Dest [${r[7]} 0 R /Fit]` },
  { title: 'No destination' },
  {
    title: 'External link',
    target: () => '/A << /S /URI /URI (https://example.com) >>',
  },
  { title: 'Last page', target: (r) => `/Dest [${r[11]} 0 R /XYZ 0 842 0]` },
];

function outlineDocument({ objectStreams = false, items = OUTLINE_ITEMS } = {}) {
  return buildDocument({
    pageCount: 12,
    objectStreams,
    pageContent: (i) => ({ content: textPage(i, { fontSize: 10 }) }),
    catalogEntries: (writer, r) => {
      const tree = writer.add(
        `<< /Limits [(sec-1-1) (zzz)] /Names [(sec-1-1) [${r[3]} 0 R /Fit] (zzz) << /D [${r[6]} 0 R /Fit] >>] >>`,
      );
      return (
        `${outlineEntry(writer, r, items)} ` +
        `/Names << /Dests << /Kids [${tree} 0 R] >> >> ` +
        `/Dests << /sec12 [${r[4]} 0 R /XYZ null null null] >>`
      );
    },
  });
}

// ---------------------------------------------------------------- page kinds

/** Dense text set as individually kerned word runs, like real producers. */
function textPage(
  index,
  {
    fontSize = 7,
    heading = true,
    kernCharacters = false,
    pageHeight = A4.height,
  } = {},
) {
  const random = prng(index + 1);
  const lines = [];
  const leading = fontSize * 1.25;
  if (heading) {
    lines.push(
      `BT /F1 18 Tf 50 ${pageHeight - 60} Td (Page ${index + 1}) Tj ET`,
    );
  }
  let y = pageHeight - (heading ? 90 : 50);
  while (y > 40) {
    const words = sentence(random, 16).split(' ');
    // Per-character kerning is what many producers (e.g. word processors)
    // emit; it makes content streams several times larger.
    const runs = kernCharacters
      ? [...words.join(' ')]
          .map((c) => `(${c}) ${-Math.round(random() * 30)}`)
          .join(' ')
      : words
          .map((word) => `(${word} ) ${-Math.round(random() * 40)}`)
          .join(' ');
    lines.push(`BT /F1 ${fontSize} Tf 50 ${y.toFixed(2)} Td [${runs}] TJ ET`);
    y -= leading;
  }
  return lines.join('\n');
}

function makeJpeg({ width, height, seed, gray, quality }) {
  const random = prng(seed);
  const data = Buffer.alloc(width * height * 4);
  // Paper texture + dark "text line" bands + a skewed scan gradient.
  const bandHeight = Math.max(6, Math.round(height / 90));
  for (let y = 0; y < height; y++) {
    const band =
      Math.floor(y / bandHeight) % 3 === 0 &&
      y > height * 0.08 &&
      y < height * 0.92;
    for (let x = 0; x < width; x++) {
      const inText =
        band &&
        x > width * 0.08 &&
        x < width * (0.6 + (0.3 * ((y * 7919) % 97)) / 97);
      let value = 235 - (x / width) * 18 + (random() - 0.5) * 30;
      if (inText && random() < 0.55) value = 40 + random() * 60;
      value = Math.max(0, Math.min(255, value));
      const offset = (y * width + x) * 4;
      data[offset] = value;
      data[offset + 1] = gray ? value : Math.max(0, value - 6);
      data[offset + 2] = gray ? value : Math.max(0, value - 14);
      data[offset + 3] = 255;
    }
  }
  return jpeg.encode({ data, width, height }, quality).data;
}

/** A small set of distinct images, reused round-robin as separate objects. */
function imagePool(count, options) {
  return Array.from({ length: count }, (_, index) =>
    makeJpeg({ ...options, seed: 1000 + index }),
  );
}

// ---------------------------------------------------------------- fixtures

const FIXTURES = {
  small: () =>
    buildDocument({
      pageCount: 10,
      compress: false,
      pageContent: (i) => ({
        content: textPage(i, { fontSize: 6, kernCharacters: true }),
      }),
    }),

  medium: () => {
    const photos = imagePool(8, {
      width: 900,
      height: 600,
      gray: false,
      quality: 80,
    });
    return buildDocument({
      pageCount: 100,
      pageContent: (i) => ({
        content: `${textPage(i, { fontSize: 8 })}\nq 400 0 0 267 97 300 cm /Im0 Do Q`,
        images: [
          {
            name: 'Im0',
            jpeg: photos[i % photos.length],
            width: 900,
            height: 600,
          },
        ],
      }),
    });
  },

  large: () =>
    scanned(500, { width: 1240, height: 1754, gray: true, quality: 60 }),

  extreme: () =>
    scanned(1000, { width: 1240, height: 1754, gray: false, quality: 50 }),

  sample: () =>
    buildDocument({
      pageCount: 3,
      pageContent: (i) => ({ content: textPage(i, { fontSize: 10 }) }),
    }),

  encrypted: () =>
    buildDocument({
      pageCount: 2,
      password: 'secret',
      pageContent: (i) => ({ content: textPage(i, { fontSize: 10 }) }),
    }),
  // `encrypted` with AES-128.
  'aes-encrypted': () =>
    buildDocument({
      pageCount: 2,
      password: 'secret',
      cipher: 'aes',
      pageContent: (i) => ({ content: textPage(i, { fontSize: 10 }) }),
    }),

  // One annotation of each common kind, each with an appearance stream and
  // the Print flag, as producers write them; the last row is a filled form
  // field. Labels on the page name what should appear to their right.
  annotations: () => {
    const kinds = [
      ['Square', 'red frame', (w, h) => `1 0 0 RG 3 w 2 2 ${w - 4} ${h - 4} re S`],
      ['Circle', 'red ellipse', (w, h) => `1 0 0 RG 3 w ${ellipse(w, h)} S`],
      ['Line', 'blue line', (w, h) => `0 0 1 RG 3 w 4 ${h / 2} m ${w - 4} ${h / 2} l S`],
      ['Polygon', 'orange triangle', (w, h) => `1 0.5 0 rg 4 2 m ${w / 2} ${h - 2} l ${w - 4} 2 l h f`],
      ['Ink', 'green stroke', (w, h) => `0 0.6 0 RG 4 w 5 5 m ${w / 2} ${h - 5} l ${w - 5} 5 l S`],
      ['Highlight', 'yellow', (w, h) => `1 1 0 rg 0 0 ${w} ${h} re f`],
      ['Underline', 'blue underline', (w) => `0 0 1 rg 0 2 ${w} 3 re f`],
      ['StrikeOut', 'red strike', (w, h) => `1 0 0 rg 0 ${h / 2 - 1} ${w} 3 re f`],
      ['FreeText', 'blue text', () => '0 0 1 rg BT /F1 16 Tf 6 12 Td (FREE TEXT) Tj ET'],
      ['Text', 'note icon', () => '1 0.85 0 rg 0 0 30 30 re f 0 g 1 w 0.5 0.5 29 29 re S'],
      ['Stamp', 'purple APPROVED', (w, h) => `0.5 0 0.8 rg 0 0 ${w} ${h} re f 1 g BT /F1 16 Tf 40 12 Td (APPROVED) Tj ET`],
    ];
    const rowHeight = 60;
    const top = 760;
    return buildDocument({
      pageCount: 1,
      pageContent: () => ({
        content: [
          '0 g BT /F1 13 Tf 40 800 Td (Each row should show its annotation on the right:) Tj ET',
          ...kinds.map(
            ([kind, what], row) =>
              `BT /F1 11 Tf 40 ${top - row * rowHeight + 12} Td (${row + 1}. ${kind} \\(${what}\\)) Tj ET`,
          ),
          `BT /F1 11 Tf 40 ${top - kinds.length * rowHeight + 12} Td (${kinds.length + 1}. Form field \\(filled text\\)) Tj ET`,
        ].join('\n'),
      }),
      catalogEntries: (writer, r) => {
        const font = writer.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
        const ap = (width, height, content) =>
          writer.add(
            `<< /Type /XObject /Subtype /Form /BBox [0 0 ${width} ${height}] /Resources << /Font << /F1 ${font} 0 R >> >> >>`,
            Buffer.from(content, 'latin1'),
          );
        const width = 200;
        const height = 36;
        const annots = kinds.map(([kind, , draw], row) => {
          const y = top - row * rowHeight;
          // A sticky note is an icon, not a box.
          const [w, h] = kind === 'Text' ? [30, 30] : [width, height];
          const rect = `[300 ${y} ${300 + w} ${y + h}]`;
          const extra = {
            Highlight: `/QuadPoints [300 ${y + height} 500 ${y + height} 300 ${y} 500 ${y}]`,
            Underline: `/QuadPoints [300 ${y + height} 500 ${y + height} 300 ${y} 500 ${y}]`,
            StrikeOut: `/QuadPoints [300 ${y + height} 500 ${y + height} 300 ${y} 500 ${y}]`,
            Line: `/L [300 ${y + height / 2} 500 ${y + height / 2}]`,
            Polygon: `/Vertices [304 ${y + 2} 400 ${y + height - 2} 496 ${y + 2}]`,
            Ink: `/InkList [[305 ${y + 5} 400 ${y + height - 5} 495 ${y + 5}]]`,
            FreeText: '/DA (0 0 1 rg /F1 16 Tf) /Contents (FREE TEXT)',
            Text: '/Contents (A sticky note) /Name /Note',
            Stamp: '/Name /Approved',
          }[kind] ?? '';
          return writer.add(
            `<< /Type /Annot /Subtype /${kind} /F 4 /Rect ${rect} ${extra} /AP << /N ${ap(w, h, draw(w, h))} 0 R >> >>`,
          );
        });
        const fieldY = top - kinds.length * rowHeight;
        const field = writer.add(
          `<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /V (Jan Kowalski) /Rect [300 ${fieldY} 500 ${fieldY + height}] /F 4 /DA (/F1 14 Tf 0 g) ` +
            `/AP << /N ${ap(width, height, '0.9 0.9 0.9 rg 0 0 200 36 re f 0 g BT /F1 16 Tf 6 12 Td (Jan Kowalski) Tj ET')} 0 R >> /P ${r[0]} 0 R >>`,
        );
        // Attached to the page by buildDocument (see pendingAnnots).
        writer.pendingAnnots = { page: r[0], refs: [...annots, field] };
        return `/AcroForm << /Fields [${field} 0 R] /DR << /Font << /F1 ${font} 0 R >> >> /DA (/F1 12 Tf 0 g) >>`;
      },
    });
  },
  forms: () => formsDocument(),
  // The same form, password "secret": its strings are encrypted too.
  'forms-encrypted': () => formsDocument({ password: 'secret' }),
  // The same form in object streams, with a cross-reference stream.
  'forms-compressed': () => formsDocument({ objectStreams: true }),
  // A text field on a cropped page and one on a page rotated 90°, without /P.
  'forms-boxes': () =>
    buildDocument({
      pageCount: 2,
      pageBoxes: (i) =>
        i === 0 ? '/MediaBox [0 0 600 800] /CropBox [50 100 550 700]' : '/MediaBox [0 0 595 842] /Rotate 90',
      pageContent: (i) => ({ content: `BT /F1 14 Tf 120 ${i === 0 ? 650 : 760} Td (Page ${i + 1}) Tj ET` }),
      catalogEntries: (writer, r) => {
        const cropped = writer.add('<< /Type /Annot /Subtype /Widget /FT /Tx /T (cropped) /V (on a cropped page) /Rect [100 600 300 630] >>');
        const rotated = writer.add('<< /Type /Annot /Subtype /Widget /FT /Tx /T (rotated) /Rect [100 700 300 730] >>');
        writer.pendingAnnots = [{ page: r[0], refs: [cropped] }, { page: r[1], refs: [rotated] }];
        return `/AcroForm << /Fields [${cropped} 0 R ${rotated} 0 R] >>`;
      },
    }),
  outline: () => outlineDocument(),
  // Timing: 1000 pages, 100 chapters × 10 sections, object streams.
  'outline-large': () =>
    buildDocument({
      pageCount: 1000,
      objectStreams: true,
      pageContent: (i) => ({ content: textPage(i, { fontSize: 10 }) }),
      catalogEntries: (writer, r) =>
        outlineEntry(
          writer,
          r,
          Array.from({ length: 100 }, (_, chapter) => ({
            title: `Chapter ${chapter + 1}`,
            target: (refs) => `/Dest [${refs[chapter * 10]} 0 R /Fit]`,
            children: Array.from({ length: 9 }, (__, section) => ({
              title: `Section ${chapter + 1}.${section + 1}`,
              target: (refs) => `/Dest [${refs[chapter * 10 + section + 1]} 0 R /Fit]`,
            })),
          })),
        ),
    }),
  'outline-compressed': () => outlineDocument({ objectStreams: true }),
  links: () =>
    // Page 1: upper-left = URL link, lower-left = link to page 3, right strip
    // = plain page. Labels are drawn inside each area for manual testing.
    // Android detects only some internal-link forms; see docs/api-design.md.
    buildDocument({
      pageCount: 3,
      pageContent: (i) =>
        i === 0
          ? {
              content: [
                '0.85 0.92 1 rg 0 421 357 421 re f',
                '0.9 1 0.9 rg 0 0 357 421 re f',
                '0 g BT /F1 20 Tf 40 620 Td (URL LINK) Tj ET',
                'BT /F1 20 Tf 40 200 Td (GO TO PAGE 3) Tj ET',
                'BT /F1 14 Tf 380 420 Td (plain page) Tj ET',
              ].join('\n'),
              annotations: (pageRefs) => [
                '<< /Type /Annot /Subtype /Link /Rect [0 421 357 842] /Border [0 0 0] ' +
                  '/A << /S /URI /URI (https://example.com/better-pdf) >> >>',
                // GoTo action with an XYZ destination: the internal-link form
                // Android's PdfRenderer reports (PDFKit supports every form).
                `<< /Type /Annot /Subtype /Link /Rect [0 0 357 421] /Border [0 0 0] ` +
                  `/A << /S /GoTo /D [${pageRefs[2]} 0 R /XYZ 0 842 0] >> >>`,
              ],
            }
          : { content: textPage(i, { fontSize: 10 }) },
    }),
  // Every form of link (Android reads them from the file, see
  // PdfLinkReaderTest; PDFKit on iOS). Page 1 for e2e, laid out like
  // `links`: upper-left = /Dest with /Fit and no action (to page 3),
  // lower-left = GoTo a string of the /Dests name tree (to page 4),
  // upper-right = a JavaScript action (a page tap), lower-right = plain.
  'links-all': () => linksAllDocument(),

  'page-boxes': () => pageBoxesDocument(),
  // Same pages: the parser's object-stream path, and the renderer fallback
  // Android uses for encrypted documents (password "secret").
  'page-boxes-compressed': () => pageBoxesDocument({ objectStreams: true }),
  'page-boxes-encrypted': () => pageBoxesDocument({ password: 'secret' }),

  // Document information: a UTF-16 title, a padded and an empty value, dates
  // with a time-zone offset and in UTC.
  metadata: () => metadataDocument(),
  'metadata-compressed': () => metadataDocument({ objectStreams: true }),
  'metadata-encrypted': () => metadataDocument({ password: 'secret' }),

  'mixed-sizes': () => {
    // Portrait, landscape, a very tall "receipt" and a square page: exercises
    // layout and the base bitmap pixel cap.
    const sizes = [
      A4,
      { width: 842, height: 595 },
      { width: 226, height: 3000 },
      A4,
      { width: 300, height: 300 },
    ];
    return buildDocument({
      pageCount: sizes.length,
      pageSize: (i) => sizes[i],
      pageContent: (i) => ({ content: textPage(i, { fontSize: 9 }) }),
    });
  },

  // One landscape page, shorter than a portrait viewport at fit-width:
  // where a short document sits, and content insets around it.
  'single-page': () =>
    buildDocument({
      pageCount: 1,
      pageSize: () => ({ width: 842, height: 595 }),
      pageContent: (i) => ({
        content: textPage(i, { fontSize: 9, pageHeight: 595 }),
      }),
    }),
};

/**
 * Page sizes as displayed: crop box (clipped to the media box), rotated by
 * /Rotate, with inheritance from the /Pages node. Each page's heading holds
 * its expected size (width × height in points).
 */
/**
 * An AcroForm on two pages: text fields (required and empty, filled,
 * multiline, read-only, with a tooltip, nested in a parent field, at the
 * bottom of a page), a checkbox, a radio group, a combo box, a list box,
 * and an unsigned signature field; page 1 starts with a square annotation
 * that is not a field. Every widget has an appearance stream and border
 * and background colours; `/NeedAppearances` lets viewers draw their own.
 */
function formsDocument(options = {}) {
  const labels = [
    [0, 760, '1. Name (required)'],
    [0, 710, '2. Email'],
    [0, 660, '3. Notes (multiline)'],
    [0, 560, '4. I agree (checkbox)'],
    [0, 510, '5. Plan (radio): Basic / Pro'],
    [0, 460, '6. Country (combo box)'],
    [0, 400, '7. Colours (list box)'],
    [0, 310, '8. ID (read-only)'],
    [0, 250, '9. Phone (with a tooltip)'],
    [0, 200, '10. City (address.city)'],
    [0, 60, '11. Postcode (bottom)'],
    [1, 760, '12. Date'],
    [1, 700, '13. Signature (unsigned)'],
  ];
  return buildDocument({
    ...options,
    pageCount: 2,
    pageContent: (i) => ({
      content: [
        `0 g BT /F1 14 Tf 40 800 Td (Form, page ${i + 1}) Tj ET`,
        ...labels
          .filter(([page]) => page === i)
          .map(([, y, text]) => `BT /F1 11 Tf 40 ${y + 8} Td (${text.replace(/[()]/g, '\\$&')}) Tj ET`),
      ].join('\n'),
    }),
    catalogEntries: (writer, r) => {
      const helv = writer.add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
      const zadb = writer.add('<< /Type /Font /Subtype /Type1 /BaseFont /ZapfDingbats >>');
      const ap = (width, height, content) =>
        writer.add(
          `<< /Type /XObject /Subtype /Form /BBox [0 0 ${width} ${height}] ` +
            `/Resources << /Font << /Helv ${helv} 0 R /ZaDb ${zadb} 0 R >> >> >>`,
          Buffer.from(content, 'latin1'),
        );
      const box = (w, h) => `0.95 0.95 1 rg 0 0 ${w} ${h} re f 0.4 0.4 0.6 RG 1 w 0.5 0.5 ${w - 1} ${h - 1} re S`;
      const textAp = (w, h, lines = []) =>
        ap(w, h, `${box(w, h)} /Tx BMC q BT /Helv 12 Tf 0 g 4 ${h - 16} Td ${lines.map((line) => `(${line}) Tj 0 -14 Td`).join(' ')} ET Q EMC`);
      const rect = (x, y, w, h) => `/Rect [${x} ${y} ${x + w} ${y + h}]`;
      const da = '/DA (/Helv 12 Tf 0 g)';
      const fields = [];
      const pageAnnots = [[], []];
      const field = (page, dictionary) => {
        const ref = writer.add(dictionary);
        fields.push(ref);
        pageAnnots[page].push(ref);
        return ref;
      };
      const widget = (page, parentRef, dictionary) => {
        const ref = writer.add(dictionary.replace('<<', `<< /Parent ${parentRef} 0 R`));
        pageAnnots[page].push(ref);
        return ref;
      };
      const common = (page, caption = '') =>
        `/Type /Annot /Subtype /Widget /F 4 /P ${r[page]} 0 R /MK << /BC [0.4 0.4 0.6] /BG [0.95 0.95 1]${caption} >>`;
      // Not a field: widgets are not the page's first annotations.
      pageAnnots[0].push(
        writer.add(
          `<< /Type /Annot /Subtype /Square /F 4 ${rect(520, 790, 40, 30)} /C [1 0 0] ` +
            `/AP << /N ${ap(40, 30, '1 0 0 RG 2 w 1 1 38 28 re S')} 0 R >> >>`,
        ),
      );

      field(0, `<< ${common(0)} /FT /Tx /T (name) /Ff 2 ${da} ${rect(200, 750, 300, 26)} /AP << /N ${textAp(300, 26)} 0 R >> >>`);
      field(0, `<< ${common(0)} /FT /Tx /T (email) /V (jan@example.com) ${da} ${rect(200, 700, 300, 26)} /AP << /N ${textAp(300, 26, ['jan@example.com'])} 0 R >> >>`);
      field(0, `<< ${common(0)} /FT /Tx /T (notes) /Ff 4096 ${da} ${rect(200, 600, 300, 76)} /AP << /N ${textAp(300, 76)} 0 R >> >>`);
      const check = (w) => `0 g BT /ZaDb ${w - 4} Tf 2 3 Td (4) Tj ET`;
      field(0,
        `<< ${common(0, ' /CA (4)')} /FT /Btn /T (agree) /V /Off /AS /Off /DA (/ZaDb 0 Tf 0 g) ${rect(200, 552, 20, 20)} ` +
          `/AP << /N << /Yes ${ap(20, 20, `${box(20, 20)} ${check(20)}`)} 0 R /Off ${ap(20, 20, box(20, 20))} 0 R >> >> >>`,
      );
      const radioRef = writer.reserve();
      fields.push(radioRef);
      const circle = (on) => `0.95 0.95 1 rg ${ellipse(20, 20)} f 0.4 0.4 0.6 RG 1 w ${ellipse(20, 20)} S` +
        (on ? ` 0 g 6 10 m 6 12.2 7.8 14 10 14 c 12.2 14 14 12.2 14 10 c 14 7.8 12.2 6 10 6 c 7.8 6 6 7.8 6 10 c f` : '');
      const radioKids = [['basic', 200], ['pro', 300]].map(([state, x]) =>
        widget(0, radioRef,
          `<< ${common(0, ' /CA (l)')} /AS /${state === 'basic' ? 'basic' : 'Off'} ${rect(x, 502, 20, 20)} ` +
            `/AP << /N << /${state} ${ap(20, 20, circle(true))} 0 R /Off ${ap(20, 20, circle(false))} 0 R >> >> >>`,
        ),
      );
      writer.set(radioRef, `<< /FT /Btn /T (plan) /Ff 49152 /V /basic /Kids [${radioKids.map((k) => `${k} 0 R`).join(' ')}] >>`);
      field(0,
        `<< ${common(0)} /FT /Ch /T (country) /Ff 131072 /Opt [(Poland) (Germany) (France)] /V (Poland) ${da} ${rect(200, 452, 200, 26)} ` +
          `/AP << /N ${textAp(200, 26, ['Poland'])} 0 R >> >>`,
      );
      field(0,
        `<< ${common(0)} /FT /Ch /T (colours) /Opt [(Red) (Green) (Blue) (Yellow)] /V (Green) /I [1] ${da} ${rect(200, 340, 200, 70)} ` +
          `/AP << /N ${textAp(200, 70, ['Red', 'Green', 'Blue', 'Yellow'])} 0 R >> >>`,
      );
      field(0, `<< ${common(0)} /FT /Tx /T (id) /Ff 1 /V (A-1234) ${da} ${rect(200, 302, 200, 26)} /AP << /N ${textAp(200, 26, ['A-1234'])} 0 R >> >>`);
      field(0, `<< ${common(0)} /FT /Tx /T (phone) /TU (Your phone number) ${da} ${rect(200, 242, 200, 26)} /AP << /N ${textAp(200, 26)} 0 R >> >>`);
      const addressRef = writer.reserve();
      fields.push(addressRef);
      const city = widget(0, addressRef, `<< ${common(0)} /FT /Tx /T (city) ${da} ${rect(200, 192, 200, 26)} /AP << /N ${textAp(200, 26)} 0 R >> >>`);
      writer.set(addressRef, `<< /T (address) /Kids [${city} 0 R] >>`);
      field(0, `<< ${common(0)} /FT /Tx /T (postcode) ${da} ${rect(200, 52, 120, 26)} /AP << /N ${textAp(120, 26)} 0 R >> >>`);
      field(1, `<< ${common(1)} /FT /Tx /T (date) ${da} ${rect(200, 750, 200, 26)} /AP << /N ${textAp(200, 26)} 0 R >> >>`);
      field(1, `<< ${common(1)} /FT /Sig /T (signature) ${rect(200, 640, 300, 70)} /AP << /N ${ap(300, 70, box(300, 70))} 0 R >> >>`);

      // Attached to the pages by buildDocument (see pendingAnnots).
      writer.pendingAnnots = pageAnnots.map((refs, page) => ({ page: r[page], refs }));
      return (
        `/AcroForm << /Fields [${fields.map((f) => `${f} 0 R`).join(' ')}] /NeedAppearances true ` +
        `/DR << /Font << /Helv ${helv} 0 R /ZaDb ${zadb} 0 R >> >> /DA (/Helv 12 Tf 0 g) >>`
      );
    },
  });
}

function linksAllDocument() {
  // An action viewers do not perform: not a link, a page tap.
  const JAVASCRIPT = "/A << /S /JavaScript /JS (app.alert\\('hi'\\)) >>";
  const link = (rect, rest) => `<< /Type /Annot /Subtype /Link /Rect [${rect}] /Border [0 0 0] ${rest} >>`;
  const label = (y, text) => `BT /F1 12 Tf 44 ${y + 14} Td (${text}) Tj ET`;
  // Page 2: one row per link, 40 pt tall, from the top.
  const rows = [
    // A URL across two lines: an area per line (/QuadPoints), not the /Rect.
    {
      rect: '40 700 400 770',
      rest:
        '/QuadPoints [40 770 400 770 40 745 400 745 40 725 200 725 40 700 200 700] ' +
        '/A << /S /URI /URI (https://example.com/two-lines) >>',
      text: 'URL across two lines',
    },
    { rect: '40 620 300 660', rest: '/Dest /chapter2', text: 'Catalog /Dests name: page 2' },
    { rect: '40 560 300 600', rest: '/Dest (intro)', text: 'Name tree dictionary: page 3' },
    { rect: '40 500 300 540', rest: '/A << /S /Named /N /NextPage >>', text: 'NextPage' },
    { rect: '40 440 300 480', rest: '/A << /S /Named /N /PrevPage >>', text: 'PrevPage' },
    { rect: '40 380 300 420', rest: '/A << /S /Named /N /FirstPage >>', text: 'FirstPage' },
    { rect: '40 320 300 360', rest: '/A << /S /Named /N /LastPage >>', text: 'LastPage' },
    { rect: '40 260 300 300', rest: JAVASCRIPT, text: 'JavaScript: not a link' },
    // A page number instead of a page: meant for other files, used locally by some producers.
    { rect: '40 200 300 240', rest: '/A << /S /GoTo /D [4 /Fit] >>', text: 'Page number 4: page 5' },
  ];
  return buildDocument({
    pageCount: 5,
    pageBoxes: (i) =>
      ({
        // Rotated: areas from the displayed page's top-left.
        2: '/MediaBox [0 0 595 842] /Rotate 90',
        // The annotation in /Annots directly, not by reference.
        3:
          '/MediaBox [0 0 595 842] /Annots [' +
          link('40 700 300 740', '/A << /S /Named /N /PrevPage >>') +
          ']',
      })[i],
    pageContent: (i) => {
      switch (i) {
        case 0:
          return {
            content: [
              '0.85 0.92 1 rg 0 421 357 421 re f',
              '0.9 1 0.9 rg 0 0 357 421 re f',
              '0 g BT /F1 20 Tf 40 620 Td (DEST /FIT: PAGE 3) Tj ET',
              'BT /F1 20 Tf 40 200 Td (NAME TREE: PAGE 4) Tj ET',
              'BT /F1 14 Tf 380 620 Td (JAVASCRIPT) Tj ET',
              'BT /F1 14 Tf 380 200 Td (plain page) Tj ET',
            ].join('\n'),
            annotations: (r) => [
              link('0 421 357 842', `/Dest [${r[2]} 0 R /Fit]`),
              link('0 0 357 421', '/A << /S /GoTo /D (appendix) >>'),
              link('357 421 595 842', JAVASCRIPT),
            ],
          };
        case 1:
          return {
            content: rows.map((row) => label(Number(row.rect.split(' ')[1]), row.text)).join('\n'),
            annotations: () => rows.map((row) => link(row.rect, row.rest)),
          };
        case 2:
          return {
            content: 'BT /F1 14 Tf 100 712 Td (URL on a rotated page) Tj ET',
            annotations: () => [link('100 700 300 730', '/A << /S /URI /URI (https://example.com/rotated) >>')],
          };
        case 3:
          return { content: 'BT /F1 14 Tf 44 714 Td (PrevPage, in /Annots directly) Tj ET' };
        default:
          return { content: textPage(i, { fontSize: 10 }) };
      }
    },
    catalogEntries: (_, r) =>
      `/Dests << /chapter2 [${r[1]} 0 R /FitH 842] >> ` +
      // Name-tree keys sorted; values an array or a dictionary with /D.
      `/Names << /Dests << /Names [(appendix) [${r[3]} 0 R /XYZ 0 842 0] (intro) << /D [${r[2]} 0 R /Fit] >>] >> >>`,
  });
}

function pageBoxesDocument(options = {}) {
  const pages = [
    { boxes: '', expect: '612 x 792 inherited media box' },
    { boxes: '/MediaBox [0 0 595 842] /Rotate 90', expect: '842 x 595 rotate 90' },
    { boxes: '/MediaBox [0 0 600 800] /CropBox [50 100 550 700]', expect: '500 x 600 crop box' },
    { boxes: '/CropBox [0 0 400 792] /Rotate 270', expect: '792 x 400 inherited, crop, rotate 270' },
    { boxes: '/MediaBox [100 100 400 500] /Rotate 180', expect: '300 x 400 offset origin, rotate 180' },
    { boxes: '/MediaBox [0 0 595 842] /CropBox [-50 -50 700 900]', expect: '595 x 842 crop clipped to media' },
    { boxes: '/MediaBox [0 0 300 500] /Rotate -90', expect: '500 x 300 rotate -90' },
  ];
  return buildDocument({
    ...options,
    pageCount: pages.length,
    pagesEntries: '/MediaBox [0 0 612 792]',
    pageBoxes: (i) => pages[i].boxes,
    pageContent: (i) => ({
      content: `BT /F1 14 Tf 20 60 Td (Page ${i + 1}: ${pages[i].expect}) Tj ET`,
    }),
  });
}

const METADATA = {
  Title: 'Raport kwartalny — żółć',
  Author: 'Better PDF fixtures',
  Subject: '',
  Keywords: 'pdf, metadata, fixture',
  Creator: '  generate-fixtures.mjs  ',
  Producer: 'react-native-better-pdf',
  CreationDate: "D:20260315093000+02'00'",
  ModDate: 'D:20260930120000Z',
};

function metadataDocument(options = {}) {
  return buildDocument({
    ...options,
    pageCount: 2,
    info: METADATA,
    pageContent: (i) => ({ content: textPage(i, { fontSize: 10 }) }),
  });
}

function scanned(pageCount, image) {
  const scans = imagePool(12, image);
  return buildDocument({
    pageCount,
    pageContent: (i) => ({
      content: `q ${A4.width} 0 0 ${A4.height} 0 0 cm /Scan Do Q`,
      images: [
        {
          name: 'Scan',
          jpeg: scans[i % scans.length],
          width: image.width,
          height: image.height,
        },
      ],
    }),
  });
}

const MALFORMED = {
  'not-a-pdf': () => Buffer.from('This is plain text, not a PDF.\n'),
  empty: () => Buffer.alloc(0),
  truncated: () => {
    const full = FIXTURES.sample();
    return full.subarray(0, Math.floor(full.length / 2));
  },
  // Outline with a /Next loop and a child pointing back at its parent.
  'outline-cycle': () =>
    buildDocument({
      pageCount: 2,
      pageContent: (i) => ({ content: textPage(i, { fontSize: 10 }) }),
      catalogEntries: (writer, r) => {
        const root = writer.reserve();
        const a = writer.reserve();
        const b = writer.reserve();
        writer.set(root, `<< /Type /Outlines /First ${a} 0 R /Last ${b} 0 R >>`);
        writer.set(a, `<< /Title (A) /Parent ${root} 0 R /Next ${b} 0 R /First ${a} 0 R /Dest [${r[0]} 0 R /Fit] >>`);
        writer.set(b, `<< /Title (B) /Parent ${root} 0 R /Next ${a} 0 R /Dest [${r[1]} 0 R /Fit] >>`);
        return `/Outlines ${root} 0 R`;
      },
    }),
  // 100 nested levels; readers must stop at their depth limit.
  'outline-deep': () => {
    let items = [];
    for (let level = 99; level >= 0; level--) {
      items = [{ title: `Level ${level}`, target: (r) => `/Dest [${r[0]} 0 R /Fit]`, children: items }];
    }
    return outlineDocument({ items });
  },
  // Broken startxref: readers must rebuild the cross-reference table.
  'outline-garbage-xref': () =>
    Buffer.from(
      outlineDocument()
        .toString('latin1')
        .replace(/startxref\n\d+/, 'startxref\n999999999'),
      'latin1',
    ),
  'garbage-xref': () => {
    const full = FIXTURES.sample().toString('latin1');
    return Buffer.from(
      full.replace(/startxref\n\d+/, 'startxref\n999999999'),
      'latin1',
    );
  },
};

// ---------------------------------------------------------------- main

const requested = process.argv.slice(2);
const all = {
  ...FIXTURES,
  ...Object.fromEntries(
    Object.entries(MALFORMED).map(([k, v]) => [`malformed-${k}`, v]),
  ),
};
const names = requested.length ? requested : Object.keys(all);

mkdirSync(OUT_DIR, { recursive: true });
for (const name of names) {
  const make = all[name];
  if (!make) {
    console.error(
      `Unknown fixture "${name}". Known: ${Object.keys(all).join(', ')}`,
    );
    process.exit(1);
  }
  const started = Date.now();
  const file = join(OUT_DIR, `${name}.pdf`);
  writeFileSync(file, make());
  const megabytes = (statSync(file).size / 1024 / 1024).toFixed(2);
  console.log(
    `${name.padEnd(24)} ${megabytes.padStart(8)} MB  (${Date.now() - started} ms)`,
  );
}

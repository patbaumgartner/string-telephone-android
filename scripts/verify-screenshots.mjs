#!/usr/bin/env node
/**
 * Screenshot verification: asserts the committed screenshots are exactly what
 * the test suite renders, that the dedicated scrollbar screenshot differs from
 * the plain chat screenshot only by the right-edge thumb, and that the call
 * screenshot changed only in the bottom control row when Phase 2 added the
 * audio-route buttons. Exits nonzero with a reason on any failure; prints
 * "screenshot verification passed" only after every assertion passes.
 *
 * Controls measured at authoring time:
 * - staleness: before the refresh, docs/09-chat-scrollbar.png was byte-equal
 *   to docs/03-chat.png (0 differing pixels), so the thumb assertion fails on
 *   the stale set and passes only when the thumb actually renders;
 * - freshness: docs/03-chat.png produced by an earlier session's build matched
 *   the current build byte-for-byte, so byte comparison is a valid freshness
 *   check rather than a flaky one.
 *
 * Requires a prior suite run (app/build/screenshots). No third-party modules;
 * PNGs are decoded here with node:zlib.
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { inflateSync } from 'node:zlib';
import { execFileSync } from 'node:child_process';

const root = process.cwd();
const THUMB_MIN_PIXELS = 1000; // a fully opaque minimum-size thumb is ~1300px
const THUMB_STRIP_PX = 25; // 3dp edge padding (7.9px) + 4dp thumb (10.4px) + margin
const PHASE1_COMMIT = 'fc56318'; // last commit before the Phase 2 call UI

function decodePng(buffer, label) {
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (!buffer.subarray(0, 8).equals(signature)) throw new Error(`${label}: not a PNG`);
  let offset = 8;
  let width = 0;
  let height = 0;
  let bitDepth = 0;
  let colorType = 0;
  let interlace = 0;
  const idat = [];
  while (offset + 8 <= buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
      interlace = data[12];
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + length;
  }
  if (bitDepth !== 8 || interlace !== 0) {
    throw new Error(`${label}: unsupported PNG (bitDepth=${bitDepth}, interlace=${interlace})`);
  }
  const channels =
    colorType === 6 ? 4 : colorType === 2 ? 3 : colorType === 0 ? 1 : -1;
  if (channels < 0) throw new Error(`${label}: unsupported colorType=${colorType}`);
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const pixels = Buffer.alloc(height * stride);
  let previous = Buffer.alloc(stride);
  let pos = 0;
  for (let y = 0; y < height; y += 1) {
    const filter = raw[pos];
    pos += 1;
    const line = raw.subarray(pos, pos + stride);
    pos += stride;
    const out = pixels.subarray(y * stride, (y + 1) * stride);
    for (let i = 0; i < stride; i += 1) {
      const a = i >= channels ? out[i - channels] : 0;
      const b = previous[i];
      const c = i >= channels ? previous[i - channels] : 0;
      const x = line[i];
      let value;
      if (filter === 0) value = x;
      else if (filter === 1) value = x + a;
      else if (filter === 2) value = x + b;
      else if (filter === 3) value = x + ((a + b) >> 1);
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        value = x + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
      } else throw new Error(`${label}: unsupported filter ${filter} on row ${y}`);
      out[i] = value & 0xff;
    }
    previous = out;
  }
  return { width, height, channels, pixels, label };
}

function diffBounds(a, b) {
  if (a.width !== b.width || a.height !== b.height) {
    throw new Error(`${a.label} and ${b.label}: size mismatch`);
  }
  let count = 0;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -1;
  let maxY = -1;
  const step = a.channels;
  for (let y = 0; y < a.height; y += 1) {
    for (let x = 0; x < a.width; x += 1) {
      const i = (y * a.width + x) * step;
      if (
        a.pixels[i] !== b.pixels[i] ||
        a.pixels[i + 1] !== b.pixels[i + 1] ||
        a.pixels[i + 2] !== b.pixels[i + 2]
      ) {
        count += 1;
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }
  return { count, minX, minY, maxX, maxY };
}

function png(relative) {
  return decodePng(readFileSync(join(root, relative)), relative);
}

function phase1Png(relative) {
  const blob = execFileSync('git', ['show', `${PHASE1_COMMIT}:${relative}`], {
    cwd: root,
    maxBuffer: 64 * 1024 * 1024,
  });
  return decodePng(blob, `${PHASE1_COMMIT}:${relative}`);
}

const assertions = [];

// A. Freshness: the committed screenshots are byte-identical to what the
//    suite just rendered in app/build/screenshots, and the sets match.
const buildDir = join(root, 'app/build/screenshots');
if (!existsSync(buildDir)) {
  assertions.push([false, 'app/build/screenshots exists (run the suite first)']);
} else {
  const buildFiles = readdirSync(buildDir).filter((f) => f.endsWith('.png')).sort();
  const docsFiles = readdirSync(join(root, 'docs/screenshots'))
    .filter((f) => f.endsWith('.png'))
    .sort();
  assertions.push([
    buildFiles.length > 0 && buildFiles.join(',') === docsFiles.join(','),
    'docs/screenshots and app/build/screenshots hold the same png set',
  ]);
  for (const file of buildFiles) {
    const built = readFileSync(join(buildDir, file));
    const committed = existsSync(join(root, 'docs/screenshots', file))
      ? readFileSync(join(root, 'docs/screenshots', file))
      : Buffer.alloc(0);
    if (!built.equals(committed)) {
      assertions.push([false, `${file} is refreshed to match the suite output`]);
    }
  }
}

// B. The scrollbar screenshots differ from the plain chat screenshots only
//    inside the right-edge thumb strip, by a thumb-sized area.
for (const [plain, scrolled] of [
  ['docs/screenshots/03-chat.png', 'docs/screenshots/09-chat-scrollbar.png'],
  ['docs/screenshots/dark-chat.png', 'docs/screenshots/dark-chat-scrollbar.png'],
]) {
  try {
    const plainPng = png(plain);
    const scrolledPng = png(scrolled);
    const delta = diffBounds(plainPng, scrolledPng);
    assertions.push([
      delta.count >= THUMB_MIN_PIXELS,
      `${scrolled} differs from ${plain} by a thumb-sized area`,
    ]);
    assertions.push([
      delta.minX >= plainPng.width - THUMB_STRIP_PX,
      `${scrolled} differs from ${plain} only inside the right-edge thumb strip`,
    ]);
  } catch (error) {
    assertions.push([false, `${scrolled}: ${error.message}`]);
  }
}

// C. The call screenshot changed only in the bottom control row between the
//    Phase 1 commit and now (Phase 2 added the audio-route buttons).
for (const [current, label] of [
  ['docs/screenshots/04-call.png', 'light call'],
  ['docs/screenshots/dark-call.png', 'dark call'],
]) {
  try {
    const before = phase1Png(current);
    const after = png(current);
    const delta = diffBounds(before, after);
    assertions.push([
      delta.count >= THUMB_MIN_PIXELS,
      `${label} screenshot changed since the phase-1 commit`,
    ]);
    assertions.push([
      delta.minY >= after.height * 0.8 && delta.maxY - delta.minY <= after.height * 0.25,
      `${label} screenshot differences stay in the bottom control row band`,
    ]);
  } catch (error) {
    assertions.push([false, `${current}: ${error.message}`]);
  }
}

let failures = 0;
for (const [condition, label] of assertions) {
  if (!condition) {
    console.error(`FAIL: ${label}`);
    failures += 1;
  }
}

if (failures > 0) {
  console.error(`screenshot verification failed (${failures} assertion(s))`);
  process.exit(1);
}
console.log('screenshot verification passed');

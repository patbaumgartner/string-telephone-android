/**
 * Minimal PNG reader for the screenshot gates. It decodes the 8-bit,
 * non-interlaced PNGs the Robolectric suite writes, so a gate can look at
 * actual pixels instead of trusting that an icon was drawn. No third-party
 * modules: node:zlib does the inflate.
 */
import { inflateSync } from 'node:zlib';

export function decodePng(buffer, label) {
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

/** The bounding box and the number of differing pixels between two images. */
export function diffBounds(a, b) {
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

/** How many pixels carry the exact colour `#rrggbb`. */
export function countColor(image, hex) {
  const wanted = [
    parseInt(hex.slice(1, 3), 16),
    parseInt(hex.slice(3, 5), 16),
    parseInt(hex.slice(5, 7), 16),
  ];
  let count = 0;
  const step = image.channels;
  for (let i = 0; i + 2 < image.pixels.length; i += step) {
    if (
      image.pixels[i] === wanted[0] &&
      image.pixels[i + 1] === wanted[1] &&
      image.pixels[i + 2] === wanted[2]
    ) {
      count += 1;
    }
  }
  return count;
}

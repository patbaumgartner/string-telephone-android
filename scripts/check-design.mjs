#!/usr/bin/env node
/**
 * Design asset check: the Manrope font family and its licence must be bundled
 * exactly like the Room Watchdog reference project, with matching byte sizes,
 * and the launcher must be a full adaptive icon (background + foreground +
 * monochrome).
 */
import { statSync, readFileSync } from 'node:fs';

function fail(message) {
  console.error(`DESIGN-CHECK-FAIL: ${message}`);
  process.exit(1);
}

function readOrEmpty(path) {
  try {
    return readFileSync(path, 'utf8');
  } catch {
    return '';
  }
}

const FONTS = ['light', 'medium', 'regular', 'semibold'];
const REF = '/home/patbaumgartner/GitHub/patbaumgartner/public/esp32-room-watchdog-android';

for (const weight of FONTS) {
  const ours = `app/src/main/res/font/manrope_${weight}.ttf`;
  const ref = `${REF}/app/src/main/res/font/manrope_${weight}.ttf`;
  let oursSize;
  try {
    oursSize = statSync(ours).size;
  } catch {
    fail(`font manrope_${weight}.ttf missing`);
  }
  let refSize;
  try {
    refSize = statSync(ref).size;
  } catch {
    fail(`reference font ${weight} unreadable at ${ref}`);
  }
  if (oursSize !== refSize) fail(`font ${weight} differs from reference`);
}

try {
  statSync('app/src/main/res/raw/manrope_license.txt');
} catch {
  fail('font licence file missing');
}

const adaptive = readOrEmpty('app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml');
for (const part of ['background', 'foreground', 'monochrome']) {
  if (!adaptive.includes(part)) fail(`adaptive icon missing ${part} layer`);
}

console.log('DESIGN-CHECK-OK');

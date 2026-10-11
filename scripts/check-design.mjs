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

const palette = readOrEmpty('app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/ui/theme/Color.kt');
const colors = readOrEmpty('app/src/main/res/values/colors.xml');
const background = readOrEmpty('app/src/main/res/drawable/ic_launcher_background.xml');
const foreground = readOrEmpty('app/src/main/res/drawable/ic_launcher_foreground.xml');
for (const [name, token] of [
  ['ic_launcher_background', 'BrandCyan'],
  ['notification_accent', 'BrandCyanDeep'],
]) {
  const hex = palette.match(new RegExp(`val ${token} = Color\\(0xFF([A-F0-9]{6})\\)`))?.[1];
  if (!hex || !colors.includes(`<color name="${name}">#${hex}</color>`)) {
    fail(`${name} must match theme token ${token}`);
  }
}
if (!background.includes('@color/ic_launcher_background')) fail('launcher background must use the brand resource');
if (!foreground.includes('#FFFFFF')) fail('launcher foreground bubble must be white on cyan');

console.log('DESIGN-CHECK-OK');

#!/usr/bin/env node
/** Verify both publishing jobs install the compile SDK explicitly. */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const workflow = readFileSync('.github/workflows/release.yml', 'utf8');
const lines = workflow.split('\n');
const sdkManager = '"${ANDROID_SDK_ROOT:-$ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"';

function job(name) {
  const start = lines.indexOf(`  ${name}:`);
  assert.notEqual(start, -1, `missing ${name} job`);
  const end = lines.findIndex((line, index) => index > start && /^  [A-Za-z0-9_-]+:/.test(line));
  return lines.slice(start, end === -1 ? lines.length : end).join('\n');
}

for (const name of ['snapshot', 'release']) {
  const block = job(name);
  assert.match(block, /- name: Install Android SDK 37\.1/, `${name} must name its SDK install step`);
  assert.ok(block.includes(sdkManager), `${name} must use the absolute sdkmanager path`);
  assert.ok(block.includes('"platforms;android-37.1"'), `${name} must install SDK Platform 37.1`);
}

console.log('RELEASE-SDK-OK');

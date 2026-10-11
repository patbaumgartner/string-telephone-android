#!/usr/bin/env node
/**
 * Gate support: verify operator-facing documentation states the JDK 27
 * toolchain and no doc still tells contributors to install JDK 17.
 * Part of the docs-cleanup + JDK 27 upgrade ledger.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const docs = ['README.md', 'CONTRIBUTING.md', 'AGENTS.md', 'docs/operations.md', 'docs/adr/0005-android-sdk-levels.md'];

for (const file of docs) {
  let text;
  try {
    text = readFileSync(file, 'utf8');
  } catch {
    console.error(`DOCS-JDK-FAIL: missing ${file}`);
    process.exit(1);
  }
  // A doc must not instruct installing/building with JDK 17 as the toolchain.
  if (/Install JDK 17\b/.test(text) || /JDK 17 toolchain/.test(text) || /Set up Java 17\b/.test(text)) {
    console.error(`DOCS-JDK-FAIL: ${file} still pins JDK 17 as the build toolchain`);
    process.exit(1);
  }
}

// The README and CONTRIBUTING must mention the JDK 27 toolchain.
for (const file of ['README.md', 'CONTRIBUTING.md']) {
  if (!/JDK 27/.test(readFileSync(file, 'utf8'))) {
    console.error(`DOCS-JDK-FAIL: ${file} does not mention JDK 27`);
    process.exit(1);
  }
}

// ADR-0005 must reflect the accepted toolchain (JDK 27 decision recorded).
const adr5 = readFileSync('docs/adr/0005-android-sdk-levels.md', 'utf8');
if (!/JDK 27/.test(adr5)) {
  console.error('DOCS-JDK-FAIL: ADR-0005 does not record the JDK 27 decision');
  process.exit(1);
}

console.log('DOCS-JDK-OK');

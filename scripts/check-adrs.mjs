#!/usr/bin/env node
/**
 * ADR presence check: every decision area the project treats as an
 * architectural gate must have an ADR with Context and Decision sections
 * under docs/adr/.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const adrDir = 'docs/adr';
const required = [
  { pattern: /^\d{4}-pbx-choice/, area: 'PBX choice' },
  { pattern: /^\d{4}-sip-engine/, area: 'SIP engine' },
  { pattern: /^\d{4}-messaging/, area: 'messaging' },
  { pattern: /^\d{4}-licensing/, area: 'licensing' },
  { pattern: /^\d{4}-android-sdk-levels/, area: 'SDK levels' },
  { pattern: /^\d{4}-background/, area: 'background policy' },
  { pattern: /^\d{4}-managed-spring-admin/, area: 'managed administration' },
  { pattern: /^\d{4}-database-postgresql/, area: 'database choice' },
  { pattern: /^\d{4}-media-storage/, area: 'media storage' },
  { pattern: /^\d{4}-message-delivery/, area: 'message delivery states' },
  { pattern: /^\d{4}-message-extras/, area: 'message reactions replies deletion' },
  { pattern: /^\d{4}-group-chat/, area: 'group chat scope' },
  { pattern: /^\d{4}-video-calls/, area: 'video calls' },
];

let files;
try {
  files = readdirSync(adrDir).filter((f) => f.endsWith('.md'));
} catch {
  console.error('ADR-CHECK-FAIL: docs/adr directory missing');
  process.exit(1);
}

for (const req of required) {
  const match = files.find((f) => req.pattern.test(f));
  if (!match) {
    console.error(`ADR-CHECK-FAIL: no ADR matching ${req.pattern} (${req.area})`);
    process.exit(1);
  }
  const text = readFileSync(join(adrDir, match), 'utf8');
  if (!/^##\s+Decision/m.test(text)) {
    console.error(`ADR-CHECK-FAIL: ${match} has no Decision section`);
    process.exit(1);
  }
  if (!/^##\s+Context/m.test(text)) {
    console.error(`ADR-CHECK-FAIL: ${match} has no Context section`);
    process.exit(1);
  }
  if (!/^#\s+ADR-/.test(text)) {
    console.error(`ADR-CHECK-FAIL: ${match} title must start with "# ADR-"`);
    process.exit(1);
  }
}

console.log('ADR-CHECK-OK');

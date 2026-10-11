#!/usr/bin/env node
/**
 * Gate support: verify docs/protocol.md documents the implemented WebSocket
 * stream and no documentation claims the stream is absent.
 * Part of the docs-cleanup + JDK 27 upgrade ledger.
 */
import { readFileSync } from 'node:fs';

const protocol = readFileSync('docs/protocol.md', 'utf8');

// The implemented endpoint must be documented.
if (!/##\s+Realtime stream \(`\/v1\/ws`\)/.test(protocol)) {
  console.error('PROTOCOL-DOC-FAIL: protocol.md has no /v1/ws section');
  process.exit(1);
}
if (!/1008/.test(protocol)) {
  console.error('PROTOCOL-DOC-FAIL: protocol.md does not document the 1008 unauthorized close');
  process.exit(1);
}

// The server must actually implement it where the doc says.
const ws = readFileSync('server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/Ws.kt', 'utf8');
if (!/addHandler\(hub,\s*"\/v1\/ws"\)/.test(ws)) {
  console.error('PROTOCOL-DOC-FAIL: /v1/ws is documented but not implemented in Ws.kt');
  process.exit(1);
}

// No doc may claim the WebSocket stream is missing.
const stale = [
  'README.md',
  'docs/protocol.md',
  'docs/architecture.md',
  'CHANGELOG.md',
];
for (const file of stale) {
  const text = readFileSync(file, 'utf8');
  if (/No WebSocket stream yet/.test(text) || /clients currently poll by cursor/.test(text)) {
    console.error(`PROTOCOL-DOC-FAIL: ${file} claims the WebSocket stream is absent`);
    process.exit(1);
  }
}

console.log('PROTOCOL-DOC-OK');

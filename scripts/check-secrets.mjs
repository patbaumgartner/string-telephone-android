#!/usr/bin/env node
/**
 * Secret scan: fails when git-tracked files contain credential-shaped
 * content. Placeholder passwords used only in test fixtures and ADR examples
 * are allow-listed explicitly.
 */
import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

let tracked;
try {
  tracked = execSync('git ls-files', { encoding: 'utf8' })
    .split('\n')
    .filter(Boolean);
} catch {
  console.error('SECRET-CHECK-FAIL: not a git repository');
  process.exit(1);
}

// Placeholder passwords that exist only in test fixtures and ADR examples.
const ALLOWLIST = [
  'prototest-alice-pass',
  'prototest-bob-pass',
  'CHANGE_ME',
  'changeme',
  'your-strong-password',
  'example-token',
];

const SECRET_PATTERNS = [
  { name: 'AWS access key', re: /AKIA[0-9A-Z]{16}/ },
  { name: 'private key block', re: /-----BEGIN [A-Z ]*PRIVATE KEY-----/ },
  { name: 'generic api token assignment', re: /\b(api[_-]?key|token|secret)\s*[:=]\s*["'][A-Za-z0-9+/_-]{24,}["']/i },
  { name: 'slack webhook', re: /hooks\.slack\.com\/services\/T[A-Za-z0-9]+\/B[A-Za-z0-9]+/ },
  { name: 'bearer token literal', re: /Authorization:\s*Bearer\s+[A-Za-z0-9\-_.]{24,}/ },
];

const TEXT_EXT = /\.(kt|kts|java|md|yml|yaml|json|toml|xml|sh|cjs|mjs|properties|conf|env|txt)$/;
const SKIP = /(^|\/)(\.git|build|\.gradle|node_modules|gradle\/wrapper)\//;

const offenders = [];

for (const file of tracked) {
  if (!TEXT_EXT.test(file) || SKIP.test(file)) continue;
  let content;
  try {
    content = readFileSync(file, 'utf8');
  } catch {
    continue;
  }
  for (const rule of SECRET_PATTERNS) {
    const match = rule.re.exec(content);
    if (!match) continue;
    if (ALLOWLIST.some((ok) => match[0].includes(ok))) continue;
    offenders.push(`${rule.name} in ${file}: ${match[0].slice(0, 12)}...`);
  }
}

if (offenders.length > 0) {
  for (const o of offenders) console.error(`SECRET-CHECK-FAIL: ${o}`);
  process.exit(1);
}

console.log('SECRET-CHECK-OK');

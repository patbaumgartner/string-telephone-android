#!/usr/bin/env node
/**
 * Gate support: verify CI and security workflows build on JDK 27.
 * Part of the docs-cleanup + JDK 27 upgrade ledger.
 */
import { readFileSync } from 'node:fs';

const workflows = ['.github/workflows/ci.yml', '.github/workflows/security.yml'];

for (const file of workflows) {
  const text = readFileSync(file, 'utf8');
  const setup = /java-version:\s*['"]?(\d+)['"]?/.exec(text);
  if (!setup) {
    console.error(`CI-JDK-FAIL: ${file} has no java-version`);
    process.exit(1);
  }
  if (Number(setup[1]) < 27) {
    console.error(`CI-JDK-FAIL: ${file} builds on JDK ${setup[1]}, expected >= 27`);
    process.exit(1);
  }
}

console.log('CI-JDK-OK');

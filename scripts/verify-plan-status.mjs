#!/usr/bin/env node
/**
 * Feature-plan status verification: asserts the roadmap marks the phases
 * named on the command line as Done. Usage:
 *
 *   node scripts/verify-plan-status.mjs 1 2
 *
 * Prints "plan status verification passed" only when every requested phase
 * shows a Done status in docs/feature-plan.md.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();
const phases = process.argv.slice(2);
if (phases.length === 0) {
  console.error('usage: node scripts/verify-plan-status.mjs <phase-number>...');
  process.exit(2);
}
// Interpolating argv into a RegExp: accept plain phase numbers only, so no
// pattern metacharacters can reach the RegExp constructor.
for (const phase of phases) {
  if (!/^\d+$/.test(phase)) {
    console.error(`FAIL: phase arguments must be plain numbers, got "${phase}"`);
    process.exit(2);
  }
}

const plan = readFileSync(join(root, 'docs/feature-plan.md'), 'utf8');

const assertions = phases.map((number) => {
  const pattern = new RegExp(
    `### Phase ${number} — [^\\n]*\\n\\n\\*\\*Effort:\\*\\* \\w+\\n(?:[^*]|\\*(?!\\*Status))*\\*\\*Status:\\*\\* Done`,
  );
  return [pattern.test(plan), `phase ${number} marked Done in the roadmap`];
});

let failures = 0;
for (const [condition, label] of assertions) {
  if (!condition) {
    console.error(`FAIL: ${label}`);
    failures += 1;
  }
}

if (failures > 0) {
  console.error(`plan status verification failed (${failures} assertion(s))`);
  process.exit(1);
}
console.log('plan status verification passed');

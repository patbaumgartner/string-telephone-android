#!/usr/bin/env node
/**
 * Commit hygiene for this session: asserts every commit since the repository
 * base has a Conventional Commit subject, and no two commits in the range
 * share a subject. Prints "commit hygiene verification passed" only after
 * every check passes.
 */
import { execFileSync } from 'node:child_process';

const BASE_COMMIT = 'b3b269d'; // repository initial commit

function git(args) {
  return execFileSync('git', args, { encoding: 'utf8' });
}

const subjects = git(['log', '--format=%s', `${BASE_COMMIT}..HEAD`])
  .trim()
  .split('\n')
  .filter(Boolean);

const conventional = /^(docs|feat|fix|test|chore|refactor|build|ci|style|perf|revert)(\([a-z0-9-]+\))?: \S/;

const assertions = [
  [subjects.length >= 2, `expected at least two commits on top of base, found ${subjects.length}`],
];

const seen = new Set();
for (const subject of subjects) {
  assertions.push([conventional.test(subject), `commit subject is conventional: ${subject}`]);
  assertions.push([!seen.has(subject), `no duplicated commit subject: ${subject}`]);
  seen.add(subject);
}

let failures = 0;
for (const [condition, label] of assertions) {
  if (!condition) {
    console.error(`FAIL: ${label}`);
    failures += 1;
  }
}

if (failures > 0) {
  console.error(`commit hygiene verification failed (${failures} assertion(s))`);
  process.exit(1);
}
console.log('commit hygiene verification passed');

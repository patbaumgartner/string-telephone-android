#!/usr/bin/env node
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';

const workflow = readFileSync('.github/workflows/dependabot-auto-merge.yml', 'utf8');

function step(name) {
  const marker = `      - name: ${name}\n`;
  const start = workflow.indexOf(marker);
  assert.notEqual(start, -1, `missing step ${name}`);
  const next = workflow.indexOf('\n      - name:', start + marker.length);
  return workflow.slice(start, next === -1 ? workflow.length : next);
}

const fetchStep = step('Fetch Dependabot metadata');
const preflightStep = step('Verify aggregate CI check is required on main');
const mergeStep = step('Enable auto-merge for patch and minor updates');
const mergeRun = mergeStep.split('        run: |\n')[1]
  ?.split('        env:')[0]
  .split('\n')
  .map((line) => line.slice(10))
  .join('\n')
  .trim();
assert.ok(mergeRun, 'merge step must have a block run script');

test('metadata and preflight use GITHUB_TOKEN; only merge uses AUTO_MERGE_TOKEN', () => {
  assert.ok(fetchStep.includes('github-token: ${{ secrets.GITHUB_TOKEN }}'));
  assert.ok(preflightStep.includes('GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}'));
  assert.ok(mergeStep.includes('GH_TOKEN: ${{ secrets.AUTO_MERGE_TOKEN }}'));
  assert.ok(!fetchStep.includes('secrets.AUTO_MERGE_TOKEN'));
  assert.ok(!preflightStep.includes('secrets.AUTO_MERGE_TOKEN'));
  assert.ok(!mergeStep.includes('secrets.GITHUB_TOKEN'));
  assert.ok(mergeStep.includes("== 'version-update:semver-patch'"));
  assert.ok(mergeStep.includes("== 'version-update:semver-minor'"));
  assert.ok(!mergeStep.includes("!= 'version-update:semver-major'"));
  assert.ok(!workflow.includes('actions/checkout@'));
});

test('merge guard fails closed without a token and invokes gh only with one', () => {
  mkdirSync('build', { recursive: true });
  const temporary = mkdtempSync(join('build', 'auto-merge-test-'));
  const bin = join(temporary, 'bin');
  const callLog = join(temporary, 'gh-calls.txt');
  mkdirSync(bin);
  const gh = join(bin, 'gh');
  writeFileSync(gh, '#!/bin/sh\nprintf \'%s\\n\' "$@" > "$GH_CALL_LOG"\n');
  chmodSync(gh, 0o755);

  function run(token) {
    return spawnSync('bash', ['-c', mergeRun], {
      env: {
        PATH: `${bin}:/usr/bin:/bin`,
        GH_TOKEN: token,
        GH_CALL_LOG: callLog,
        PR_URL: 'https://example.test/pull/1',
      },
      encoding: 'utf8',
    });
  }

  try {
    const missing = run('');
    assert.equal(missing.status, 1);
    assert.equal(existsSync(callLog), false, 'an empty token must not invoke gh');
    const message = missing.stdout + missing.stderr;
    assert.match(message, /AUTO_MERGE_TOKEN/);
    assert.match(message, /Contents/);
    assert.match(message, /Pull requests/);
    assert.match(message, /Workflows/);

    const configured = run('test-only-token');
    assert.equal(configured.status, 0, configured.stderr);
    assert.equal(
      readFileSync(callLog, 'utf8'),
      'pr\nmerge\n--auto\n--rebase\nhttps://example.test/pull/1\n',
    );
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
});

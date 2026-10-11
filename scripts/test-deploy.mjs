#!/usr/bin/env node
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const scripts = fileURLToPath(new URL('.', import.meta.url));
const packageName = 'com.patbaumgartner.android.stringtelephone';

function deploy(t, { target = 'device', devices = 'phone-1 device', env = {}, args = [] } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'string-telephone-deploy-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  cpSync(scripts, join(root, 'scripts'), {
    recursive: true,
    filter: (path) => !path.endsWith('test-deploy.mjs'),
  });
  const tools = join(root, 'sdk', 'platform-tools');
  mkdirSync(tools, { recursive: true });
  const log = join(root, 'calls.log');
  writeFileSync(log, '');
  const adb = join(tools, 'adb');
  writeFileSync(adb, `#!/usr/bin/env bash
set -euo pipefail
printf 'adb %s\\n' "$*" >> "$TEST_LOG"
if [[ "$1" == "devices" ]]; then
    if [[ "\${TEST_DEVICES_EXIT:-0}" != "0" ]]; then
        echo "adb device enumeration failed" >&2
        exit "$TEST_DEVICES_EXIT"
    fi
    printf 'List of devices attached\\n%s\\n' "$TEST_DEVICES"
elif [[ "$*" == *"getprop sys.boot_completed" ]]; then
    printf '%s\\r\\n' "\${TEST_BOOT:-1}"
elif [[ "$*" == *" install -r "* ]]; then
    exit "\${TEST_INSTALL_EXIT:-0}"
elif [[ "$*" == *"shell am start"* ]]; then
    printf '%s\\n' "\${TEST_LAUNCH_OUTPUT:-Status: ok}"
    exit "\${TEST_LAUNCH_EXIT:-0}"
fi
`);
  const gradle = join(root, 'gradlew');
  writeFileSync(gradle, `#!/usr/bin/env bash
printf 'gradle %s\\n' "$*" >> "$TEST_LOG"
exit "\${TEST_BUILD_EXIT:-0}"
`);
  chmodSync(adb, 0o755);
  chmodSync(gradle, 0o755);
  const result = spawnSync('bash', [join(root, 'scripts', `deploy-${target}.sh`), ...args], {
    encoding: 'utf8',
    env: {
      ...process.env,
      ANDROID_HOME: join(root, 'sdk'),
      ANDROID_SERIAL: '',
      DEVICE_SERIAL: '',
      EMULATOR_SERIAL: '',
      TEST_LOG: log,
      TEST_DEVICES: devices,
      ...env,
    },
  });
  assert.ifError(result.error);
  return { ...result, calls: readFileSync(log, 'utf8'), root };
}

test('device helper installs and launches only the physical target, even with an emulator attached', (t) => {
  const result = deploy(t, { devices: 'emulator-5554 device\nphone-1 device' });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.calls, /gradle --no-daemon :app:assembleDebug/);
  assert.ok(result.calls.includes(`adb -s phone-1 install -r ${result.root}/app/build/outputs/apk/debug/app-debug.apk`));
  assert.ok(result.calls.includes(`adb -s phone-1 shell am start -W -n ${packageName}/.ui.MainActivity`));
  assert.doesNotMatch(result.calls, /adb -s emulator-/);
});

test('emulator helper never installs on an attached physical phone', (t) => {
  const result = deploy(t, { target: 'emulator', devices: 'phone-1 device\nemulator-5554 device' });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.calls, /adb -s emulator-5554 install -r/);
  assert.doesNotMatch(result.calls, /adb -s phone-1/);
});

for (const [target, selector, serials] of [
  ['device', 'DEVICE_SERIAL', ['phone-1', 'phone-2']],
  ['emulator', 'EMULATOR_SERIAL', ['emulator-5554', 'emulator-5556']],
]) {
  test(`${target} helper rejects ambiguous targets before building`, (t) => {
    const result = deploy(t, { target, devices: serials.map((s) => `${s} device`).join('\n') });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Expected one ready/);
    assert.doesNotMatch(result.calls, /gradle| install -r|shell am start/);
  });

  test(`${selector} selects one of multiple targets`, (t) => {
    const result = deploy(t, {
      target,
      devices: serials.map((s) => `${s} device`).join('\n'),
      env: { [selector]: serials[1] },
    });
    assert.equal(result.status, 0, result.stderr);
    assert.ok(result.calls.includes(`adb -s ${serials[1]} install -r`));
    assert.ok(!result.calls.includes(`adb -s ${serials[0]} `));
  });
}

for (const devices of ['', 'phone-1 offline', 'phone-1 unauthorized', 'emulator-5554 device']) {
  test(`device helper rejects missing or unavailable physical targets: ${devices || 'none'}`, (t) => {
    const result = deploy(t, { devices });
    assert.equal(result.status, 1);
    assert.doesNotMatch(result.calls, /gradle| install -r|shell am start/);
  });
}

test('explicit serial cannot bypass the device-type check', (t) => {
  const result = deploy(t, {
    devices: 'phone-1 device\nemulator-5554 device',
    env: { DEVICE_SERIAL: 'emulator-5554' },
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /not a ready device/);
  assert.doesNotMatch(result.calls, /gradle| install -r/);
});

test('ANDROID_SERIAL is honored when no specific selector is supplied', (t) => {
  const result = deploy(t, {
    devices: 'phone-1 device\nphone-2 device',
    env: { ANDROID_SERIAL: 'phone-2' },
  });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.calls, /adb -s phone-2 install -r/);
});

test('an emulator must finish booting before building', (t) => {
  const result = deploy(t, {
    target: 'emulator',
    devices: 'emulator-5554 device',
    env: { TEST_BOOT: '0' },
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /not finished booting/);
  assert.doesNotMatch(result.calls, /gradle| install -r/);
});

for (const variable of ['TEST_BUILD_EXIT', 'TEST_INSTALL_EXIT', 'TEST_LAUNCH_EXIT']) {
  test(`failure propagates from ${variable} without reporting success`, (t) => {
    const result = deploy(t, { env: { [variable]: '7' } });
    assert.equal(result.status, 7);
    assert.doesNotMatch(result.stdout, /launched on/);
    if (variable === 'TEST_BUILD_EXIT') assert.doesNotMatch(result.calls, / install -r/);
    if (variable !== 'TEST_LAUNCH_EXIT') assert.doesNotMatch(result.calls, /shell am start/);
  });
}

test('unexpected arguments are rejected before adb or Gradle runs', (t) => {
  const result = deploy(t, { args: ['unexpected'] });
  assert.equal(result.status, 2);
  assert.match(result.stderr, /Usage:/);
  assert.equal(result.calls, '');
});

test('a zero-exit Android error is not reported as a successful launch', (t) => {
  const result = deploy(t, { env: { TEST_LAUNCH_OUTPUT: 'Error: Activity class does not exist.' } });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /did not confirm a successful activity launch/);
  assert.doesNotMatch(result.stdout, /launched on/);
});

test('adb enumeration failure is surfaced before building', (t) => {
  const result = deploy(t, { env: { TEST_DEVICES_EXIT: '9' } });
  assert.equal(result.status, 9);
  assert.match(result.stderr, /adb device enumeration failed/);
  assert.doesNotMatch(result.calls, /gradle| install -r/);
});

test('an explicitly selected offline device is rejected', (t) => {
  const result = deploy(t, {
    devices: 'phone-1 device\nphone-2 offline',
    env: { DEVICE_SERIAL: 'phone-2' },
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /not a ready device/);
  assert.doesNotMatch(result.calls, /gradle| install -r/);
});

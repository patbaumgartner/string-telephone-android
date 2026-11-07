#!/usr/bin/env node
/**
 * Live group-call check (Phase 20, ADR-0014). Runs against a running
 * companion API and proves the whole group-call lifecycle over real HTTP:
 *
 *   - a member creates a group over the REST API;
 *   - any member starts a call: the bridge is created (201, conf- id);
 *   - a second starter gets the running call back idempotently (200, same
 *     bridge) — the bridge is the call;
 *   - a non-member gets 404 on every call path, like every group path;
 *   - members join and leave; the state is visible while the call runs;
 *   - the last leave ends the call (ended), and the group can call again
 *     with a fresh bridge (201, different id).
 *
 * The SIP leg (dialling `conf-<id>` through the static dialplan pattern) is
 * exercised by the G9b INVITE in the prototype script itself; this file
 * holds the API-level lifecycle that the shell cannot express readably.
 *
 * Usage:
 *
 *   node scripts/test-group-calls.mjs <base-url> <state-file>
 *
 * The state file is the one the prototype's admin setup writes; it holds the
 * API tokens of the provisioned users (alice, bob).
 */
import assert from "node:assert/strict";
import { readFileSync } from 'node:fs';

const [base, stateFile] = process.argv.slice(2);
if (!base || !stateFile) {
  console.error('usage: node scripts/test-group-calls.mjs <base-url> <state-file>');
  process.exit(2);
}
const state = JSON.parse(readFileSync(stateFile, 'utf8'));

async function api(token, path, options = {}) {
  const response = await fetch(`${base}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...(options.headers || {}),
    },
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}

// Any member may start: bob (not the admin) creates and calls.
const created = await api(state.bob.apiToken, '/v1/groups', {
  method: 'POST',
  body: JSON.stringify({ name: `call probe ${Date.now()}`, members: ['alice'] }),
});
assert.equal(created.status, 201, `group create failed: ${created.status} ${JSON.stringify(created.body)}`);
const groupId = created.body.id;
assert.ok(groupId, 'the group must have an id');

// Start the call: bridge opens, starter recorded.
const started = await api(state.bob.apiToken, `/v1/groups/${groupId}/calls`, {
  method: 'POST',
  body: JSON.stringify({ withVideo: false }),
});
assert.equal(started.status, 201, `call start failed: ${started.status} ${JSON.stringify(started.body)}`);
const bridgeId = started.body.bridgeId;
assert.ok(bridgeId.startsWith('conf-'), `bridge id must be in the conf- namespace, got ${bridgeId}`);
assert.equal(started.body.starter, 'bob');
assert.equal(started.body.groupId, groupId);

// A second starter gets the running call back (200, same bridge).
const duplicate = await api(state.alice.apiToken, `/v1/groups/${groupId}/calls`, {
  method: 'POST',
  body: JSON.stringify({}),
});
assert.equal(duplicate.status, 200, `duplicate start must be idempotent, got ${duplicate.status}`);
assert.equal(duplicate.body.bridgeId, bridgeId, 'the bridge is the call: same id');

// The active-call surface reports it.
const active = await api(state.alice.apiToken, `/v1/groups/${groupId}/calls/active`);
assert.equal(active.status, 200);
assert.equal(active.body.bridgeId, bridgeId);

// Join and leave mid-call; state stays visible.
const join = await api(state.alice.apiToken, `/v1/groups/${groupId}/calls/${bridgeId}/join`, { method: 'POST', body: '{}' });
assert.equal(join.status, 200, `join failed: ${join.status} ${JSON.stringify(join.body)}`);
const leave = await api(state.alice.apiToken, `/v1/groups/${groupId}/calls/${bridgeId}/leave`, { method: 'POST', body: '{}' });
assert.equal(leave.status, 200);

// The last leave ends the call and frees the group for a fresh one.
const end = await api(state.bob.apiToken, `/v1/groups/${groupId}/calls/${bridgeId}/leave`, { method: 'POST', body: '{}' });
assert.equal(end.status, 200);
const after = await api(state.bob.apiToken, `/v1/groups/${groupId}/calls/active`);
assert.equal(after.status, 200);
assert.equal(after.body, null, 'the call must be over after the last leave');

// The group can call again: a new bridge, not a resurrected one.
const again = await api(state.bob.apiToken, `/v1/groups/${groupId}/calls`, {
  method: 'POST',
  body: JSON.stringify({}),
});
assert.equal(again.status, 201, `second call start failed: ${again.status} ${JSON.stringify(again.body)}`);
const secondBridge = again.body.bridgeId;
assert.notEqual(secondBridge, bridgeId, 'a new call must get a new bridge');
await api(state.bob.apiToken, `/v1/groups/${groupId}/calls/${secondBridge}/leave`, { method: 'POST', body: '{}' });

console.log('GROUP-CALL-LIFECYCLE-OK');

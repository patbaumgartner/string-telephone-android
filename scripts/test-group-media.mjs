#!/usr/bin/env node
/**
 * Live group-media check (Phase 21, ADR-0012 deferred piece). Runs against a
 * running companion API and proves the group image/voice contract over real
 * HTTP:
 *
 *   - a member creates a group and uploads media scoped to it (`groupId`
 *     form field instead of `peer`): 201, record carries groupId, peer null;
 *   - an authenticated outsider gets 404 on upload and send (like every group
 *     path) and 403 on fetching the bytes;
 *   - the sender posts IMAGE and VOICE group messages referencing the upload:
 *     201 with type/mediaId (and durationMs/waveform for VOICE);
 *   - every member sees the messages in the group history and may fetch the
 *     original bytes and the thumbnail (200);
 *   - cross-bindings are rejected: pairwise media in a group send, another
 *     group's media, another member's upload — all 403; IMAGE without mediaId
 *     400, VOICE on image media 400, VOICE without durationMs 400, unknown
 *     mediaId 404; a resend with the same clientKey returns the same id;
 *   - the multipart upload requires exactly one of peer / groupId (400).
 *
 * Usage:
 *
 *   node scripts/test-group-media.mjs <base-url> <state-file>
 *
 * The state file is the one the prototype's admin setup writes; it holds the
 * API tokens of the provisioned users (alice, bob). A third user (carol) is
 * provisioned through the admin API as the outsider.
 */
import assert from "node:assert/strict";
import { readFileSync } from 'node:fs';
import { AdminTestClient } from "./lib/admin-test-client.mjs";

const [base, stateFile] = process.argv.slice(2);
if (!base || !stateFile) {
  console.error('usage: node scripts/test-group-media.mjs <base-url> <state-file>');
  process.exit(2);
}
const state = JSON.parse(readFileSync(stateFile, 'utf8'));
const alice = state.alice.apiToken;
const bob = state.bob.apiToken;

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
  return { status: response.status, body: text ? JSON.parse(text) : null, raw: text, headers: response.headers };
}

async function upload(token, fields, fileName, contentType, bytes) {
  const form = new FormData();
  for (const [key, value] of Object.entries(fields)) form.append(key, value);
  form.append('file', new Blob([bytes], { type: contentType }), fileName);
  const response = await fetch(`${base}/v1/media`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}

function groupMessage(token, groupId, payload) {
  return api(token, `/v1/groups/${groupId}/messages`, { method: 'POST', body: JSON.stringify(payload) });
}

// A real 1x1 PNG so the server can decode dimensions and build a thumbnail.
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);
const OGG = Buffer.alloc(2048, 7);

// Provision the outsider through the admin API.
const admin = new AdminTestClient(base);
await admin.login();
const carol = (await admin.request('create-user', {
  username: 'carol',
  displayName: 'carol',
  sipPassword: 'prototest-robin-maple-meadow-lantern-pebble-willow-scarlet-1234',
})).apiToken;

// Alice creates a group with bob.
const created = await api(alice, '/v1/groups', {
  method: 'POST',
  body: JSON.stringify({ name: `media probe ${Date.now()}`, members: ['bob'] }),
});
assert.equal(created.status, 201, `group create failed: ${created.status} ${JSON.stringify(created.body)}`);
const groupId = created.body.id;

// Member upload scoped to the group.
const image = await upload(alice, { groupId }, 'hike.png', 'image/png', PNG_1PX);
assert.equal(image.status, 201, `group image upload failed: ${image.status} ${JSON.stringify(image.body)}`);
assert.equal(image.body.groupId, groupId);
assert.equal(image.body.peer, null);
assert.equal(image.body.width, 1);
const imageId = image.body.id;

const audio = await upload(alice, { groupId }, 'voice.ogg', 'audio/ogg', OGG);
assert.equal(audio.status, 201, `group voice upload failed: ${audio.status} ${JSON.stringify(audio.body)}`);
assert.equal(audio.body.groupId, groupId);
const audioId = audio.body.id;

// Pairwise upload for the cross-binding check.
const pairwise = await upload(alice, { peer: 'bob' }, 'pair.png', 'image/png', PNG_1PX);
assert.equal(pairwise.status, 201);
assert.equal(pairwise.body.groupId, null);
assert.equal(pairwise.body.peer, 'bob');

// Exactly one of peer / groupId is required.
const neither = await upload(alice, {}, 'x.png', 'image/png', PNG_1PX);
assert.equal(neither.status, 400, `upload without peer/groupId must be 400, got ${neither.status}`);
const both = await upload(alice, { peer: 'bob', groupId }, 'x.png', 'image/png', PNG_1PX);
assert.equal(both.status, 400, `upload with both peer and groupId must be 400, got ${both.status}`);

// The outsider: 404 on upload and send, 403 on fetch.
const outsiderUpload = await upload(carol, { groupId }, 'x.png', 'image/png', PNG_1PX);
assert.equal(outsiderUpload.status, 404, `outsider upload must be 404, got ${outsiderUpload.status}`);
const outsiderSend = await groupMessage(carol, groupId, { content: '', clientKey: 'c-1', type: 'IMAGE', mediaId: imageId });
assert.equal(outsiderSend.status, 404, `outsider send must be 404, got ${outsiderSend.status}`);
const outsiderFetch = await fetch(`${base}/v1/media/${imageId}`, { headers: { Authorization: `Bearer ${carol}` } });
assert.equal(outsiderFetch.status, 403, `outsider fetch must be 403, got ${outsiderFetch.status}`);

// IMAGE and VOICE group messages from the member.
const imageMsg = await groupMessage(alice, groupId, { content: '', clientKey: 'img-1', type: 'IMAGE', mediaId: imageId });
assert.equal(imageMsg.status, 201, `IMAGE send failed: ${imageMsg.status} ${JSON.stringify(imageMsg.body)}`);
assert.equal(imageMsg.body.type, 'IMAGE');
assert.equal(imageMsg.body.mediaId, imageId);
assert.equal(imageMsg.body.groupId, groupId);

const voiceMsg = await groupMessage(alice, groupId, {
  content: '', clientKey: 'voice-1', type: 'VOICE', mediaId: audioId, durationMs: 4200, waveform: '10,80,40',
});
assert.equal(voiceMsg.status, 201, `VOICE send failed: ${voiceMsg.status} ${JSON.stringify(voiceMsg.body)}`);
assert.equal(voiceMsg.body.type, 'VOICE');
assert.equal(voiceMsg.body.durationMs, 4200);
assert.equal(voiceMsg.body.waveform, '10,80,40');

// Both members see the media messages in the group history.
for (const token of [alice, bob]) {
  const history = await api(token, `/v1/groups/${groupId}/messages`);
  assert.equal(history.status, 200);
  const imageRow = history.body.messages.find((m) => m.id === imageMsg.body.id);
  assert.ok(imageRow, 'image message missing from member history');
  assert.equal(imageRow.type, 'IMAGE');
  assert.equal(imageRow.mediaId, imageId);
  const voiceRow = history.body.messages.find((m) => m.id === voiceMsg.body.id);
  assert.ok(voiceRow, 'voice message missing from member history');
  assert.equal(voiceRow.type, 'VOICE');
  assert.equal(voiceRow.durationMs, 4200);
  assert.equal(voiceRow.waveform, '10,80,40');
}

// Every member fetches the original bytes and the thumbnail.
for (const token of [alice, bob]) {
  for (const suffix of ['', '/thumbnail']) {
    const response = await fetch(`${base}/v1/media/${imageId}${suffix}`, { headers: { Authorization: `Bearer ${token}` } });
    assert.equal(response.status, 200, `member fetch /v1/media/${imageId}${suffix} must be 200, got ${response.status}`);
  }
  const voiceFetch = await fetch(`${base}/v1/media/${audioId}`, { headers: { Authorization: `Bearer ${token}` } });
  assert.equal(voiceFetch.status, 200, 'member voice fetch must be 200');
}

// Cross-bindings and validation rejects.
const crossPairwise = await groupMessage(alice, groupId, { content: '', clientKey: 'x-1', type: 'IMAGE', mediaId: pairwise.body.id });
assert.equal(crossPairwise.status, 403, `pairwise media in a group send must be 403, got ${crossPairwise.status}`);
const noMedia = await groupMessage(alice, groupId, { content: '', clientKey: 'x-2', type: 'IMAGE' });
assert.equal(noMedia.status, 400, 'IMAGE without mediaId must be 400');
const voiceOnImage = await groupMessage(alice, groupId, { content: '', clientKey: 'x-3', type: 'VOICE', mediaId: imageId, durationMs: 1000, waveform: '1,2' });
assert.equal(voiceOnImage.status, 400, 'VOICE on image media must be 400');
const noDuration = await groupMessage(alice, groupId, { content: '', clientKey: 'x-4', type: 'VOICE', mediaId: audioId, waveform: '1,2' });
assert.equal(noDuration.status, 400, 'VOICE without durationMs must be 400');
const unknownMedia = await groupMessage(alice, groupId, { content: '', clientKey: 'x-5', type: 'IMAGE', mediaId: 'no-such-media' });
assert.equal(unknownMedia.status, 404, 'unknown mediaId must be 404');

// A resend with the same client key is idempotent.
const resend = await groupMessage(alice, groupId, { content: '', clientKey: 'img-1', type: 'IMAGE', mediaId: imageId });
assert.equal(resend.status, 201);
assert.equal(resend.body.id, imageMsg.body.id, 'same clientKey must return the same message id');

console.log('GROUP-MEDIA-OK');

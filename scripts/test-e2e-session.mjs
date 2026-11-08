#!/usr/bin/env node
/**
 * Full app-protocol end-to-end session (E2E verification, 2026-11-08). Runs
 * against a LIVE companion API stack (Docker: Asterisk + PostgreSQL + API)
 * and drives the exact HTTP + WebSocket contract the Android app uses, as
 * two concurrent clients (alice and bob):
 *
 *   auth (/v1/me, wrong-token 401) -> contacts -> websocket connect +
 *   presence frames -> text send (WS new-message fan-out, idempotent resend)
 *   -> typing frames -> read receipts (message-read frame + readAt) ->
 *   reaction (+ reaction frame) -> reply -> edit (+ message-edited) ->
 *   delete (+ message-deleted tombstone) -> server-side search ->
 *   conversation state (pin) -> image upload + IMAGE message + media fetch
 *   + thumbnail -> voice upload + VOICE message -> group create/members ->
 *   group text/image/voice + group-message frames + group read + group
 *   reaction -> outsider 404s -> group disband -> group call lifecycle
 *   (start/invite/join/state/leave/ended) -> offline presence on disconnect
 *   -> negative paths (413 oversize, 404 unknown peer).
 *
 * Usage: node scripts/test-e2e-session.mjs <base-url> <state-file>
 * The state file comes from `scripts/test-managed-admin.mjs setup` and holds
 * the provisioned users' API tokens (alice, bob).
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const [base, stateFile] = process.argv.slice(2);
if (!base || !stateFile) {
  console.error('usage: node scripts/test-e2e-session.mjs <base-url> <state-file>');
  process.exit(2);
}
const state = JSON.parse(readFileSync(stateFile, 'utf8'));
const alice = state.alice.apiToken;
const bob = state.bob.apiToken;
const wsBase = base.replace(/^http/, 'ws');

let checks = 0;
function ok(label) { checks += 1; console.log(`  ok ${checks}: ${label}`); }

async function api(token, path, options = {}) {
  const response = await fetch(`${base}${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...(options.headers || {}) },
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null, raw: text };
}

/** Minimal WS client with a per-type frame queue. */
class WsClient {
  constructor(token, name) {
    this.name = name;
    this.frames = [];
    this.waiters = [];
    this.ws = new WebSocket(`${wsBase}/v1/ws`, { headers: { Authorization: `Bearer ${token}` } });
    this.ws.onmessage = (event) => {
      const envelope = JSON.parse(event.data);
      const frame = { type: envelope.type, payload: JSON.parse(envelope.payload) };
      this.frames.push(frame);
      this.waiters = this.waiters.filter((w) => !w(frame));
    };
    this.opened = new Promise((resolve, reject) => {
      this.ws.onopen = resolve;
      this.ws.onerror = reject;
    });
  }
  send(obj) { this.ws.send(JSON.stringify(obj)); }
  /** Waits for a frame of `type` (optionally matching pred) within timeout ms. */
  next(type, pred = () => true, timeoutMs = 10000) {
    const found = this.frames.findIndex((f) => f.type === type && pred(f));
    if (found >= 0) return Promise.resolve(this.frames.splice(found, 1)[0]);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${this.name}: timeout waiting for ${type}`)), timeoutMs);
      this.waiters.push((frame) => {
        if (frame.type === type && pred(frame)) {
          clearTimeout(timer);
          resolve(frame);
          return true;
        }
        return false;
      });
    });
  }
  close() { this.ws.close(); }
}

async function upload(token, fields, fileName, contentType, bytes) {
  const form = new FormData();
  for (const [k, v] of Object.entries(fields)) form.append(k, v);
  form.append('file', new Blob([bytes], { type: contentType }), fileName);
  const response = await fetch(`${base}/v1/media`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}

const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);
const OGG = Buffer.alloc(2048, 7);
const key = (p) => `${p}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

/** Builds a syntactically-valid wire envelope matching the server's
 *  `^[23]:[A-Za-z0-9+/]+={0,2}$` / 16384-char cap (ADR-0015). The live stack
 *  relays opaque envelopes; the client can't decrypt without libsignal, so the
 *  test asserts structure (content blanked, envelopes present per recipient)
 *  rather than plaintext round-trip. Mirrors the server test helper
 *  `envelope(user, seed="ct")` = `"3:" + base64("$seed:$user")`. */
const envelope = (user) => `3:${Buffer.from(`ct:${user}`).toString('base64')}`;
const ENVELOPE_RE = /^[23]:[A-Za-z0-9+/]+={0,2}$/;

// ── 1. auth + contacts ────────────────────────────────────────────────────
{
  const me = await api(alice, '/v1/me');
  assert.equal(me.status, 200);
  assert.equal(me.body.username, 'alice');
  ok('GET /v1/me authenticates the API token');
  const bad = await api('wrong-token', '/v1/me');
  assert.equal(bad.status, 401);
  ok('wrong bearer token is rejected with 401');
  const contacts = await api(alice, '/v1/contacts');
  assert.equal(contacts.status, 200);
  assert.ok(Array.isArray(contacts.body.contacts));
  ok('GET /v1/contacts lists contacts');
}

// ── 2. websockets + presence ──────────────────────────────────────────────
const aliceWs = new WsClient(alice, 'alice');
const bobWs = new WsClient(bob, 'bob');
await aliceWs.opened;
await bobWs.opened;
{
  const presence = await aliceWs.next('presence', (f) => f.payload.username === 'bob' && f.payload.presence === 'online');
  assert.ok(presence.payload.updatedAt || presence.payload.lastSeenAt !== undefined);
  ok('bob connecting fans out an online presence frame to alice');
}

// ── 3. text message + fan-out + idempotency ───────────────────────────────
let textId;
{
  const sent = await api(alice, '/v1/conversations/bob/messages', { method: 'POST', body: JSON.stringify({ recipient: 'bob', content: '', clientKey: key('t1'), envelopes: { bob: envelope('bob'), alice: envelope('alice') } }) });
  assert.equal(sent.status, 201, sent.raw);
  textId = sent.body.id;
  assert.equal(sent.body.sender, 'alice');
  assert.equal(sent.body.content, '', 'content is blanked when envelopes are present');
  ok('POST pairwise E2EE text returns 201 with blanked content and per-recipient envelopes');
  const frame = await bobWs.next('new-message', (f) => f.payload.id === textId);
  assert.equal(frame.payload.content, '', 'framed content is blanked');
  assert.equal(frame.payload.sender, 'alice');
  assert.equal(frame.payload.encrypted, true, 'frame is marked encrypted');
  assert.ok(ENVELOPE_RE.test(frame.payload.envelope || ''), 'frame carries a well-formed envelope');
  ok('bob receives the E2EE new-message WS frame (blanked content + envelope)');
  const again = await api(alice, '/v1/conversations/bob/messages', { method: 'POST', body: JSON.stringify({ recipient: 'bob', content: '', clientKey: sent.body.clientKey, envelopes: { bob: envelope('bob'), alice: envelope('alice') } }) });
  assert.equal(again.status, 201);
  assert.equal(again.body.id, textId);
  ok('resend with the same clientKey and envelopes is idempotent (same id)');
  const unknown = await api(alice, '/v1/conversations/nobody/messages', { method: 'POST', body: JSON.stringify({ recipient: 'nobody', content: 'x', clientKey: key('t2') }) });
  assert.equal(unknown.status, 404);
  ok('unknown peer is 404 (peer check precedes envelope validation)');
  const big = await api(alice, '/v1/conversations/bob/messages', { method: 'POST', body: JSON.stringify({ recipient: 'bob', content: 'x'.repeat(9000), clientKey: key('t3') }) });
  assert.equal(big.status, 400, big.raw);
  ok('plaintext TEXT without envelopes is rejected (400 envelopes required) — E2EE mandatory');
  const malformed = await api(alice, '/v1/conversations/bob/messages', { method: 'POST', body: JSON.stringify({ recipient: 'bob', content: '', clientKey: key('t4'), envelopes: { bob: 'not-an-envelope', alice: envelope('alice') } }) });
  assert.equal(malformed.status, 400, malformed.raw);
  ok('malformed envelope is rejected (400)');
}

// ── 4. typing ─────────────────────────────────────────────────────────────
{
  aliceWs.send({ type: 'typing', payload: JSON.stringify({ typing: true }) });
  const frame = await bobWs.next('typing', (f) => f.payload.peer === 'alice' && f.payload.typing === true);
  assert.ok(frame);
  ok('typing frame fans out over WS to the peer');
}

// ── 5. read receipts ──────────────────────────────────────────────────────
{
  const mark = await api(bob, '/v1/conversations/alice/read', { method: 'POST', body: JSON.stringify({ upToMessageId: textId }) });
  assert.equal(mark.status, 200, mark.raw);
  assert.ok(mark.body.messageIds.includes(textId));
  const frame = await aliceWs.next('message-read', (f) => f.payload.messageIds.includes(textId));
  assert.ok(frame);
  const history = await api(alice, '/v1/conversations/bob/messages');
  const row = history.body.messages.find((m) => m.id === textId);
  assert.ok(row.readAt > 0);
  ok('mark-read returns ids, fans out message-read, and stamps readAt in history');
}

// ── 6. reaction + reply + edit + delete ────────────────────────────────────
let replyId;
{
  const react = await api(bob, `/v1/conversations/alice/messages/${textId}/reactions`, { method: 'PUT', body: JSON.stringify({ emoji: '👍' }) });
  assert.equal(react.status, 200, react.raw);
  const frame = await aliceWs.next('message-reaction', (f) => f.payload.messageId === textId && f.payload.emoji === '👍');
  assert.equal(frame.payload.authorUsername, 'bob');
  ok('reaction is stored and fanned out with the author');

  const reply = await api(bob, '/v1/conversations/alice/messages', { method: 'POST', body: JSON.stringify({ recipient: 'alice', content: '', clientKey: key('r1'), replyToId: textId, envelopes: { alice: envelope('alice'), bob: envelope('bob') } }) });
  assert.equal(reply.status, 201, reply.raw);
  replyId = reply.body.id;
  assert.equal(reply.body.replyToId, textId);
  const replyFrame = await aliceWs.next('new-message', (f) => f.payload.id === replyId);
  assert.equal(replyFrame.payload.replyToId, textId);
  assert.equal(replyFrame.payload.content, '', 'reply content is blanked');
  assert.equal(replyFrame.payload.encrypted, true, 'reply frame is marked encrypted');
  ok('E2EE reply carries replyToId end to end (send + WS frame)');

  const edit = await api(alice, `/v1/conversations/bob/messages/${textId}`, { method: 'PUT', body: JSON.stringify({ content: '', envelopes: { bob: envelope('bob'), alice: envelope('alice') } }) });
  assert.equal(edit.status, 200, edit.raw);
  assert.ok(edit.body.editedAt > 0);
  const edited = await bobWs.next('message-edited', (f) => f.payload.messageId === textId);
  assert.equal(edited.payload.content, '', 'edited content is blanked');
  assert.ok(ENVELOPE_RE.test(edited.payload.envelope || ''), 'edited frame carries a well-formed envelope');
  ok('E2EE edit returns editedAt and fans out message-edited with envelopes');

  const del = await api(alice, `/v1/conversations/bob/messages/${textId}`, { method: 'DELETE' });
  assert.equal(del.status, 200, del.raw);
  const deleted = await bobWs.next('message-deleted', (f) => f.payload.messageId === textId);
  assert.ok(deleted.payload.deletedAt > 0);
  const history = await api(bob, '/v1/conversations/alice/messages');
  const row = history.body.messages.find((m) => m.id === textId);
  assert.ok(row.deletedAt > 0 && row.content === '');
  ok('delete tombstones the row (empty content) and fans out message-deleted');
}

// ── 7. search retired (server no longer indexes E2EE text) + conversation state ──
{
  const retired = await api(alice, `/v1/conversations/bob/search?q=${encodeURIComponent('replying')}`);
  assert.equal(retired.status, 404, retired.raw);
  ok('server-side search endpoint is retired (404); search is client-side over decrypted cache');
  const pin = await api(bob, '/v1/conversations/alice/state', { method: 'PUT', body: JSON.stringify({ pinned: true }) });
  assert.equal(pin.status, 200, pin.raw);
  const st = await api(bob, '/v1/conversations/alice/state');
  assert.equal(st.body.pinned, true);
  ok('conversation state PUT/GET round-trips (pin)');
}

// ── 8. image + voice media ────────────────────────────────────────────────
{
  const img = await upload(alice, { peer: 'bob' }, 'pic.png', 'image/png', PNG);
  assert.equal(img.status, 201, JSON.stringify(img.body));
  assert.equal(img.body.peer, 'bob');
  assert.equal(img.body.width, 1);
  const imgMsg = await api(alice, '/v1/conversations/bob/messages', { method: 'POST', body: JSON.stringify({ recipient: 'bob', content: '', clientKey: key('i1'), type: 'IMAGE', mediaId: img.body.id }) });
  assert.equal(imgMsg.status, 201, imgMsg.raw);
  assert.equal(imgMsg.body.type, 'IMAGE');
  const imgFrame = await bobWs.next('new-message', (f) => f.payload.id === imgMsg.body.id);
  assert.equal(imgFrame.payload.mediaId, img.body.id);
  for (const suffix of ['', '/thumbnail']) {
    const fetchRes = await fetch(`${base}/v1/media/${img.body.id}${suffix}`, { headers: { Authorization: `Bearer ${bob}` } });
    assert.equal(fetchRes.status, 200);
  }
  ok('image upload -> IMAGE message -> WS frame -> peer fetches original + thumbnail (200)');

  const voice = await upload(alice, { peer: 'bob' }, 'v.ogg', 'audio/ogg', OGG);
  assert.equal(voice.status, 201);
  const voiceMsg = await api(alice, '/v1/conversations/bob/messages', { method: 'POST', body: JSON.stringify({ recipient: 'bob', content: '', clientKey: key('v1'), type: 'VOICE', mediaId: voice.body.id, durationMs: 4200, waveform: '10,80,40' }) });
  assert.equal(voiceMsg.status, 201, voiceMsg.raw);
  assert.equal(voiceMsg.body.durationMs, 4200);
  assert.equal(voiceMsg.body.waveform, '10,80,40');
  const vframe = await bobWs.next('new-message', (f) => f.payload.id === voiceMsg.body.id);
  assert.equal(vframe.payload.durationMs, 4200);
  ok('voice upload -> VOICE message with durationMs/waveform -> WS frame');
}

// ── 9. group lifecycle: text, media, reads, reactions, outsider, disband ───
{
  const created = await api(alice, '/v1/groups', { method: 'POST', body: JSON.stringify({ name: `e2e group ${Date.now()}`, members: ['bob'] }) });
  assert.equal(created.status, 201, created.raw);
  const groupId = created.body.id;
  ok('group created with bob as member');

  const gText = await api(alice, `/v1/groups/${groupId}/messages`, { method: 'POST', body: JSON.stringify({ content: '', clientKey: key('g1'), envelopes: { alice: envelope('alice'), bob: envelope('bob') } }) });
  assert.equal(gText.status, 201, gText.raw);
  assert.equal(gText.body.groupId, groupId);
  const gFrame = await bobWs.next('group-message', (f) => f.payload.id === gText.body.id);
  assert.equal(gFrame.payload.content, '', 'group text content is blanked');
  assert.equal(gFrame.payload.encrypted, true, 'group text frame is marked encrypted');
  ok('E2EE group text fans out as group-message to members');

  const gImg = await upload(alice, { groupId }, 'g.png', 'image/png', PNG);
  assert.equal(gImg.status, 201);
  const gImgMsg = await api(alice, `/v1/groups/${groupId}/messages`, { method: 'POST', body: JSON.stringify({ content: '', clientKey: key('g2'), type: 'IMAGE', mediaId: gImg.body.id }) });
  assert.equal(gImgMsg.status, 201, gImgMsg.raw);
  const gImgFrame = await bobWs.next('group-message', (f) => f.payload.id === gImgMsg.body.id && f.payload.type === 'IMAGE');
  assert.equal(gImgFrame.payload.mediaId, gImg.body.id);
  const gVoice = await upload(alice, { groupId }, 'g.ogg', 'audio/ogg', OGG);
  const gVoiceMsg = await api(alice, `/v1/groups/${groupId}/messages`, { method: 'POST', body: JSON.stringify({ content: '', clientKey: key('g3'), type: 'VOICE', mediaId: gVoice.body.id, durationMs: 1500, waveform: '5,50,5' }) });
  assert.equal(gVoiceMsg.status, 201, gVoiceMsg.raw);
  await bobWs.next('group-message', (f) => f.payload.id === gVoiceMsg.body.id && f.payload.type === 'VOICE');
  const gFetch = await fetch(`${base}/v1/media/${gImg.body.id}`, { headers: { Authorization: `Bearer ${bob}` } });
  assert.equal(gFetch.status, 200);
  ok('group IMAGE + VOICE messages fan out; members fetch group media');

  const gMark = await api(bob, `/v1/groups/${groupId}/read`, { method: 'POST', body: JSON.stringify({ upToMessageId: gText.body.id }) });
  assert.equal(gMark.status, 200, gMark.raw);
  const gRead = await aliceWs.next('group-message-read', (f) => f.payload.groupId === groupId && f.payload.reader === 'bob');
  assert.ok(gRead);
  const gHistory = await api(bob, `/v1/groups/${groupId}/messages`);
  const gRow = gHistory.body.messages.find((m) => m.id === gText.body.id);
  assert.ok(gRow.readCount >= 1);
  ok('group read mark fans out group-message-read and bumps readCount');

  const gReact = await api(bob, `/v1/groups/${groupId}/messages/${gText.body.id}/reactions`, { method: 'PUT', body: JSON.stringify({ emoji: '🎉' }) });
  assert.equal(gReact.status, 200, gReact.raw);
  await aliceWs.next('group-reaction', (f) => f.payload.messageId === gText.body.id && f.payload.emoji === '🎉');
  ok('group reaction fans out group-reaction');

  // Group typing: client frame with embedded object payload carrying groupId.
  aliceWs.send({ type: 'typing', payload: { groupId, typing: true } });
  const gTyping = await bobWs.next('typing', (f) => f.payload.groupId === groupId && f.payload.typing === true);
  assert.equal(gTyping.payload.peer, 'alice');
  ok('group typing frame (object payload) fans out to members except the composer');

  // Outsider checks: provision carol through the admin API.
  const { AdminTestClient } = await import('./lib/admin-test-client.mjs');
  const admin = new AdminTestClient(base);
  await admin.login();
  const carol = (await admin.request('create-user', { username: `carol-${Date.now().toString(36)}`, displayName: 'carol', sipPassword: 'prototest-robin-maple-meadow-lantern-pebble-willow-scarlet-1234' })).apiToken;
  assert.equal((await api(carol, `/v1/groups/${groupId}`)).status, 404);
  assert.equal((await api(carol, `/v1/groups/${groupId}/messages`, { method: 'POST', body: JSON.stringify({ content: 'intruder', clientKey: key('c1') }) })).status, 404);
  assert.equal((await fetch(`${base}/v1/media/${gImg.body.id}`, { headers: { Authorization: `Bearer ${carol}` } })).status, 403);
  ok('non-member gets 404 on group paths and 403 on group media fetch');

  // Group call lifecycle over the API.
  const call = await api(alice, `/v1/groups/${groupId}/calls`, { method: 'POST', body: JSON.stringify({ withVideo: false }) });
  assert.equal(call.status, 201, call.raw);
  const invite = await bobWs.next('group-call-invite', (f) => f.payload.groupId === groupId);
  assert.equal(invite.payload.bridgeId, call.body.bridgeId);
  const join = await api(bob, `/v1/groups/${groupId}/calls/${call.body.bridgeId}/join`, { method: 'POST' });
  assert.equal(join.status, 200, join.raw);
  await aliceWs.next('group-call-state', (f) => f.payload.state === 'joined' && f.payload.bridgeId === call.body.bridgeId);
  const leave = await api(bob, `/v1/groups/${groupId}/calls/${call.body.bridgeId}/leave`, { method: 'POST' });
  assert.equal(leave.status, 200, leave.raw);
  ok('group call start -> invite frame -> join -> state -> leave all work');

  const disband = await api(alice, `/v1/groups/${groupId}`, { method: 'DELETE' });
  assert.equal(disband.status, 200, disband.raw);
  assert.equal((await api(bob, `/v1/groups/${groupId}`)).status, 404);
  ok('group disband removes it for members (404 afterwards)');
}

// ── 10. offline presence on disconnect ────────────────────────────────────
{
  bobWs.close();
  const offline = await aliceWs.next('presence', (f) => f.payload.username === 'bob' && f.payload.presence === 'offline', 15000);
  assert.ok(offline);
  ok('closing bob\'s socket fans out an offline presence frame');
  aliceWs.close();
}

console.log(`E2E-SESSION-OK (${checks} checks)`);
process.exit(0);

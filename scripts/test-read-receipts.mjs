#!/usr/bin/env node
/**
 * Live read-receipt check (ADR-0010). Runs against a running companion API
 * and proves the whole path against real HTTP:
 *
 *   - the author sends a message and the server stores it;
 *   - the author may not mark it read (404 — the reader is the peer);
 *   - the recipient marks it read and the server names the message;
 *   - the author sees the receipt in the conversation history;
 *   - marking again changes nothing and never moves the receipt.
 *
 * Usage:
 *
 *   node scripts/test-read-receipts.mjs <base-url> <state-file>
 *
 * The state file is the one the prototype's admin setup writes; it holds the
 * API tokens of the provisioned users.
 */
import assert from "node:assert/strict";
import { readFileSync } from 'node:fs';

const [base, stateFile] = process.argv.slice(2);
if (!base || !stateFile) {
  console.error('usage: node scripts/test-read-receipts.mjs <base-url> <state-file>');
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

const content = `read receipt probe ${Date.now()}`;

// The author sends the message.
const sent = await api(state.alice.apiToken, '/v1/conversations/bob/messages', {
  method: 'POST',
  body: JSON.stringify({ recipient: 'bob', content, clientKey: `probe-${Date.now()}` }),
});
assert.equal(sent.status, 201, `send failed with ${sent.status}: ${JSON.stringify(sent.body)}`);
const id = sent.body.id;
assert.ok(id, 'the send must return a message id');
assert.equal(sent.body.readAt, undefined, 'a fresh message is unread');

// The author may not mark her own message read.
const author = await api(state.alice.apiToken, '/v1/conversations/bob/read', {
  method: 'POST',
  body: JSON.stringify({ upToMessageId: id }),
});
assert.equal(author.status, 404, `the author must not mark a message read, got ${author.status}`);

// The recipient marks it read.
const mark = await api(state.bob.apiToken, '/v1/conversations/alice/read', {
  method: 'POST',
  body: JSON.stringify({ upToMessageId: id }),
});
assert.equal(mark.status, 200, `the read mark failed with ${mark.status}: ${JSON.stringify(mark.body)}`);
assert.deepEqual(mark.body.messageIds, [id], 'the mark must name the message it changed');
assert.ok(mark.body.readAt > 0, 'the mark must carry a timestamp');

// The author learns about the receipt from the history.
const history = await api(state.alice.apiToken, '/v1/conversations/bob/messages');
assert.equal(history.status, 200);
const message = history.body.messages.find((candidate) => candidate.id === id);
assert.ok(message, 'the message must be in the conversation history');
assert.equal(message.readAt, mark.body.readAt, 'the history must carry the read timestamp');

// A second mark changes nothing and never moves the receipt.
const again = await api(state.bob.apiToken, '/v1/conversations/alice/read', {
  method: 'POST',
  body: JSON.stringify({ upToMessageId: id }),
});
assert.equal(again.status, 200);
assert.deepEqual(again.body.messageIds, [], 'an already read message must not change again');
const historyAgain = await api(state.alice.apiToken, '/v1/conversations/bob/messages');
const messageAgain = historyAgain.body.messages.find((candidate) => candidate.id === id);
assert.equal(messageAgain.readAt, mark.body.readAt, 'the receipt must not move');

console.log('READ-RECEIPT-OK');

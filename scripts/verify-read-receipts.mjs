#!/usr/bin/env node
/**
 * Read-receipt verification (ADR-0010): asserts that the server persists a
 * per-message read state, exposes it in the message history, announces it on
 * the realtime stream, and that the app only marks a conversation read while
 * it is on screen. Exits nonzero with a reason per failure; prints
 * "read receipt verification passed" only after every assertion passes.
 *
 * The point of the receipts is that they mean "a human looked at this", so the
 * oracle checks the negative cases too: no sender identity in the request, no
 * read mark from a background history load, no read mark from a notification.
 */
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { decodePng, diffBounds, countColor } from './lib/png.mjs';

const root = process.cwd();

function read(relative) {
  try {
    return readFileSync(join(root, relative), 'utf8');
  } catch {
    return '';
  }
}

/** The body of a Kotlin member, up to the next member declaration. */
function memberBody(source, signature) {
  const start = source.indexOf(signature);
  if (start < 0) return '';
  const end = source.indexOf('\n    fun ', start + signature.length);
  return source.slice(start, end < 0 ? source.length : end);
}

const server = 'server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/';
const app = 'app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/';
const core = 'core/src/main/kotlin/com/patbaumgartner/android/stringtelephone/core/';

const api = read(server + 'Api.kt');
const dto = read(server + 'Dto.kt');
const messages = read(server + 'Messages.kt');
const ws = read(server + 'Ws.kt');
const wsFrames = read(server + 'WsFrames.kt');
const wsSessions = read(server + 'WsSessions.kt');
const migrations = read('server/api/src/main/resources/db/migration/V4__message_read_state.sql');
const apiTest = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/ApiIntegrationTest.kt');

const coreDtos = read(core + 'ApiDtos.kt');
const coreModels = read(core + 'Models.kt');
const chatCache = read(app + 'chat/ChatCache.kt');
const apiClient = read(app + 'network/ApiClient.kt');
const repository = read(app + 'data/StringTelephoneRepository.kt');
const chatScreen = read(app + 'ui/ChatScreen.kt');
const icons = read(app + 'ui/StringTelephoneIcons.kt');

const protocol = read('docs/protocol.md');
const plan = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');
const adr = read('docs/adr/0010-message-delivery-states.md');

const loadHistory = memberBody(repository, 'fun loadHistory(');
const markConversationRead = memberBody(repository, 'fun markConversationRead(');

// The tick colours of the theme: the ticks wear the brand accent, a failed
// message wears the error colour.
const BRAND = '#0079A3';
const ERROR = '#AC4B2F';

function screenshot(relative) {
  const file = join(root, relative);
  if (!existsSync(file)) return null;
  return decodePng(readFileSync(file), relative);
}

function ticksRendered(relative) {
  const image = screenshot(relative);
  if (!image) return false;
  return countColor(image, BRAND) > 0 && countColor(image, ERROR) > 0;
}

function differsFromPlainChat(relative) {
  const delivery = screenshot(relative);
  const plain = screenshot('docs/screenshots/03-chat.png');
  if (!delivery || !plain) return false;
  try {
    return diffBounds(delivery, plain).count > 0;
  } catch {
    return false;
  }
}

const assertions = [
  // Server: the read state is persisted, not derived.
  [
    /ALTER TABLE messages\s+ADD COLUMN read_at BIGINT/i.test(migrations),
    'a migration adds the read_at column to messages',
  ],
  [
    /CREATE INDEX .*read/i.test(migrations),
    'the read state is indexed for the receipt queries',
  ],
  [
    /read_at/.test(messages) && /fun markRead\(/.test(messages),
    'the message store reads and writes the read state',
  ],
  [
    /UPDATE messages SET read_at/.test(messages),
    'the read mark is a single atomic update',
  ],

  // Server: the endpoint and its contract.
  [
    /@PostMapping\("\/v1\/conversations\/\{peer\}\/read"\)/.test(api),
    'the API exposes POST /v1/conversations/{peer}/read',
  ],
  [
    /class MarkReadRequest\(\s*val upToMessageId: String\? = null\s*\)/.test(dto),
    'the read request carries only the message id',
  ],
  [
    /class MarkReadResponse\(/.test(dto),
    'the read response reports the marked ids and the timestamp',
  ],
  [
    /readAt/.test(dto),
    'the message DTO carries the read timestamp',
  ],
  [
    /read_at/.test(messages) && /readAt/.test(dto),
    'the history response includes the read state',
  ],

  // Server: the identity comes from the principal, never from the body.
  [
    /principal\(request\)/.test(api),
    'the read mark derives both sides from the authenticated principal',
  ],
  [
    /read_at = \?/.test(messages) && /sender = \? AND recipient = \?/.test(messages),
    'the update is scoped to the conversation of the caller',
  ],

  // Server: the realtime announcement.
  [
    /"message-read"/.test(wsFrames),
    'the realtime stream announces a message-read frame',
  ],
  [
    /fun broadcastMessageRead/.test(ws),
    'the hub broadcasts the read receipt to the author',
  ],

  // App: the tick states.
  [
    /enum class SendState/.test(chatCache),
    'the app models a delivery state per message',
  ],
  ...['PENDING', 'SENT', 'READ', 'FAILED'].map((state) => [
    new RegExp(`\\b${state}\\b`).test(chatCache),
    `the app knows the ${state} delivery state`,
  ]),
  [
    /READ/.test(coreModels),
    'the shared model knows the read state',
  ],

  // App: the receipt arrives and turns the tick.
  [
    /fun applyReadReceipt/.test(chatCache),
    'the chat cache applies a read receipt',
  ],
  [
    /MessageRead/.test(coreDtos) && /message-read/.test(coreDtos),
    'the app parses the message-read event',
  ],

  // App: only the foreground chat marks a conversation read.
  [
    /markConversationRead/.test(chatScreen),
    'the chat screen marks the rendered messages read',
  ],
  [
    /fun markConversationRead/.test(repository) && /client\.markRead\(peer/.test(markConversationRead),
    'the repository posts the read mark to the server',
  ],
  [
    loadHistory.length > 0 && !/markRead|markConversationRead/.test(loadHistory),
    'a background history load never marks the conversation read',
  ],
  [
    /fun clearUnreadBadge/.test(repository) &&
      !/client\.markRead/.test(memberBody(repository, 'fun clearUnreadBadge(')),
    'clearing the local unread badge never talks to the server',
  ],
  [
    !/markRead\(|markConversationRead\(/.test(read(app + 'notify/AppNotifications.kt')),
    'a notification never marks the conversation read',
  ],
  [
    !/markRead\(|markConversationRead\(/.test(read(app + 'notify/CallActionReceiver.kt')),
    'the notification action receiver never marks the conversation read',
  ],
  [
    !/markRead\(|markConversationRead\(/.test(read(app + 'session/SessionController.kt')),
    'the background session controller never marks the conversation read',
  ],

  // App: the double tick is drawn in the brand colour.
  [
    /DoneAll/.test(icons) && existsSync(join(root, 'app/src/main/res/drawable/ic_done_all.xml')),
    'the app ships a double tick icon',
  ],
  [
    /DoneAll/.test(chatScreen),
    'the chat draws the double tick for a read message',
  ],

  // Documentation.
  [
    /\/v1\/conversations\/\{peer\}\/read/.test(protocol),
    'protocol.md documents the read endpoint',
  ],
  [
    /readAt/.test(protocol),
    'protocol.md documents the read timestamp',
  ],
  [
    /message-read/.test(protocol),
    'protocol.md documents the message-read event',
  ],
  [
    /### Phase 5 — [^\n]*\n\n\*\*Effort:\*\* \w+\n(?:[^*]|\*(?!\*Status))*\*\*Status:\*\* Done/.test(plan),
    'the roadmap marks phase 5 done',
  ],
  [
    /read receipt/i.test(changelog),
    'the changelog announces the read receipts',
  ],
  [
    adr.length > 0 && /read/i.test(adr),
    'ADR-0010 documents the read receipt',
  ],

  // Screenshots: every tick state is reviewable as an image.
  [
    existsSync(join(root, 'docs/screenshots/10-chat-delivery-states.png')),
    'the delivery states are documented as a screenshot',
  ],
  [
    existsSync(join(root, 'docs/screenshots/dark-chat-delivery-states.png')),
    'the delivery states are documented as a dark screenshot',
  ],
  [
    ticksRendered('docs/screenshots/10-chat-delivery-states.png'),
    'the delivery-state screenshot draws the brand tick and the error tick',
  ],
  [
    differsFromPlainChat('docs/screenshots/10-chat-delivery-states.png'),
    'the delivery-state screenshot differs from the plain chat screenshot',
  ],
];

let failed = 0;
for (const [ok, reason] of assertions) {
  if (!ok) {
    failed += 1;
    console.error(`FAIL: ${reason}`);
  }
}
if (failed > 0) {
  console.error(`read receipt verification failed: ${failed} assertion(s)`);
  process.exit(1);
}
console.log('read receipt verification passed');

#!/usr/bin/env node
/**
 * Reply/quote verification (Phase 9, ADR-0011): a message may reference one
 * other message of the same conversation as its quoted target; REST history
 * and the realtime stream carry `replyToId` so both devices render the
 * snapshot. Exits nonzero with a reason per failure; prints "reply
 * verification passed" only after every assertion passes.
 *
 * Checks:
 *  - Server: reply_to column, insert validation, send endpoint wiring,
 *    history embedding, new-message frame carrying replyToId.
 *  - Core: MessageDto / WsNewMessageDto / RealtimeEvent.NewMessage carry it.
 *  - App: ChatMessage reply field, quoted snapshot resolution, repository
 *    send path, ViewModel reply target.
 *  - UI: long-press reply option, quoted banner above the composer,
 *    quoted bubble inside the message column.
 *  - Tests: cache resolution, realtime parsing.
 *  - Docs: protocol.md contract, feature-plan status, changelog entry.
 *
 * The live REST cycle is proved by the prototype script; this script
 * verifies the contract is wired correctly in the source.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

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
    const endAlt = source.indexOf('\n    private fun ', start + signature.length);
    const endClass = source.indexOf('\nclass ', start + signature.length);
    const endIface = source.indexOf('\ninterface ', start + signature.length);
    return source.slice(start, Math.min(
        end < 0 ? source.length : end,
        endAlt < 0 ? source.length : endAlt,
        endClass < 0 ? source.length : endClass,
        endIface < 0 ? source.length : endIface,
    ));
}

const server = 'server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/';
const app    = 'app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/';
const core   = 'core/src/main/kotlin/com/patbaumgartner/android/stringtelephone/core/';

const messages  = read(server + 'Messages.kt');
const api       = read(server + 'Api.kt');
const wsFrames  = read(server + 'WsFrames.kt');
const dto       = read(server + 'Dto.kt');
const migration = read('server/api/src/main/resources/db/migration/V7__replies.sql');
const protocol  = read('docs/protocol.md');

const coreDtos  = read(core + 'ApiDtos.kt');

const apiClient  = read(app + 'network/ApiClient.kt');
const repo       = read(app + 'data/StringTelephoneRepository.kt');
const chatCache  = read(app + 'chat/ChatCache.kt');
const vm         = read(app + 'ui/AppViewModel.kt');
const chatScreen = read(app + 'ui/ChatScreen.kt');
const strings    = read('app/src/main/res/values/strings.xml');

const coreTest  = read('core/src/test/kotlin/com/patbaumgartner/android/stringtelephone/core/RealtimeEventTest.kt');
const cacheTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/chat/ChatCacheTest.kt');

const plan      = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');

let fails = [];

function check(name, condition, hint) {
    if (!condition) {
        fails.push(`${name}${hint ? ' — ' + hint : ''}`);
    }
}

const insertFn = memberBody(messages, 'fun insert(');
const sendFn   = memberBody(api, 'fun send(');
const vmSend   = memberBody(apiClient, 'fun sendText(');
const parseFn  = memberBody(coreDtos, 'fun parseRealtimeEvent(');

// ── Server contract ──────────────────────────────────────────────────────────

check('Migration V7 — reply_to column',            migration.includes('ADD COLUMN reply_to'));
check('Migration V7 — references messages',        migration.includes('REFERENCES messages(id)'));
check('Messages — record carries replyTo',         messages.includes('val replyTo: String?'));
check('Messages — insert persists reply_to',       insertFn.includes('reply_to'));
check('Messages — page selects reply_to',          memberBody(messages, 'fun page(').includes('SELECT_COLUMNS') && messages.includes('reply_to, deleted_at'));
check('Messages — findById persists reply_to',     memberBody(messages, 'fun findById(').includes('SELECT_COLUMNS') && messages.includes('reply_to, deleted_at'));
check('WsFrames — new-message frame carries replyTo', wsFrames.includes('message.replyTo'));
check('Dto — MessageDto carries replyToId',        dto.includes('val replyToId: String? = null'));
check('Dto — SendMessageRequest carries replyToId', dto.includes('val replyToId: String? = null'));

// ── REST contract ────────────────────────────────────────────────────────────

check('Api — send validates reply target exists',  sendFn.includes('reply target not found'));
check('Api — reply must be in the same conversation', sendFn.includes('reply target not in this conversation'));
check('Api — insert passes validated replyTo',     sendFn.includes('replyToId'));
check('Api — history messageDto carries replyTo',  api.includes('record.replyTo'));

// ── Core parsing ─────────────────────────────────────────────────────────────

check('Core ApiDtos — MessageDto carries replyToId', coreDtos.includes('val replyToId: String? = null'));
check('Core ApiDtos — WsNewMessageDto carries replyToId', coreDtos.includes('data class WsNewMessageDto') && coreDtos.includes('val replyToId: String? = null'));
check('Core ApiDtos — RealtimeEvent.NewMessage carries replyToId', coreDtos.includes('data class NewMessage(') && parseFn.includes('replyToId'));

// ── App network seam ─────────────────────────────────────────────────────────

check('ApiClient — sendText accepts replyToId',    vmSend.includes('replyToId'));
check('ApiClient — MessageResult carries replyToId', apiClient.includes('val replyToId: String? = null'));
// Reply mapping lives in the shared toMessageResult() seam (see verify-voice.mjs).
const messageResultMapper = (() => {
    const start = apiClient.indexOf('private fun toMessageResult(');
    return start < 0 ? '' : apiClient.slice(start, apiClient.indexOf('\n)', start) + 2);
})();
check('ApiClient — toMessageResult maps replyToId', messageResultMapper.includes('m.replyToId'));
check('ApiClient — history maps through toMessageResult',
    apiClient.includes('messages = page.messages.map { toMessageResult(it) }'));

// ── App repository / cache ───────────────────────────────────────────────────

check('Repository — send takes replyToId',         repo.includes('fun send(peer: String, content: String, replyToId: String?'));
check('Repository — new-message frame maps replyToId', repo.includes('replyToId = event.replyToId'));
check('ChatCache — replyToId field',               chatCache.includes('val replyToId: String? = null'));
check('ChatMessage — fromServer maps replyToId',   chatCache.includes('replyToId = m.replyToId'));
check('ChatCache — findQuotedMessage resolver',    chatCache.includes('fun findQuotedMessage(peer: String, replyToId: String): ChatMessage?'));

// ── App ViewModel / UI ───────────────────────────────────────────────────────

check('AppViewModel — replyingTo state',           vm.includes('val replyingTo: StateFlow<ChatMessage?>'));
check('AppViewModel — setReplyingTo / clearReplyTo', vm.includes('fun setReplyingTo(message: ChatMessage?)') && vm.includes('fun clearReplyTo()'));
check('AppViewModel — sendCurrent passes reply target', memberBody(vm, 'fun sendCurrent(').includes('_replyingTo.value?.serverId'));
check('ChatScreen — ReplyOptionsSheet',            chatScreen.includes('ReplyOptionsSheet'));
check('ChatScreen — QuotedMessageBanner above composer', chatScreen.includes('QuotedMessageBanner('));
check('ChatScreen — QuotedBubble in message',      memberBody(chatScreen, 'internal fun ChatBubble(').includes('QuotedBubble('));
check('ChatScreen — long-press offers reply',      chatScreen.includes('ReplyOptionsSheet('));
check('strings.xml — chat_reply resource',         strings.includes('chat_reply'));
check('strings.xml — reply_to resource',           strings.includes('"reply_to"'));

// ── Tests ────────────────────────────────────────────────────────────────────

check('RealtimeEventTest — reply target parsing',  coreTest.includes('replyToId'));
check('ChatCacheTest — reply carries target id',   cacheTest.includes('reply carries its target id'));
check('ChatCacheTest — findQuotedMessage',         cacheTest.includes('findQuotedMessage resolves the referenced message'));

// ── Protocol doc ─────────────────────────────────────────────────────────────

check('docs/protocol.md — replyToId documented',   protocol.includes('replyToId'));
check('docs/protocol.md — same-conversation validation documented', protocol.includes("caller's conversation with `{peer}`"));

// ── Feature plan ────────────────────────────────────────────────────────────

check('docs/feature-plan.md — Phase 9 present',    plan.includes('### Phase 9'));
check('docs/feature-plan.md — Phase 9 done',       plan.includes('Phase 9') && plan.includes('**Status:** Done'));

// ── Changelog ─────────────────────────────────────────────────────────────────

check('CHANGELOG.md — Phase 9 entry',              changelog.includes('Phase 9'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const f of fails) console.error('FAIL:', f);
    process.exit(1);
}
console.log('reply verification passed');

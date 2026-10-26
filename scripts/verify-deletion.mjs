#!/usr/bin/env node
/**
 * Message-deletion verification (Phase 10, ADR-0011): "delete for both"
 * tombstones the server row (content cleared, row retained) and fans a
 * message-deleted frame; "delete for me" is a client-local hide. Exits
 * nonzero with a reason per failure; prints "deletion verification passed"
 * only after every assertion passes.
 *
 * Checks:
 *  - Server: V8 columns, tombstone update in Messages.delete, DELETE
 *    endpoint with participant check and principal-derived deleter,
 *    message-deleted fan-out frame.
 *  - Core: MessageDto / WsMessageDeletedDto / RealtimeEvent.MessageDeleted
 *    and the parse branch.
 *  - App: ChatMessage tombstone fields, applyDeleted/hideLocally cache
 *    methods, ApiClient.deleteMessage, repository delete paths.
 *  - UI: long-press delete options, tombstone placeholder, "for both" only
 *    with a server id.
 *  - Tests: cache tombstone/idempotence/hide, realtime deletion frame.
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
    return source.slice(start, Math.min(
        end < 0 ? source.length : end,
        endAlt < 0 ? source.length : endAlt,
        endClass < 0 ? source.length : endClass,
    ));
}

const server = 'server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/';
const app    = 'app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/';
const core   = 'core/src/main/kotlin/com/patbaumgartner/android/stringtelephone/core/';

const messages  = read(server + 'Messages.kt');
const api       = read(server + 'Api.kt');
const wsFrames  = read(server + 'WsFrames.kt');
const wsHub     = read(server + 'Ws.kt');
const dto       = read(server + 'Dto.kt');
const migration = read('server/api/src/main/resources/db/migration/V8__message_deletion.sql');
const protocol  = read('docs/protocol.md');

const coreDtos = read(core + 'ApiDtos.kt');

const apiClient  = read(app + 'network/ApiClient.kt');
const repo       = read(app + 'data/StringTelephoneRepository.kt');
const chatCache  = read(app + 'chat/ChatCache.kt');
const vm         = read(app + 'ui/AppViewModel.kt');
const chatScreen = read(app + 'ui/ChatScreen.kt');
const strings    = read('app/src/main/res/values/strings.xml');

const coreTest  = read('core/src/test/kotlin/com/patbaumgartner/android/stringtelephone/core/RealtimeEventTest.kt');
const cacheTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/chat/ChatCacheTest.kt');
const repoTest  = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/data/StringTelephoneRepositoryRealtimeTest.kt');

const plan      = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');

const fails = [];

function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

const deleteFn   = memberBody(messages, 'fun delete(messageId');
const endpoint   = memberBody(api, 'fun deleteMessage(');
const parseFn    = memberBody(coreDtos, 'fun parseRealtimeEvent(');
const cacheKt    = memberBody(chatCache, 'fun applyDeleted(');
const repoDelete = memberBody(repo, 'fun deleteMessage(');

// ── Server contract ──────────────────────────────────────────────────────────

check('Migration V8 — deleted_at column',      migration.includes('ADD COLUMN deleted_at BIGINT'));
check('Migration V8 — deleted_by column',      migration.includes('ADD COLUMN deleted_by TEXT'));
check('Messages — record carries deletedAt',   messages.includes('val deletedAt: Long? = null'));
check('Messages — record carries deletedBy',   messages.includes('val deletedBy: String? = null'));
check('Messages — page selects deleted columns', memberBody(messages, 'fun page(').includes('SELECT_COLUMNS') && messages.includes('deleted_at, deleted_by'));
check('Messages — delete tombstones content',  memberBody(messages, 'fun delete(messageId').includes("content = ''"));
check('Messages — delete stamps deleted_at',   memberBody(messages, 'fun delete(messageId').includes('deleted_at = ?'));
check('Messages — delete is idempotent',       memberBody(messages, 'fun delete(messageId').includes('deleted_at IS NULL'));
check('WsFrames — message-deleted frame',      wsFrames.includes('fun messageDeleted('));
check('Dto — MessageDto carries deletedAt',    read(server + 'Dto.kt').includes('val deletedAt: Long? = null'));
check('Dto — WsMessageDeletedEvent defined',   read(server + 'Dto.kt').includes('data class WsMessageDeletedEvent'));

// ── REST contract ────────────────────────────────────────────────────────────

check('Api — DELETE messages endpoint',        api.includes('@DeleteMapping("/v1/conversations/{peer}/messages/{messageId}")'));
check('Api — 404 on unknown message',          endpoint.includes('no such message'));
check('Api — only participants may delete',    endpoint.includes('not a participant'));
check('Api — deleter from principal',          endpoint.includes('messages.delete(messageId, me.username)'));
check('Api — deletion fans out to the hub',    endpoint.includes('hub.broadcastMessageDeleted(tombstone)'));
check('Api — messageDto carries tombstone',    api.includes('record.deletedAt, record.deletedBy'));

// ── Core parsing ─────────────────────────────────────────────────────────────

check('Core ApiDtos — MessageDto carries deletedAt', coreDtos.includes('val deletedAt: Long? = null'));
check('Core ApiDtos — WsMessageDeletedDto payload',  coreDtos.includes('data class WsMessageDeletedDto'));
check('Core ApiDtos — RealtimeEvent.MessageDeleted', coreDtos.includes('data class MessageDeleted('));
check('Core ApiDtos — message-deleted parse branch', read(core + 'ApiDtos.kt').includes('"message-deleted" -> runCatching'));

// ── App network seam ─────────────────────────────────────────────────────────

check('ApiClient — deleteMessage method',      read(app + 'network/ApiClient.kt').includes('fun deleteMessage(peer: String, messageId: String): MessageResult'));
check('ApiClient — MessageResult carries tombstone', read(app + 'network/ApiClient.kt').includes('val deletedAt: Long? = null'));
// Tombstone mapping lives in the shared toMessageResult() seam (see verify-voice.mjs).
const mapperStart = apiClient.indexOf('private fun toMessageResult(');
const messageResultMapper = mapperStart < 0
    ? ''
    : apiClient.slice(mapperStart, apiClient.indexOf('\n)', mapperStart) + 2);
check('ApiClient — toMessageResult maps tombstone',
    messageResultMapper.includes('m.deletedAt') && messageResultMapper.includes('m.deletedBy'));

// ── App repository / cache ───────────────────────────────────────────────────

check('Repository — deleteMessage endpoint call', memberBody(repo, 'fun deleteMessage(').includes('client.deleteMessage(peer, serverId)'));
check('Repository — optimistic tombstone',     memberBody(repo, 'fun deleteMessage(').includes('chatCache.applyDeleted'));
check('Repository — delete for me is local',   memberBody(repo, 'fun deleteForMe(').includes('hideLocally'));
check('Repository — deletion frame handler',   repo.includes('is RealtimeEvent.MessageDeleted -> onMessageDeleted(event)'));
check('ChatCache — deletedAtEpochMs field',    chatCache.includes('val deletedAtEpochMs: Long? = null'));
check('ChatMessage — deleted getter',          chatCache.includes('val deleted: Boolean'));
check('ChatMessage — fromServer maps tombstone', chatCache.includes('deletedAtEpochMs = m.deletedAt'));
check('ChatCache — applyDeleted tombstones',   chatCache.includes('fun applyDeleted(serverId: String, deletedAtEpochMs: Long, deletedBy: String?): String?'));
check('ChatCache — hideLocally for delete-for-me', chatCache.includes('fun hideLocally(peer: String, clientKey: String): Boolean'));
check('ChatCache — tombstone keeps content',   cacheIncludesQuoteComment());

function cacheIncludesQuoteComment() {
    return chatCache.includes('Content stays in the local record so the reply');
}

// ── App ViewModel / UI ───────────────────────────────────────────────────────

check('AppViewModel — deleteMessage pass-through',   vm.includes('fun deleteMessage(peer: String, message: ChatMessage)'));
check('AppViewModel — deleteForMe pass-through',     vm.includes('fun deleteForMe(peer: String, message: ChatMessage)'));
check('ChatScreen — delete options in sheet',        chatScreen.includes('onDeleteForMe') && chatScreen.includes('onDeleteForBoth'));
check('ChatScreen — tombstone placeholder',          memberBody(chatScreen, 'private fun ChatBubble(').includes('message.deleted'));
check('ChatScreen — placeholder string',             chatScreen.includes('R.string.chat_deleted'));
check('ChatScreen — delete for both needs server id', chatScreen.includes('if (message.serverId != null)'));
check('strings.xml — chat_delete resources',         strings.includes('chat_delete_for_everyone') && strings.includes('chat_delete_for_me'));
check('strings.xml — chat_deleted placeholder',      strings.includes('Message deleted'));

// ── Tests ────────────────────────────────────────────────────────────────────

check('RealtimeEventTest — message-deleted parsing', coreTest.includes('parses a message-deleted envelope'));
check('ChatCacheTest — applyDeleted tombstone',      cacheTest.includes('applyDeleted tombstones exactly the named message'));
check('ChatCacheTest — hideLocally',                 cacheTest.includes('hideLocally removes only the named message'));
check('RepositoryRealtimeTest — deletion frame',     repoTest.includes('a deletion frame tombstones the cached message'));

// ── Protocol doc ─────────────────────────────────────────────────────────────

check('docs/protocol.md — DELETE endpoint documented',  protocol.includes('DELETE /v1/conversations/{peer}/messages/{messageId}'));
check('docs/protocol.md — tombstone shape documented',  protocol.includes('deletedBy'));
check('docs/protocol.md — delete-for-me has no endpoint', protocol.includes('client-local hide'));
check('docs/protocol.md — message-deleted frame documented', protocol.includes('"type":"message-deleted"'));

// ── Feature plan ─────────────────────────────────────────────────────────────

check('docs/feature-plan.md — Phase 10 done', plan.includes('### Phase 10 — Message deletion') && plan.includes('**Done 2026-10-25.** V8 migration'));

// ── Changelog ────────────────────────────────────────────────────────────────

check('CHANGELOG.md — Phase 10 entry', changelog.includes('message deletion (Phase 10'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const f of fails) console.error('FAIL:', f);
    process.exit(1);
}
console.log('deletion verification passed');

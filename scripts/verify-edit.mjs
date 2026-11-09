#!/usr/bin/env node
/**
 * Message-editing verification (Phase 17): the author re-stores the content
 * of one message, both sides learn it via a message-edited frame, and the
 * edited bubble shows an "edited" hint. Exits nonzero with a reason per
 * failure; prints "edit verification passed" only after every assertion
 * passes.
 *
 * Checks (Phase 17, updated for Phase 22 / ADR-0015):
 *  - Server: V12 column, Messages.edit (author-only, receipt-preserving,
 *    tombstone-refusing; encrypted rows blank the content and upsert the new
 *    envelopes in one transaction), PUT endpoint (403/404/409/400 plus a
 *    400 when an encrypted row lacks envelopes), personalized
 *    message-edited fan-out frame.
 *  - Core: MessageDto/WsMessageEditedDto/RealtimeEvent.MessageEdited carry an
 *    optional envelope, EditMessageRequest carries content + envelopes, the
 *    parse branch.
 *  - App: ChatMessage editedAtEpochMs/canEdit, ChatCache.applyEdited,
 *    ApiClient.editMessage (blanks content when envelopes are given),
 *    repository edit (encrypts fresh envelopes, rolls back on failure) +
 *    realtime handler (decrypts this device's envelope first), ViewModel
 *    edit mode, EditMessageBanner, Edit sheet action, "edited" hint.
 *  - Tests: integration (author-only/sync, tombstone 409, read-preserved),
 *    cache applyEdited, realtime edit frame, parser branch.
 *  - Docs: protocol.md contract, feature-plan status, changelog entry,
 *    run-session-verify hook.
 *
 * The live REST cycle is proved by :server:api:test (Testcontainers); this
 * script verifies the contract is wired correctly in the source.
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

const serverDir = 'server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/';
const appDir = 'app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/';
const coreDir = 'core/src/main/kotlin/com/patbaumgartner/android/stringtelephone/core/';

const migration = read('server/api/src/main/resources/db/migration/V12__message_editing.sql');
const messages = read(serverDir + 'Messages.kt');
const api = read(serverDir + 'Api.kt');
const wsFrames = read(serverDir + 'WsFrames.kt');
const wsHub = read(serverDir + 'Ws.kt');
const dto = read(serverDir + 'Dto.kt');

const coreDtos = read(coreDir + 'ApiDtos.kt');

const apiClient = read(appDir + 'network/ApiClient.kt');
const repository = read(appDir + 'data/StringTelephoneRepository.kt');
const chatCache = read(appDir + 'chat/ChatCache.kt');
const viewModel = read(appDir + 'ui/AppViewModel.kt');
const chatScreen = read(appDir + 'ui/ChatScreen.kt');
const icons = read(appDir + 'ui/StringTelephoneIcons.kt');
const strings = read('app/src/main/res/values/strings.xml');

const integration = read('server/api/test/kotlin/com/patbaumgartner/stringtelephone/ApiIntegrationTest.kt') ||
    read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/ApiIntegrationTest.kt');
const runningServer = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/RunningServer.kt');
const cacheTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/chat/ChatCacheTest.kt');
const realtimeTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/data/StringTelephoneRepositoryRealtimeTest.kt');
const parserTest = read('core/src/test/kotlin/com/patbaumgartner/android/stringtelephone/core/RealtimeEventTest.kt');
const screenshotTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotTest.kt');
const screenshotActivity = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotActivity.kt');
const previewFactory = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/FakeCallEngine.kt');

const protocol = read('docs/protocol.md');
const plan = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');
const sessionVerify = read('scripts/run-session-verify.sh');

const fails = [];

function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

// ── Server ───────────────────────────────────────────────────────────────────

check('V12 — edited_at BIGINT column', migration.includes('ALTER TABLE messages ADD COLUMN edited_at BIGINT'));
check('V12 — documents the tombstone precedence', migration.includes('deletes take precedence'));
check('MessageRecord — editedAt field and SELECT column', messages.includes('val editedAt: Long? = null') && messages.includes('waveform, edited_at'));
check('Messages.edit — signature takes encrypted flag + envelopes (Phase 22)', messages.includes('fun edit(messageId: String, author: String, content: String, encrypted: Boolean, envelopes: Map<String, String>? = null)'));
check('Messages.edit — author-guarded, tombstone-refusing in both branches', (messages.match(/WHERE id = \? AND sender = \? AND deleted_at IS NULL/g) ?? []).length === 2);
check('Messages.edit — encrypted branch blanks content and upserts envelopes in one tx', messages.includes("UPDATE messages SET content = '', edited_at = ? ") && messages.includes('INSERT INTO message_envelopes(message_id, recipient, envelope) VALUES(?, ?, ?)') && messages.includes('ON CONFLICT (message_id, recipient) DO UPDATE SET envelope = EXCLUDED.envelope'));
check('Messages.edit — stamps edited_at', messages.includes('SET content = ?, edited_at = ?'));
check('Messages.edit — preserves the read receipt', !messages.includes('read_at = NULL'));
check('Messages.edit — same content caps as sends', /require\(bytes\.size <= MAX_CONTENT_BYTES\)/.test(messages.split('fun edit')[1] ?? ''));
check('Api — PUT endpoint with author-only 403', api.includes('@PutMapping("/v1/conversations/{peer}/messages/{messageId}")') && api.includes('"only the author may edit"'));
check('Api — tombstone and non-text edits are 409', api.includes('"message is deleted"') && api.includes('"only text messages can be edited"'));
check('Api — unknown message 404, wrong peer 400', api.includes('"no such message"') && api.includes('"message not in this conversation"'));
check('Api — encrypted edit without envelopes for peer+author is 400', api.includes('"envelopes required to edit an encrypted message"') && api.includes('body.envelopes[peer].isNullOrBlank()') && api.includes('body.envelopes[me.username].isNullOrBlank()'));
check('Api — edit fans out and returns the record with the caller envelope', api.includes('hub.broadcastMessageEdited(edited)') && api.includes('return messageDto(edited, envelope = if (edited.encrypted) messages.envelopeFor(edited.id, me.username) else null)'));
check('WsFrames — message-edited frame with optional own envelope', wsFrames.includes('WsMessageDto("message-edited"') && wsFrames.includes('fun messageEdited(message: MessageRecord, envelope: String? = null)') && wsFrames.includes('WsMessageEditedEvent') && wsFrames.includes('envelope = envelope'));
check('WsHub — broadcastMessageEdited reaches both participants with personalized envelopes', wsHub.includes('fun broadcastMessageEdited(message: MessageRecord)') && wsHub.includes('for (user in setOf(message.sender, message.recipient))') && wsHub.includes('WsFrames.messageEdited(message, envelopeFor(message.id, user))'));
check('Dto — MessageDto and WsNewMessageEvent carry editedAt', dto.includes('val editedAt: Long? = null,'));
check('Dto — WsMessageEditedEvent shape with optional envelope', dto.includes('data class WsMessageEditedEvent(') && dto.includes('val editedAt: Long,') && dto.includes('val envelope: String? = null,'));
check('Dto — EditMessageRequest carries blankable content + envelopes (Phase 22)', dto.includes('data class EditMessageRequest(') && dto.includes('val content: String = "",') && dto.includes('val envelopes: Map<String, String>? = null,'));

// ── Core ─────────────────────────────────────────────────────────────────────

check('core MessageDto — editedAt', coreDtos.includes('val editedAt: Long? = null'));
check('core WsMessageEditedDto — payload shape with optional envelope', coreDtos.includes('data class WsMessageEditedDto(') && coreDtos.includes('val editedAt: Long,') && coreDtos.includes('val envelope: String? = null,'));
check('core EditMessageRequest — PUT body with envelopes (Phase 22)', coreDtos.includes('data class EditMessageRequest(') && coreDtos.includes('val content: String = "",') && coreDtos.includes('val envelopes: Map<String, String>? = null,'));
check('core RealtimeEvent.MessageEdited — event type', coreDtos.includes('data class MessageEdited(') && coreDtos.includes('val editedAtEpochMs: Long,'));
check('core parser — message-edited branch', coreDtos.includes('"message-edited" -> runCatching'));

// ── App wire + state ─────────────────────────────────────────────────────────

check('ApiClient — editMessage PUT with 409 mapping, blanks content when envelopes given', apiClient.includes('fun editMessage(peer: String, messageId: String, content: String, envelopes: Map<String, String>? = null): MessageResult') && apiClient.includes('.url("$baseUrl/v1/conversations/$peer/messages/$messageId")') && apiClient.includes('r.code == 409 -> throw ApiException.Protocol("message cannot be edited")') && apiClient.includes('EditMessageRequest(if (envelopes != null) "" else content, envelopes)') && /EditMessageRequest\.serializer\(\)/.test(apiClient));
check('ApiClient — toMessageResult forwards editedAt', apiClient.includes('m.waveform, m.editedAt,'));
check('ChatMessage — editedAtEpochMs and canEdit gate', chatCache.includes('val editedAtEpochMs: Long? = null,') && chatCache.includes('val canEdit: Boolean') && chatCache.includes('fromMe && type == "TEXT" && !deleted'));
check('ChatMessage.fromServer — maps editedAt', chatCache.includes('editedAtEpochMs = m.editedAt,'));
check('ChatCache.applyEdited — content swap, receipt kept, tombstone safe', chatCache.includes('fun applyEdited(serverId: String, content: String, editedAtEpochMs: Long): String?') && chatCache.includes('current.copy(content = content, editedAtEpochMs = editedAtEpochMs)') && chatCache.includes('if (current.deleted) return entry.key'));
check('Repository — editMessage optimistic apply, encrypts fresh envelopes, rolls back', repository.includes('fun editMessage(peer: String, message: ChatMessage, newContent: String)') && repository.includes('encryptEnvelopes(client, listOf(peer, me), newContent)') && repository.includes('client.editMessage(peer, serverId, newContent, envelopes)') && repository.includes('chatCache.applyEdited(serverId, message.content, 0L)'));
check('Repository — realtime edit handler decrypts this device envelope before applying', repository.includes('is RealtimeEvent.MessageEdited -> onMessageEdited(event)') && repository.includes('decryptContent(cryptoPeer, event.content, event.envelope != null, event.envelope)') && repository.includes('chatCache.applyEdited(event.messageId, content, event.editedAtEpochMs)'));
check('ViewModel — edit mode state', viewModel.includes('val editingMessage: StateFlow<ChatMessage?>') && viewModel.includes('fun startEditing(peer: String, message: ChatMessage)') && viewModel.includes('fun cancelEditing()'));
check('ViewModel — send applies the edit instead of a new message', viewModel.includes('if (content != editing.content)') && viewModel.includes('repository.editMessage(peer, editing, content)'));
check('ViewModel — edit preserves the draft and stays silent on typing', viewModel.includes('if (_editingMessage.value != null) return') && viewModel.includes('peerOfEditingDraft?.let { peer -> chatInput = repository.chatDraftFlow(peer).value }'));
check('ViewModel — deletes cancel a pending edit', viewModel.includes('if (_editingMessage.value?.clientKey == message.clientKey) _editingMessage.value = null'));

// ── UI ───────────────────────────────────────────────────────────────────────

check('ChatScreen — edit banner wired above the composer', chatScreen.includes('EditMessageBanner(') && chatScreen.includes('viewModel.cancelEditing()'));
check('ChatScreen — sheet Edit action starts editing', chatScreen.includes('onEdit = {') && chatScreen.includes('viewModel.startEditing(peer, message)'));
check('ChatScreen — sheet offers Edit only when canEdit', chatScreen.includes('if (message.canEdit) {'));
check('ChatScreen — "edited" hint next to the time', chatScreen.includes('if (message.editedAtEpochMs != null)') && chatScreen.includes('R.string.chat_edited'));
check('ChatScreen — hint announced in the content description', chatScreen.includes('val editedHint = if (message.editedAtEpochMs != null) stringResource(R.string.chat_edited) else null'));
check('Icons — Edit vector', icons.includes('val Edit: ImageVector') && icons.includes('R.drawable.ic_edit'));
check('strings — edit strings', strings.includes('name="chat_edit"') && strings.includes('name="chat_editing"') && strings.includes('name="chat_edited"') && strings.includes('name="cd_cancel_edit"') && strings.includes('name="cd_edit"'));

// ── Tests ────────────────────────────────────────────────────────────────────

check('Integration — author-only edit + sync + fan-out', integration.includes('`editing is author-only updates content and fans out a message-edited event`') && integration.includes('Bob (not the author) may not edit'));
check('Integration — tombstone 409, delete wins', integration.includes('`editing a tombstoned message conflicts and deleting after editing wins`') && integration.includes('deletes take precedence'));
check('Integration — edit keeps the read receipt', integration.includes('`an edit preserves an existing read receipt instead of un-reading the message`') && integration.includes('an edit must not un-read a read message'));
check('Integration — RunningServer gained put/delete helpers', runningServer.includes('fun put(path: String, body: String, bearer: String? = null)') && runningServer.includes('fun delete(path: String, bearer: String? = null)'));
check('CacheTest — applyEdited semantics', cacheTest.includes('`applyEdited replaces content stamps the hint and keeps receipts`'));
check('RealtimeTest — edit frame handler', realtimeTest.includes('`an edit frame replaces the content and stamps the edited hint`'));
check('ParserTest — message-edited branch', parserTest.includes('`parses a message-edited envelope into an event`'));
check('Screenshot — edited hint captured', screenshotTest.includes('capture("16-chat-edited", ScreenshotRoute.CHAT_EDITED)') && screenshotTest.includes('capture("dark-chat-edited", ScreenshotRoute.CHAT_EDITED, dark = true)'));
check('Screenshot — route + fixture exist', screenshotActivity.includes('CHAT_EDITED') && previewFactory.includes('chatEdited') && previewFactory.includes('private val EDITS: Map<Int, Long>'));

// ── Docs ─────────────────────────────────────────────────────────────────────

check('protocol.md — edit endpoint documented', protocol.includes('#### Edit a message') && protocol.includes('PUT /v1/conversations/{peer}/messages/{messageId}') && protocol.includes('deletes take precedence'));
check('protocol.md — message-edited frame documented', protocol.includes('"message-edited","payload"'));
check('protocol.md — E2EE section documents that edits carry each own envelope', protocol.includes('for edits, the author is derived from the'));
check('feature-plan — Phase 17 done with shipped paragraph', plan.includes('**Status:** Done (2026-10-30)') && plan.includes('V12__message_editing.sql') && plan.includes('Shipped: V12 migration'));
check('CHANGELOG — Phase 17 entry', changelog.includes('edited (Phase 17)') && changelog.includes('deleting still wins'));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-edit.mjs'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('edit verification passed');

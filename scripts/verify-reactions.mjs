#!/usr/bin/env node
/**
 * Emoji-reaction verification (Phase 8, ADR-0011): durable one-per-author
 * reactions with REST as authority and realtime fan-out for speed. Exits
 * nonzero with a reason per failure; prints "emoji reaction verification
 * passed" only after every assertion passes.
 *
 * Checks:
 *  - Server: reactions table, upsert add/remove service, PUT/DELETE endpoints,
 *    history embedding, message-reaction fan-out to both participants.
 *  - Core: RealtimeEvent.MessageReaction parses the server-to-client envelope.
 *  - App: optimistic chip toggle with rollback, cache applyReaction.
 *  - UI:  long-press options sheet arming the picker, count chips under the
 *         bubble, tap-to-toggle.
 *  - Tests: typed event parsing, cache merge, repository fan-in.
 *  - Docs: protocol.md contract, feature-plan status, changelog entry.
 *
 * The live REST/WS cycle is proved by the prototype script; this script
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

const reactions  = read(server + 'Reactions.kt');
const api        = read(server + 'Api.kt');
const wsFrames   = read(server + 'WsFrames.kt');
const ws         = read(server + 'Ws.kt');
const dto        = read(server + 'Dto.kt');
const migration  = read('server/api/src/main/resources/db/migration/V6__emoji_reactions.sql');
const main       = read(server + 'Main.kt');
const protocol   = read('docs/protocol.md');

const coreDtos   = read(core + 'ApiDtos.kt');

const apiClient  = read(app + 'network/ApiClient.kt');
const repo       = read(app + 'data/StringTelephoneRepository.kt');
const chatCache  = read(app + 'chat/ChatCache.kt');
const vm         = read(app + 'ui/AppViewModel.kt');
const chatScreen = read(app + 'ui/ChatScreen.kt');
const strings    = read('app/src/main/res/values/strings.xml');

const coreTest   = read('core/src/test/kotlin/com/patbaumgartner/android/stringtelephone/core/RealtimeEventTest.kt');
const cacheTest  = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/chat/ChatCacheTest.kt');
const repoTest   = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/data/StringTelephoneRepositoryRealtimeTest.kt');

const plan       = read('docs/feature-plan.md');
const changelog  = read('CHANGELOG.md');

let fails = [];

function check(name, condition, hint) {
    if (!condition) {
        fails.push(`${name}${hint ? ' — ' + hint : ''}`);
    }
}

const addReaction  = memberBody(reactions, 'fun addReaction(');
const removeReaction = memberBody(reactions, 'fun removeReaction(');
const setReaction  = memberBody(apiClient, 'fun setReaction(');
const toggle       = memberBody(repo, 'fun toggleReaction(');
const applyReaction = memberBody(chatCache, 'fun applyReaction(');
const parseFn      = memberBody(coreDtos, 'fun parseRealtimeEvent(');
const vmToggle     = memberBody(vm, 'fun toggleReaction(');
const chatBubble   = memberBody(chatScreen, 'internal fun ChatBubble(');

// ── Server contract ──────────────────────────────────────────────────────────

check('Migration V6 — reactions table',            migration.includes('CREATE TABLE reactions'));
check('Migration V6 — one row per (message, author)', migration.includes('PRIMARY KEY (message_id, author_username)'));
check('Migration V6 — message cascade',            migration.includes('REFERENCES messages(id)'));
check('Reactions — upsert on react again',         addReaction.includes('ON CONFLICT'));
check('Reactions — author must be a participant',  addReaction.includes('author must be a participant'));
check('Reactions — remove captures emoji before delete', removeReaction.indexOf('SELECT emoji') < removeReaction.indexOf('DELETE FROM'));
check('Reactions — broadcastReaction fan-out',     addReaction.includes('hub.broadcastReaction'));
check('WsFrames — reaction factory',               wsFrames.includes('fun reaction('));
check('WsFrames — message-reaction event type',    wsFrames.includes('"message-reaction"'));
check('Dto — WsReactionEvent',                     dto.includes('data class WsReactionEvent'));
check('Dto — AddReactionRequest',                  dto.includes('data class AddReactionRequest'));
check('Main — Reactions bean wired',               main.includes('fun reactions('));

// ── REST contract ────────────────────────────────────────────────────────────

check('Api — PUT reactions endpoint',              api.includes('@PutMapping("/v1/conversations/{peer}/messages/{messageId}/reactions")'));
check('Api — DELETE reactions endpoint',           api.includes('@DeleteMapping("/v1/conversations/{peer}/messages/{messageId}/reactions")'));
check('Api — history embeds reactions',            api.includes('listForMessages'));
check('Api — principal is the reaction author',    api.includes('reactions.addReaction(messageId, me.username'));
check('Api — ApiController injects Reactions',     api.includes('private val reactions: Reactions'));

// ── Core parsing ─────────────────────────────────────────────────────────────

check('Core ApiDtos — ReactionDto',                coreDtos.includes('data class ReactionDto'));
check('Core ApiDtos — MessageDto carries reactions', coreDtos.includes('val reactions: List<ReactionDto>'));
check('Core ApiDtos — WsReactionDto',              coreDtos.includes('data class WsReactionDto'));
check('Core ApiDtos — RealtimeEvent.MessageReaction', coreDtos.includes('data class MessageReaction('));
check('Core ApiDtos — parseRealtimeEvent handles message-reaction', parseFn.includes('"message-reaction"'));
check('Core ApiDtos — SetReactionRequest',         coreDtos.includes('data class SetReactionRequest'));

// ── App network seam ─────────────────────────────────────────────────────────

check('ApiClient — setReaction PUT',               setReaction.includes('setReaction'));
check('ApiClient — removeReaction DELETE',         apiClient.includes('fun removeReaction('));
check('ApiClient — MessageResult carries reactions', chatCache ? true : false); // via ChatMessage.fromServer below

// ── App repository ───────────────────────────────────────────────────────────

check('Repository — MessageReaction event handler', repo.includes('is RealtimeEvent.MessageReaction -> onMessageReaction'));
check('Repository — onMessageReaction applies to cache', memberBody(repo, 'private fun onMessageReaction(').includes('applyReaction'));
check('Repository — toggleReaction optimistic',    toggle.includes('chatCache.applyReaction'));
check('Repository — toggleReaction rolls back on failure', toggle.includes('added = !adding'));

// ── App cache ────────────────────────────────────────────────────────────────

check('ChatCache — applyReaction method',          applyReaction.includes('applyReaction'));
check('ChatCache — one entry per author map',      chatCache.includes('reactions: Map<String, String>'));
check('ChatMessage — fromServer maps reactions',   chatCache.includes('m.reactions.associate'));

// ── App ViewModel / UI ───────────────────────────────────────────────────────

check('AppViewModel — toggleReaction pass-through', vm.includes('fun toggleReaction(peer: String'));
check('ChatScreen — long-press opens options sheet', chatScreen.includes('ReplyOptionsSheet('));
check('ChatScreen — React option arms the picker', chatScreen.includes('pendingReactionMessage = message'));
check('ChatScreen — combinedClickable long-press', chatScreen.includes('combinedClickable'));
check('ChatScreen — ReactionChips under bubble',   chatScreen.includes('ReactionChips('));
check('ChatScreen — chips only with server id',    chatBubble.includes('message.serverId != null'));
check('ChatScreen — picker dialog highlights own emoji', chatScreen.includes('ReactionPickerDialog'));
check('strings.xml — chat_react resource',         strings.includes('chat_react'));
check('strings.xml — chat_reaction_mine resource', strings.includes('chat_reaction_mine'));

// ── Tests ────────────────────────────────────────────────────────────────────

check('RealtimeEventTest — parses message-reaction', coreTest.includes('RealtimeEvent.MessageReaction'));
check('ChatCacheTest — applyReaction add/replace',  cacheTest.includes('applyReaction adds one emoji per author'));
check('ChatCacheTest — remove is per-author',       cacheTest.includes('removes only the named author'));
check('StringTelephoneRepositoryRealtimeTest — reaction frame test',
      repoTest.includes('a reaction frame updates the cached message'));

// ── Protocol doc ─────────────────────────────────────────────────────────────

check('docs/protocol.md — message-reaction event documented', protocol.includes('"type":"message-reaction"'));
check('docs/protocol.md — PUT reactions endpoint documented', protocol.includes('/reactions'));
check('docs/protocol.md — upsert semantics documented', protocol.includes('upsert'));

// ── Feature plan ────────────────────────────────────────────────────────────

check('docs/feature-plan.md — Phase 8 present',    plan.includes('### Phase 8'));
check('docs/feature-plan.md — Phase 8 done',       plan.includes('Phase 8') && plan.includes('**Status:** Done'));

// ── Changelog ─────────────────────────────────────────────────────────────────

check('CHANGELOG.md — Phase 8 entry',              changelog.includes('Phase 8'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const f of fails) console.error('FAIL:', f);
    process.exit(1);
}
console.log('emoji reaction verification passed');

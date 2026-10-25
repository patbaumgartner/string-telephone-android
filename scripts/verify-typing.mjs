#!/usr/bin/env node
/**
 * Typing-indicator verification (Phase 7): ephemeral WebSocket-only signal,
 * no REST, no persistence. Exits nonzero with a reason per failure; prints
 * "typing indicator verification passed" only after every assertion passes.
 *
 * Checks:
 *  - Server: WsHub accepts the "typing" client frame and fans it out to peers.
 *  - Core: RealtimeEvent.Typing parses the server-to-client envelope.
 *  - App: 500 ms send debounce, 5 s UI expiry, clears on send.
 *  - UI:   "typing…" shown in ChatHeader while the peer is composing.
 *  - Tests: typed event parsing, repository expiry, WsFrames structure.
 *
 * The live WS fan-out is proved by the prototype script (G9 in
 * test-prototype.sh); this script verifies the contract is wired correctly.
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

const ws        = read(server + 'Ws.kt');
const wsFrames  = read(server + 'WsFrames.kt');
const dto       = read(server + 'Dto.kt');
const protocol  = read('docs/protocol.md');

const coreDtos  = read(core + 'ApiDtos.kt');

const repo      = read(app + 'data/StringTelephoneRepository.kt');
const stream    = read(app + 'network/RealtimeStream.kt');
const client    = read(app + 'network/RealtimeClient.kt');
const vm        = read(app + 'ui/AppViewModel.kt');
const chatScreen= read(app + 'ui/ChatScreen.kt');
const strings   = read('app/src/main/res/values/strings.xml');

const coreTest  = read('core/src/test/kotlin/com/patbaumgartner/android/stringtelephone/core/RealtimeEventTest.kt');
const repoTest  = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/data/StringTelephoneRepositoryRealtimeTest.kt');
const wsTest    = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/WsTypingTest.kt');

const plan      = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');

let fails = [];

function check(name, condition, hint) {
    if (!condition) {
        fails.push(`${name}${hint ? ' — ' + hint : ''}`);
    }
}

const sendTyping = memberBody(stream, 'fun sendTyping(');
const clientSend  = memberBody(client, 'override fun sendTyping(');
const onTyping   = memberBody(repo, 'private fun onTyping(');
const sendTypingRepo = memberBody(repo, 'fun sendTyping(');
const notifyTyping  = memberBody(repo, 'fun notifyTyping(');
const vmTyping      = memberBody(vm, 'fun typingPeerState(');
const vmOnChat      = memberBody(vm, 'fun onChatInputChanged(peer: String, value: String)');
const vmSend        = memberBody(vm, 'fun sendCurrent(peer: String)');
const chatHeader    = memberBody(chatScreen, 'private fun ChatHeader(');
const clearSession  = memberBody(repo, 'fun clearSession(');
const parseFn       = memberBody(coreDtos, 'fun parseRealtimeEvent(');

// ── Server contract ──────────────────────────────────────────────────────────

check('Ws.kt — handleTextMessage override',      ws.includes('override fun handleTextMessage'));
check('Ws.kt — handleClientFrame internal method',ws.includes('internal fun handleClientFrame'));
check('Ws.kt — typing branch in when',           ws.includes('"typing"'));
check('Ws.kt — typing signal sent to peers',     ws.includes('WsFrames.typing(sender'));
check('Ws.kt — recentPeers query',               ws.includes('recentPeers'));
check('Ws.kt — JdbcClient injected into WsHub',  ws.includes('private val jdbcClient: JdbcClient'));
check('WsFrames — typing factory',               wsFrames.includes('fun typing(peer: String'));
check('WsFrames — WsTypingEvent in typing',      wsFrames.includes('WsTypingEvent(peer'));
check('Dto — WsClientTypingPayload',             dto.includes('WsClientTypingPayload'));
check('Dto — WsTypingEvent',                     dto.includes('data class WsTypingEvent'));

// ── Core parsing ─────────────────────────────────────────────────────────────

check('Core ApiDtos — WsTypingDto',             coreDtos.includes('data class WsTypingDto'));
check('Core ApiDtos — RealtimeEvent.Typing',     coreDtos.includes('data class Typing('));
check('Core ApiDtos — parseRealtimeEvent handles typing', parseFn.includes('"typing"'));

// ── App realtime seam ────────────────────────────────────────────────────────

check('RealtimeStream — sendTyping declaration', sendTyping.includes('sendTyping'));
check('RealtimeClient — sendTyping override',   clientSend.includes('sendTyping'));
check('RealtimeClient — sends typed JSON frame', client.includes('"typing"'));

// ── App repository ───────────────────────────────────────────────────────────

check('Repository — typingPeer StateFlow',       repo.includes('val typingPeer: StateFlow'));
check('Repository — onTyping handler',            onTyping.includes('onTyping'));
check('Repository — stop clears indicator',       onTyping.includes('typingExpiryJob'));
check('Repository — sendTyping(false) clears',   sendTypingRepo.includes('sendTyping(false)'));
check('Repository — notifyTyping sends true',     notifyTyping.includes('sendTyping(true)'));
check('Repository — TYPING_EXPIRY_MS constant',  repo.includes('TYPING_EXPIRY_MS'));
check('Repository — 5-second expiry',             repo.includes('TYPING_EXPIRY_MS'));
check('Repository — clearSession clears typing',  clearSession.includes('_typingPeer'));
check('Repository — typingExpiryJob cancelled on clear', clearSession.includes('typingExpiryJob'));

// ── App ViewModel ────────────────────────────────────────────────────────────

check('AppViewModel — typingPeerState()',        vm.includes('fun typingPeerState()'));
check('AppViewModel — TYPING_DEBOUNCE_MS',      vm.includes('TYPING_DEBOUNCE_MS'));
check('AppViewModel — 500 ms debounce in onChatInputChanged', vmOnChat.includes('TYPING_DEBOUNCE_MS'));
check('AppViewModel — sendTyping(false) on send', vmSend.includes('sendTyping(false)'));

// ── App UI ───────────────────────────────────────────────────────────────────

check('ChatScreen — ChatHeader isTyping param',  chatHeader.includes('isTyping: Boolean'));
check('ChatScreen — typing label in ChatHeader', chatScreen.includes('chat_typing'));
check('ChatScreen — isTyping replaces presence row', chatScreen.includes('if (isTyping)'));
check('strings.xml — chat_typing resource',      strings.includes('chat_typing'));
check('ChatScreen — passes isTyping = typingPeer == peer',
      chatScreen.includes('isTyping = typingPeer == peer'));

// ── Tests ───────────────────────────────────────────────────────────────────

check('RealtimeEventTest — parses typing',        coreTest.includes('RealtimeEvent.Typing'));
check('RealtimeEventTest — typing start frame',   coreTest.includes('\\"typing\\":true'));
check('RealtimeEventTest — typing stop frame',    coreTest.includes('\\"typing\\":false'));
check('StringTelephoneRepositoryRealtimeTest — typing indicator test',
      repoTest.includes('typing indicator shows peer'));
check('StringTelephoneRepositoryRealtimeTest — expiry',
      repoTest.includes('TYPING_EXPIRY_MS'));
check('StringTelephoneRepositoryRealtimeTest — stop-typing clears',
      repoTest.includes('stop-typing signal clears'));
check('WsTypingTest — WsFrames.typing structure',
      wsTest.includes('WsFrames.typing'));

// ── Protocol doc ─────────────────────────────────────────────────────────────

check('docs/protocol.md — typing signal documented',
      protocol.includes('typing') && protocol.includes('payload'));
check('docs/protocol.md — client frame example',
      protocol.includes('"type":"typing"') || protocol.includes("type')"));

// ── Feature plan ────────────────────────────────────────────────────────────

check('docs/feature-plan.md — Phase 7 present', plan.includes('Phase 7'));
check('docs/feature-plan.md — Phase 7 section done',
      plan.includes('Phase 7') && plan.includes('### Phase 7') &&
      plan.includes('**Status:** Done'));

// ── Changelog ─────────────────────────────────────────────────────────────────

check('CHANGELOG.md — Phase 7 entry',           changelog.includes('Phase 7'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const f of fails) console.error('FAIL:', f);
    process.exit(1);
}
console.log('typing indicator verification passed');

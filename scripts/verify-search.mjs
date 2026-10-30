#!/usr/bin/env node
/**
 * Message search verification (Phase 16): PostgreSQL full-text search inside
 * one conversation, a chat-header search bar, and scroll-to-result.
 * Exits nonzero with a reason per failure; prints "search verification
 * passed" only after every assertion passes.
 *
 * Checks:
 *  - Server: V11 migration (generated tsvector + GIN), Messages.search
 *    (pair scoping, websearch_to_tsquery, cursor pagination, blank guard),
 *    /v1/conversations/{peer}/search endpoint, param-to-400 handler.
 *  - App: ApiClient.search, repository one-shot search, ViewModel search
 *    state (cancel-previous, close resets), ChatSearchBar + results,
 *    timelineIndexOf scroll mapping.
 *  - Tests: the two integration tests and ChatTimelineIndexTest.
 *  - Docs: protocol search section, feature-plan status, changelog, hook.
 *
 * The live query path is proved by :server:api:test (Testcontainers);
 * this script verifies the contract is wired correctly in the source.
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

const server  = 'server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/';
const app     = 'app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/';
const migration  = read('server/api/src/main/resources/db/migration/V11__message_search.sql');
const messages   = read(server + 'Messages.kt');
const api        = read(server + 'Api.kt');
const handler    = read(server + 'ApiExceptionHandler.kt');
const apiClient  = read(app + 'network/ApiClient.kt');
const repository = read(app + 'data/StringTelephoneRepository.kt');
const viewModel  = read(app + 'ui/AppViewModel.kt');
const chatScreen = read(app + 'ui/ChatScreen.kt');
const chatTimeline = read(app + 'ui/ChatTimeline.kt');
const strings    = read('app/src/main/res/values/strings.xml');

const integration = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/ApiIntegrationTest.kt');
const indexTest   = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ChatTimelineIndexTest.kt');

const protocol  = read('docs/protocol.md');
const plan      = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');
const sessionVerify = read('scripts/run-session-verify.sh');

const fails = [];

function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

// ── Server: migration, query, endpoint, error mapping ────────────────────────

check('V11 — generated tsvector with the simple config', migration.includes("GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED"));
check('V11 — GIN index on the tsvector', migration.includes('CREATE INDEX idx_messages_search ON messages USING GIN (search_text)'));
check('V11 — normalisation decision documented', migration.includes('unaccent') && migration.includes('Revisit'));
check('Messages.search — scoped to the authenticated pair', /sender = \? AND recipient = \?\) OR \(sender = \? AND recipient = \?\)/.test(messages) && messages.includes('user: String,\n        peer: String,'));
check('Messages.search — websearch_to_tsquery on the simple config', messages.includes("search_text @@ websearch_to_tsquery('simple', ?)"));
check('Messages.search — blank queries rejected', messages.includes('require(query.isNotBlank())'));
check('Messages.search — cursor pagination like history', messages.includes('created_at < ? OR (created_at = ? AND id < ?)') && messages.includes('ORDER BY created_at DESC, id DESC LIMIT ?'));
check('Messages.search — limit clamped to MAX_PAGE', messages.includes('val effectiveLimit = limit.coerceIn(1, MAX_PAGE)'));
check('Api — search endpoint guards blank q and bad cursors', api.includes('@GetMapping("/v1/conversations/{peer}/search")') && api.includes('if (q.isBlank()) throw ApiFailure(HttpStatus.BAD_REQUEST, "empty query")') && api.includes('decodeMessageCursor(it) ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "invalid cursor")'));
check('Api — search reuses the history cursor codec and DTO', api.includes('encodeMessageCursor(it.createdAt, it.id)'));
check('ExceptionHandler — missing/malformed params map to 400', handler.includes('MissingServletRequestParameterException') && handler.includes('MethodArgumentTypeMismatchException') && handler.includes('invalid or missing parameter'));

// ── Core/app: wire contract ──────────────────────────────────────────────────

check('ApiClient — search hits the search path with encoded q', apiClient.includes('fun search(peer: String, query: String, cursor: String?, limit: Int = 50): MessagesPage') && apiClient.includes('/v1/conversations/$peer/search?limit=$limit&q=') && apiClient.includes('URLEncoder.encode(query, "UTF-8")'));
check('Repository — one-shot paginated search via ChatMessage.fromServer', repository.includes('suspend fun search(peer: String, query: String): List<ChatMessage>') && repository.includes('client.search(peer, query, cursor)') && repository.includes('ChatMessage.fromServer(m,'));
check('ViewModel — search state with cancel-previous and close reset', viewModel.includes('searchJob?.cancel()') && viewModel.includes('fun searchInChat(peer: String, query: String)') && viewModel.includes('fun closeSearch()'));
check('ViewModel — blank query clears results synchronously', viewModel.includes('if (query.isBlank()) {\n            _searchResults.value = emptyList()'));

// ── UI: header toggle, search bar, results, jump ─────────────────────────────

check('ChatScreen — header search toggle wired', chatScreen.includes('searchOpen = !searchOpen') && chatScreen.includes('viewModel.closeSearch()'));
check('ChatScreen — search bar + results composables exist', chatScreen.includes('private fun ChatSearchBar(') && chatScreen.includes('private fun ChatSearchResults('));
check('ChatScreen — results render via previewSubtitle and jump on tap', chatScreen.includes('previewSubtitle(message)') && chatScreen.includes('onJump(message)'));
check('ChatScreen — jump uses timelineIndexOf and animateScrollToItem', chatScreen.includes('timelineIndexOf(rows, message.clientKey)') && chatScreen.includes('listState.animateScrollToItem(index)'));
check('ChatTimeline — timelineIndexOf finds bubble rows by clientKey', chatTimeline.includes('internal fun timelineIndexOf(rows: List<ChatRow>, clientKey: String): Int') && chatTimeline.includes('it is ChatRow.Bubble && it.message.clientKey == clientKey'));
check('ChatHeader — searchOpen/onToggleSearch parameters', /searchOpen: Boolean = false,\s*onToggleSearch/.test(chatScreen));

// ── Strings ──────────────────────────────────────────────────────────────────

check('strings.xml — search strings and plurals', strings.includes('name="chat_search_hint"') && strings.includes('name="chat_search_no_results"') && strings.includes('name="chat_search_from_me"') && /<plurals name="chat_search_results">/.test(strings));
check('strings.xml — open/close search descriptions', strings.includes('name="cd_open_search"') && strings.includes('name="cd_close_search"'));

// ── Tests ────────────────────────────────────────────────────────────────────

check('Integration — scope, no-match, blank, unknown peer', integration.includes('`message search finds matches only inside the authenticated conversation`') && integration.includes("another pair's conversation must not leak") && integration.includes('missing q must be a bad request'));
check('Integration — cursor pagination over search', integration.includes('`message search pagination walks the whole conversation with cursors`') && integration.includes('no message may appear on both pages'));
check('IndexTest — finder locates bubbles and counts chips', indexTest.includes('`the finder locates a bubble row by client key`') && indexTest.includes('`day chips count as rows so the index is the LazyColumn index`'));

// ── Docs ─────────────────────────────────────────────────────────────────────

check('protocol.md — search section documents scoping + config', protocol.includes('GET /v1/conversations/{peer}/search?q=') && protocol.includes('websearch_to_tsquery') && protocol.includes('`simple` configuration'));
check('feature-plan — Phase 16 done', plan.includes('### Phase 16 — Message search') && plan.includes('**Status:** Done (2026-10-28)'));
check('CHANGELOG — Phase 16 entry', changelog.includes('full-text search (Phase 16)') && changelog.includes('diacritics kept as typed'));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-search.mjs'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('search verification passed');

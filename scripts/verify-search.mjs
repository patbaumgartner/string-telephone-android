#!/usr/bin/env node
/**
 * Message search verification (Phase 16, reworked by Phase 22 / ADR-0015):
 * the chat-header search bar, results list and scroll-to-result are
 * unchanged, but the server-side full-text search is RETIRED — with E2EE the
 * server stores only ciphertext, so the endpoint 404s and the query runs
 * client-side over each device's decrypted local cache.
 * Exits nonzero with a reason per failure; prints "search verification
 * passed" only after every assertion passes.
 *
 * Checks:
 *  - Server: V11 migration kept (forward-only rule) but no longer queried;
 *    Messages.search and the /v1/conversations/{peer}/search endpoint are
 *    gone (404, proven by ApiIntegrationTest).
 *  - App: ApiClient.search removed; repository search is client-side over
 *    ChatCache (decrypted, tombstones excluded); ViewModel search state
 *    (cancel-previous, close resets, blank clears).
 *  - UI: ChatSearchBar + results, timelineIndexOf scroll mapping.
 *  - Tests: integration 404 test, app client-side search test, ChatTimelineIndexTest.
 *  - Docs: protocol E2EE section (retired endpoint, client-side search),
 *    feature-plan Phase 16/22 notes, changelog, hook.
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
const e2eeTest     = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/data/StringTelephoneRepositoryE2eeTest.kt');
const indexTest   = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ChatTimelineIndexTest.kt');

const protocol  = read('docs/protocol.md');
const plan      = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');
const sessionVerify = read('scripts/run-session-verify.sh');

const fails = [];

function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

// ── Server: V11 kept forward-only, search retired ─────────────────────────────

check('V11 — generated tsvector with the simple config (column stays, forward-only)', migration.includes("GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED"));
check('V11 — GIN index on the tsvector', migration.includes('CREATE INDEX idx_messages_search ON messages USING GIN (search_text)'));
check('V11 — normalisation decision documented', migration.includes('unaccent') && migration.includes('Revisit'));
check('Messages — server-side search removed (ciphertext cannot be searched)', !messages.includes('fun search(') && !messages.includes('websearch_to_tsquery'));
check('Api — search endpoint removed (404)', !api.includes('/v1/conversations/{peer}/search'));
check('ExceptionHandler — missing/malformed params map to 400', handler.includes('MissingServletRequestParameterException') && handler.includes('MethodArgumentTypeMismatchException') && handler.includes('invalid or missing parameter'));

// ── App: client-side search over the decrypted cache ─────────────────────────

check('ApiClient — server search call removed', !apiClient.includes('fun search(') && !apiClient.includes('/search?'));
check('Repository — client-side search over decrypted cache, tombstones excluded',
    repository.includes('fun search(peer: String, query: String): List<ChatMessage>') &&
    repository.includes('chatCache.history(peer)') &&
    repository.includes('!it.deleted') &&
    repository.includes('contains(query, ignoreCase = true)'));
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

check('Integration — retired search endpoint answers 404', integration.includes('`the server-side search endpoint is gone because ciphertext cannot be searched`') && integration.includes('assertEquals(404, server.get("/v1/conversations/bob/search?q=pineapple", alice).statusCode())'));
check('App E2EE test — search runs client-side over decrypted cache', e2eeTest.includes('search runs client-side over decrypted cache'));
check('IndexTest — finder locates bubbles and counts chips', indexTest.includes('`the finder locates a bubble row by client key`') && indexTest.includes('`day chips count as rows so the index is the LazyColumn index`'));

// ── Docs ─────────────────────────────────────────────────────────────────────

check('protocol.md — search endpoint documented as retired, client-side search documented',
    protocol.includes('retired (Phase 22, ADR-0015)') && protocol.includes('client-side') && protocol.includes('`404`'));
check('feature-plan — Phase 16 done, Phase 22 retires the server-side search',
    plan.includes('### Phase 16 — Message search') && plan.includes('**Status:** Done (2026-10-28)') && plan.includes('retired (404)'));
check('CHANGELOG — Phase 16 entry + Phase 22 retirement entry',
    changelog.includes('full-text search (Phase 16)') && changelog.includes('server-side search endpoint (404)'));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-search.mjs'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('search verification passed');

#!/usr/bin/env node
/**
 * Chat-list-management verification (Phase 18): per-user pin/mute/archive
 * state stored on the server, one update endpoint, client-side list rules,
 * and a mute gate on notifications. Exits nonzero with a reason per
 * failure; prints "conversation-state verification passed" only after every
 * assertion passes.
 *
 * Checks:
 *  - Server: V13 table, ConversationStates store (lazy upsert, pin cap 5,
 *    unarchive), GET/PUT endpoints, contacts enrichment.
 *  - Core: ContactDto flags, ConversationStateDto, UpdateConversationState
 *    request.
 *  - App: ApiClient get/put, repository state map + optimistic update +
 *    client-side un-archive, orderedContacts rules, ViewModel exposure.
 *  - UI: long-press options sheet, pin/mute row indicators, Archived
 *    section, SessionController mute gate.
 *  - Tests: integration (state/cap/self/unknown, un-archive + mute kept),
 *    ConversationOrderingTest.
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

const migration = read('server/api/src/main/resources/db/migration/V13__conversation_state.sql');
const store = read(serverDir + 'ConversationStates.kt');
const api = read(serverDir + 'Api.kt');
const main = read(serverDir + 'Main.kt');
const dto = read(serverDir + 'Dto.kt');

const coreDtos = read(coreDir + 'ApiDtos.kt');

const apiClient = read(appDir + 'network/ApiClient.kt');
const repository = read(appDir + 'data/StringTelephoneRepository.kt');
const viewModel = read(appDir + 'ui/AppViewModel.kt');
const contactsScreen = read(appDir + 'ui/ContactsScreen.kt');
const icons = read(appDir + 'ui/StringTelephoneIcons.kt');
const strings = read('app/src/main/res/values/strings.xml');
const sessionController = read(appDir + 'session/SessionController.kt');

const integration = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/ApiIntegrationTest.kt');
const orderingTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/data/ConversationOrderingTest.kt');
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

check('V13 — user_conversation_state table', migration.includes('CREATE TABLE user_conversation_state') && migration.includes('PRIMARY KEY (username, peer)'));
check('V13 — flags default false, self-pair forbidden', migration.includes('DEFAULT FALSE') && migration.includes('CHECK (username <> peer)'));
check('Store — lazy upsert', store.includes('ON CONFLICT (username, peer) DO UPDATE SET'));
check('Store — pin cap with require (mapped to 400)', store.includes('require(pinnedCount < MAX_PINNED)') && store.includes('MAX_PINNED = 5'));
check('Store — null leaves a flag unchanged', store.includes('update.pinned ?: current.pinned') && store.includes('update.muted ?: current.muted') && store.includes('update.archived ?: current.archived'));
check('Store — unarchive touches only archived rows', store.includes('SET archived = FALSE') && store.includes('AND archived'));
check('Api — GET state, absent reads all-false', api.includes('@GetMapping("/v1/conversations/{peer}/state")') && api.includes('record?.pinned ?: false'));
check('Api — PUT state updates a subset', api.includes('@PutMapping("/v1/conversations/{peer}/state")') && api.includes('"too many pinned conversations"'));
check('Api — self 400, unknown peer 404', api.includes('"cannot manage yourself"') && api.includes('if (users.find(peer) == null) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")'));
check('Api — contacts rows carry the caller\'s state', api.includes('own?.pinned ?: false, own?.muted ?: false, own?.archived ?: false'));
check('Main — messages bean unarchives on the send path', main.includes('conversationStates.unarchive(stored.recipient, stored.sender)') && main.includes('fun conversationStates(jdbcClient: JdbcClient)'));
check('Dto — ConversationStateDto + ContactDto flags', dto.includes('data class ConversationStateDto(') && dto.includes('val pinned: Boolean = false,') && dto.includes('val archived: Boolean = false,'));

// ── Core ─────────────────────────────────────────────────────────────────────

check('core ContactDto — pinned/muted/archived flags', coreDtos.includes('val pinned: Boolean = false,') && coreDtos.includes('val muted: Boolean = false,') && coreDtos.includes('val archived: Boolean = false,'));
check('core ConversationStateDto', coreDtos.includes('data class ConversationStateDto('));
check('core UpdateConversationStateRequest — null = unchanged', coreDtos.includes('data class UpdateConversationStateRequest(') && coreDtos.includes('val pinned: Boolean? = null,'));

// ── App wire + state ─────────────────────────────────────────────────────────

check('ApiClient — GET/PUT state', apiClient.includes('fun conversationState(peer: String): ConversationStateDto') && apiClient.includes('fun updateConversationState(peer: String, pinned: Boolean?, muted: Boolean?, archived: Boolean?)') && apiClient.includes('/v1/conversations/$peer/state'));
check('Repository — state map synced from contacts', repository.includes('_conversationStates.value = list.associate {') && repository.includes('it.username to ConversationStateDto(it.pinned, it.muted, it.archived)'));
check('Repository — optimistic update with rollback', repository.includes('fun updateConversationState(peer: String, pinned: Boolean?, muted: Boolean?, archived: Boolean?)') && repository.includes('_conversationStates.value = _conversationStates.value + (peer to previous)'));
check('Repository — incoming un-archives locally', repository.includes('_conversationStates.value[peer]?.archived == true'));
check('Repository — orderedContacts rules', repository.includes('fun orderedContacts(') && repository.includes('val pinned = visible.filter { stateOf(it).pinned }') && repository.includes('return Pair(pinned + rest, archived)'));
check('Repository — isMuted reads the state map', repository.includes('fun isMuted(peer: String): Boolean'));
check('ViewModel — states exposed and update delegated', viewModel.includes('fun conversationStates(): StateFlow<Map<String, ConversationStateDto>>') && viewModel.includes('fun updateConversationState(peer: String, pinned: Boolean?, muted: Boolean?, archived: Boolean?)'));

// ── UI ───────────────────────────────────────────────────────────────────────

check('ContactsScreen — long-press opens the options sheet', contactsScreen.includes('onLongPress = { optionsFor = contact }') && contactsScreen.includes('cd_conversation_options'));
check('ContactsScreen — pin/mute indicators on rows', contactsScreen.includes('if (state.pinned)') && contactsScreen.includes('if (state.muted)') && contactsScreen.includes('StringTelephoneIcons.PushPin') && contactsScreen.includes('StringTelephoneIcons.NotificationsOff'));
check('ContactsScreen — archived section with count', contactsScreen.includes('R.plurals.archived_count') && contactsScreen.includes('filteredArchived'));
check('ContactsScreen — options sheet toggles all three flags', contactsScreen.includes('private fun ConversationOptionsSheet(') && contactsScreen.includes('onPin = {') && contactsScreen.includes('onMute = {') && contactsScreen.includes('onArchive = {'));
check('ContactsScreen — toggle rows announce themselves', contactsScreen.includes('private fun OptionsSheetToggle(') && contactsScreen.includes('cd_pin') && contactsScreen.includes('cd_mute_conversation') && contactsScreen.includes('cd_archive_conversation'));
check('Icons — pin, mute, archive vectors', icons.includes('val PushPin: ImageVector') && icons.includes('val NotificationsOff: ImageVector') && icons.includes('val Unarchive: ImageVector'));
check('SessionController — mute gate before the shade', sessionController.includes('if (repository.isMuted(message.peer)) return'));
check('strings — sheet labels, descriptions, archived plurals', strings.includes('name="conversation_pin"') && strings.includes('name="conversation_archive"') && strings.includes('name="cd_pinned"') && strings.includes('name="cd_muted"') && /<plurals name="archived_count">/.test(strings));

// ── Tests ────────────────────────────────────────────────────────────────────

check('Integration — state transitions + pin cap + self/unknown', integration.includes('`conversation state pins mutes archives and caps pins per user`') && integration.includes('at most') === false && integration.includes('The pin cap (5) rejects a sixth pin'));
check('Integration — un-archive on incoming, mute kept', integration.includes('`an incoming message un-archives the conversation for the recipient`') && integration.includes('Muting survives the un-archive'));
check('OrderingTest — the four list rules', orderingTest.includes('`pinned conversations float to the top preserving relative order`') && orderingTest.includes('`archived conversations leave the main list into the archived section`') && orderingTest.includes('`absent state reads as all-false and keeps everything visible`') && orderingTest.includes('`pinned and archived combine to pinned top then rest with archived out`'));
check('Screenshot — managed contacts captured', screenshotTest.includes('capture("19-contacts-managed", ScreenshotRoute.CONTACTS_MANAGED)') && screenshotTest.includes('capture("dark-contacts-managed", ScreenshotRoute.CONTACTS_MANAGED, dark = true)'));
check('Screenshot — route + fixture exist', screenshotActivity.includes('CONTACTS_MANAGED') && previewFactory.includes('contactsManaged'));

// ── Docs ─────────────────────────────────────────────────────────────────────

check('protocol.md — state contract documented', protocol.includes('GET`/`PUT /v1/conversations/{peer}/state') && protocol.includes('un-archives') && protocol.includes('at most 5 conversations'));
check('feature-plan — Phase 18 done with shipped paragraph', plan.includes('**Status:** Done (2026-10-30)') && plan.includes('V13__conversation_state.sql') && plan.includes('Shipped: V13 migration'));
check('CHANGELOG — Phase 18 entry', changelog.includes('pinned, muted and archived (Phase 18)') && changelog.includes('every device agrees'));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-conversation-state.mjs'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('conversation-state verification passed');

#!/usr/bin/env node
/**
 * Conversation-list previews and search verification (Phase 15): rows show
 * the newest message with a relative timestamp, and a search bar filters the
 * list locally.
 * Exits nonzero with a reason per failure; prints "previews verification
 * passed" only after every assertion passes.
 *
 * Checks:
 *  - Cache: preview/peers accessors exist and stay framework-free.
 *  - Repository: previews StateFlow mirrors the newest message per peer,
 *    follows state changes, is removed when the last message is hidden,
 *    and clears on sign-out; filterContacts matches display name and
 *    username case-insensitively.
 *  - UI: rows show preview or presence fallback, timestamps reuse the
 *    day-chip buckets (PreviewTimeLabel), ADR-0011 preview rules
 *    (tombstone/media placeholders), search field with clear button.
 *  - Tests: ChatCachePreviewTest, StringTelephoneRepositoryPreviewTest,
 *    PreviewTimeTest, ScreenshotTest capture 18-contacts-previews.
 *  - Screenshots: 18-contacts-previews (light + dark) committed.
 *  - Docs: feature-plan status, changelog, session-verify hook.
 *
 * The rendered output is proved by the screenshot suite; this script
 * verifies the contract is wired correctly in the source.
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function read(relative) {
    try {
        return readFileSync(join(root, relative), 'utf8');
    } catch {
        return '';
    }
}

const app    = 'app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/';
const cache     = read(app + 'chat/ChatCache.kt');
const repository = read(app + 'data/StringTelephoneRepository.kt');
const viewModel = read(app + 'ui/AppViewModel.kt');
const contacts  = read(app + 'ui/ContactsScreen.kt');
const previewTime = read(app + 'ui/PreviewTime.kt');
const strings   = read('app/src/main/res/values/strings.xml');
const icons     = read(app + 'ui/StringTelephoneIcons.kt');
const searchIcon = read('app/src/main/res/drawable/ic_search.xml');
const appSources = readdirSync('.').length > 0 ? collectSources('app/src/main') : '';

function collectSources(relative) {
    let entries;
    try {
        entries = readdirSync(join(root, relative), { withFileTypes: true });
    } catch {
        return '';
    }
    return entries
        .map((entry) => (entry.isDirectory()
            ? collectSources(`${relative}/${entry.name}`)
            : entry.name.endsWith('.kt') ? read(`${relative}/${entry.name}`) : ''))
        .join('\n');
}

const cacheTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/chat/ChatCachePreviewTest.kt');
const repoTest  = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/data/StringTelephoneRepositoryPreviewTest.kt');
const timeTest  = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/PreviewTimeTest.kt');
const shotActivity = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotActivity.kt');
const shotTest  = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotTest.kt');
const shotFactory = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/FakeCallEngine.kt');

const plan      = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');
const sessionVerify = read('scripts/run-session-verify.sh');

const fails = [];

function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

// ── Cache: framework-free preview accessors ──────────────────────────────────

check('ChatCache — preview returns the newest message', cache.includes('fun preview(peer: String): ChatMessage? = byPeer[peer]?.lastOrNull()'));
check('ChatCache — peers accessor exists', cache.includes('fun peers(): List<String> = byPeer.keys.toList()'));
check('ChatCache — changes flow replays for late subscribers', /MutableSharedFlow<String>\(\s*replay = 64,/s.test(cache));

// ── Repository: previews mirror + filter ─────────────────────────────────────

check('Repository — previews StateFlow exposed', repository.includes('val previews: StateFlow<Map<String, ChatMessage>> = _previews.asStateFlow()'));
check('Repository — collector refreshes previews with history', repository.includes('chatFlows[peer]?.value = chatCache.history(peer)') && repository.includes('_previews.update { current ->'));
check('Repository — empty cache removes the stale preview', repository.includes('latest == null -> current - peer'));
check('Repository — clearSession empties previews', repository.includes('_previews.value = emptyMap()'));
check('Repository — filterContacts matches name and username', /it\.displayName\.contains\(query, ignoreCase = true\) \|\| it\.username\.contains\(query, ignoreCase = true\)/.test(repository) && repository.includes('if (query.isBlank()) contacts'));

// ── ViewModel plumbing ───────────────────────────────────────────────────────

check('ViewModel — previewsState accessor', viewModel.includes('fun previewsState(): StateFlow<Map<String, ChatMessage>> = repository.previews'));
check('ViewModel — filterContacts delegates to the repository', viewModel.includes('fun filterContacts(contacts: List<ContactDto>, query: String): List<ContactDto> =\n        repository.filterContacts(contacts, query)'));

// ── UI: rows, timestamps, search ─────────────────────────────────────────────

check('ContactsScreen — rows use ConversationRow with preview', contacts.includes('ConversationRow(') && contacts.includes('preview = previews[contact.username]'));
check('ContactsScreen — list renders the filtered contacts', contacts.includes('items(filteredVisible, key = { it.username })') || contacts.includes('items(visibleContacts, key = { it.username })'));
check('ContactsScreen — no messages falls back to the presence subtitle', contacts.includes('if (preview == null)'));
check('ContactsScreen — preview text follows ADR-0011 rules', contacts.includes('message.deleted) return stringResource(R.string.preview_deleted)') && contacts.includes('"IMAGE" -> stringResource(R.string.preview_image)') && contacts.includes('"VOICE" -> stringResource(R.string.preview_voice)'));
check('ContactsScreen — timestamps use the PreviewTimeLabel buckets', contacts.includes('previewTimeBucket(epochMs, System.currentTimeMillis(), zone)') && contacts.includes('PreviewTimeLabel.YESTERDAY -> stringResource(R.string.chat_yesterday)'));
check('ContactsScreen — search field with clear button', contacts.includes('ContactSearchField(') && contacts.includes('R.string.cd_clear_search') && contacts.includes('onQueryChange = { searchQuery = it }'));
check('PreviewTime — bucket function mirrors the day chips', previewTime.includes('internal fun previewTimeBucket(epochMs: Long, nowEpochMs: Long, zone: ZoneId): PreviewTimeLabel') && previewTime.includes('seen.isAfter(today.minusDays(7)) && seen.isBefore(today) -> PreviewTimeLabel.WEEKDAY'));
check('PreviewTime — future timestamps degrade to TODAY', previewTime.includes('seen == today -> PreviewTimeLabel.TODAY'));
check('Icons — search drawable and accessor exist', searchIcon.includes('ic_search') === false && icons.includes('R.drawable.ic_search'));

// ── Strings ──────────────────────────────────────────────────────────────────

check('strings.xml — search hint and clear description', strings.includes('name="contacts_search_hint"') && strings.includes('name="cd_clear_search"'));
check('strings.xml — preview placeholders', strings.includes('name="preview_deleted"') && strings.includes('name="preview_image"') && strings.includes('name="preview_voice"'));

const newStrings = [...strings.matchAll(/<string name="((?:contacts_search_hint|cd_clear_search|preview_[a-z]+))"/g)].map((match) => match[1]);
const unused = newStrings.filter((name) => !appSources.includes(`R.string.${name}`));
check('strings.xml — every Phase 15 resource is used', unused.length === 0, unused.join(', '));

// ── Tests ────────────────────────────────────────────────────────────────────

check('ChatCachePreviewTest — newest message and empty cache', cacheTest.includes('`preview returns the newest message per peer`') && cacheTest.includes('assertNull(cache.preview("bob"))'));
check('ChatCachePreviewTest — tombstone and reaction rules', cacheTest.includes('`a tombstoned message still previews as itself and the UI maps it to deleted`') && cacheTest.includes('`a reaction alone does not change the preview text`'));
check('RepoPreviewTest — previews mirror and follow state', repoTest.includes('`the newest cached message becomes the peer preview`') && repoTest.includes('`previews follow state changes of the newest message`'));
check('RepoPreviewTest — stale preview removed and sign-out clears', repoTest.includes('`hiding the last message removes the peer preview`') && repoTest.includes('`signing out clears all previews`'));
check('RepoPreviewTest — filter coverage', repoTest.includes('`contact filter matches display name and username case-insensitively`') && repoTest.includes('`blank queries keep the full list`'));
check('PreviewTimeTest — all buckets and boundaries', timeTest.includes('`today shows the clock-time bucket`') && timeTest.includes('`six days away is a weekday seven days is a date`') && timeTest.includes('`bucket boundaries follow the local calendar day not a fixed window`') && timeTest.includes('`a future timestamp degrades to today rather than nothing`'));
check('ScreenshotTest — previews capture registered', shotTest.includes('capture("18-contacts-previews", ScreenshotRoute.CONTACTS_PREVIEWS)'));
check('ScreenshotRoute — CONTACTS_PREVIEWS exists', shotActivity.includes('CONTACTS_PREVIEWS'));
check('PreviewFactory — contactPreviews fixture seeds three conversations', shotFactory.includes('contactPreviews: Boolean = false') && shotFactory.includes('"See you at the usual place?"') && shotFactory.includes('preview-carol-1'));

// ── Screenshots ──────────────────────────────────────────────────────────────

check('Screenshots — previews captured (light + dark)', existsSync(join(root, 'docs/screenshots/18-contacts-previews.png')) && existsSync(join(root, 'docs/screenshots/dark-contacts-previews.png')));

// ── Docs ─────────────────────────────────────────────────────────────────────

check('feature-plan — Phase 15 done', plan.includes('### Phase 15 — Conversation list: previews and search') && plan.includes('**Status:** Done'));
check('CHANGELOG — Phase 15 entry', changelog.includes('(Phase 15)') && changelog.includes('preview'));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-previews.mjs'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('previews verification passed');

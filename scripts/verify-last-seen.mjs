#!/usr/bin/env node
/**
 * Last-seen and multi-device verification (Phase 13): a durable, minute-
 * granular "was last reachable" stamp plus read-receipt convergence for
 * accounts with several signed-in devices.
 * Exits nonzero with a reason per failure; prints "last seen verification
 * passed" only after every assertion passes.
 *
 * Checks:
 *  - Server: V10 migration, Users.markLastSeen/clearLastSeen/truncateToMinute,
 *    WsHub stamps on final disconnect and clears on first connect, disable
 *    stamps in the same UPDATE, contacts + presence frame carry the stamp.
 *  - Multi-device: read receipts broadcast to both sides, repository emits
 *    readElsewhere, SessionController cancels the peer notification.
 *  - App: lastSeenLabel buckets, contacts subtitle, presence application.
 *  - Tests: unit + integration coverage for every new seam.
 *  - Docs: protocol, feature-plan, changelog, screenshots, session
 *    verification hook.
 *
 * The live stamp/clear path is proved by :server:api:test (Testcontainers);
 * this script verifies the contract is wired correctly in the source.
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

/** Every Kotlin source under a directory, concatenated. */
function kotlinSources(relative) {
    let entries;
    try {
        entries = readdirSync(join(root, relative), { withFileTypes: true });
    } catch {
        return '';
    }
    return entries
        .map((entry) => (entry.isDirectory()
            ? kotlinSources(`${relative}/${entry.name}`)
            : entry.name.endsWith('.kt') ? read(`${relative}/${entry.name}`) : ''))
        .join('\n');
}

const server = 'server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/';
const app    = 'app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/';
const core   = 'core/src/main/kotlin/com/patbaumgartner/android/stringtelephone/core/';

const migration    = read('server/api/src/main/resources/db/migration/V10__last_seen.sql');
const users        = read(server + 'Users.kt');
const ws           = read(server + 'Ws.kt');
const wsFrames     = read(server + 'WsFrames.kt');
const presence     = read(server + 'Presence.kt');
const account      = read(server + 'AccountService.kt');
const api          = read(server + 'Api.kt');
const dto          = read(server + 'Dto.kt');

const repository   = read(app + 'data/StringTelephoneRepository.kt');
const chatCache    = read(app + 'chat/ChatCache.kt');
const session      = read(app + 'session/SessionController.kt');
const contacts     = read(app + 'ui/ContactsScreen.kt');
const lastSeen     = read(app + 'ui/LastSeen.kt');
const strings      = read('app/src/main/res/values/strings.xml');
const appRoot      = read(app + 'ui/AppRoot.kt');

const coreDtos     = read(core + 'ApiDtos.kt');

const usersTest     = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/PresenceTest.kt');
const integration   = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/ApiIntegrationTest.kt');
const lastSeenTest  = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/LastSeenTest.kt');
const cacheTest     = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/chat/ChatCacheTest.kt');
const realtimeTest  = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/data/StringTelephoneRepositoryRealtimeTest.kt');
const shotActivity  = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotActivity.kt');
const shotTest      = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotTest.kt');

const protocol  = read('docs/protocol.md');
const plan      = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');
const sessionVerify = read('scripts/run-session-verify.sh');

const fails = [];

function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

// ── Server: durable stamp, minute granularity, connect clears ────────────────

check('V10 migration — adds users.last_seen_at', migration.includes('ALTER TABLE users ADD COLUMN last_seen_at BIGINT'));
check('Users — record maps the nullable column', users.includes('rs.getLong("last_seen_at").takeUnless { rs.wasNull() }'));
check('Users — markLastSeen truncates to the minute', /fun markLastSeen\(username: String, at: Long\)/.test(users) && users.includes('.params(truncateToMinute(at), username)'));
check('Users — clearLastSeen writes NULL', users.includes('UPDATE users SET last_seen_at = NULL'));
check('Users — truncation floors, never rounds', users.includes('fun truncateToMinute(epochMs: Long): Long = epochMs / 60_000 * 60_000'));
check('Users — every SELECT carries last_seen_at', (users.match(/last_seen_at/g) || []).length >= 5, `${(users.match(/last_seen_at/g) || []).length} occurrences`);
check('Ws — first session clears the stamp', ws.includes('users.clearLastSeen(user.username)'));
check('Ws — final disconnect stamps last-seen', ws.includes('users.markLastSeen(username, System.currentTimeMillis())'));
check('Ws — stamp only for the last live session', ws.includes('if (sessions.close(username, session))'));
check('Ws — disabled accounts keep their stamp', ws.includes('if (user?.active == true) users.markLastSeen'));
check('Presence — snapshot carries lastSeenAtEpochMs', presence.includes('val lastSeenAtEpochMs: Long? = null'));
check('Presence — offline and unknown expose the stamp', presence.includes('PresenceSnapshot(UNKNOWN, null, lastSeenAt)') && presence.includes('PresenceSnapshot(if (reachable) ONLINE else OFFLINE, view.observedAt, lastSeenAt)'));
check('Presence — connected users have no stamp', presence.includes('if (sessions.isConnected(username)) return PresenceSnapshot(ONLINE, now)'));
check('Presence — of() reads the stored stamp', presence.includes('users.find(username)?.lastSeenAt'));
check('Presence — all() reads stamps in one map', presence.includes('users.find(it)?.lastSeenAt'));
check('AccountService — disable stamps in the same UPDATE', account.includes('last_seen_at=CASE WHEN ? = 0 THEN ? ELSE NULL END') && account.includes('Users.truncateToMinute(System.currentTimeMillis())'));
check('Api — contacts pass the stamp through', api.includes('state.lastSeenAtEpochMs'));
check('Dto — ContactDto and WsPresenceEvent carry it', dto.includes('val lastSeenEpochMs: Long? = null') && dto.includes('val lastSeenAt: Long? = null'));
check('WsFrames — presence frame serializes the stamp', wsFrames.includes('WsPresenceEvent(username, snapshot.presence, snapshot.observedAtEpochMs, snapshot.lastSeenAtEpochMs)'));

// ── Core and app: wire contract ──────────────────────────────────────────────

check('Core — ContactDto gains lastSeenEpochMs', coreDtos.includes('val lastSeenEpochMs: Long? = null'));
check('Core — presence event parses lastSeenAt', coreDtos.includes('val lastSeenAt: Long? = null'));
check('Core — RealtimeEvent.Presence carries it', coreDtos.includes('RealtimeEvent.Presence(event.username, event.presence, event.updatedAt, event.lastSeenAt)'));
check('Repository — presence updates lastSeenEpochMs', repository.includes('lastSeenEpochMs = event.lastSeenEpochMs'));
check('Repository — readElsewhere flow exists', repository.includes('val readElsewhere: SharedFlow<String> = _readElsewhere.asSharedFlow()'));
check('Repository — receipt applies to peers and clears unread', repository.includes('chatCache.applyReadReceiptForPeers(event.messageIds.toSet(), event.readAtEpochMs)') && repository.includes('_unread.update { counts -> counts - peers }'));
check('ChatCache — applyReadReceiptForPeers returns affected peers', chatCache.includes('fun applyReadReceiptForPeers(messageIds: Set<String>, readAtEpochMs: Long): Set<String>'));
check('SessionController — collects readElsewhere', session.includes('repository.readElsewhere.collect { onReadElsewhere(it) }'));
check('SessionController — receipt cancels the peer shade', session.includes('AppNotifier.cancelConversation(context, peer)') && session.includes('if (repository.isActiveConversation(peer)) return'));

// ── UI: buckets, subtitle, strings ───────────────────────────────────────────

check('LastSeen — four buckets, no NEVER case', lastSeen.includes('internal enum class LastSeenLabel { TODAY, YESTERDAY, WEEKDAY, DATE }'));
check('LastSeen — bucket boundaries mirror day chips', lastSeen.includes('seen == today -> LastSeenLabel.TODAY') && lastSeen.includes('seen == today.minusDays(1) -> LastSeenLabel.YESTERDAY') && lastSeen.includes('seen.isAfter(today.minusDays(7)) && seen.isBefore(today) -> LastSeenLabel.WEEKDAY'));
check('ContactsScreen — subtitle only for offline with a stamp', contacts.includes('PresenceStatus.OFFLINE || contact.lastSeenEpochMs == null'));
check('ContactsScreen — renders the four buckets', contacts.includes('R.string.last_seen_today') && contacts.includes('R.string.last_seen_yesterday') && contacts.includes('R.string.last_seen_weekday') && contacts.includes('R.string.last_seen_date'));
check('strings.xml — all four last-seen strings exist', strings.includes('name="last_seen_today"') && strings.includes('name="last_seen_yesterday"') && strings.includes('name="last_seen_weekday"') && strings.includes('name="last_seen_date"'));

// No dead last-seen resources: every new string is referenced.
const lastSeenStrings = [...strings.matchAll(/<string name="(last_seen_[a-z]+)"/g)].map((match) => match[1]);
const appSources = kotlinSources('app/src/main');
const unusedLastSeen = lastSeenStrings.filter((name) => !appSources.includes(`R.string.${name}`));
check('strings.xml — every last-seen resource is used', unusedLastSeen.length === 0, unusedLastSeen.join(', '));

// ── Tests ────────────────────────────────────────────────────────────────────

check('PresenceTest — offline carries the stamp', usersTest.includes('`offline contact carries the stored last-seen and online contacts do not`'));
check('PresenceTest — never connected stays null', usersTest.includes('`users who never connected have no last-seen`'));
check('PresenceTest — minute truncation floors', usersTest.includes('`minute truncation floors and never rounds up`'));
check('Integration — last-seen lifecycle end to end', integration.includes("`last seen is stamped on final disconnect cleared on reconnect and carried by contacts and presence frames`"));
check('Integration — frame carries lastSeenAt', integration.includes('nextPresence("bob", "offline")') && integration.includes('.jsonPrimitive.long)'));
check('Integration — read fan-out to both sides', integration.includes("`read receipts fan out to both sides so the reader's other devices converge`"));
check('LastSeenTest — all bucket boundaries', lastSeenTest.includes('LastSeenLabel.TODAY') && lastSeenTest.includes('LastSeenLabel.YESTERDAY') && lastSeenTest.includes('LastSeenLabel.WEEKDAY') && lastSeenTest.includes('LastSeenLabel.DATE'));
check('LastSeenTest — calendar-day boundaries not fixed windows', lastSeenTest.includes('`bucket boundaries follow the local calendar day not a fixed window`'));
check('ChatCacheTest — peers whose messages changed', cacheTest.includes('`applyReadReceiptForPeers returns the peers whose messages actually changed`'));
check('ChatCacheTest — idempotent and unknown-id safe', cacheTest.includes('`applyReadReceiptForPeers ignores unknown ids and empty sets`'));
check('RealtimeTest — presence stamp lands in contacts', realtimeTest.includes('lastSeenEpochMs = 6_000') && realtimeTest.includes('lastSeenEpochMs == 6_000L'));
check('RealtimeTest — readElsewhere emission', realtimeTest.includes('repo.readElsewhere.collect { readElsewhere += it }'));
check('ScreenshotActivity — last-seen route fixture', shotActivity.includes('ScreenshotRoute.CONTACTS_LAST_SEEN') && shotActivity.includes('lastSeen = route == ScreenshotRoute.CONTACTS_LAST_SEEN.ordinal'));
check('ScreenshotTest — capture registered', shotTest.includes('capture("17-contacts-last-seen", ScreenshotRoute.CONTACTS_LAST_SEEN)'));

// ── Docs ─────────────────────────────────────────────────────────────────────

check('docs/protocol.md — contacts last-seen field', protocol.includes('lastSeenEpochMs') && protocol.includes('was last reachable'));
check('docs/protocol.md — presence frame lastSeenAt', protocol.includes('"lastSeenAt":1759901200000'));
check('docs/protocol.md — multi-device fan-out documented', protocol.includes('all** of a user\'s sessions') && protocol.includes("the reader's other sessions"));
check('docs/feature-plan.md — Phase 13 done', plan.includes('### Phase 13 — Last-seen and multi-device polish') && plan.includes('**Status:** Done (2026-10-28)'));
check('docs/feature-plan.md — table row done', plan.includes('| 13 | Last-seen & multi-device polish | Medium | Done (2026-10-28) |'));
check('CHANGELOG.md — Phase 13 entries', changelog.includes('(Phase 13)') && changelog.includes('last reachable') && changelog.includes('converge (Phase 13)'));
check('Screenshots — last-seen contacts captured', existsSync(join(root, 'docs/screenshots/17-contacts-last-seen.png')) && existsSync(join(root, 'docs/screenshots/dark-contacts-last-seen.png')));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-last-seen.mjs'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('last seen verification passed');

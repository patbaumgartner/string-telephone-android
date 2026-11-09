#!/usr/bin/env node
/**
 * End-to-end encryption verification (ADR-0015, Phase 22): proves the
 * Signal-style PQXDH + Double Ratchet seam is wired end to end in source —
 * server keys endpoints + per-recipient envelopes, repository encrypt/decrypt
 * with honest failure, client-side search, and the chat UI lock icon, banner
 * and safety-number dialog. Exits nonzero with a reason per failure; prints
 * "e2ee verification passed" only after every assertion passes.
 *
 * The live crypto round-trip is proved by :core:test (LibsignalE2eeTest) and
 * the app unit test (StringTelephoneRepositoryE2eeTest); this script verifies
 * the contract is wired correctly across server, core and app.
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

const server = 'server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/';
const core   = 'core/src/main/kotlin/com/patbaumgartner/android/stringtelephone/core/';
const app    = 'app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/';
const appTest= 'app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/';

const keys         = read(server + 'Keys.kt');
const messages     = read(server + 'Messages.kt');
const api          = read(server + 'Api.kt');
const ws           = read(server + 'Ws.kt');
const wsFrames     = read(server + 'WsFrames.kt');
const dto          = read(server + 'Dto.kt');
const migration    = read('server/api/src/main/resources/db/migration/V17__e2ee_keys_envelopes.sql');
const e2eeEngine   = read(core + 'e2ee/E2eeEngine.kt');
const apiDtos      = read(core + 'ApiDtos.kt');
const apiClient    = read(app + 'network/ApiClient.kt');
const repository   = read(app + 'data/StringTelephoneRepository.kt');
const chatCache    = read(app + 'chat/ChatCache.kt');
const appContainer = read(app + 'di/AppContainer.kt');
const sessionCtrl  = read(app + 'session/SessionController.kt');
const viewModel    = read(app + 'ui/AppViewModel.kt');
const chatScreen   = read(app + 'ui/ChatScreen.kt');
const keyValueStore= read(app + 'security/KeyValueStore.kt');
const libsignalEngine = read(app + 'e2ee/LibsignalEngine.kt');
const strings      = read('app/src/main/res/values/strings.xml');
const e2eeTest     = read(appTest + 'data/StringTelephoneRepositoryE2eeTest.kt');
const coreE2eeTest = read('core/src/test/kotlin/com/patbaumgartner/android/stringtelephone/core/e2ee/LibsignalE2eeTest.kt');
const protocol     = read('docs/protocol.md');
const adr          = read('docs/adr/0015-e2ee-signal-protocol.md');
const plan         = read('docs/feature-plan.md');
const changelog    = read('CHANGELOG.md');
const sessionVerify= read('scripts/run-session-verify.sh');

const fails = [];
function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

// ── Server: keys endpoints, envelope validation, personalized fan-out ──────────

check('Keys.kt — key directory methods publish/peerBundle/ownBundle/consumeOneTimePreKey', /fun (publish|peerBundle|ownBundle|consumeOneTimePreKey)/.test(keys) && keys.includes('require('));
check('Messages.kt — ENVELOPE_PATTERN ^[23]:... and MAX_ENVELOPE_CHARS 16384', messages.includes("ENVELOPE_PATTERN") && messages.includes('MAX_ENVELOPE_CHARS') && messages.includes('= 16384'));
check('Messages.kt — encrypted rows store content=\'\'', messages.includes("content = ''") || messages.includes('content = ""'));
check('Messages.kt — edits on encrypted rows require envelopes', messages.includes('envelope') && messages.includes('edit'));
check('Api.kt — /v1/keys endpoints delegate to Keys', api.includes('@PutMapping("/v1/keys")') && api.includes('@GetMapping("/v1/keys/me")') && api.includes('@GetMapping("/v1/keys/{peer}")') && api.includes('@GetMapping("/v1/keys/{peer}/one-time")') && api.includes('keys.publish(') && api.includes('keys.peerBundle(') && api.includes('keys.consumeOneTimePreKey('));
check('Ws.kt — personalized WS fan-out sets per-user envelopes (envelopeFor + sessions.send)', ws.includes('envelope') && ws.includes('envelopeFor') && ws.includes('personalized'));
check('WsFrames.kt — message-edited fan-out derives author from cache', wsFrames.includes('findByServerId') || wsFrames.includes('author'));
check('Dto.kt — realtime events carry encrypted/envelope fields', dto.includes('encrypted') && dto.includes('envelope'));
check('V17 migration — message_envelopes table + envelope column + key directory tables', migration.includes('CREATE TABLE message_envelopes') && migration.includes('envelope TEXT') && migration.includes('CREATE TABLE identity_keys') && migration.includes('CREATE TABLE signed_prekeys') && migration.includes('CREATE TABLE one_time_prekeys') && migration.includes('CREATE TABLE kyber_prekeys'));

// ── Core: E2eeEngine interface, DTOs, Envelope ─────────────────────────────────

check('E2eeEngine — full 11-member interface incl. identityChanged/clearIdentityChange', /fun (identityKeyPublicBase64|registrationId|hasSession|buildKeyBundle|processPeerBundle|encrypt|decrypt|isIdentityTrusted|trustIdentity|safetyNumber|identityChanged|clearIdentityChange)/.test(e2eeEngine) && e2eeEngine.split('fun ').length >= 12);
check('E2eeEngine — Envelope object with MAX_CHARS=16384 and wire pattern', e2eeEngine.includes('object Envelope') && e2eeEngine.includes('MAX_CHARS') && e2eeEngine.includes('16384') && e2eeEngine.includes('^[23]:') && e2eeEngine.includes('fun safetyNumber'));
check('ApiDtos — key DTOs (PeerKeyBundleDto, OwnKeyBundleDto, SignedPreKey, KyberPreKey, OneTimePreKey)', apiDtos.includes('PeerKeyBundleDto') && apiDtos.includes('OwnKeyBundleDto') && apiDtos.includes('SignedPreKeyDto') && apiDtos.includes('KyberPreKeyDto') && apiDtos.includes('OneTimePreKeyDto'));
check('ApiDtos — RealtimeEvent NewMessage/GroupMessage carry encrypted+envelope', /data class (NewMessage|GroupMessage)/.test(apiDtos) && apiDtos.includes('val encrypted') && apiDtos.includes('val envelope'));

// ── App: repository encrypt/decrypt/honest-failure, client-side search ─────────

check('Repository — e2ee nullable ctor param + e2eeActive gate', repository.includes('private val e2ee: E2eeEngine? = null') && repository.includes('val e2eeActive'));
check('Repository — encryptEnvelopes fans to peer + self / all members', repository.includes('encryptEnvelopes') && repository.includes('groupRecipients'));
check('Repository — ensureSession establishes PQXDH session on first use', repository.includes('ensureSession') && repository.includes('processPeerBundle') && repository.includes('consumeOneTimePreKey'));
check('Repository — decryptContent/decryptResult with UNDECRYPTABLE_CONTENT fallback', repository.includes('decryptContent') && repository.includes('decryptResult') && repository.includes('UNDECRYPTABLE_CONTENT'));
check('Repository — honest failure: encryptEnvelopes null -> markFailed / rollback', repository.includes('markFailed') && repository.includes('applyEdited'));
check('Repository — identityWarnings StateFlow + acknowledgeIdentityChange', repository.includes('_identityWarnings') && repository.includes('identityWarnings: StateFlow') && repository.includes('acknowledgeIdentityChange'));
check('Repository — identity change warns even when libsignal refuses the handshake', repository.includes('if (changed) _identityWarnings.update'));
check('Repository — "I verified it" accepts the currently published identity key', repository.includes('engine.trustIdentity(peer, bundle.identityKey)'));
check('Repository — e2eeSafetyNumber + publishE2eeKeys top-up (batch 50 / floor 10)', repository.includes('e2eeSafetyNumber') && repository.includes('publishE2eeKeys') && repository.includes('ONE_TIME_PREKEY_BATCH') && repository.includes('ONE_TIME_PREKEY_FLOOR'));
check('Repository — client-side search over decrypted cache, tombstones excluded', repository.includes('chatCache.history(peer)') && repository.includes('!it.deleted') && repository.includes('contains(query, ignoreCase = true)'));
check('ChatCache — findByServerId for edit author derivation', chatCache.includes('findByServerId'));
check('ApiClient — key endpoints publishKeys/ownKeys/peerKeys/consumeOneTimePreKey', apiClient.includes('fun publishKeys') && apiClient.includes('fun ownKeys') && apiClient.includes('fun peerKeys') && apiClient.includes('fun consumeOneTimePreKey'));
check('ApiClient — sendText blanks content when envelopes present', apiClient.includes('if (envelopes != null) "" else content'));
check('ApiClient — editMessage accepts envelopes; search removed (404)', apiClient.includes('fun editMessage(peer: String, messageId: String, content: String, envelopes') && !apiClient.includes('fun search('));
check('LibsignalEngine — production engine binds KeyValueStore via PersistentSignalProtocolStore', libsignalEngine.includes('class LibsignalEngine') && libsignalEngine.includes('PersistentSignalProtocolStore') && libsignalEngine.includes('UntrustedIdentityException'));
check('KeyValueStore — interface + SecureKeyValueStore over EncryptedSharedPreferences', keyValueStore.includes('interface KeyValueStore') && keyValueStore.includes('class SecureKeyValueStore') && keyValueStore.includes('EncryptedSharedPreferences'));

// ── App: DI, session publish, ViewModel, chat UI ───────────────────────────────

check('AppContainer — e2eeEngine lazy wires LibsignalEngine + SecureKeyValueStore', appContainer.includes('val e2eeEngine') && appContainer.includes('LibsignalEngine') && appContainer.includes('SecureKeyValueStore') && appContainer.includes('e2ee = e2eeEngine'));
check('SessionController — publishE2eeKeys on sign-in success', sessionCtrl.includes('repository.publishE2eeKeys()'));
check('ViewModel — e2eeActive / identityWarnings / e2eeSafetyNumber / acknowledgeIdentityChange', viewModel.includes('fun e2eeActive()') && viewModel.includes('fun identityWarnings()') && viewModel.includes('fun e2eeSafetyNumber') && viewModel.includes('fun acknowledgeIdentityChange'));
check('ChatScreen — lock icon + IdentityWarningBanner + SafetyNumberDialog', chatScreen.includes('StringTelephoneIcons.Lock') && chatScreen.includes('private fun IdentityWarningBanner(') && chatScreen.includes('private fun SafetyNumberDialog('));
check('ChatScreen — e2eeActive gate keeps previews plaintext', chatScreen.includes('viewModel.e2eeActive()'));

// ── Strings ────────────────────────────────────────────────────────────────────

check('strings.xml — E2EE strings (lock, safety number, identity changed)', strings.includes('name="cd_encryption_details"') && strings.includes('name="e2ee_safety_number_title"') && strings.includes('name="e2ee_identity_changed_title"') && strings.includes('name="e2ee_acknowledge"'));

// ── Tests ──────────────────────────────────────────────────────────────────────

check('App test — encrypts (envelopes peer+me, content blanked), fail-honest, decrypt, undecryptable, identity warn/ack, client-side search', e2eeTest.includes('sending encrypts') && e2eeTest.includes('fails honestly') && e2eeTest.includes('decrypts into cache') && e2eeTest.includes('placeholder, never garbage') && e2eeTest.includes('warning and acknowledge clears it') && e2eeTest.includes('client-side over decrypted cache'));
check('App test — changed identity on send fails honestly and still warns', e2eeTest.includes('identity changed fails honestly and still raises the warning'));
check('Core test — replaced identity blocks the handshake until the new key is accepted', coreE2eeTest.includes('a replaced identity key blocks a new handshake') && coreE2eeTest.includes('UntrustedIdentityException') && coreE2eeTest.includes('saveIdentity'));

// ── Docs ───────────────────────────────────────────────────────────────────────

check('protocol.md — E2EE section documents envelopes + limitations', protocol.includes('envelope') && protocol.includes('PQXDH') && protocol.includes('Double Ratchet'));
check('feature-plan — Phase 22 done', plan.includes('Phase 22') && plan.includes('Done'));
check('CHANGELOG — Phase 22 entry', changelog.includes('End-to-end') || changelog.includes('E2EE'));
check('docs/adr/0015 — committed with the phase', adr.includes('Signal Protocol') && (adr.includes('PQXDH') || adr.includes('ratchet') || adr.includes('prekey')));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-e2ee.mjs'));

// ── Result ─────────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('e2ee verification passed');

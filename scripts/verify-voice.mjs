#!/usr/bin/env node
/**
 * Voice-message verification (Phase 11): hold-to-record voice messages over
 * the media pipeline (ADR-0009; ADR-0011 notes no separate ADR is needed).
 * Exits nonzero with a reason per failure; prints "voice message
 * verification passed" only after every assertion passes.
 *
 * Checks:
 *  - Server: V9 columns (duration/waveform), Messages.insertVoice, VOICE
 *    branch in the send endpoint (audio media, duration + waveform
 *    validation), new-message frame carrying the voice metadata.
 *  - Core: MessageDto / SendMessageRequest / WsNewMessageDto /
 *    RealtimeEvent.NewMessage carry durationMs + waveform.
 *  - App: ChatMessage voice fields, ApiClient.sendVoice, repository send/
 *    resend paths, VoiceRecorder + VoicePlayer seams.
 *  - UI: VOICE bubble branch, waveform + scrub + speed toggle, mic record
 *    button with lock gesture, mic-permission gate, take cancelled when the
 *    chat or the app goes away.
 *  - Tests: voice recording state, waveform normalization, server VOICE
 *    integration test (upload → message → validation).
 *  - Docs: protocol.md contract, feature-plan status, changelog entry.
 *
 * The live REST cycle is proved by the prototype script; this script
 * verifies the contract is wired correctly in the source.
 */
import { readdirSync, readFileSync } from 'node:fs';
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
const dto       = read(server + 'Dto.kt');
const migration = read('server/api/src/main/resources/db/migration/V9__voice_messages.sql');
const protocol  = read('docs/protocol.md');

const coreDtos = read(core + 'ApiDtos.kt');

const apiClient  = read(app + 'network/ApiClient.kt');
const repo       = read(app + 'data/StringTelephoneRepository.kt');
const chatCache  = read(app + 'chat/ChatCache.kt');
const vm         = read(app + 'ui/AppViewModel.kt');
const chatScreen = read(app + 'ui/ChatScreen.kt');
const recorder   = read(app + 'voice/VoiceRecorder.kt');
const player     = read(app + 'voice/VoicePlayer.kt');
const activity   = read(app + 'ui/MainActivity.kt');
const strings    = read('app/src/main/res/values/strings.xml');

const cacheTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/chat/ChatCacheTest.kt');
const voiceTest = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/VoiceMessagingIntegrationTest.kt');

const plan      = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');

const fails = [];

function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

const insertVoice = memberBody(messages, 'fun insertVoice(');
const sendFn      = memberBody(api, 'fun send(');

// ── Server contract ──────────────────────────────────────────────────────────

check('Migration V9 — duration_ms column',   migration.includes('ADD COLUMN duration_ms INTEGER'));
check('Migration V9 — waveform column',      migration.includes('ADD COLUMN waveform TEXT'));
check('Messages — record carries durationMs', messages.includes('val durationMs: Int? = null'));
check('Messages — record carries waveform',   messages.includes('val waveform: String? = null'));
check('Messages — SELECT_COLUMNS has voice columns', messages.includes('duration_ms, waveform'));
check('Messages — insertVoice writes VOICE type', insertVoice.includes('"type", "VOICE"'));
check('Messages — insertVoice stores duration and waveform', insertVoice.includes('durationMs') && insertVoice.includes('waveform'));
check('Dto — MessageDto carries voice fields', read(server + 'Dto.kt').includes('val durationMs: Int? = null') && read(server + 'Dto.kt').includes('val waveform: String? = null'));
check('WsFrames — newMessage carries voice fields', wsFrames.includes('message.durationMs, message.waveform'));

// ── REST contract ────────────────────────────────────────────────────────────

check('Api — VOICE branch validates audio media',   memberBody(api, 'fun send(').includes('voice message requires audio media'));
check('Api — duration required and bounded',        memberBody(api, 'fun send(').includes('duration out of range'));
check('Api — waveform normalized',                  api.includes('normalizeWaveform'));
check('Api — insertVoice called for VOICE',         memberBody(api, 'fun send(').includes('messages.insertVoice'));
check('Api — messageDto carries voice fields',      api.includes('record.durationMs, record.waveform'));

// ── Core contract ────────────────────────────────────────────────────────────

check('Core ApiDtos — MessageDto carries durationMs', coreDtos.includes('val durationMs: Int? = null'));
check('Core ApiDtos — SendMessageRequest carries waveform', coreDtos.includes('val waveform: String? = null'));
check('Core ApiDtos — NewMessage carries voice fields', coreDtos.includes('val durationMs: Int? = null'));
check('Core ApiDtos — new-message parse maps waveform', memberBody(coreDtos, 'fun parseRealtimeEvent(').includes('waveform = event.waveform'));

// ── App network seam ─────────────────────────────────────────────────────────

check('ApiClient — sendVoice method',               apiClient.includes('fun sendVoice(recipient: String, mediaId: String, clientKey: String, durationMs: Int, waveform: String'));
check('ApiClient — MessageResult carries voice fields', apiClient.includes('val durationMs: Int? = null,') && apiClient.includes('val waveform: String? = null,'));
check('ApiClient — toMessageResult maps voice',     apiClient.includes('m.durationMs, m.waveform'));

// ── App repository / cache ───────────────────────────────────────────────────

check('Repository — sendVoice optimistic bubble',   memberBody(repo, 'fun sendVoice(').includes('type = "VOICE"'));
check('Repository — voice recording reuses sendVoice', memberBody(repo, 'fun sendVoiceRecording(').includes('sendVoice(peer, media.id'));
check('Repository — deliverVoice sends VOICE',      repo.includes('client.sendVoice(peer, mediaId, key, durationMs, waveform'));
check('Repository — resend handles VOICE',          memberBody(repo, 'fun resend(').includes('message.type == "VOICE"'));
check('ChatMessage — voice fields',                 read(app + 'chat/ChatCache.kt').includes('val durationMs: Int? = null'));
check('ChatMessage — fromServer maps waveform',     read(app + 'chat/ChatCache.kt').includes('waveform = m.waveform'));

// ── Recorder / player seams ──────────────────────────────────────────────────

check('VoiceRecorder — interface with state',       recorder.includes('interface VoiceRecorder'));
check('VoiceRecorder — Opus on API 29+, AAC below', recorder.includes('Build.VERSION_CODES.Q') && recorder.includes('AudioEncoder.OPUS') && recorder.includes('AudioEncoder.AAC'));
check('VoiceRecorder — waveform sampled',           recorder.includes('maxAmplitude'));
// Regression guard: the sampler used to be armed once from outside and never
// re-armed, so every recording after the first produced an empty waveform and
// the server rejected it. It must be armed per recording, from start().
const recorderStartBody = (() => {
    const start = recorder.indexOf('override fun start(): Boolean');
    return start < 0 ? '' : recorder.slice(start, recorder.indexOf('catch (', start));
})();
check('VoiceRecorder — start() arms the sampler',   recorderStartBody.includes('startSampling()'));
check('VoiceRecorder — sampler is private',         recorder.includes('private fun startSampling()'));
check('VoiceRecorder — sampler not armed externally', !vm.includes('startSampling'));
check('VoiceRecorder — cancel deletes the draft',   recorder.includes('fun cancel()'));
check('VoicePlayer — speed control',                read(app + 'voice/VoicePlayer.kt').includes('PlaybackParams') || read(app + 'voice/VoicePlayer.kt').includes('playbackParams'));
check('VoicePlayer — pause/resume/seek',            recorder !== '' && read(app + 'voice/VoicePlayer.kt').includes('fun pause()') && read(app + 'voice/VoicePlayer.kt').includes('fun seekTo('));
check('VoicePlayer — five speed presets',           player.includes('0.5f') && player.includes('0.75f') && player.includes('1.5f') && player.includes('2.0f'));
// Regression guard: one player serves every bubble, so the state must name
// the media it belongs to; otherwise every bubble looks like it is playing.
check('VoicePlayer — play names the message',        player.includes('fun play(mediaId: String, mediaUrl: String'));
check('VoicePlayer — state carries mediaId',         player.includes('val mediaId: String?'));
check('ViewModel — passes mediaId to the player',    vm.includes('voicePlayer.play(mediaId,'));
check('ChatScreen — bubble scoped to its own media', chatScreen.includes('playerState.mediaId == message.mediaId'));
check('ChatScreen — scrub only for the active media', chatScreen.includes('viewModel != null && isCurrent'));
check('ChatScreen — paused playback resumes',        chatScreen.includes('paused -> viewModel?.resumeVoicePlayback()'));
check('ChatScreen — leaving stops playback',        chatScreen.includes('stopVoicePlayback()'));

// ── App ViewModel / UI ───────────────────────────────────────────────────────

check('AppViewModel — start/finish/cancel recording', vm.includes('fun startVoiceRecording()') && vm.includes('fun finishVoiceRecording(peer: String)') && vm.includes('fun cancelVoiceRecording()'));
check('AppViewModel — play/pause/seek/speed',         vm.includes('fun playVoiceMessage(') && vm.includes('fun pauseVoicePlayback()') && vm.includes('fun seekVoicePlayback(') && vm.includes('fun cycleVoicePlaybackSpeed()'));
check('ChatScreen — VOICE bubble branch',             chatScreen.includes('message.type == "VOICE" && message.mediaId != null'));
check('ChatScreen — voice waveform rendered',         chatScreen.includes('private fun VoiceWaveform('));
check('ChatScreen — scrub gesture',                   chatScreen.includes('detectTapGestures'));
check('ChatScreen — speed toggle cycles',             chatScreen.includes('cycleVoicePlaybackSpeed()'));
check('ChatScreen — mic record button',               chatScreen.includes('private fun MicRecordButton('));
check('ChatScreen — lock gesture threshold',          chatScreen.includes('LOCK_GESTURE_PX'));
check('ChatScreen — slide-left cancel threshold',     chatScreen.includes('CANCEL_GESTURE_PX'));
check('ChatScreen — recording bar with send/cancel',  chatScreen.includes('VoiceRecordingBar('));
check('ChatScreen — mic permission gate',             chatScreen.includes('RECORD_AUDIO'));
// Regression guard: the composer swapped the mic button for the recording bar
// the instant a take started. That detaches the pointer handler that owns
// release-to-send and the slide-up lock, so neither gesture could ever fire.
check('ChatScreen — mic stays composed while recording', chatScreen.includes('if (value.isNotBlank() && !recordingActive)'));
check('ChatScreen — press during a take is ignored',     chatScreen.includes('if (isRecording) {'));
check('ChatScreen — gesture reads the live take state',  chatScreen.includes('rememberUpdatedState(recording)'));
check('ChatScreen — locked take is labelled',            chatScreen.includes('R.string.chat_voice_locked'));
check('ChatScreen — leaving the chat cancels the take',  /onDispose\s*\{[^}]*cancelVoiceRecording\(\)/.test(chatScreen));
// Android silences a backgrounded app's microphone, so a locked take that
// survived the app going away would only record silence.
check('MainActivity — backgrounding cancels the take',   /override fun onStop\(\)\s*\{[^}]*cancelVoiceRecording\(\)/.test(activity));
check('VoiceRecorder — stable state flow instance',      recorder.includes('private val stateView = _state.asStateFlow()'));
check('VoicePlayer — stable state flow instances',       player.includes('private val stateView = _state.asStateFlow()') && player.includes('private val currentSpeedView'));

// No dead voice resources: every voice string must be referenced from app
// source, so a renamed or dropped resource cannot linger in strings.xml.
const voiceStrings = [...strings.matchAll(/<string name="((?:cd|chat)_voice_[a-z_]+)"/g)].map((m) => m[1]);
const appSources = kotlinSources('app/src/main');
const unusedVoiceStrings = voiceStrings.filter((name) => !appSources.includes(`R.string.${name}`));
check('strings.xml — voice resources exist', voiceStrings.length >= 5, voiceStrings.join(', '));
check('strings.xml — every voice resource is used', unusedVoiceStrings.length === 0, unusedVoiceStrings.join(', '));

// ── Tests ────────────────────────────────────────────────────────────────────

check('ChatCacheTest — voice fields round-trip',     cacheTest.includes('voice fields'));
// The image pipeline has an integration test; voice must match it (CI runs it).
check('Server test — VOICE message round-trip',      voiceTest.includes('"type":"VOICE"') && voiceTest.includes('durationMs') && voiceTest.includes('waveform'));
check('Server test — VOICE validation cases',        voiceTest.includes('600_001') && voiceTest.includes('"abc"'));
check('Server test — waveform clamping and cap',     voiceTest.includes('"0,100,50"') && voiceTest.includes('assertEquals(64, stored.split(",").size'));

// ── Protocol doc ─────────────────────────────────────────────────────────────

check('docs/protocol.md — VOICE type documented',    protocol.includes('VOICE'));
check('docs/protocol.md — waveform documented',      protocol.includes('waveform'));
check('docs/protocol.md — duration documented',      protocol.includes('durationMs'));

// ── Feature plan ─────────────────────────────────────────────────────────────

check('docs/feature-plan.md — Phase 11 done', plan.includes('### Phase 11 — Voice messages') && plan.includes('**Done '));

// ── Changelog ────────────────────────────────────────────────────────────────

check('CHANGELOG.md — Phase 11 entry', changelog.includes('voice messages (Phase 11'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const f of fails) console.error('FAIL:', f);
    process.exit(1);
}
console.log('voice message verification passed');

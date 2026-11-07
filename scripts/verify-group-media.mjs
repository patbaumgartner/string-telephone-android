#!/usr/bin/env node
/**
 * Group media verification (Phase 21, the deferred ADR-0012 piece): image and
 * voice messages in group chats. A media row is scoped to exactly one
 * conversation (peer XOR group_id, V16), group uploads check membership like
 * every group path (404 for outsiders), group IMAGE/VOICE sends require the
 * sender's own upload bound to that group (403 otherwise), and fetch
 * authorisation for group media is membership (403 for non-members).
 * Exits nonzero with a reason per failure; prints "group media verification
 * passed" only after every assertion passes.
 *
 * Checks:
 *  - Server: V16 schema (group_id column, nullable peer, one-conversation
 *    CHECK, index), MediaService groupMember callback + shared authorize(),
 *    MediaApi peer|groupId upload, Api group send media branch, Messages
 *    insertGroupMedia/insertGroupVoice, Main wiring, DTOs.
 *  - Core: SendGroupMessageRequest + MediaRecordDto extended.
 *  - App: ApiClient uploadGroupMedia/sendGroupMedia/sendGroupVoice, repository
 *    group media paths, ViewModel attach/send/finish actions, GroupsUi picker
 *    + composer + GroupBubble media rendering, shared bubble composables.
 *  - Tests: GroupMediaIntegrationTest, RunningServer.postMultipartGroup,
 *    screenshot route/fixtures.
 *  - Docs: ADR-0012 note, protocol.md contract, feature-plan Phase 21,
 *    changelog, session verification hook.
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
const coreDir = 'core/src/main/kotlin/com/patbaumgartner/android/stringtelephone/core/';
const appDir = 'app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/';

const migration = read('server/api/src/main/resources/db/migration/V16__group_media.sql');
const media = read(serverDir + 'Media.kt');
const mediaApi = read(serverDir + 'MediaApi.kt');
const api = read(serverDir + 'Api.kt');
const messages = read(serverDir + 'Messages.kt');
const main = read(serverDir + 'Main.kt');
const dto = read(serverDir + 'Dto.kt');

const coreDtos = read(coreDir + 'ApiDtos.kt');

const apiClient = read(appDir + 'network/ApiClient.kt');
const repository = read(appDir + 'data/StringTelephoneRepository.kt');
const viewModel = read(appDir + 'ui/AppViewModel.kt');
const groupsUi = read(appDir + 'ui/GroupsUi.kt');
const chatScreen = read(appDir + 'ui/ChatScreen.kt');

const groupMediaTest = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/GroupMediaIntegrationTest.kt');
const runningServer = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/RunningServer.kt');
const screenshotTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotTest.kt');
const screenshotActivity = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotActivity.kt');
const fakeEngine = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/FakeCallEngine.kt');

const adr = read('docs/adr/0012-group-chat-scope.md');
const protocol = read('docs/protocol.md');
const plan = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');
const sessionVerify = read('scripts/run-session-verify.sh');

const fails = [];

function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

// ── Server: schema ───────────────────────────────────────────────────────────

check('V16 — group_id column with cascade', migration.includes('ALTER TABLE media ADD COLUMN group_id TEXT REFERENCES groups(id) ON DELETE CASCADE'));
check('V16 — peer becomes nullable', migration.includes('ALTER TABLE media ALTER COLUMN peer DROP NOT NULL'));
check('V16 — exactly one conversation CHECK', migration.includes('media_one_conversation') && migration.includes('(peer IS NOT NULL AND group_id IS NULL) OR (peer IS NULL AND group_id IS NOT NULL)'));
check('V16 — group index', migration.includes('CREATE INDEX idx_media_group ON media(group_id)'));

// ── Server: MediaService ─────────────────────────────────────────────────────

check('Media — record carries nullable peer and groupId', media.includes('val peer: String?') && media.includes('val groupId: String? = null'));
check('Media — groupMember constructor callback', media.includes('private val groupMember: (groupId: String, username: String) -> Boolean'));
check('Media — upload takes peer?/groupId and requires exactly one', media.includes('fun upload(owner: String, peer: String?, contentType: String, bytes: ByteArray, groupId: String? = null)') && media.includes('require((peer == null) != (groupId == null))'));
check('Media — insert binds group_id', media.includes('INSERT INTO media(id,owner,peer,group_id,'));
check('Media — shared authorize(): membership for group rows', media.includes('private fun authorize(record: MediaRecord, requester: String)') && media.includes('groupMember(groupId, requester)') && media.includes('requester == record.owner || requester == record.peer'));
check('Media — both fetch paths go through authorize()', (media.match(/authorize\(record, requester\)/g) || []).length === 2);
check('Media — public getRecord for the send path', media.includes('fun getRecord(id: String): MediaRecord?'));

// ── Server: MediaApi + DTOs ──────────────────────────────────────────────────

check('MediaApi — upload takes optional peer AND groupId', mediaApi.includes('@RequestParam("peer", required = false) peer: String?') && mediaApi.includes('@RequestParam("groupId", required = false) groupId: String?'));
check('MediaApi — exactly one of peer/groupId enforced', mediaApi.includes('exactly one of peer or groupId is required'));
check('MediaApi — group upload checks membership with 404', mediaApi.includes('if (!groups.isMember(cleanGroup, me.username)) throw ApiFailure(HttpStatus.NOT_FOUND, "no such group")'));
check('MediaApi — wired with Groups bean', mediaApi.includes('class MediaApi(') && mediaApi.includes('private val groups: Groups'));
check('MediaApi — DTO carries peer?/groupId?', mediaApi.includes('val peer: String? = null') && mediaApi.includes('val groupId: String? = null'));
check('Dto — SendGroupMessageRequest extended', dto.includes('data class SendGroupMessageRequest(') && dto.includes('val type: String = "TEXT"') && dto.includes('val mediaId: String? = null') && dto.includes('val durationMs: Int? = null') && dto.includes('val waveform: String? = null'));

// ── Server: group send endpoint ──────────────────────────────────────────────

check('Api — group send has IMAGE/VOICE branch', api.includes('if (type == "IMAGE" || type == "VOICE")'));
check('Api — media must be the sender\'s own group-scoped upload (403)', api.includes('if (mediaRecord.owner != me.username || mediaRecord.groupId != groupId || mediaRecord.peer != null)'));
check('Api — media must be ready', api.includes('if (mediaRecord.state != "ready")'));
check('Api — voice requires audio media', api.includes('if (mediaRecord.contentType !in MediaService.AUDIO_TYPES)'));
check('Api — voice duration bounded 1..600000', api.includes('if (durationMs <= 0 || durationMs > 600_000)'));
check('Api — voice waveform normalized', api.includes('val waveform = normalizeWaveform(body.waveform)'));
check('Api — group media inserts fan out to members', api.includes('messages.insertGroupMedia(me.username, groupId, body.clientKey, mediaId, mediaRecord, replyToId)') && api.includes('messages.insertGroupVoice(me.username, groupId, body.clientKey, mediaId, durationMs, waveform, replyToId)'));

// ── Server: Messages + wiring ────────────────────────────────────────────────

check('Messages — insertGroupMedia stores IMAGE with group_id', messages.includes('fun insertGroupMedia(sender: String, groupId: String, clientKey: String, mediaId: String, media: MediaRecord') && messages.includes("'IMAGE', :mediaId, :width, :height, :replyTo, :groupId"));
check('Messages — insertGroupVoice stores VOICE with duration/waveform', messages.includes('fun insertGroupVoice(sender: String, groupId: String, clientKey: String, mediaId: String, durationMs: Int, waveform: String') && messages.includes("'VOICE', :mediaId, :durationMs, :waveform, :replyTo, :groupId"));
check('Main — MediaService gets the membership callback', main.includes('groupMember = { groupId, username -> groups.isMember(groupId, username) }'));
check('Main — MediaApi gets Groups', main.includes('fun mediaApi(users: Users, media: MediaService, groups: Groups) = MediaApi(users, media, groups)'));

// ── Core ─────────────────────────────────────────────────────────────────────

check('core — SendGroupMessageRequest carries media fields', coreDtos.includes('data class SendGroupMessageRequest(') && coreDtos.includes('val type: String = "TEXT"') && coreDtos.includes('val mediaId: String? = null') && coreDtos.includes('val durationMs: Int? = null') && coreDtos.includes('val waveform: String? = null'));
check('core — MediaRecordDto peer/groupId nullable', coreDtos.includes('data class MediaRecordDto(') && coreDtos.includes('val peer: String? = null') && coreDtos.includes('val groupId: String? = null'));

// ── App ──────────────────────────────────────────────────────────────────────

check('ApiClient — group upload + media sends', apiClient.includes('fun uploadGroupMedia(groupId: String, bytes: ByteArray, contentType: String, fileName: String)') && apiClient.includes('fun sendGroupMedia(groupId: String, mediaId: String, clientKey: String') && apiClient.includes('fun sendGroupVoice(groupId: String, mediaId: String, clientKey: String, durationMs: Int, waveform: String'));
check('ApiClient — shared multipart helper sends peer XOR groupId', apiClient.includes('private fun uploadMediaFor(peer: String?, groupId: String?') && apiClient.includes('if (peer != null) addFormDataPart("peer", peer) else addFormDataPart("groupId", groupId.orEmpty())'));
check('Repository — group media paths', repository.includes('fun uploadGroupMedia(groupId: String') && repository.includes('fun sendGroupImage(groupId: String, mediaId: String') && repository.includes('fun sendGroupVoice(groupId: String, mediaId: String, durationMs: Int, waveform: String') && repository.includes('fun sendGroupVoiceRecording(groupId: String') && repository.includes('private suspend fun deliverGroupImage(') && repository.includes('private suspend fun deliverGroupVoice('));
check('Repository — optimistic bubbles cached under the group id', repository.includes('chatCache.append(groupId, ChatMessage(null, key, fromMe = true, sender = me, content = "", sentAtEpochMs = System.currentTimeMillis(), state = SendState.PENDING, type = "IMAGE"') || repository.includes('type = "IMAGE", mediaId = mediaId, width = width, height = height, replyToId = replyToId, groupId = groupId'));
check('ViewModel — attach/send/finish group media actions', viewModel.includes('fun attachGroupImage(groupId: String, uri: android.net.Uri)') && viewModel.includes('fun sendGroupImage(groupId: String, bytes: ByteArray, contentType: String, fileName: String)') && viewModel.includes('fun finishGroupVoiceRecording(groupId: String)'));
check('GroupsUi — picker wired to attachGroupImage', groupsUi.includes('rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia())') && groupsUi.includes('viewModel.attachGroupImage(groupId, it)'));
check('GroupsUi — composer attach + voice recording', groupsUi.includes('onAttach = {') && groupsUi.includes('PickVisualMedia.ImageOnly') && groupsUi.includes('onFinishRecording = { viewModel.finishGroupVoiceRecording(groupId) }') && groupsUi.includes('onStartRecording = { viewModel.startVoiceRecording() }'));
check('GroupsUi — GroupBubble renders IMAGE/VOICE via shared composables', groupsUi.includes('ImageBubbleContent(message, StringTelephoneTheme.chat') && groupsUi.includes('VoiceBubbleContent(message, StringTelephoneTheme.chat'));
check('ChatScreen — bubble composables are internal (shared with groups)', chatScreen.includes('internal fun ImageBubbleContent(') && chatScreen.includes('internal fun VoiceBubbleContent('));

// ── Tests ────────────────────────────────────────────────────────────────────

check('Integration — group media test exists with the core scenarios', groupMediaTest.includes('class GroupMediaIntegrationTest') && groupMediaTest.includes('`a member sends IMAGE and VOICE group messages that every member reads and fetches`') && groupMediaTest.includes('`group media paths reject non-members and cross-bindings`'));
check('Integration — outsider 404/403 and cross-binding 403 asserted', groupMediaTest.includes('assertEquals(404, uploadGroup(dave, groupId') && groupMediaTest.includes('assertEquals(403, server.get("/v1/media/$imageId", dave).statusCode())') && groupMediaTest.includes('type = "IMAGE", mediaId = pairwiseId).statusCode())'));
check('Integration — WS fan-out carries media fields', groupMediaTest.includes('bobEvents.next("group-message")') && groupMediaTest.includes('payload.getValue("mediaId").jsonPrimitive.content'));
check('Integration — idempotent client key resend', groupMediaTest.includes('groupMessage(alice, groupId, "img-ok", type = "IMAGE", mediaId = groupImageId)'));
check('RunningServer — postMultipartGroup helper', runningServer.includes('fun postMultipartGroup(path: String, groupId: String, fileName: String, contentType: String, bytes: ByteArray, bearer: String)'));
check('Screenshots — GROUP_CHAT_MEDIA route, fixture and capture', screenshotActivity.includes('GROUP_CHAT_MEDIA') && fakeEngine.includes('groupChatMedia: Boolean = false') && fakeEngine.includes('"group-media-image"') && screenshotTest.includes('capture("23-group-chat-media", ScreenshotRoute.GROUP_CHAT_MEDIA)'));

// ── Docs ─────────────────────────────────────────────────────────────────────

check('ADR-0012 — Phase 21 note', adr.includes('Phase 21'));
check('protocol.md — group media contract', protocol.includes('groupId') && protocol.includes('exactly one of') && protocol.includes('IMAGE') && protocol.includes('VOICE'));
check('feature-plan — Phase 21 entry', plan.includes('Phase 21'));
check('CHANGELOG — Phase 21 entry', changelog.includes('Phase 21'));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-group-media.mjs'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('group media verification passed');

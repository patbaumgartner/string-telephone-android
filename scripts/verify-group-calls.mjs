#!/usr/bin/env node
/**
 * Group-call verification (Phase 20, ADR-0014): one confbridge bridge per
 * call, created on demand over AMI and destroyed on the last leave.
 * Exits nonzero with a reason per failure; prints "group call verification
 * passed" only after every assertion passes.
 *
 * Checks:
 *  - Server: V15 schema, GroupCalls store, GroupCallService lifecycle rules
 *    (membership 404, idempotent start, all-member state fan-out, last-leave
 *    destroy), PbxGateway confbridge actions + bridge-id validation,
 *    static generic dialplan pattern, Ws fan-outs + frames, Dtos, endpoints.
 *  - Core: GroupCallInvite/GroupCallState events + parser branches, DTOs.
 *  - App: ApiClient call methods, repository ring/state surfaces + join/leave,
 *    SessionController ring notification with busy auto-decline, ViewModel
 *    join/start with mic gate + bridge dial, GroupChatScreen call button and
 *    ringing banner, group-call notification.
 *  - Tests: PbxGateway AMI lifecycle + injection, integration lifecycle test,
 *    core parser test, session controller ring tests.
 *  - Docs: ADR-0014, protocol.md contract, feature-plan status, changelog,
 *    session verification hook.
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

const migration = read('server/api/src/main/resources/db/migration/V15__group_calls.sql');
const store = read(serverDir + 'GroupCalls.kt');
const service = read(serverDir + 'GroupCallService.kt');
const pbx = read(serverDir + 'PbxGateway.kt');
const config = read(serverDir + 'AsteriskConfig.kt');
const api = read(serverDir + 'Api.kt');
const ws = read(serverDir + 'Ws.kt');
const wsFrames = read(serverDir + 'WsFrames.kt');
const dto = read(serverDir + 'Dto.kt');
const main = read(serverDir + 'Main.kt');

const coreDtos = read(coreDir + 'ApiDtos.kt');

const apiClient = read(appDir + 'network/ApiClient.kt');
const repository = read(appDir + 'data/StringTelephoneRepository.kt');
const session = read(appDir + 'session/SessionController.kt');
const viewModel = read(appDir + 'ui/AppViewModel.kt');
const groupsUi = read(appDir + 'ui/GroupsUi.kt');
const appRoot = read(appDir + 'ui/AppRoot.kt');
const callScreen = read(appDir + 'ui/CallScreen.kt');
const notifications = read(appDir + 'notify/AppNotifications.kt');
const strings = read('app/src/main/res/values/strings.xml');

const integration = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/ApiIntegrationTest.kt');
const pbxTest = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/PbxGatewayTest.kt');
const runningServer = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/RunningServer.kt');
const realtimeEventTest = read('core/src/test/kotlin/com/patbaumgartner/android/stringtelephone/core/RealtimeEventTest.kt');
const sessionTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/session/SessionControllerTest.kt');

const adr = read('docs/adr/0014-group-calls-confbridge.md');
const protocol = read('docs/protocol.md');
const plan = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');
const sessionVerify = read('scripts/run-session-verify.sh');

const fails = [];

function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

// ── Server: schema ───────────────────────────────────────────────────────────

check('V15 — group_calls table', migration.includes('CREATE TABLE group_calls') && migration.includes('bridge_id  TEXT PRIMARY KEY') && migration.includes('group_id   TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE'));
check('V15 — participants with composite key and left_at', migration.includes('CREATE TABLE group_call_participants') && migration.includes('PRIMARY KEY (bridge_id, username)') && migration.includes('left_at    BIGINT'));
check('V15 — indexes for active-call and participant lookups', migration.includes('CREATE INDEX idx_group_calls_group') && migration.includes('CREATE INDEX idx_group_call_participants_user'));

// ── Server: GroupCalls store ─────────────────────────────────────────────────

check('Store — conf- prefix is generated, never accepted from clients', store.includes('const val BRIDGE_PREFIX = "conf-"') && store.includes('fun newBridgeId(): String = BRIDGE_PREFIX + newId()'));
check('Store — open records the call and the starter as first participant', store.includes('fun open(bridgeId: String') && (store.match(/INSERT INTO group_call_participants/g) || []).length >= 2 && store.includes('VALUES(:bridgeId, :username, :joinedAt)'));
check('Store — join is re-join aware (revives a left row)', store.includes('SET left_at = NULL, joined_at = :joinedAt'));
check('Store — leave returns the remaining participants', store.includes('fun leave(bridgeId: String, username: String, now: Long): List<String>'));
check('Store — end stamps the call and every stale participant', store.includes('UPDATE group_call_participants SET left_at = :leftAt WHERE bridge_id = :id AND left_at IS NULL'));
check('Store — activeForGroup is the one-call rule', store.includes('fun activeForGroup(groupId: String): GroupCallRecord?'));

// ── Server: GroupCallService ─────────────────────────────────────────────────

check('Service — PBX first, then a narrow transaction', service.includes('accounts.withReconcileLock { pbx.openConference(bridgeId, withVideo) }') && service.includes('tx.executeWithoutResult'));
check('Service — a failed row insert destroys the bridge (no leak)', service.includes('runCatching { accounts.withReconcileLock { pbx.destroyConference(bridgeId) } }') && service.includes('throw error'));
check('Service — membership is the only authorisation (404)', service.includes('if (!withMembers.isMember(member)) throw ApiFailure(HttpStatus.NOT_FOUND, "no such group")'));
check('Service — bridge must belong to the group in the path', service.includes('if (call.groupId != groupId) throw ApiFailure(HttpStatus.NOT_FOUND, "no such call")'));
check('Service — state fans out to every group member (ring stop works)', service.includes('members = group.memberNames()') && service.includes('val members = groups.find(groupId)?.let { groups.withMembers(it).memberNames() }'));
check('Service — last leave destroys and ends', service.includes('if (remaining.isEmpty()) end(groupId, bridgeId, reason = "last-member-left", by = member)'));
check('Service — failed destroy stays visibly pending and is retried', service.includes('log.warn("Bridge {} destroy failed, retry loop will reclaim: {}"'));

// ── Server: PbxGateway + dialplan ────────────────────────────────────────────

check('Gateway — open validates only (no create action exists)', pbx.includes('override fun openConference(bridgeId: String, withVideo: Boolean): Unit = boundary {') && pbx.includes('Asterisk 22 has no\n     * create action'));
check('Gateway — destroy kicks everyone and confirms empty', pbx.includes('confbridge kick $bridgeId all') && pbx.includes('liveBridgeChannelCount(bridgeId) == 0'));
check('Gateway — members read from Confbridge channels, not a nonexistent list action', pbx.includes('frame["Application"] == "Confbridge"') && !pbx.includes('"Action" to "ConfbridgeList"'));
check('Gateway — bridge ids validated before any AMI access', pbx.includes('require(bridgeId.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid bridge id" }'));
check('Dialplan — one static generic pattern, per-call bridges', config.includes('exten => _[c][o][n][f]-.,1,Confbridge(\\${EXTEN})'));

// ── Server: REST + fan-outs ──────────────────────────────────────────────────

check('Api — start returns 201 created / 200 existing', api.includes('ResponseEntity.status(HttpStatus.CREATED).body(GroupCallDto(') && api.includes('return ResponseEntity.ok(GroupCallDto('));
check('Api — all four call paths exist', api.includes('@PostMapping("/v1/groups/{groupId}/calls")') && api.includes('@GetMapping("/v1/groups/{groupId}/calls/active")') && api.includes('@PostMapping("/v1/groups/{groupId}/calls/{bridgeId}/join")') && api.includes('@PostMapping("/v1/groups/{groupId}/calls/{bridgeId}/leave")'));
check('Api — active returns literal null when none runs', api.includes('fun activeGroupCall'));
check('Ws — invite fan-out excludes the starter', ws.includes('sessions.sendTo(members.toSet(), WsFrames.groupCallInvite(groupId, bridgeId, starter, withVideo))'));
check('Ws — state fan-out reaches every member incl. ended target', ws.includes('if (state == "ended") (members.toSet() + by) else members.toSet()'));
check('Frames — invite and state payloads', wsFrames.includes('fun groupCallInvite(groupId: String, bridgeId: String, starter: String, withVideo: Boolean)') && wsFrames.includes('fun groupCallState(groupId: String, bridgeId: String, state: String, by: String, members: List<String>, reason: String?)'));
check('Dto — call record + invite/state events', dto.includes('data class GroupCallDto(') && dto.includes('data class WsGroupCallInviteEvent(') && dto.includes('data class WsGroupCallStateEvent('));
check('Main — bean wired', main.includes('fun groupCalls(') && main.includes('GroupCallService(tx, groups, GroupCalls(jdbcClient), pbx, hub, accounts)'));

// ── Core ─────────────────────────────────────────────────────────────────────

check('core — invite + state events', coreDtos.includes('data class GroupCallInvite(') && coreDtos.includes('data class GroupCallState('));
check('core — parser branches for both frames', coreDtos.includes('"group-call-invite" -> runCatching {') && coreDtos.includes('"group-call-state" -> runCatching {'));
check('core — REST DTOs', coreDtos.includes('data class GroupCallDto(') && coreDtos.includes('data class StartGroupCallRequest('));

// ── App ──────────────────────────────────────────────────────────────────────

check('ApiClient — start/active/join/leave methods', apiClient.includes('fun startGroupCall(groupId: String, withVideo: Boolean)') && apiClient.includes('fun activeGroupCall(groupId: String)') && apiClient.includes('fun joinGroupCall(groupId: String, bridgeId: String)') && apiClient.includes('fun leaveGroupCall(groupId: String, bridgeId: String)'));
check('Repository — ring + state surfaces', repository.includes('val groupCallRing: SharedFlow<RealtimeEvent.GroupCallInvite>') && repository.includes('val groupCallState: SharedFlow<RealtimeEvent.GroupCallState>'));
check('Repository — start/join/leave passthroughs', repository.includes('fun startGroupCall(groupId: String, withVideo: Boolean, onReady: (GroupCallDto?) -> Unit)') && repository.includes('fun joinGroupCall(groupId: String, bridgeId: String)') && repository.includes('fun leaveGroupCall(groupId: String, bridgeId: String)'));
check('Session — ring sets state and notifies when hidden', session.includes('private fun onGroupCallRing(invite: RealtimeEvent.GroupCallInvite)') && session.includes('AppNotifier.notifyGroupCall(context, invite.groupId, display, invite.starter, invite.withVideo)'));
check('Session — busy devices auto-decline silently', session.includes('if (engine.state.value.state != CallState.IDLE) return'));
check('Session — first answer/ended stops the ring', session.includes('private fun onGroupCallState(event: RealtimeEvent.GroupCallState)') && session.includes('AppNotifier.cancelIncomingCall(context)'));
check('ViewModel — join dials the bridge after navigation', viewModel.includes('_screen.value = AppScreen.Call(invite.bridgeId, incoming = false)') && viewModel.includes('engine.call(remoteUser = invite.bridgeId, withVideo = invite.withVideo)'));
check('ViewModel — start navigates then dials', viewModel.includes('fun startGroupCall(groupId: String, withVideo: Boolean)') && viewModel.includes('engine.call(remoteUser = call.bridgeId, withVideo = withVideo)'));
check('ViewModel — hangUp records the group leave', viewModel.includes('activeGroupCall?.let { repository.leaveGroupCall(it.groupId, it.bridgeId) }'));
check('ViewModel — call screen titles group calls by name', viewModel.includes('fun callTitleOf(peer: String, contacts: List<ContactDto>): String'));
check('GroupsUi — call button with microphone gate', groupsUi.includes('requestMicrophone { viewModel.startGroupCall(groupId, withVideo = false) }'));
check('GroupsUi — ringing banner with join/dismiss', groupsUi.includes('internal fun GroupCallRingingBanner(') && groupsUi.includes('viewModel.joinGroupCall(ring)') && groupsUi.includes('viewModel.dismissGroupCallRing(ring)'));
check('AppRoot — pendingGroupAnswer passes the microphone gate', appRoot.includes('viewModel.consumePendingGroupAnswer()') && appRoot.includes('requestMicrophone { viewModel.joinGroupCall(ring) }'));
check('CallScreen — group calls show the group name', callScreen.includes('viewModel.callTitleOf(peer, contactsState.contacts)'));
check('Notifications — dedicated group-call notification', notifications.includes('fun notifyGroupCall(context: Context, groupId: String, displayGroup: String, starter: String, withVideo: Boolean = false)') && notifications.includes('R.string.notification_group_call_title'));
check('strings — group-call labels', strings.includes('name="call_group_start_voice"') && strings.includes('name="call_group_join"') && strings.includes('name="call_group_ring_from"') && strings.includes('name="notification_group_call_title"'));

// ── Tests ────────────────────────────────────────────────────────────────────

check('Integration — full lifecycle test', integration.includes('`group calls bridge one active call per group with invite fan out and end on last leave`'));
check('Integration — duplicate start is idempotent 200', integration.includes('a second starter gets the running call') && integration.includes('assertEquals(200, duplicate.statusCode()'));
check('Integration — outsider 404 on every call path', (integration.match(/calls\/\$bridgeId\/(?:join|leave)", "", outsider/g) || []).length === 2 && integration.includes('"/v1/groups/$groupId/calls", "{}", outsider'));
check('PbxGateway — AMI lifecycle tested', pbxTest.includes('`confbridge lifecycle verifies kick and members over the AMI fixture`') && pbxTest.includes('`an unknown bridge reads as no members instead of a PBX error`') && pbxTest.includes('`a bridge id with AMI metacharacters is rejected before any network access`'));
check('PbxGateway — dialplan pattern tested', pbxTest.includes('`dialplan gains one generic confbridge pattern independent of groups`'));
check('FakePbx — conferences map for tests', runningServer.includes('val conferences = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()'));
check('core — group-call parsing tested', realtimeEventTest.includes('`parses group call frames into their events`'));
check('Session — ring/busy/ended tested', sessionTest.includes('`group call invite rings through a notification while the app is closed`') && sessionTest.includes('`a busy device does not ring for a group call`') && sessionTest.includes('`the ended fan-out clears a ring that no answer stopped`'));

// ── Docs ─────────────────────────────────────────────────────────────────────

check('ADR — 0014 exists with decisions', adr.includes('# ADR-0014: Group calls') && adr.includes('Any member may start') && adr.includes('One bridge per call') && adr.includes('static and generic'));
check('protocol.md — call endpoints documented', protocol.includes('POST /v1/groups/{groupId}/calls') && protocol.includes('calls/{bridgeId}/join') && protocol.includes('reason:"last-member-left"'));
check('protocol.md — both frames documented', protocol.includes('"group-call-invite"') && protocol.includes('"group-call-state"'));
check('protocol.md — media path note', protocol.includes('dial a confbridge bridge') && protocol.includes('_[c][o][n][f]-.'));
check('feature-plan — Phase 20 entry', plan.includes('Phase 20 — Group calls (confbridge)') && plan.includes('Any member may start a call') && plan.includes('Ring all at once'));
check('CHANGELOG — Phase 20 entry', changelog.includes('Group calls (Phase 20)'));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-group-calls.mjs'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('group call verification passed');

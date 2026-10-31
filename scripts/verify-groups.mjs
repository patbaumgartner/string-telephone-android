#!/usr/bin/env node
/**
 * Group-chat verification (Phase 19, ADR-0012): membership in its own
 * tables, group messages addressed by group id, per-member read receipts,
 * and the group surfaces on REST, websocket and app. Exits nonzero with a
 * reason per failure; prints "groups verification passed" only after every
 * assertion passes.
 *
 * Checks:
 *  - Server: V14 tables + partial idempotency indexes, Groups store
 *    (admin rules, member cap, disband), group REST endpoints (membership
 *    guard, non-members 404), group send/history/read/reactions/delete,
 *    pairwise endpoints filter group rows, WsHub group fan-outs.
 *  - Core: GroupDto/GroupMemberDto, group requests, groupId/readCount on
 *    messages, RealtimeEvent.Group* variants with `reader` on reads.
 *  - App: ApiClient group methods, repository group state/events/send/read,
 *    ChatCache per-member reads, ViewModel + GroupsUi surfaces.
 *  - Notifications: group intent flag on content/quick-reply/mark-read.
 *  - Tests: server integration group suites, core group frame parsing,
 *    screenshot captures 20-groups/21-group-chat.
 *  - Docs: protocol.md group contract, feature-plan Phase 19 done,
 *    changelog entry, run-session-verify hook.
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

const migration = read('server/api/src/main/resources/db/migration/V14__group_chats.sql');
const groupsStore = read(serverDir + 'Groups.kt');
const api = read(serverDir + 'Api.kt');
const main = read(serverDir + 'Main.kt');
const dto = read(serverDir + 'Dto.kt');
const messages = read(serverDir + 'Messages.kt');
const reactions = read(serverDir + 'Reactions.kt');
const ws = read(serverDir + 'Ws.kt');
const wsFrames = read(serverDir + 'WsFrames.kt');
const security = read(serverDir + 'Security.kt');

const coreDtos = read(coreDir + 'ApiDtos.kt');

const apiClient = read(appDir + 'network/ApiClient.kt');
const repository = read(appDir + 'data/StringTelephoneRepository.kt');
const chatCache = read(appDir + 'chat/ChatCache.kt');
const viewModel = read(appDir + 'ui/AppViewModel.kt');
const groupsUi = read(appDir + 'ui/GroupsUi.kt');
const appRoot = read(appDir + 'ui/AppRoot.kt');
const contactsScreen = read(appDir + 'ui/ContactsScreen.kt');
const notifications = read(appDir + 'notify/AppNotifications.kt');
const actionReceiver = read(appDir + 'notify/MessageActionReceiver.kt');
const mainActivity = read(appDir + 'ui/MainActivity.kt');
const strings = read('app/src/main/res/values/strings.xml');

const integration = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/ApiIntegrationTest.kt');
const realtimeEventTest = read('core/src/test/kotlin/com/patbaumgartner/android/stringtelephone/core/RealtimeEventTest.kt');
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

// ── Server: schema ───────────────────────────────────────────────────────────

check('V14 — groups table', migration.includes('CREATE TABLE groups') && migration.includes('creator    TEXT NOT NULL REFERENCES users(username)'));
check('V14 — group_members with role CHECK', migration.includes('CREATE TABLE group_members') && migration.includes("CHECK (role IN ('ADMIN', 'MEMBER'))") && migration.includes('PRIMARY KEY (group_id, username)'));
check('V14 — messages.group_id FK, no CHECK overloads the message shape', migration.includes('ALTER TABLE messages ADD COLUMN group_id TEXT REFERENCES groups(id) ON DELETE CASCADE') && !/CHECK\s*\([^)]*group_id/.test(migration));
check('V14 — partial idempotency indexes per kind', migration.includes('CREATE UNIQUE INDEX idx_messages_pair_client_key ON messages(sender, recipient, client_key) WHERE group_id IS NULL') && migration.includes('CREATE UNIQUE INDEX idx_messages_group_client_key ON messages(sender, group_id, client_key) WHERE group_id IS NOT NULL'));
check('V14 — group_message_reads per-member reads', migration.includes('CREATE TABLE group_message_reads') && migration.includes('PRIMARY KEY (message_id, username)'));

// ── Server: Groups store ─────────────────────────────────────────────────────

check('Store — creator becomes sole ADMIN in one transaction path', groupsStore.includes("VALUES(:groupId, :username, 'ADMIN', :joinedAt)"));
check('Store — name validated (blank, max 64)', groupsStore.includes('MAX_NAME = 64') && groupsStore.includes('name must not be blank'));
check('Store — member cap 32 with require', groupsStore.includes('MAX_MEMBERS = 32') && groupsStore.includes('at most $MAX_MEMBERS members'));
check('Store — idempotent add (existing member no-op)', groupsStore.includes('if (member(groupId, username) != null) return members(groupId)'));
check('Store — admin removes others, member removes self, admin cannot leave', groupsStore.includes('require(target == acting || actor.role == "ADMIN")') && groupsStore.includes('the admin disbands instead of leaving'));
check('Store — non-member removal returns null (404 upstream)', groupsStore.includes('val actor = member(groupId, acting) ?: return null'));
check('Store — disband deletes the group row (membership/messages cascade)', groupsStore.includes('DELETE FROM groups WHERE id = :id'));
check('Store — live membership fan-out helper', groupsStore.includes('fun memberNames(groupId: String): List<String>'));

// ── Server: REST endpoints ───────────────────────────────────────────────────

check('Api — POST /v1/groups creates with creator admin, 201', api.includes('@PostMapping("/v1/groups")') && api.includes('ResponseEntity.status(HttpStatus.CREATED).body(groupDto('));
check('Api — GET /v1/groups lists own groups', api.includes('@GetMapping("/v1/groups")') && api.includes('groups.groupsOf(me.username).map { groupDto(it) }'));
check('Api — membership guard: non-members get 404, not 403', api.includes('if (!withMembers.isMember(me.username)) throw ApiFailure(HttpStatus.NOT_FOUND, "no such group")'));
check('Api — rename ADMIN only', api.includes('if (!group.isAdmin(me.username)) throw ApiFailure(HttpStatus.FORBIDDEN, "only the admin may rename")'));
check('Api — add member ADMIN only, unknown user 404', api.includes('if (!group.isAdmin(me.username)) throw ApiFailure(HttpStatus.FORBIDDEN, "only the admin may add members")') && api.includes('if (users.find(body.username)?.active != true) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")'));
check('Api — remove member: admin removes others / member leaves', api.includes('groups.removeMember(groupId, me.username, username)') && api.includes('"not allowed to remove this member"'));
check('Api — disband ADMIN only', api.includes('if (!group.isAdmin(me.username)) throw ApiFailure(HttpStatus.FORBIDDEN, "only the admin may disband")') && api.includes('groups.disband(groupId)'));
check('Api — group send derives authorship, 413 on empty/oversize', api.includes('fun sendGroupMessage') && api.includes('HttpStatus.CONTENT_TOO_LARGE') && api.includes('messages.insertGroupText(me.username, groupId, body.content, body.clientKey, replyToId)'));
check('Api — group send validates the reply target belongs to the group', api.includes('if (replyMsg.groupId != groupId) throw ApiFailure(HttpStatus.BAD_REQUEST, "reply target not in this group")'));
check('Api — group history paginates with read counts', api.includes('fun groupMessages') && api.includes('messages.groupReadCounts(messageIds)') && api.includes('GroupMessagesPageDto('));
check('Api — group read marks and fans out', api.includes('fun markGroupRead') && api.includes('messages.markGroupRead(groupId, me.username, upTo)') && api.includes('hub.broadcastGroupRead(groupId, me.username, group.memberNames(), mark)'));
check('Api — group read: bad or foreign upToMessageId rejected', api.includes('"upToMessageId not in this group"'));
check('Api — group reactions guarded, member-only', api.includes('ensureGroupMessage(groupId, messageId)') && api.includes('reactions.addGroupReaction(messageId, me.username, body.emoji)') && api.includes('GroupMessageAccessException'));
check('Api — group delete allowed for author or admin', api.includes('val allowed = message.sender == me.username || group.isAdmin(me.username)') && api.includes('only the author or the admin may delete'));
check('Api — pairwise endpoints reject group rows (404)', api.includes('if (message.groupId != null || (message.sender != peer && message.recipient != peer))') && api.includes('private fun ensurePairwiseMessage'));
check('Api — pairwise history filters group rows', messages.includes('WHERE group_id IS NULL AND ((sender = ? AND recipient = ?)'));
check('Api — security matcher covers /v1/groups/**', security.includes('"/v1/groups/**"'));

// ── Server: store + fan-out ──────────────────────────────────────────────────

check('Messages — group insert keeps recipient empty, binds group id', messages.includes("INSERT INTO messages(id, sender, recipient, content, created_at, client_key, type, reply_to, group_id)") && messages.includes("VALUES(:id, :sender, '', :content, :createdAt, :clientKey, 'TEXT', :replyTo, :groupId)"));
check('Messages — group idempotency scope (sender, group, clientKey)', messages.includes('WHERE sender = ? AND group_id = ? AND client_key = ?'));
check('Messages — markGroupRead never marks own messages', messages.includes('WHERE group_id = ? AND sender <> ? AND deleted_at IS NULL'));
check('Messages — groupReadCounts per message', messages.includes('SELECT message_id, COUNT(*) AS readers FROM group_message_reads'));
check('Reactions — group membership enforced, 404 via exception', reactions.includes('class GroupMessageAccessException') && reactions.includes('if (!groups.isMember(groupId, author)) throw GroupMessageAccessException()'));
check('WsHub — group message fan-out to members', ws.includes('fun broadcastGroupMessage') && ws.includes('sessions.sendTo(members.toSet(), WsFrames.groupNewMessage(message))'));
check('WsHub — group read fan-out carries the reader', ws.includes('fun broadcastGroupRead(groupId: String, reader: String, members: List<String>, mark: ReadMark)'));
check('WsHub — group reaction/deletion fan-outs', ws.includes('WsFrames.groupReaction(message, author, emoji, added)') && ws.includes('WsFrames.groupMessageDeleted(message)'));
check('WsHub — group typing excludes the composer', ws.includes('sessions.sendTo((members.toSet() - from), WsFrames.groupTyping(groupId, from, typing))'));
check('WsFrames — group frames and reader field', wsFrames.includes('"group-message"') && wsFrames.includes('"group-message-read"') && wsFrames.includes('WsGroupMessageReadEvent(groupId, reader, mark.messageIds, mark.readAt)') && wsFrames.includes('"group-reaction"') && wsFrames.includes('"group-deleted"') && wsFrames.includes('WsTypingEvent(from, typing, groupId)'));
check('Main — send-path fan-out routes group rows by membership', main.includes('if (stored.groupId == null)') && main.includes('hub.broadcastGroupMessage(stored, groups.memberNames(stored.groupId))'));
check('Main — incoming group message un-archives for every member but the author', main.includes('for (member in groups.memberNames(stored.groupId))') && main.includes('if (member != stored.sender) conversationStates.unarchive(member, stored.sender)'));
check('Main — groups bean wired', main.includes('fun groups(jdbcClient: JdbcClient) = Groups(jdbcClient)'));

// ── Core ─────────────────────────────────────────────────────────────────────

check('core GroupDto + GroupMemberDto', coreDtos.includes('data class GroupDto(') && coreDtos.includes('data class GroupMemberDto('));
check('core group requests', coreDtos.includes('data class CreateGroupRequest(') && coreDtos.includes('data class RenameGroupRequest(') && coreDtos.includes('data class AddGroupMemberRequest(') && coreDtos.includes('data class SendGroupMessageRequest(') && coreDtos.includes('data class MarkReadRequest('));
check('core groupId/readCount on MessageDto', coreDtos.includes('val groupId: String? = null,') && coreDtos.includes('val readCount: Int? = null,'));
check('core WsGroupMessageReadDto carries the reader', coreDtos.includes('data class WsGroupMessageReadDto(') && coreDtos.includes('val reader: String,'));
check('core RealtimeEvent.Group* variants', coreDtos.includes('data class GroupMessage(') && coreDtos.includes('data class GroupMessageRead(') && coreDtos.includes('data class GroupMessageReaction(') && coreDtos.includes('data class GroupMessageDeleted(') && coreDtos.includes('data class GroupTyping('));
check('core parser routes group frames', coreDtos.includes('"group-message" ->') && coreDtos.includes('"group-message-read" ->') && coreDtos.includes('"group-reaction" ->') && coreDtos.includes('"group-deleted" ->'));
check('core typing payload gains groupId', coreDtos.includes('data class WsTypingDto(val peer: String, val typing: Boolean, val groupId: String? = null)'));

// ── App wire + state ─────────────────────────────────────────────────────────

check('ApiClient — group management methods', apiClient.includes('fun groups(): List<GroupDto>') && apiClient.includes('fun createGroup(name: String, members: List<String>): GroupDto') && apiClient.includes('fun groupDetails(groupId: String): GroupDto') && apiClient.includes('fun renameGroup(groupId: String, name: String): GroupDto') && apiClient.includes('fun addGroupMember(groupId: String, username: String): GroupDto') && apiClient.includes('fun removeGroupMember(groupId: String, username: String): GroupDto') && apiClient.includes('fun disbandGroup(groupId: String)'));
check('ApiClient — group messaging methods', apiClient.includes('fun sendGroupMessage(') && apiClient.includes('/v1/groups/$groupId/messages') && apiClient.includes('fun markGroupRead(') && apiClient.includes('/v1/groups/$groupId/read') && apiClient.includes('fun setGroupReaction(groupId: String, messageId: String, emoji: String)') && apiClient.includes('fun removeGroupReaction(groupId: String, messageId: String)') && apiClient.includes('fun deleteGroupMessage(groupId: String, messageId: String)'));
check('ApiClient — non-member 404 maps to Protocol error', apiClient.includes('r.code == 404 -> throw ApiException.Protocol("no such group")'));
check('Repository — group state flows', repository.includes('private val _groups = MutableStateFlow<List<GroupDto>>(emptyList())') && repository.includes('val groups: StateFlow<List<GroupDto>> = _groups.asStateFlow()') && repository.includes('val groupTyping: StateFlow<Map<String, Set<String>>> = _groupTyping.asStateFlow()'));
check('Repository — realtime group events dispatched', repository.includes('is RealtimeEvent.GroupMessage -> onGroupMessage(event)') && repository.includes('is RealtimeEvent.GroupMessageRead -> onGroupMessageRead(event)') && repository.includes('is RealtimeEvent.GroupMessageReaction -> onGroupMessageReaction(event)') && repository.includes('is RealtimeEvent.GroupMessageDeleted -> onGroupMessageDeleted(event)') && repository.includes('is RealtimeEvent.GroupTyping -> onGroupTyping(event)'));
check('Repository — group events ignore non-member groups', repository.includes('if (!groups.value.any { it.id == event.groupId }) return'));
check('Repository — incoming group message emits with sender + groupId', repository.includes('_incoming.tryEmit(IncomingMessage(key, event.content, event.createdAtEpochMs, sender = event.sender, groupId = event.groupId))'));
check('Repository — group read converges via applyGroupRead', repository.includes('chatCache.applyGroupRead(event.groupId, event.reader, event.messageIds.toSet(), event.readAtEpochMs)'));
check('Repository — refreshGroups reloads from the server', repository.includes('fun refreshGroups()') && repository.includes('val list = withContext(Dispatchers.IO) { client.groups() }'));
check('Repository — createGroup prepends and opens', repository.includes('fun createGroup(name: String, members: List<String>, onResult: (GroupDto?) -> Unit)') && repository.includes('_groups.value = listOf(created) + _groups.value'));
check('Repository — rename/add/remove update the list in place', repository.includes('fun renameGroup(groupId: String, name: String, onResult: (GroupDto?) -> Unit)') && repository.includes('fun addGroupMember(groupId: String, username: String, onResult: (GroupDto?) -> Unit)') && repository.includes('fun removeGroupMember(groupId: String, username: String, onResult: (GroupDto?) -> Unit)') && repository.includes('private fun replaceGroup(updated: GroupDto)'));
check('Repository — disband reports and drops the group', repository.includes('fun disbandGroup(groupId: String, onResult: (Boolean) -> Unit)'));
check('Repository — group history, send, read, reaction paths', repository.includes('fun loadGroupHistory(groupId: String, myUsername: String)') && repository.includes('fun sendGroup(groupId: String, content: String, replyToId: String? = null, onResult: (Boolean) -> Unit)') && repository.includes('fun markGroupConversationRead(groupId: String, upToMessageId: String?)') && repository.includes('fun toggleGroupReaction(groupId: String, message: ChatMessage, emoji: String)'));
check('Repository — group typing notify/stop', repository.includes('fun notifyGroupTyping(groupId: String)') && repository.includes('fun sendGroupTypingStop(groupId: String)'));
check('ChatCache — per-member group read convergence', chatCache.includes('fun applyGroupRead(groupId: String, reader: String, messageIds: Set<String>, readAtEpochMs: Long): String?'));
check('ChatCache — messages carry sender + groupId + readers', chatCache.includes('val sender: String = "",') && chatCache.includes('val groupId: String? = null,') && chatCache.includes('val readBy: Set<String> = emptySet(),'));
check('RealtimeClient — group typing client frame', read(appDir + 'network/RealtimeClient.kt').includes('"""{"type":"typing","payload":{"groupId":"$groupId","typing":$typing}}"""') || read(appDir + 'network/RealtimeClient.kt').includes('sendGroupTyping(groupId: String, typing: Boolean)'));

// ── ViewModel + UI ───────────────────────────────────────────────────────────

check('ViewModel — Groups screens in navigation', viewModel.includes('data object Groups : AppScreen') && viewModel.includes('data class GroupChat(val groupId: String) : AppScreen') && viewModel.includes('data class GroupDetail(val groupId: String) : AppScreen'));
check('ViewModel — handleLaunchAction routes group opens', viewModel.includes('fun handleLaunchAction(action: String?, peer: String?, isGroup: Boolean = false)') && viewModel.includes('if (isGroup) openGroupChat(peer) else openChat(peer)'));
check('ViewModel — group surface methods', viewModel.includes('fun groupsState(): StateFlow<List<GroupDto>>') && viewModel.includes('fun groupTypingState(): StateFlow<Map<String, Set<String>>>') && viewModel.includes('fun groupById(groupId: String): GroupDto?'));
check('ViewModel — group management delegated', viewModel.includes('fun createGroup(name: String, members: List<String>, onResult: (GroupDto?) -> Unit)') && viewModel.includes('fun renameGroup(groupId: String, name: String, onResult: (GroupDto?) -> Unit)') && viewModel.includes('fun addGroupMember(groupId: String, username: String, onResult: (GroupDto?) -> Unit)') && viewModel.includes('fun removeGroupMember(groupId: String, username: String, onResult: (GroupDto?) -> Unit)') && viewModel.includes('fun disbandGroup(groupId: String, onResult: (Boolean) -> Unit)'));
check('ViewModel — openGroupChat loads history and restores the draft', viewModel.includes('fun openGroupChat(groupId: String)') && viewModel.includes('repository.loadGroupHistory(groupId, me.username)'));
check('ViewModel — group send/read/typing mirror the pairwise composer', viewModel.includes('fun sendGroupMessage(groupId: String, content: String)') && viewModel.includes('fun markGroupConversationRead(groupId: String, upToMessageId: String?)') && viewModel.includes('fun onGroupChatInputChanged(groupId: String, value: String)') && viewModel.includes('fun toggleGroupReaction(groupId: String, message: ChatMessage, emoji: String)'));
check('GroupsUi — list screen with create dialog', groupsUi.includes('internal fun GroupsScreen(viewModel: AppViewModel)') && groupsUi.includes('internal fun CreateGroupDialog(') && groupsUi.includes('R.string.groups_create_cd'));
check('GroupsUi — rows show member count and unread badge', groupsUi.includes('internal fun GroupRow(') && groupsUi.includes('unreadCount = unread[group.id] ?: 0') && groupsUi.includes('internal fun GroupAvatar('));
check('GroupsUi — detail screen with admin actions', groupsUi.includes('internal fun GroupDetailScreen(') && groupsUi.includes('R.string.groups_disband') && groupsUi.includes('R.string.groups_leave') && groupsUi.includes('R.string.groups_rename') && groupsUi.includes('R.string.groups_add_member'));
check('GroupsUi — group chat screen with sender names and read counter', groupsUi.includes('internal fun GroupChatScreen(') && groupsUi.includes('viewModel.markGroupConversationRead(groupId, id)') && groupsUi.includes('onSend = { viewModel.sendGroupMessage(groupId, viewModel.chatInput) }'));
check('AppRoot — group screens routed', appRoot.includes('is AppScreen.Groups -> GroupsScreen(viewModel)') && appRoot.includes('is AppScreen.GroupDetail -> GroupDetailScreen(') && appRoot.includes('is AppScreen.GroupChat -> GroupChatScreen('));
check('Contacts — menu entry opens the group list', contactsScreen.includes('R.string.groups_title') && contactsScreen.includes('viewModel.openGroups()'));
check('strings — group UI labels', strings.includes('name="groups_title"') && strings.includes('name="groups_create_cd"') && strings.includes('name="groups_empty_hint"') && strings.includes('name="groups_disband_confirm_body"') && strings.includes('name="groups_role_admin"') && strings.includes('name="groups_member_count"'));

// ── Notifications ────────────────────────────────────────────────────────────

check('Notifications — group flag drives the content intent', notifications.includes('val groupSenders = senderNames != null') && notifications.includes('.putExtra(MainActivity.EXTRA_IS_GROUP, isGroup)') && notifications.includes('activityIntent(context, MainActivity.ACTION_OPEN_CHAT, peer, peer.hashCode(), groupSenders)'));
check('Notifications — quick reply + mark read receive the group flag', notifications.includes('MessageActionReceiver.quickReplyIntent(context, peer, groupSenders)') && notifications.includes('MessageActionReceiver.markReadIntent(context, peer, groupSenders)'));
check('MessageActionReceiver — routes group quick replies and marks', actionReceiver.includes('val isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)') && actionReceiver.includes('if (isGroup) container.repository.sendGroup(target, body) { onResult(it) } else container.repository.send(target, body) { onResult(it) }') && actionReceiver.includes('if (isGroup) container.repository.markGroupConversationRead(peer, null) else container.repository.markConversationRead(peer, null)'));
check('MessageActionReceiver — intents carry EXTRA_IS_GROUP', actionReceiver.includes('const val EXTRA_IS_GROUP      = "is_group"') && actionReceiver.includes('fun quickReplyIntent(context: Context, peer: String, isGroup: Boolean = false)') && actionReceiver.includes('fun markReadIntent(context: Context, peer: String, isGroup: Boolean = false)'));
check('MainActivity — launch reads the group flag', mainActivity.includes('const val EXTRA_IS_GROUP = "is_group"') && mainActivity.includes('intent?.getBooleanExtra(EXTRA_IS_GROUP, false) ?: false'));

// ── Tests ────────────────────────────────────────────────────────────────────

check('Integration — group lifecycle suite', integration.includes('`group lifecycle covers create list rename membership and disband with admin-only powers`'));
check('Integration — group messages + per-member receipts', integration.includes('`group messages send read and paginate like pairwise with per-member receipts`'));
check('Integration — group websocket fan-out', integration.includes('`group message fan-out reaches every member over the websocket with group frames`'));
check('Integration — group reactions/deletion, pairwise rejection', integration.includes('`group reactions and deletion fan out and pairwise endpoints reject group rows`'));
check('core — group frame parsing tested', realtimeEventTest.includes('`parses group frames into their events`') && realtimeEventTest.includes('`group frames without a groupId are dropped instead of misrouted`'));
check('Screenshot — group captures exist', screenshotTest.includes('capture("20-groups", ScreenshotRoute.GROUPS)') && screenshotTest.includes('capture("21-group-chat", ScreenshotRoute.GROUP_CHAT)') && screenshotTest.includes('ScreenshotRoute.entries.filter { it != ScreenshotRoute.CHAT_IMAGE }'));
check('Screenshot — routes + fixtures exist', screenshotActivity.includes('ScreenshotRoute.GROUPS') && screenshotActivity.includes('GroupChatScreen("grp-hike", viewModel)') && previewFactory.includes('PREVIEW_GROUPS') && previewFactory.includes('groups = if (groupChat) PREVIEW_GROUPS else emptyList()'));

// ── Docs ─────────────────────────────────────────────────────────────────────

check('protocol.md — group management endpoints documented', protocol.includes('POST /v1/groups') && protocol.includes('GET /v1/groups/{groupId}') && protocol.includes('DELETE /v1/groups/{groupId}/members/{username}') && protocol.includes('ADMIN only'));
check('protocol.md — group messaging contract documented', protocol.includes('POST /v1/groups/{groupId}/messages') && protocol.includes('POST /v1/groups/{groupId}/read') && protocol.includes('group_message_reads'));
check('protocol.md — group websocket frames documented with reader', protocol.includes('"group-message"') && protocol.includes('"group-message-read"') && protocol.includes('"group-reaction"') && protocol.includes('"group-deleted"') && protocol.includes('reader'));
check('protocol.md — group typing variant documented', protocol.includes('{"groupId":"grp-uuid"'));
check('feature-plan — Phase 19 done with shipped paragraph', plan.includes('**Status:** Done (2026-10-31)') && plan.includes('V14__group_chats.sql') && plan.includes('Shipped: V14 migration'));
check('feature-plan — Phase 20 group calls entry exists', plan.includes('Phase 20 — Group calls') && plan.includes('confbridge'));
check('CHANGELOG — Phase 19 entry', changelog.includes('Group chats (Phase 19)') && !changelog.includes('applyGroupRead') && changelog.includes('reader'));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-groups.mjs'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('groups verification passed');

package com.patbaumgartner.stringtelephone

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

fun bearerToken(header: String?): String? =
    header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

class ApiFailure(val status: HttpStatus, message: String) : RuntimeException(message)

@RestController
class ApiController(
    private val users: Users,
    private val messages: Messages,
    private val presence: Presence,
    private val healthProbe: HealthProbe,
    private val rateLimiter: RateLimiter,
    private val hub: WsHub,
    private val media: MediaService,
    private val reactions: Reactions,
    private val conversationStates: ConversationStates,
    private val groups: Groups,
) {
    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")

    /**
     * Liveness plus database reachability, the same probe as `/actuator/health`
     * below. Returns 503 when PostgreSQL is unreachable, so uptime checks and
     * rolling deployments never route to an instance whose DataSource is dead.
     */
    @GetMapping("/ready")
    fun ready(): ResponseEntity<Map<String, String>> =
        if (healthProbe.isReachable()) {
            ResponseEntity.ok(mapOf("status" to "ok", "database" to "up"))
        } else {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("status" to "unavailable", "database" to "down"))
        }

    private fun principal(request: HttpServletRequest, attemptKey: String? = null): UserRecord {
        val token = bearerToken(request.getHeader("Authorization"))
        if (attemptKey != null && !rateLimiter.allow(attemptKey)) {
            throw RateLimited("Too many authentication attempts; retry in a minute")
        }
        return token?.let(users::findByToken)
            ?: throw ApiFailure(HttpStatus.UNAUTHORIZED, "unauthorized")
    }

    @GetMapping("/v1/me")
    fun me(request: HttpServletRequest): MeDto =
        principal(request, attemptKey = "me:" + remoteAddressOf(request.remoteAddr))
            .let { MeDto(it.username, it.displayName) }

    @GetMapping("/v1/contacts")
    fun contacts(request: HttpServletRequest): ContactsDto {
        val me = principal(request)
        val contacts = users.list().filter { it.active && it.username != me.username }
        val states = presence.all(contacts.map { it.username })
        val conversationState = conversationStates.all(me.username).associateBy { it.peer }
        return ContactsDto(contacts.map {
            val state = states.getValue(it.username)
            val own = conversationState[it.username]
            ContactDto(
                it.username, it.displayName, state.presence, state.observedAtEpochMs, state.lastSeenAtEpochMs,
                own?.pinned ?: false, own?.muted ?: false, own?.archived ?: false,
            )
        })
    }

    // ── Conversation state (Phase 18: pin/mute/archive) ─────────────────────

    /**
     * Reads the caller's state for one conversation. Absent state is all
     * defaults (`false`), so a fresh conversation needs no row.
     */
    @GetMapping("/v1/conversations/{peer}/state")
    fun conversationState(request: HttpServletRequest, @PathVariable peer: String): ConversationStateDto {
        val me = principal(request)
        if (users.find(peer) == null) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")
        val record = conversationStates.find(me.username, peer)
        return ConversationStateDto(
            record?.pinned ?: false,
            record?.muted ?: false,
            record?.archived ?: false,
        )
    }

    /**
     * Updates the caller's state for one conversation (Phase 18): pin,
     * mute, archive — any subset, one endpoint. The server derives the user
     * from the authenticated principal; the peer must exist. Pinning beyond
     * [ConversationStates.MAX_PINNED] is a `400`.
     */
    @PutMapping("/v1/conversations/{peer}/state")
    fun updateConversationState(
        request: HttpServletRequest,
        @PathVariable peer: String,
        @RequestBody body: ConversationStateUpdate,
    ): ConversationStateDto {
        val me = principal(request)
        if (peer == me.username) throw ApiFailure(HttpStatus.BAD_REQUEST, "cannot manage yourself")
        if (users.find(peer)?.active != true) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")
        val record = try {
            conversationStates.update(me.username, peer, body)
        } catch (_: IllegalArgumentException) {
            throw ApiFailure(HttpStatus.BAD_REQUEST, "too many pinned conversations")
        }
        return ConversationStateDto(record.pinned, record.muted, record.archived)
    }

    // ── Groups (Phase 19, ADR-0012) ─────────────────────────────────────────

    private fun groupDto(withMembers: GroupWithMembers) = GroupDto(
        withMembers.group.id, withMembers.group.name, withMembers.group.creator,
        withMembers.group.createdAt, withMembers.group.updatedAt,
        withMembers.members.map { GroupMemberDto(it.username, it.role, it.joinedAt) },
    )

    /** Loads a group and checks the caller's membership; non-members see 404. */
    private fun memberGroup(request: HttpServletRequest, groupId: String): Pair<UserRecord, GroupWithMembers> {
        val me = principal(request)
        val group = groups.find(groupId) ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such group")
        val withMembers = groups.withMembers(group)
        if (!withMembers.isMember(me.username)) throw ApiFailure(HttpStatus.NOT_FOUND, "no such group")
        return me to withMembers
    }

    @PostMapping("/v1/groups")
    fun createGroup(request: HttpServletRequest, @RequestBody body: CreateGroupRequest): ResponseEntity<GroupDto> {
        val me = principal(request)
        val members = body.members.distinct().filter { it != me.username }
        for (member in members) {
            if (users.find(member)?.active != true) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user: $member")
        }
        val created = try {
            groups.create(body.name, me.username)
        } catch (_: IllegalArgumentException) {
            throw ApiFailure(HttpStatus.BAD_REQUEST, "invalid group name")
        }
        for (member in members) {
            try {
                groups.addMember(created.group.id, member)
            } catch (_: IllegalArgumentException) {
                throw ApiFailure(HttpStatus.BAD_REQUEST, "too many members")
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(groupDto(groups.withMembers(created.group)))
    }

    @GetMapping("/v1/groups")
    fun myGroups(request: HttpServletRequest): List<GroupDto> {
        val me = principal(request)
        return groups.groupsOf(me.username).map { groupDto(it) }
    }

    @GetMapping("/v1/groups/{groupId}")
    fun groupDetails(request: HttpServletRequest, @PathVariable groupId: String): GroupDto {
        val (_, group) = memberGroup(request, groupId)
        return groupDto(group)
    }

    @PutMapping("/v1/groups/{groupId}")
    fun renameGroup(request: HttpServletRequest, @PathVariable groupId: String, @RequestBody body: RenameGroupRequest): GroupDto {
        val (me, group) = memberGroup(request, groupId)
        if (!group.isAdmin(me.username)) throw ApiFailure(HttpStatus.FORBIDDEN, "only the admin may rename")
        val renamed = try {
            groups.rename(groupId, body.name)
        } catch (_: IllegalArgumentException) {
            throw ApiFailure(HttpStatus.BAD_REQUEST, "invalid group name")
        }
        return groupDto(groups.withMembers(renamed))
    }

    @PostMapping("/v1/groups/{groupId}/members")
    fun addGroupMember(request: HttpServletRequest, @PathVariable groupId: String, @RequestBody body: AddGroupMemberRequest): GroupDto {
        val (me, group) = memberGroup(request, groupId)
        if (!group.isAdmin(me.username)) throw ApiFailure(HttpStatus.FORBIDDEN, "only the admin may add members")
        if (users.find(body.username)?.active != true) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")
        val members = try {
            groups.addMember(groupId, body.username)
        } catch (_: IllegalArgumentException) {
            throw ApiFailure(HttpStatus.BAD_REQUEST, "too many members")
        }
        return groupDto(GroupWithMembers(group.group, members))
    }

    @DeleteMapping("/v1/groups/{groupId}/members/{username}")
    fun removeGroupMember(request: HttpServletRequest, @PathVariable groupId: String, @PathVariable username: String): GroupDto {
        val (me, group) = memberGroup(request, groupId)
        val members = try {
            groups.removeMember(groupId, me.username, username)
        } catch (_: IllegalArgumentException) {
            throw ApiFailure(HttpStatus.FORBIDDEN, "not allowed to remove this member")
        }
        return groupDto(GroupWithMembers(group.group, members!!))
    }

    @DeleteMapping("/v1/groups/{groupId}")
    fun disbandGroup(request: HttpServletRequest, @PathVariable groupId: String): Map<String, String> {
        val (me, group) = memberGroup(request, groupId)
        if (!group.isAdmin(me.username)) throw ApiFailure(HttpStatus.FORBIDDEN, "only the admin may disband")
        groups.disband(groupId)
        return mapOf("status" to "disbanded")
    }

    /**
     * Sends a group message (Phase 19): authorship comes from the principal
     * (ADR-0012 constraint 4), membership is the only authorisation, and
     * the fan-out reaches every member over the websocket.
     */
    @PostMapping("/v1/groups/{groupId}/messages")
    fun sendGroupMessage(request: HttpServletRequest, @PathVariable groupId: String, @RequestBody body: SendGroupMessageRequest): ResponseEntity<MessageDto> {
        val (me, group) = memberGroup(request, groupId)
        val replyToId = body.replyToId?.takeIf { it.isNotBlank() }
        if (replyToId != null) {
            val replyMsg = messages.findById(replyToId)
                ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "reply target not found")
            if (replyMsg.groupId != groupId) throw ApiFailure(HttpStatus.BAD_REQUEST, "reply target not in this group")
        }
        if (body.content.isEmpty() || body.content.toByteArray(Charsets.UTF_8).size > Messages.MAX_CONTENT_BYTES) {
            throw ApiFailure(HttpStatus.CONTENT_TOO_LARGE, "content size invalid")
        }
        val stored = messages.insertGroupText(me.username, groupId, body.content, body.clientKey, replyToId)
        hub.broadcastGroupMessage(stored, group.memberNames())
        return ResponseEntity.status(HttpStatus.CREATED).body(messageDto(stored))
    }

    /**
     * Group message history (Phase 19): identical pagination semantics to
     * the pairwise page, scoped to the caller's membership.
     */
    @GetMapping("/v1/groups/{groupId}/messages")
    fun groupMessages(
        request: HttpServletRequest,
        @PathVariable groupId: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): GroupMessagesPageDto {
        val (_, _) = memberGroup(request, groupId)
        val before = cursor?.let {
            decodeMessageCursor(it) ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "invalid cursor")
        }
        val rows = messages.groupPage(groupId, before, limit)
        val messageIds = rows.map { it.id }
        val reactionsMap = if (messageIds.isNotEmpty()) reactions.listForMessages(messageIds) else emptyMap()
        val readCounts = if (messageIds.isNotEmpty()) messages.groupReadCounts(messageIds) else emptyMap()
        return GroupMessagesPageDto(
            rows.map { record -> messageDto(record, reactionsMap[record.id] ?: emptyList(), readCounts[record.id] ?: 0) },
            rows.lastOrNull()?.let { encodeMessageCursor(it.createdAt, it.id) },
        )
    }

    /**
     * Marks group messages read (Phase 19): one row per (message, reader)
     * in `group_message_reads`, fanned to every member. Never marks the
     * caller's own messages.
     */
    @PostMapping("/v1/groups/{groupId}/read")
    fun markGroupRead(request: HttpServletRequest, @PathVariable groupId: String, @RequestBody body: MarkGroupReadRequest): MarkReadResponse {
        val (me, group) = memberGroup(request, groupId)
        val upTo = body.upToMessageId?.takeIf { it.isNotBlank() }?.let {
            val message = messages.findById(it)
                ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "upToMessageId not found")
            if (message.groupId != groupId) throw ApiFailure(HttpStatus.BAD_REQUEST, "upToMessageId not in this group")
            Pair(message.createdAt, message.id)
        }
        val mark = messages.markGroupRead(groupId, me.username, upTo)
        if (mark.messageIds.isNotEmpty()) hub.broadcastGroupRead(groupId, me.username, group.memberNames(), mark)
        return MarkReadResponse(mark.messageIds, mark.readAt)
    }

    @GetMapping("/v1/conversations/{peer}/messages")
    fun page(
        request: HttpServletRequest,
        @PathVariable peer: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): MessagesPageDto {
        val me = principal(request)
        if (users.find(peer) == null) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")
        val before = cursor?.let {
            decodeMessageCursor(it) ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "invalid cursor")
        }
        val rows = messages.page(me.username, peer, before, limit)
        val messageIds = rows.map { it.id }
        val reactionsMap = if (messageIds.isNotEmpty()) reactions.listForMessages(messageIds) else emptyMap()
        return MessagesPageDto(
            rows.map { messageDto(it, reactionsMap[it.id].orEmpty()) },
            rows.lastOrNull()?.let { encodeMessageCursor(it.createdAt, it.id) },
        )
    }

    /**
     * Full-text search within one conversation (Phase 16). The scoping is
     * identical to the history endpoint, so a caller only ever searches
     * their own conversations; pagination and cursor semantics match too.
     */
    @GetMapping("/v1/conversations/{peer}/search")
    fun search(
        request: HttpServletRequest,
        @PathVariable peer: String,
        @RequestParam q: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): MessagesPageDto {
        val me = principal(request)
        if (users.find(peer) == null) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")
        if (q.isBlank()) throw ApiFailure(HttpStatus.BAD_REQUEST, "empty query")
        val before = cursor?.let {
            decodeMessageCursor(it) ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "invalid cursor")
        }
        val rows = messages.search(me.username, peer, q, before, limit)
        val messageIds = rows.map { it.id }
        val reactionsMap = if (messageIds.isNotEmpty()) reactions.listForMessages(messageIds) else emptyMap()
        return MessagesPageDto(
            rows.map { messageDto(it, reactionsMap[it.id].orEmpty()) },
            rows.lastOrNull()?.let { encodeMessageCursor(it.createdAt, it.id) },
        )
    }

    @PostMapping("/v1/conversations/{peer}/messages")
    fun send(request: HttpServletRequest, @PathVariable peer: String, @RequestBody body: SendMessageRequest): ResponseEntity<MessageDto> {
        val me = principal(request)
        if (peer == me.username) throw ApiFailure(HttpStatus.BAD_REQUEST, "cannot message yourself")
        if (users.find(peer)?.active != true) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")
        if (body.recipient != peer) throw ApiFailure(HttpStatus.BAD_REQUEST, "recipient mismatch")

        // Validate reply target when supplied (ADR-0011, Phase 9).
        val replyToId = body.replyToId?.takeIf { it.isNotBlank() }
        if (replyToId != null) {
            val replyMsg = messages.findById(replyToId)
                ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "reply target not found")
            val sameConversation = (replyMsg.sender == me.username && replyMsg.recipient == peer) ||
                                   (replyMsg.sender == peer && replyMsg.recipient == me.username)
            if (!sameConversation) throw ApiFailure(HttpStatus.BAD_REQUEST, "reply target not in this conversation")
        }

        val type = body.type.uppercase()
        if (type == "IMAGE" || type == "VOICE") {
            val mediaId = body.mediaId?.takeIf { it.isNotBlank() }
                ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "mediaId required")
            val mediaRecord = media.getRecord(mediaId)
                ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such media")
            if (mediaRecord.owner != me.username || mediaRecord.peer != peer) {
                throw ApiFailure(HttpStatus.FORBIDDEN, "forbidden")
            }
            if (mediaRecord.state != "ready") {
                throw ApiFailure(HttpStatus.BAD_REQUEST, "media not ready")
            }
            if (type == "VOICE") {
                // The recorded audio must be the media the sender uploaded.
                if (mediaRecord.contentType !in MediaService.AUDIO_TYPES) {
                    throw ApiFailure(HttpStatus.BAD_REQUEST, "voice message requires audio media")
                }
                val durationMs = body.durationMs
                    ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "durationMs required")
                if (durationMs <= 0 || durationMs > 600_000) {
                    throw ApiFailure(HttpStatus.BAD_REQUEST, "duration out of range")
                }
                val waveform = normalizeWaveform(body.waveform)
                    ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "waveform required")
                val stored = messages.insertVoice(me.username, peer, body.clientKey, mediaId, durationMs, waveform, replyToId)
                return ResponseEntity.status(HttpStatus.CREATED).body(messageDto(stored))
            }
            val stored = messages.insertMedia(me.username, peer, body.clientKey, mediaId, mediaRecord, replyToId)
            return ResponseEntity.status(HttpStatus.CREATED).body(messageDto(stored))
        }
        if (body.content.isEmpty() || body.content.toByteArray(Charsets.UTF_8).size > Messages.MAX_CONTENT_BYTES) {
            throw ApiFailure(HttpStatus.CONTENT_TOO_LARGE, "content size invalid")
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(messageDto(messages.insert(me.username, peer, body.content, body.clientKey, replyToId)))
    }

    /**
     * Marks the peer's messages as read by the caller (ADR-0010). Only the
     * recipient may mark a message read; the server derives both sides from
     * the authenticated principal, never from the request body.
     *
     * The client calls this only from the foreground chat screen while the
     * messages are rendered — never from a notification handler, a
     * background sync or a cursor catch-up.
     */
    @PostMapping("/v1/conversations/{peer}/read")
    fun markRead(
        request: HttpServletRequest,
        @PathVariable peer: String,
        @RequestBody(required = false) body: MarkReadRequest?,
    ): MarkReadResponse {
        val me = principal(request)
        if (peer == me.username) throw ApiFailure(HttpStatus.BAD_REQUEST, "cannot read your own messages")
        if (users.find(peer) == null) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")
        val upTo = body?.upToMessageId?.takeIf { it.isNotBlank() }?.let { id ->
            val record = messages.findById(id) ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
            if (record.sender != peer || record.recipient != me.username) {
                throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
            }
            record.createdAt to record.id
        }
        val mark = messages.markRead(me.username, peer, upTo)
        if (mark.messageIds.isNotEmpty()) hub.broadcastMessageRead(me.username, peer, mark)
        return MarkReadResponse(mark.messageIds, mark.readAt)
    }

    // ── Reactions (ADR-0011, Phase 8) ───────────────────────────────────────

    @PutMapping("/v1/conversations/{peer}/messages/{messageId}/reactions")
    fun addReaction(
        request: HttpServletRequest,
        @PathVariable peer: String,
        @PathVariable messageId: String,
        @RequestBody body: AddReactionRequest,
    ): List<ReactionDto> {
        val me = principal(request)
        ensurePairwiseMessage(peer, messageId)
        val result = reactions.addReaction(messageId, me.username, body.emoji)
        return result
    }

    @DeleteMapping("/v1/conversations/{peer}/messages/{messageId}/reactions")
    fun removeReaction(
        request: HttpServletRequest,
        @PathVariable peer: String,
        @PathVariable messageId: String,
    ): List<ReactionDto> {
        val me = principal(request)
        ensurePairwiseMessage(peer, messageId)
        return reactions.removeReaction(messageId, me.username)
    }

    // ── Group reactions and deletion (Phase 19) ─────────────────────────────

    @PutMapping("/v1/groups/{groupId}/messages/{messageId}/reactions")
    fun addGroupReaction(
        request: HttpServletRequest,
        @PathVariable groupId: String,
        @PathVariable messageId: String,
        @RequestBody body: AddReactionRequest,
    ): List<ReactionDto> {
        val me = principal(request)
        ensureGroupMessage(groupId, messageId)
        return try {
            reactions.addGroupReaction(messageId, me.username, body.emoji)
        } catch (_: IllegalArgumentException) {
            throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        } catch (_: GroupMessageAccessException) {
            throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        }
    }

    @DeleteMapping("/v1/groups/{groupId}/messages/{messageId}/reactions")
    fun removeGroupReaction(
        request: HttpServletRequest,
        @PathVariable groupId: String,
        @PathVariable messageId: String,
    ): List<ReactionDto> {
        val me = principal(request)
        ensureGroupMessage(groupId, messageId)
        return try {
            reactions.removeGroupReaction(messageId, me.username)
        } catch (_: IllegalArgumentException) {
            throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        } catch (_: GroupMessageAccessException) {
            throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        }
    }

    /**
     * Deletes a group message for all members (Phase 19): the author or the
     * admin may tombstone; the tombstone fans out as `group-deleted`.
     */
    @DeleteMapping("/v1/groups/{groupId}/messages/{messageId}")
    fun deleteGroupMessage(
        request: HttpServletRequest,
        @PathVariable groupId: String,
        @PathVariable messageId: String,
    ): MessageDto {
        val (me, group) = memberGroup(request, groupId)
        val message = messages.findById(messageId)
            ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        if (message.groupId != groupId) {
            throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        }
        val allowed = message.sender == me.username || group.isAdmin(me.username)
        if (!allowed) throw ApiFailure(HttpStatus.FORBIDDEN, "only the author or the admin may delete")
        val tombstone = messages.delete(messageId, me.username)
        if (tombstone != null && tombstone.deletedAt != null) {
            hub.broadcastGroupMessageDeleted(tombstone, group.memberNames())
        }
        return messageDto(tombstone!!)
    }

    /** Rejects a group path whose message does not belong to that group (404). */
    private fun ensureGroupMessage(groupId: String, messageId: String) {
        val message = messages.findById(messageId)
            ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        if (message.groupId != groupId) throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
    }

    /** Rejects a pairwise path whose message is a group message or foreign to the pair (404). */
    private fun ensurePairwiseMessage(peer: String, messageId: String) {
        val message = messages.findById(messageId)
            ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        if (message.groupId != null || (message.sender != peer && message.recipient != peer)) {
            throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        }
    }

    // ── Deletion (ADR-0011, Phase 10) ───────────────────────────────────────

    /**
     * Deletes a message for both sides (ADR-0011, Phase 10). Either
     * participant may tombstone a message of their conversation; the server
     * derives the deleter from the authenticated principal. The response is
     * the tombstone: content cleared, `deletedAt`/`deletedBy` stamped.
     *
     * Idempotent: deleting twice returns the first tombstone unchanged.
     */
    @DeleteMapping("/v1/conversations/{peer}/messages/{messageId}")
    fun deleteMessage(
        request: HttpServletRequest,
        @PathVariable peer: String,
        @PathVariable messageId: String,
    ): MessageDto {
        val me = principal(request)
        val message = messages.findById(messageId)
            ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        // A group row lives in the group namespace only: the pairwise path
        // answers 404 instead of leaking its existence (ADR-0012).
        if (message.groupId != null) throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        if (message.sender != me.username && message.recipient != me.username) {
            throw ApiFailure(HttpStatus.FORBIDDEN, "not a participant")
        }
        val tombstone = messages.delete(messageId, me.username)
        if (tombstone != null && tombstone.deletedAt != null) {
            hub.broadcastMessageDeleted(tombstone)
        }
        return messageDto(tombstone!!)
    }

    // ── Editing (Phase 17) ──────────────────────────────────────────────────

    /**
     * Re-stores the content of one message (Phase 17). Only the author may
     * edit, there is no time window (consistent with deletion's no-window
     * decision), and editing a tombstoned message is a `409` — deletes take
     * precedence. Editing a non-text message (image/voice) is a `409` too:
     * they carry no editable text. The response is the full updated record;
     * a `message-edited` event informs both participants' devices.
     */
    @PutMapping("/v1/conversations/{peer}/messages/{messageId}")
    fun editMessage(
        request: HttpServletRequest,
        @PathVariable peer: String,
        @PathVariable messageId: String,
        @RequestBody body: EditMessageRequest,
    ): MessageDto {
        val me = principal(request)
        val message = messages.findById(messageId)
            ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        if (message.groupId != null) {
            throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        }
        if (message.sender != me.username) {
            throw ApiFailure(HttpStatus.FORBIDDEN, "only the author may edit")
        }
        val peerOfMessage = if (message.sender == me.username) message.recipient else message.sender
        if (peerOfMessage != peer) {
            throw ApiFailure(HttpStatus.BAD_REQUEST, "message not in this conversation")
        }
        if (message.deleted) {
            throw ApiFailure(HttpStatus.CONFLICT, "message is deleted")
        }
        if (message.type != "TEXT") {
            throw ApiFailure(HttpStatus.CONFLICT, "only text messages can be edited")
        }
        val edited = messages.edit(messageId, me.username, body.content)
            ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
        hub.broadcastMessageEdited(edited)
        return messageDto(edited)
    }
}

private fun messageDto(record: MessageRecord, reactions: List<ReactionDto> = emptyList(), readCount: Int? = null) =
    MessageDto(record.id, record.sender, record.recipient, record.content, record.createdAt, record.clientKey, record.readAt, record.type, record.mediaId, record.width, record.height,
        if (reactions.isEmpty()) null else reactions, record.replyTo, record.deletedAt, record.deletedBy, record.durationMs, record.waveform, record.editedAt, record.groupId, readCount)

/**
 * Normalizes the client's waveform sample list (Phase 11): keeps numeric
 * amplitudes 0-100, caps the sample count at 64, and returns null when the
 * payload is absent or entirely non-numeric — the message is then rejected.
 */
private fun normalizeWaveform(waveform: String?): String? {
    if (waveform.isNullOrBlank()) return null
    val samples = waveform.split(',').mapNotNull { it.trim().toIntOrNull() }
        .map { it.coerceIn(0, 100) }
    if (samples.isEmpty()) return null
    return samples.take(64).joinToString(",")
}

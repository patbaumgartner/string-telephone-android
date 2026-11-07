package com.patbaumgartner.stringtelephone

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/**
 * Group-call lifecycle (Phase 20, ADR-0014): one confbridge bridge per call,
 * created on demand over AMI and destroyed when the last participant leaves.
 *
 * The rules from the ADR:
 *  - any member may start; the bridge id is generated here, never supplied
 *    by a client;
 *  - membership is the only authorisation — non-members get 404;
 *  - one active call per group (the bridge is the call; a second starter
 *    idempotently gets the running call back); members may join a call that
 *    is already running;
 *  - every state change fans out to the members through the realtime hub so
 *    devices ring, stop ringing and show "in call" without polling.
 */
class GroupCallService(
    private val tx: TransactionTemplate,
    private val groups: Groups,
    private val calls: GroupCalls,
    private val pbx: PbxGateway,
    private val hub: WsHub,
    private val accounts: AccountService,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(GroupCallService::class.java)

    /**
     * Starts a group call: opens the bridge on the PBX first (explicit 503 on
     * failure — never a half-open call), then records it in one narrowly
     * scoped transaction, then fans the invite. Ringing semantics are the
     * clients' business; the frame lists every member so each device can
     * decide locally (ADR-0014 rule 5: busy members auto-decline).
     */
    fun start(groupId: String, caller: String, withVideo: Boolean): GroupCallRecord {
        val group = memberGroup(caller, groupId)
        calls.activeForGroup(groupId)?.let { return it }
        val bridgeId = calls.newBridgeId()
        accounts.withReconcileLock { pbx.openConference(bridgeId, withVideo) }
        val now = System.currentTimeMillis()
        try {
            tx.executeWithoutResult {
                calls.open(bridgeId, groupId, caller, withVideo, now)
            }
        } catch (error: Exception) {
            // The row is the source of truth; a bridge without a row is a leak.
            runCatching { accounts.withReconcileLock { pbx.destroyConference(bridgeId) } }
            throw error
        }
        hub.broadcastGroupCallInvite(groupId, bridgeId, caller, group.memberNames() - caller, withVideo)
        return calls.find(bridgeId)!!
    }

    /**
     * Joins a running call. Membership-checked like every group path; the
     * PBX join is the INVITE the app already dialled (`conf-<id>`), so the
     * API only records the participant and reports the call state.
     */
    fun join(groupId: String, bridgeId: String, member: String): GroupCallRecord {
        val group = memberGroup(member, groupId)
        val call = existingCall(groupId, bridgeId)
        calls.join(bridgeId, member, System.currentTimeMillis())
        // State goes to every group member, not only the bridge: the members
        // who still ring are exactly the ones that must stop ringing.
        hub.broadcastGroupCallState(
            groupId, bridgeId,
            state = "joined", by = member,
            members = group.memberNames(),
        )
        return call
    }

    /**
     * Leaves a call. When the last participant is gone the bridge is
     * destroyed and the call row ended, so nothing leaks (ADR-0014 rule 2).
     */
    fun leave(groupId: String, bridgeId: String, member: String) {
        val group = memberGroup(member, groupId)
        existingCall(groupId, bridgeId)
        val now = System.currentTimeMillis()
        val remaining = calls.leave(bridgeId, member, now)
        if (remaining.isEmpty()) end(groupId, bridgeId, reason = "last-member-left", by = member)
        else hub.broadcastGroupCallState(groupId, bridgeId, "left", member, group.memberNames())
    }

    /**
     * Ends the call: destroys the bridge (PBX truth first — a failed destroy
     * keeps the call visibly pending and the retry loop reclaims it), then
     * closes the row, then tells the group.
     */
    fun end(groupId: String, bridgeId: String, reason: String, by: String) {
        runCatching { accounts.withReconcileLock { pbx.destroyConference(bridgeId) } }
            .onFailure { log.warn("Bridge {} destroy failed, retry loop will reclaim: {}", bridgeId, it.message) }
        calls.end(bridgeId, System.currentTimeMillis())
        // Every member learns the call is over — ringing devices stop, the
        // starter's UI clears. Not only the bridge members.
        val members = groups.find(groupId)?.let { groups.withMembers(it).memberNames() } ?: emptyList()
        hub.broadcastGroupCallState(groupId, bridgeId, "ended", by, members, reason)
    }

    /** The group's active call for the "call in progress" UI hint; membership-checked. */
    fun activeCall(groupId: String, member: String): GroupCallRecord? {
        memberGroup(member, groupId)
        return calls.activeForGroup(groupId)
    }

    private fun memberGroup(member: String, groupId: String): GroupWithMembers {
        val group = groups.find(groupId) ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such group")
        val withMembers = groups.withMembers(group)
        if (!withMembers.isMember(member)) throw ApiFailure(HttpStatus.NOT_FOUND, "no such group")
        return withMembers
    }

    /** The call must exist and belong to the group in the path. */
    private fun existingCall(groupId: String, bridgeId: String): GroupCallRecord {
        val call = calls.find(bridgeId) ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such call")
        if (call.groupId != groupId) throw ApiFailure(HttpStatus.NOT_FOUND, "no such call")
        return call
    }
}

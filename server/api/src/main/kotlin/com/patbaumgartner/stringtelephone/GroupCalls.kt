package com.patbaumgartner.stringtelephone

import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Group-call store (Phase 20, ADR-0014): the API's record of which confbridge
 * belongs to which group and who is in it. The bridge itself lives only in
 * Asterisk; these rows make the lifecycle explicit so a fan-out frame can
 * name the call and a restart cannot orphan a live bridge record.
 *
 * One active call per group at a time: the bridge is the call.
 */
data class GroupCallRecord(
    val bridgeId: String,
    val groupId: String,
    val starter: String,
    val withVideo: Boolean,
    val startedAt: Long,
    val endedAt: Long?,
)

data class GroupCallParticipantRecord(
    val bridgeId: String,
    val username: String,
    val joinedAt: Long,
    val leftAt: Long?,
)

class GroupCalls(private val db: JdbcClient) {

    companion object {
        const val BRIDGE_PREFIX = "conf-"

        private const val CALL_COLUMNS = "bridge_id, group_id, starter, with_video, started_at, ended_at"
        private const val PARTICIPANT_COLUMNS = "bridge_id, username, joined_at, left_at"
    }

    private fun callFromRow(rs: java.sql.ResultSet) = GroupCallRecord(
        rs.getString("bridge_id"),
        rs.getString("group_id"),
        rs.getString("starter"),
        rs.getBoolean("with_video"),
        rs.getLong("started_at"),
        rs.getLong("ended_at").let { if (rs.wasNull()) null else it },
    )

    private fun participantFromRow(rs: java.sql.ResultSet) = GroupCallParticipantRecord(
        rs.getString("bridge_id"),
        rs.getString("username"),
        rs.getLong("joined_at"),
        rs.getLong("left_at").let { if (rs.wasNull()) null else it },
    )

    /** A random, non-guessable bridge id in the dialplan's `conf-X` namespace. */
    fun newBridgeId(): String = BRIDGE_PREFIX + newId()

    fun find(bridgeId: String): GroupCallRecord? =
        db.sql("SELECT $CALL_COLUMNS FROM group_calls WHERE bridge_id = :id").param("id", bridgeId)
            .query { rs, _ -> callFromRow(rs) }.optional().orElse(null)

    /** The group's active call, if any (the bridge is the call). */
    fun activeForGroup(groupId: String): GroupCallRecord? =
        db.sql("SELECT $CALL_COLUMNS FROM group_calls WHERE group_id = :id AND ended_at IS NULL")
            .param("id", groupId).query { rs, _ -> callFromRow(rs) }.optional().orElse(null)

    fun participants(bridgeId: String): List<GroupCallParticipantRecord> =
        db.sql("SELECT $PARTICIPANT_COLUMNS FROM group_call_participants WHERE bridge_id = :id AND left_at IS NULL ORDER BY joined_at")
            .param("id", bridgeId).query { rs, _ -> participantFromRow(rs) }.list()

    /**
     * Opens a call row for a freshly created bridge. Called only after the
     * PBX confirmed the bridge exists; a failure between the two is reclaimed
     * by the retry loop (a row without a bridge is ended by [end]).
     */
    fun open(bridgeId: String, groupId: String, starter: String, withVideo: Boolean, now: Long) {
        db.sql(
            "INSERT INTO group_calls(bridge_id, group_id, starter, with_video, started_at) " +
                "VALUES(:bridgeId, :groupId, :starter, :withVideo, :startedAt)",
        ).param("bridgeId", bridgeId).param("groupId", groupId).param("starter", starter)
            .param("withVideo", withVideo).param("startedAt", now).update()
        db.sql(
            "INSERT INTO group_call_participants(bridge_id, username, joined_at) VALUES(:bridgeId, :username, :joinedAt)",
        ).param("bridgeId", bridgeId).param("username", starter).param("joinedAt", now).update()
    }

    /** Records a member joining the bridge; idempotent for re-joins. */
    fun join(bridgeId: String, username: String, now: Long) {
        val joined = db.sql(
            "UPDATE group_call_participants SET left_at = NULL, joined_at = :joinedAt " +
                "WHERE bridge_id = :bridgeId AND username = :username",
        ).param("joinedAt", now).param("bridgeId", bridgeId).param("username", username).update()
        if (joined == 0) {
            db.sql(
                "INSERT INTO group_call_participants(bridge_id, username, joined_at) VALUES(:bridgeId, :username, :joinedAt)",
            ).param("bridgeId", bridgeId).param("username", username).param("joinedAt", now).update()
        }
    }

    /** Records a member leaving; returns the remaining participant names. */
    fun leave(bridgeId: String, username: String, now: Long): List<String> {
        db.sql(
            "UPDATE group_call_participants SET left_at = :leftAt " +
                "WHERE bridge_id = :bridgeId AND username = :username AND left_at IS NULL",
        ).param("leftAt", now).param("bridgeId", bridgeId).param("username", username).update()
        return participants(bridgeId).map { it.username }
    }

    /** Marks the call over and stamps every stale participant as left. */
    fun end(bridgeId: String, now: Long) {
        db.sql("UPDATE group_calls SET ended_at = :endedAt WHERE bridge_id = :id AND ended_at IS NULL")
            .param("endedAt", now).param("id", bridgeId).update()
        db.sql(
            "UPDATE group_call_participants SET left_at = :leftAt WHERE bridge_id = :id AND left_at IS NULL",
        ).param("leftAt", now).param("id", bridgeId).update()
    }
}

package com.patbaumgartner.stringtelephone

import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Group chat store (Phase 19, ADR-0012): membership lives in its own
 * tables, never overloaded onto the pairwise `recipient` column. Each group
 * has exactly one ADMIN — the creator; every other member is a MEMBER.
 *
 * Authorisation is membership-based: every query that reads or writes group
 * data takes the acting user and checks membership first, so a non-member
 * can never even learn a group exists (404, not 403).
 */
data class GroupRecord(
    val id: String,
    val name: String,
    val creator: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class GroupMemberRecord(
    val groupId: String,
    val username: String,
    val role: String,
    val joinedAt: Long,
)

/** A group with its member list, as the REST surface returns it. */
data class GroupWithMembers(
    val group: GroupRecord,
    val members: List<GroupMemberRecord>,
) {
    fun memberNames(): List<String> = members.map { it.username }
    fun isAdmin(username: String): Boolean =
        members.any { it.username == username && it.role == "ADMIN" }
    fun isMember(username: String): Boolean = members.any { it.username == username }
}

class Groups(private val db: JdbcClient) {

    companion object {
        const val MAX_NAME = 64
        const val MAX_MEMBERS = 32

        private const val GROUP_COLUMNS = "id, name, creator, created_at, updated_at"
        private const val MEMBER_COLUMNS = "group_id, username, role, joined_at"
    }

    private fun groupFromRow(rs: java.sql.ResultSet) = GroupRecord(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("creator"),
        rs.getLong("created_at"),
        rs.getLong("updated_at"),
    )

    private fun memberFromRow(rs: java.sql.ResultSet) = GroupMemberRecord(
        rs.getString("group_id"),
        rs.getString("username"),
        rs.getString("role"),
        rs.getLong("joined_at"),
    )

    /**
     * Creates a group with [creator] as its sole ADMIN member. Both writes
     * run in one narrowly scoped transaction (AGENTS.md rule): group row
     * plus membership row, no outer calls inside.
     */
    fun create(name: String, creator: String): GroupWithMembers {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "name must not be blank" }
        require(trimmed.length <= MAX_NAME) { "name too long (max $MAX_NAME)" }
        val id = newId()
        val now = System.currentTimeMillis()
        db.sql(
            "INSERT INTO groups(id, name, creator, created_at, updated_at) VALUES(:id, :name, :creator, :createdAt, :updatedAt)",
        ).param("id", id).param("name", trimmed).param("creator", creator)
            .param("createdAt", now).param("updatedAt", now).update()
        db.sql(
            "INSERT INTO group_members(group_id, username, role, joined_at) VALUES(:groupId, :username, 'ADMIN', :joinedAt)",
        ).param("groupId", id).param("username", creator).param("joinedAt", now).update()
        return withMembers(find(id)!!)
    }

    fun find(groupId: String): GroupRecord? =
        db.sql("SELECT $GROUP_COLUMNS FROM groups WHERE id = :id").param("id", groupId)
            .query { rs, _ -> groupFromRow(rs) }.optional().orElse(null)

    fun members(groupId: String): List<GroupMemberRecord> =
        db.sql("SELECT $MEMBER_COLUMNS FROM group_members WHERE group_id = :groupId ORDER BY joined_at, username")
            .param("groupId", groupId).query { rs, _ -> memberFromRow(rs) }.list()

    /** Member usernames only, for fan-out targets (Phase 19). */
    fun memberNames(groupId: String): List<String> = members(groupId).map { it.username }

    fun member(groupId: String, username: String): GroupMemberRecord? =
        db.sql("SELECT $MEMBER_COLUMNS FROM group_members WHERE group_id = :groupId AND username = :username")
            .param("groupId", groupId).param("username", username)
            .query { rs, _ -> memberFromRow(rs) }.optional().orElse(null)

    fun groupsOf(username: String): List<GroupWithMembers> =
        db.sql(
            "SELECT $GROUP_COLUMNS FROM groups g WHERE EXISTS " +
                "(SELECT 1 FROM group_members m WHERE m.group_id = g.id AND m.username = :username) " +
                "ORDER BY g.updated_at DESC",
        ).param("username", username).query { rs, _ -> groupFromRow(rs) }.list()
            .map { withMembers(it) }

    fun withMembers(group: GroupRecord) = GroupWithMembers(group, members(group.id))

    /** Renames a group (ADMIN only, validated by the caller); bumps updated_at. */
    fun rename(groupId: String, name: String): GroupRecord {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "name must not be blank" }
        require(trimmed.length <= MAX_NAME) { "name too long (max $MAX_NAME)" }
        db.sql(
            "UPDATE groups SET name = :name, updated_at = :updatedAt WHERE id = :id",
        ).param("name", trimmed).param("updatedAt", System.currentTimeMillis()).param("id", groupId).update()
        return find(groupId)!!
    }

    /**
     * Adds a member as MEMBER (ADMIN only, validated by the caller). The
     * group must not exceed [MAX_MEMBERS]; an existing member is a no-op
     * (idempotent add).
     */
    fun addMember(groupId: String, username: String): List<GroupMemberRecord> {
        if (member(groupId, username) != null) return members(groupId)
        val count = members(groupId).size
        require(count < MAX_MEMBERS) { "at most $MAX_MEMBERS members" }
        db.sql(
            "INSERT INTO group_members(group_id, username, role, joined_at) VALUES(:groupId, :username, 'MEMBER', :joinedAt)",
        ).param("groupId", groupId).param("username", username)
            .param("joinedAt", System.currentTimeMillis()).update()
        return members(groupId)
    }

    /**
     * Removes a member (Phase 19): the ADMIN may remove anyone but
     * themselves (disband instead), a MEMBER may only remove themselves
     * (leave). Returns the member list after the removal, or null when the
     * acting user is not a member at all.
     */
    fun removeMember(groupId: String, acting: String, target: String): List<GroupMemberRecord>? {
        val actor = member(groupId, acting) ?: return null
        require(target == acting || actor.role == "ADMIN") { "only the admin may remove other members" }
        require(!(target == acting && actor.role == "ADMIN")) { "the admin disbands instead of leaving" }
        db.sql("DELETE FROM group_members WHERE group_id = :groupId AND username = :username")
            .param("groupId", groupId).param("username", target).update()
        return members(groupId)
    }

    /** Deletes a group row; membership and messages cascade (V14 FKs). */
    fun disband(groupId: String) {
        db.sql("DELETE FROM groups WHERE id = :id").param("id", groupId).update()
    }

    /** Membership check for the send/read paths; null group means no membership. */
    fun isMember(groupId: String, username: String): Boolean =
        member(groupId, username) != null
}

package com.patbaumgartner.stringtelephone

data class PresenceSnapshot(
    val presence: String,
    val observedAtEpochMs: Long?,
    /** When the user was last reachable; null when online or never connected (Phase 13). */
    val lastSeenAtEpochMs: Long? = null,
)

/**
 * Derives contact presence from two server-side observations: an Asterisk
 * endpoint with a registered contact (SIP reachable) or an open realtime
 * stream. PBX state is cached briefly so contact lists and WebSocket churn do
 * not open an AMI connection per lookup.
 */
class Presence(
    private val pbx: PbxGateway,
    private val sessions: WsSessions,
    private val users: Users,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMs: Long = 5_000,
) {
    private data class PbxView(val observedAt: Long, val states: Map<String, String>?)

    @Volatile private var cached: PbxView? = null
    private val published = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * SIP registrations change without an API request, so connected clients
     * receive a `presence` event whenever a user's derived state changes.
     */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 5000, fixedDelay = 10000)
    fun publishChanges() {
        if (!sessions.hasSessions()) return
        invalidate()
        val active = users.list().filter { it.active }.map { it.username }
        published.keys.retainAll(active.toSet())
        for ((username, snapshot) in all(active)) {
            if (published.put(username, snapshot.presence) != snapshot.presence) publish(username)
        }
    }

    fun of(username: String): PresenceSnapshot = snapshot(username, pbxView(), users.find(username)?.lastSeenAt)

    fun all(usernames: Collection<String>): Map<String, PresenceSnapshot> {
        val view = pbxView()
        val lastSeen = usernames.associateWith { users.find(it)?.lastSeenAt }
        return usernames.associateWith { snapshot(it, view, lastSeen[it]) }
    }

    /** Tells every other connected client about [username]'s current presence. */
    fun publish(username: String) {
        val snapshot = of(username)
        sessions.broadcast(except = username, payload = WsFrames.presence(username, snapshot))
    }

    /** Drops cached PBX state so the next lookup observes a fresh registration. */
    fun invalidate() {
        cached = null
    }

    private fun snapshot(username: String, view: PbxView, lastSeenAt: Long?): PresenceSnapshot {
        val now = clock()
        if (sessions.isConnected(username)) return PresenceSnapshot(ONLINE, now)
        val states = view.states ?: return PresenceSnapshot(UNKNOWN, null, lastSeenAt)
        val reachable = states[username]?.let(::sipReachable) == true
        return PresenceSnapshot(if (reachable) ONLINE else OFFLINE, view.observedAt, lastSeenAt)
    }

    private fun pbxView(): PbxView {
        val now = clock()
        cached?.takeIf { now - it.observedAt < cacheMs }?.let { return it }
        val states = try {
            pbx.status().endpoints.associate { it.username to it.state }
        } catch (_: PbxUnavailable) {
            null
        }
        return PbxView(now, states).also { cached = it }
    }

    companion object {
        const val ONLINE = "online"
        const val OFFLINE = "offline"
        const val UNKNOWN = "unknown"

        /** Asterisk reports endpoints without a registered contact as Unavailable. */
        fun sipReachable(deviceState: String): Boolean =
            deviceState.trim().lowercase() !in setOf("unavailable", "invalid", "unknown", "")
    }
}

package com.patbaumgartner.stringtelephone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresenceTest {
    private val pbx = FakePbx().apply {
        applied = listOf(PbxAccount("bob", "digest"), PbxAccount("carol", "digest"))
    }
    private var now = 1_000L
    private val users = Users(TestPostgres.newJdbcClient())
    private val presence = Presence(pbx, WsSessions(), users, clock = { now }, cacheMs = 5_000)

    @Test
    fun `registered endpoints are online and unavailable endpoints offline`() {
        pbx.deviceStates["carol"] = "Unavailable"
        assertEquals(PresenceSnapshot("online", 1_000), presence.of("bob"))
        assertEquals(PresenceSnapshot("offline", 1_000), presence.of("carol"))
        assertEquals(PresenceSnapshot("offline", 1_000), presence.of("nobody"))
    }

    @Test
    fun `PBX state is cached briefly and refreshed afterwards`() {
        assertEquals("online", presence.of("bob").presence)
        pbx.deviceStates["bob"] = "Unavailable"
        now += 4_000
        assertEquals("online", presence.of("bob").presence)
        now += 2_000
        assertEquals("offline", presence.of("bob").presence)
    }

    @Test
    fun `unreachable PBX without a realtime stream is unknown`() {
        pbx.available = false
        assertEquals(PresenceSnapshot("unknown", null), presence.of("bob"))
    }

    @Test
    fun `device states in use or ringing still count as reachable`() {
        listOf("Not in use", "In use", "Busy", "Ringing", "On Hold").forEach { assertTrue(Presence.sipReachable(it), it) }
        listOf("Unavailable", "Invalid", "Unknown", "").forEach { assertFalse(Presence.sipReachable(it), it) }
    }
}

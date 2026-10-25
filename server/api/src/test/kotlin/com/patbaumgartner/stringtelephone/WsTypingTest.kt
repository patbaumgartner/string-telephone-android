package com.patbaumgartner.stringtelephone

import org.junit.jupiter.api.Test
import kotlin.test.assertContains

/**
 * Unit tests for the typing indicator frame construction (Phase 7).
 * The fan-out through WebSocket sessions is covered by the prototype oracle
 * (`verify-typing.mjs`) rather than in-process tests because connecting two
 * WebSocket clients against a real Spring context is a separate integration
 * concern already exercised by the live test script.
 */
class WsTypingTest {

    @Test
    fun `typing frame is a valid JSON envelope with the correct structure`() {
        val start = WsFrames.typing("bob", true)
        assertContains(start, "\"type\":\"typing\"")
        assertContains(start, "\"peer\":\"bob\"")
        assertContains(start, "\"typing\":true")

        val stop = WsFrames.typing("alice", false)
        assertContains(stop, "\"type\":\"typing\"")
        assertContains(stop, "\"peer\":\"alice\"")
        assertContains(stop, "\"typing\":false")
    }
}

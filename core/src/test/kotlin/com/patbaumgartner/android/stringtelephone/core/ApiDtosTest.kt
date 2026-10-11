package com.patbaumgartner.android.stringtelephone.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiDtosTest {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Test
    fun `message dto round trips through json`() {
        val dto = MessageDto("m1", "alice", "bob", "hi", 123L, "k1")
        val encoded = json.encodeToString(MessageDto.serializer(), dto)
        val decoded = json.decodeFromString(MessageDto.serializer(), encoded)
        assertEquals(dto, decoded)
    }

    @Test
    fun `messages page ignores unknown fields`() {
        val raw = """{"messages":[{"id":"m1","sender":"alice","recipient":"bob","content":"hi","createdAt":1,"clientKey":"k","future":"x"}],"nextCursor":null}"""
        val page = json.decodeFromString(MessagesPageDto.serializer(), raw)
        assertEquals(1, page.messages.size)
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun `contact dto tolerates absent presence timestamp`() {
        val dto = json.decodeFromString(
            ContactDto.serializer(),
            """{"username":"bob","displayName":"Bob","presence":"online"}""",
        )
        assertEquals("bob", dto.username)
        assertEquals(null, dto.presenceUpdatedAtEpochMs)
    }
}

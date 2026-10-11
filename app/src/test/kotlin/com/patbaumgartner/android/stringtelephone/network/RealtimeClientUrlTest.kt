package com.patbaumgartner.android.stringtelephone.network

import kotlin.test.Test
import kotlin.test.assertEquals

/** OkHttp only accepts http/https URLs, so the ws/wss endpoint is remapped. */
class RealtimeClientUrlTest {

    @Test
    fun `secure base url yields an https websocket request url`() {
        assertEquals(
            "https://phone.example.com/v1/ws",
            okHttpWebSocketUrl("https://phone.example.com"),
        )
    }

    @Test
    fun `cleartext base url yields an http websocket request url`() {
        assertEquals(
            "http://127.0.0.1:8080/v1/ws",
            okHttpWebSocketUrl("http://127.0.0.1:8080"),
        )
    }

    @Test
    fun `websocket authentication uses a bearer header and never exposes token in URL`() {
        val request = authenticatedWebSocketRequest("https://phone.example.com", "a+b=c")
        assertEquals("https://phone.example.com/v1/ws", request.url.toString())
        assertEquals("Bearer a+b=c", request.header("Authorization"))
        assertEquals(null, request.url.query)
    }
}

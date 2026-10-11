package com.patbaumgartner.android.stringtelephone.network

import kotlin.test.Test
import kotlin.test.assertEquals

/** OkHttp only accepts http/https URLs, so the ws/wss endpoint is remapped. */
class RealtimeClientUrlTest {

    @Test
    fun `secure base url yields an https websocket request url`() {
        assertEquals(
            "https://phone.example.com/v1/ws?token=tok",
            okHttpWebSocketUrl("https://phone.example.com", "tok"),
        )
    }

    @Test
    fun `cleartext base url yields an http websocket request url`() {
        assertEquals(
            "http://127.0.0.1:8080/v1/ws?token=tok",
            okHttpWebSocketUrl("http://127.0.0.1:8080", "tok"),
        )
    }

    @Test
    fun `token is url encoded in the query string`() {
        assertEquals(
            "https://phone.example.com/v1/ws?token=a%2Bb%3Dc",
            okHttpWebSocketUrl("https://phone.example.com", "a+b=c"),
        )
    }
}

package com.patbaumgartner.android.stringtelephone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransportTest {

    @Test
    fun `https base url maps to a secure websocket, http to an unsecure one`() {
        assertEquals("wss://phone.example.com/v1/ws", webSocketUrl("https://phone.example.com"))
        assertEquals("ws://192.168.1.10:8080/v1/ws", webSocketUrl("http://192.168.1.10:8080"))
    }

    @Test
    fun `websocket url ignores a trailing slash and carries the token`() {
        assertEquals(
            "wss://phone.example.com:8443/v1/ws?token=st_a%2Bb%2Fc",
            webSocketUrl("https://phone.example.com:8443/", "st_a+b/c"),
        )
    }

    @Test
    fun `https base url derives sip over tls on the secure port`() {
        assertEquals(
            SipTarget(host = "phone.example.com", port = SIP_PORT_SECURED, secure = true),
            deriveSipTarget("https://phone.example.com"),
        )
    }

    @Test
    fun `http base url derives plain sip on the unsecured port`() {
        assertEquals(
            SipTarget(host = "192.168.1.10", port = SIP_PORT_UNSECURED, secure = false),
            deriveSipTarget("http://192.168.1.10:8080"),
        )
    }

    @Test
    fun `api port never changes the sip port`() {
        assertEquals(
            SipTarget(host = "phone.example.com", port = SIP_PORT_SECURED, secure = true),
            deriveSipTarget("https://phone.example.com:8443"),
        )
        assertEquals(
            SipTarget(host = "phone.example.com", port = SIP_PORT_UNSECURED, secure = false),
            deriveSipTarget("http://phone.example.com:8080"),
        )
    }

    @Test
    fun `missing host yields no sip target`() {
        assertNull(deriveSipTarget("https://"))
        assertNull(deriveSipTarget("not a url"))
    }
}

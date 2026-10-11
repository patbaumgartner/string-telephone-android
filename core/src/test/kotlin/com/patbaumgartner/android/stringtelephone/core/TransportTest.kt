package com.patbaumgartner.android.stringtelephone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TransportTest {

    @Test
    fun `https base url maps to a secure websocket, http to an unsecure one`() {
        assertEquals("wss://phone.example.com/v1/ws", webSocketUrl("https://phone.example.com"))
        assertEquals("ws://192.168.1.10:8080/v1/ws", webSocketUrl("http://192.168.1.10:8080"))
    }

    @Test
    fun `websocket url ignores a trailing slash and contains no credentials`() {
        assertEquals(
            "wss://phone.example.com:8443/v1/ws",
            webSocketUrl("https://phone.example.com:8443/"),
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

    @Test
    fun `mixed case HTTPS cannot downgrade SIP or websocket transport`() {
        assertEquals(SipTarget("phone.example.com", 5061, true), deriveSipTarget("HTTPS://phone.example.com"))
        assertEquals("wss://phone.example.com/v1/ws", webSocketUrl("HTTPS://phone.example.com"))
    }

    @Test
    fun `invalid scheme and authority never fall back to insecure transport`() {
        for (url in listOf("ftp://phone.example.com", "https://phone.example.com?next=http://evil", "https://:443")) {
            assertNull(deriveSipTarget(url))
            assertFailsWith<IllegalArgumentException> { webSocketUrl(url) }
        }
    }

    @Test
    fun `IPv6 uses the secure SIP port independently of the API port`() {
        assertEquals(SipTarget("2001:db8::1", 5061, true), deriveSipTarget("https://[2001:db8::1]:8443"))
    }
}

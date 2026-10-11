package com.patbaumgartner.android.stringtelephone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SipTest {

    private val challenge401 = listOf(
        "SIP/2.0 401 Unauthorized",
        "Via: SIP/2.0/UDP 10.0.0.2:5060;branch=z9hG4bK1",
        "From: <sip:alice@10.0.0.2>;tag=abc",
        "To: <sip:alice@pbx>;tag=as7c3f9",
        "Call-ID: reg-1@10.0.0.2",
        "CSeq: 1 REGISTER",
        "WWW-Authenticate: Digest realm=\"asterisk\",nonce=\"1747000000/abc\",opaque=\"zz\",algorithm=MD5,qop=\"auth\"",
        "Content-Length: 0",
        "",
        "",
    ).joinToString("\r\n")

    @Test
    fun `parses status line`() {
        val (status, reason) = parseSipStatusLine("SIP/2.0 200 OK")!!
        assertEquals(200, status)
        assertEquals("OK", reason)
    }

    @Test
    fun `rejects non-status lines`() {
        assertNull(parseSipStatusLine("REGISTER sip:x SIP/2.0"))
        assertNull(parseSipStatusLine("garbage"))
    }

    @Test
    fun `parses response headers case-insensitively through lookup`() {
        val response = parseSipResponse(
            listOf(
                "SIP/2.0 401 Unauthorized",
                "WWW-Authenticate: Digest realm=\"asterisk\",nonce=\"n1\"",
                "",
                "",
            ).joinToString("\r\n"),
        )!!
        val challenge = digestChallengeFrom(response)!!
        assertEquals("asterisk", challenge["realm"])
        assertEquals("n1", challenge["nonce"])
    }

    @Test
    fun `returns null for malformed first line`() {
        assertNull(parseSipResponse("not sip at all"))
    }

    @Test
    fun `digest challenge missing header yields null`() {
        val response = parseSipResponse("SIP/2.0 200 OK\r\n\r\n")
        assertEquals(200, response?.status)
        assertEquals(null, if (response == null) null else digestChallengeFrom(response))
    }

    @Test
    fun `digest response is a 32 character hex digest`() {
        val response = digestResponse(
            username = "alice",
            password = "secret",
            realm = "asterisk",
            nonce = "n1",
            method = "REGISTER",
            uri = "sip:pbx:5060",
            cnonce = "c1",
        )
        assertEquals(32, response.length)
        assertTrue(response.all { it.isDigit() || it in 'a'..'f' }, "hex digest expected: $response")
    }

    @Test
    fun `authorization header carries all required digest parameters`() {
        val header = digestAuthorizationHeader(
            username = "alice",
            password = "secret",
            realm = "asterisk",
            nonce = "n1",
            opaque = "opq1",
            method = "REGISTER",
            uri = "sip:pbx",
            cnonce = "c1",
        )
        assertTrue("username=\"alice\"" in header, "username missing: $header")
        assertTrue("nonce=\"n1\"" in header, "nonce missing: $header")
        assertTrue("opaque=\"opq1\"" in header, "opaque missing: $header")
        assertTrue("qop=auth" in header, "qop missing: $header")
    }

    @Test
    fun `cursor round trip`() {
        val encoded = encodeMessageCursor(1_700_000_000_000, "msg-42")
        val decoded = decodeMessageCursor(encoded)
        assertEquals(1_700_000_000_000L to "msg-42", decoded)
    }

    @Test
    fun `invalid cursor decodes to null`() {
        assertNull(decodeMessageCursor("!!!not-base64!!!"))
        assertNull(decodeMessageCursor(java.util.Base64.getUrlEncoder().encodeToString("noseparator".toByteArray())))
    }
}

package com.patbaumgartner.android.stringtelephone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerUrlTest {

    @Test
    fun `normalises a valid https url to scheme and authority`() {
        val result = validateServerUrl("  https://phone.example.com/api/  ")
        assertIs<ServerUrlValidation.Valid>(result)
        assertEquals("https://phone.example.com", result.normalized)
        assertTrue(result.secure)
    }

    @Test
    fun `accepts http as an unsecured local development url`() {
        val result = validateServerUrl("http://192.168.1.10:8080/")
        assertIs<ServerUrlValidation.Valid>(result)
        assertEquals("http://192.168.1.10:8080", result.normalized)
        assertFalse(result.secure)
    }

    @Test
    fun `rejects other schemes`() {
        assertIs<ServerUrlValidation.Invalid>(validateServerUrl("ftp://phone.example.com"))
    }

    @Test
    fun `rejects scheme-less input`() {
        assertIs<ServerUrlValidation.Invalid>(validateServerUrl("phone.example.com"))
    }

    @Test
    fun `rejects empty input`() {
        assertIs<ServerUrlValidation.Invalid>(validateServerUrl("   "))
    }

    @Test
    fun `rejects embedded credentials`() {
        assertIs<ServerUrlValidation.Invalid>(validateServerUrl("https://alice:secret@phone.example.com"))
    }

    @Test
    fun `rejects missing host`() {
        assertIs<ServerUrlValidation.Invalid>(validateServerUrl("https://"))
    }
}

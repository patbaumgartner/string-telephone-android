package com.patbaumgartner.stringtelephone

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerTlsTest {

    @Test
    fun `no keystore means TLS stays off`() {
        assertNull(ServerTls.fromEnv { null })
        assertNull(ServerTls.fromEnv { "" })
    }

    @Test
    fun `loads a keystore from the environment and defaults the port`() {
        val file = Files.createTempFile("st-api", ".p12")
        generateTestCertificate(file)
        val env = mapOf(
            "API_TLS_KEYSTORE" to file.toString(),
            "API_TLS_KEYSTORE_PASSWORD" to "changeit",
        )
        val tls = ServerTls.fromEnv { env[it] }
        requireNotNull(tls)
        assertEquals(DEFAULT_HTTPS_PORT, tls.port)
        assertEquals("api", tls.keyAlias)
        assertEquals("changeit", tls.keyPassword)
    }

    @Test
    fun `explicit alias and port win over the defaults`() {
        val file = Files.createTempFile("st-api", ".p12")
        generateTestCertificate(file)
        val env = mapOf(
            "API_TLS_KEYSTORE" to file.toString(),
            "API_TLS_KEYSTORE_PASSWORD" to "changeit",
            "API_TLS_KEY_ALIAS" to "api",
            "API_HTTPS_PORT" to "9443",
        )
        val tls = ServerTls.fromEnv { env[it] }
        requireNotNull(tls)
        assertEquals(9443, tls.port)
        assertEquals("api", tls.keyAlias)
    }

    @Test
    fun `a missing keystore file fails fast`() {
        val env = mapOf("API_TLS_KEYSTORE" to "/nonexistent/api.p12")
        assertFailsWith<IllegalArgumentException> { ServerTls.fromEnv { env[it] } }
    }
}

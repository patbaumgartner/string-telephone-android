package com.patbaumgartner.android.stringtelephone.call

import org.linphone.core.Core
import org.linphone.core.MediaEncryption
import org.linphone.core.Transports
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallSecurityTest {
    private val config = EngineConfig("phone.example.com", 5061, "alice", "test-only")

    @Test
    fun `production requires TLS while development may use plaintext`() {
        config.requireAllowedTransport(development = false)
        for (transport in listOf(EngineConfig.Transport.UDP, EngineConfig.Transport.TCP)) {
            val plaintext = config.copy(transport = transport)
            assertFailsWith<IllegalArgumentException> { plaintext.requireAllowedTransport(development = false) }
            plaintext.requireAllowedTransport(development = true)
        }
    }

    @Test
    fun `outbound destination retains registrar port and account transport`() {
        assertEquals("sip:phone.example.com:5061;transport=tls", config.registrarUri())
        assertEquals("sip:bob@phone.example.com:5061;transport=tls", config.callUri("bob"))
        val development = config.copy(serverPort = 5060, transport = EngineConfig.Transport.UDP)
        assertEquals("sip:bob@phone.example.com:5060;transport=udp", development.callUri("bob"))
        assertEquals(
            "sip:bob@[2001:db8::1]:5061;transport=tls",
            config.copy(serverHost = "2001:db8::1").callUri("bob"),
        )
    }

    @Test
    fun `destination cannot inject a different host or transport`() {
        for (user in listOf("bob@attacker.example", "bob;transport=udp", "", "bob?Route=evil")) {
            assertFailsWith<IllegalArgumentException> { config.callUri(user) }
        }
    }

    @Test
    fun `TLS verifies trust and name and mandates SDES SRTP without plaintext listeners`() {
        val calls = mutableMapOf<String, Any?>()
        val transports = recordingProxy<Transports>(calls)
        val core = recordingProxy<Core>(calls, transports)

        configureCallSecurity(core, config)

        assertEquals(true, calls["verifyServerCertificates"])
        assertEquals(true, calls["verifyServerCn"])
        assertEquals(MediaEncryption.SRTP, calls["setMediaEncryption"])
        assertEquals(true, calls["setMediaEncryptionMandatory"])
        assertEquals("AES_CM_128_HMAC_SHA1_80", calls["setSrtpCryptoSuites"])
        assertEquals(false, calls["setCapabilityNegociationEnabled"])
        assertEquals(0, calls["setUdpPort"])
        assertEquals(0, calls["setTcpPort"])
        assertEquals(0, calls["setDtlsPort"])
        assertEquals(-1, calls["setTlsPort"])
        assertTrue(calls.containsKey("setTransports"))
    }

    @Test
    fun `SRTP configuration failure does not fall back to plaintext`() {
        val calls = mutableMapOf<String, Any?>()
        val core = recordingProxy<Core>(calls, encryptionResult = -1)
        assertFailsWith<IllegalStateException> { configureCallSecurity(core, config) }
        assertEquals(MediaEncryption.SRTP, calls["setMediaEncryption"])
        assertFalse(calls.containsKey("setTransports"))
    }

    @Test
    fun `TLS configuration failure does not fall back to another transport`() {
        val calls = mutableMapOf<String, Any?>()
        val transports = recordingProxy<Transports>(calls)
        val core = recordingProxy<Core>(calls, transports, transportResult = -1)
        assertFailsWith<IllegalStateException> { configureCallSecurity(core, config) }
        assertEquals(0, calls["setUdpPort"])
        assertEquals(0, calls["setTcpPort"])
    }

    @Test
    fun `development UDP does not require SDES keys over plaintext signaling`() {
        val calls = mutableMapOf<String, Any?>()
        configureCallSecurity(recordingProxy<Core>(calls), config.copy(transport = EngineConfig.Transport.UDP))
        assertTrue(calls.isEmpty())
    }

    private inline fun <reified T> recordingProxy(
        calls: MutableMap<String, Any?>,
        transports: Transports? = null,
        encryptionResult: Int = 0,
        transportResult: Int = 0,
    ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        if (method.name == "getTransports") {
            transports
        } else {
            calls[method.name] = args?.firstOrNull()
            when (method.name) {
                "setMediaEncryption" -> encryptionResult
                "setTransports" -> transportResult
                else -> null
            }
        }
    } as T
}

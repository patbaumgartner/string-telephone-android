package com.patbaumgartner.stringtelephone

import org.springframework.mock.env.MockEnvironment
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import kotlin.test.*

class PassphraseTest {
    @Test
    fun `admin credentials are mandatory and long passwords are not truncated at 72 bytes`() {
        val config = SecurityConfiguration()
        assertFailsWith<IllegalArgumentException> { config.adminIdentity(MockEnvironment()) }
        val password = "$TEST_PASSPHRASE-$TEST_PASSPHRASE"
        val env = MockEnvironment().withProperty("st.admin.username", "operator").withProperty("st.admin.password", password)
        val user = config.adminIdentity(env).loadUserByUsername("operator")
        val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
        assertTrue(encoder.matches(password, user.password))
        assertFalse(encoder.matches(password.take(72), user.password))
        assertFalse(encoder.matches(password.dropLast(1) + "x", user.password))
    }

    @Test
    fun `SIP passphrase is hashed completely and configuration values cannot inject Asterisk sections`() {
        val digest = sipDigest("alice", TEST_PASSPHRASE)
        assertNotEquals(digest, sipDigest("alice", TEST_PASSPHRASE.dropLast(1)))
        val config = AsteriskConfig.endpoints(listOf(PbxAccount("alice", digest)), PbxSettings())
        assertTrue(config.contains("password_digest=MD5:$digest"))
        assertFalse(config.contains(TEST_PASSPHRASE))
        assertFailsWith<IllegalArgumentException> { sipDigest("../etc", TEST_PASSPHRASE) }
        for (username in listOf("a", "e", "h", "i", "o", "s", "t")) {
            assertFailsWith<IllegalArgumentException> { validateUsername(username) }
        }
        assertFailsWith<IllegalArgumentException> { sipDigest("alice", "$TEST_PASSPHRASE\n[bad]") }
        assertFailsWith<IllegalArgumentException> { PbxSettings(codecs = "opus\n[bad]").validate() }
        assertFailsWith<IllegalArgumentException> { PbxSettings(localNetwork = "0.0.0.0/0").validate() }
        assertFailsWith<IllegalArgumentException> { PbxSettings(ringSeconds = 0).validate() }
        assertFailsWith<IllegalArgumentException> { PbxSettings(maxContacts = 100).validate() }
    }
}

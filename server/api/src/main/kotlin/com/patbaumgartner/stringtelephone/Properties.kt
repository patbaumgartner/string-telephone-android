package com.patbaumgartner.stringtelephone

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * Typed configuration (Boot relaxed binding maps `PBX_AMI_PASSWORD` to
 * `st.pbx.password`, `ASTERISK_MANAGED_DIR` to `st.pbx.directory`, ...).
 * Failures name the property that is wrong instead of a bare placeholder error.
 *
 * `requireTls`, `remoteAddr` and both admin/rate-limit settings are the
 * environment-variable ports of record: `ST_PBX_REQUIRE_TLS`,
 * `ST_REMOTE_ADDR_HEADER`, `ADMIN_USERNAME`, `ADMIN_PASSWORD` and
 * `ST_AUTH_RATE_LIMIT_ATTEMPTS`. A supported list, because binding an
 * arbitrary subset is not a contract worth testing.
 */
@ConfigurationProperties("st.pbx")
data class PbxProperties(
    @DefaultValue("asterisk") val host: String = "asterisk",
    @DefaultValue("5038") @field:Min(1) @field:Max(65535) val port: Int = 5038,
    @DefaultValue("stringtelephone") @field:NotBlank val username: String = "stringtelephone",
    @DefaultValue("") @field:NotBlank val password: String = "",
    @DefaultValue("data/pbx") @field:NotBlank val directory: String = "data/pbx",
    @DefaultValue("false") val requireTls: Boolean = false,
)

/**
 * Native TLS is enabled by pointing `st.tls.keystore` at a PKCS12/JKS file;
 * `API_TLS_KEYSTORE` binds to it, so existing deployments are unaffected.
 */
@ConfigurationProperties("st.tls")
data class TlsProperties(
    val keystore: String? = null,
    val keystorePassword: String = "",
    val keystoreType: String = "PKCS12",
    val keyAlias: String? = null,
    val keyPassword: String? = null,
    @field:Min(1) @field:Max(65535) val port: Int = DEFAULT_HTTPS_PORT,
) {
    val enabled: Boolean get() = !keystore.isNullOrBlank()
}

/**
 * Administrator credential, the shared rate-limiter budget for terminal
 * authentication, and the header carrying the caller address. Blank values fail
 * the context with a property name instead of a bare placeholder error.
 */
@ConfigurationProperties("st.auth")
data class AuthProperties(
    @field:NotBlank val adminUsername: String = "",
    @field:NotBlank @field:Min(24) val adminPassword: String = "",
    @DefaultValue("RemoteAddr")
    @field:Pattern(regexp = "RemoteAddr|X-Forwarded-For|X-Real-IP")
    val remoteAddr: String = "RemoteAddr",
    @DefaultValue("30") @field:Min(0) val rateLimitAttempts: Int = 30,
)

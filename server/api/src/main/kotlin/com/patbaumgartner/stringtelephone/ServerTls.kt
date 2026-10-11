package com.patbaumgartner.stringtelephone

import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.sslConnector
import java.io.File
import java.security.KeyStore

/**
 * Optional native TLS for the companion API, enabled by configuring a
 * keystore. The plain HTTP connector is always started as well, so both
 * secured (https/wss) and unsecured (http/ws) access keep working; deployments
 * behind a TLS-terminating proxy simply leave this unconfigured.
 */
data class ServerTls(
    val port: Int,
    val keyStore: KeyStore,
    val keyStorePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    companion object {
        /** Reads the `API_TLS_*` environment variables; null when TLS is off. */
        fun fromEnv(env: (String) -> String? = System::getenv): ServerTls? {
            val path = env("API_TLS_KEYSTORE")?.takeIf { it.isNotBlank() } ?: return null
            val keyStorePassword = env("API_TLS_KEYSTORE_PASSWORD").orEmpty()
            val keyStoreType = env("API_TLS_KEYSTORE_TYPE")?.takeIf { it.isNotBlank() } ?: "PKCS12"
            val file = File(path)
            require(file.isFile) { "API_TLS_KEYSTORE points at a missing file: $path" }
            val keyStore = KeyStore.getInstance(keyStoreType).apply {
                file.inputStream().use { load(it, keyStorePassword.toCharArray()) }
            }
            val keyAlias = env("API_TLS_KEY_ALIAS")?.takeIf { it.isNotBlank() }
                ?: keyStore.aliases().asSequence().firstOrNull { keyStore.isKeyEntry(it) }
                ?: throw IllegalStateException("API_TLS_KEYSTORE has no private-key entry")
            return ServerTls(
                port = env("API_HTTPS_PORT")?.toIntOrNull() ?: DEFAULT_HTTPS_PORT,
                keyStore = keyStore,
                keyStorePassword = keyStorePassword,
                keyAlias = keyAlias,
                keyPassword = env("API_TLS_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: keyStorePassword,
            )
        }
    }
}

const val DEFAULT_API_PORT = 8080
const val DEFAULT_HTTPS_PORT = 8443

/** Adds the HTTPS/WSS connector for [tls] alongside the plain HTTP one. */
fun ApplicationEngine.Configuration.secureConnector(tls: ServerTls) {
    sslConnector(
        keyStore = tls.keyStore,
        keyAlias = tls.keyAlias,
        keyStorePassword = { tls.keyStorePassword.toCharArray() },
        privateKeyPassword = { tls.keyPassword.toCharArray() },
    ) {
        host = "0.0.0.0"
        port = tls.port
    }
}

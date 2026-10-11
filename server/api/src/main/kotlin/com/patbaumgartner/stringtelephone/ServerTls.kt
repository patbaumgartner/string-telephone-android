package com.patbaumgartner.stringtelephone

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.apache.catalina.connector.Connector
import org.apache.tomcat.util.net.SSLHostConfig
import org.apache.tomcat.util.net.SSLHostConfigCertificate
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

@Configuration(proxyBeanMethods = false)
class TlsConfiguration {
    @Bean
    fun tlsCustomizer(env: Environment): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            val tls = ServerTls.fromEnv(env::getProperty)
            if (tls != null) factory.addAdditionalConnectors(tlsConnector(tls))
        }
}

fun tlsConnector(tls: ServerTls): Connector = Connector("org.apache.coyote.http11.Http11NioProtocol").apply {
    port = tls.port
    scheme = "https"
    secure = true
    setProperty("SSLEnabled", "true")
    val host = SSLHostConfig()
    host.addCertificate(SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED).apply {
        certificateKeystore = tls.keyStore
        certificateKeystorePassword = tls.keyStorePassword
        certificateKeyPassword = tls.keyPassword
        certificateKeyAlias = tls.keyAlias
    })
    addSslHostConfig(host)
}

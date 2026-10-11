package com.patbaumgartner.stringtelephone

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.CookieManager
import java.net.CookiePolicy
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.serialization.json.*
import kotlin.test.*

class TlsIntegrationTest {
    @Test
    fun `native HTTPS WSS and HTTP all work with trusted certificates`() {
        val directory = Files.createTempDirectory("st-tls")
        val file = directory.resolve("api.p12")
        try {
            generateTestCertificate(file)
            val keyStore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, "changeit".toCharArray()) } }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            val http = HttpClient.newBuilder().sslContext(ssl).cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build()
            val port = ServerSocket(0).use { it.localPort }
            RunningServer(mapOf("API_TLS_KEYSTORE" to file.toString(), "API_TLS_KEYSTORE_PASSWORD" to "changeit", "API_HTTPS_PORT" to "$port"), http).use { server ->
                val health = http.send(HttpRequest.newBuilder(URI("https://localhost:$port/health")).build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(200, health.statusCode())
                assertEquals(200, server.get("/health").statusCode())
                server.login()
                val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
                val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content
                val listener = SocketListener()
                val socket = http.newWebSocketBuilder().header("Authorization", "Bearer $bob")
                    .buildAsync(URI("wss://localhost:$port/v1/ws"), listener).join()
                assertEquals(201, server.post("/v1/conversations/bob/messages", """{"recipient":"bob","content":"secure","clientKey":"tls1"}""", alice).statusCode())
                assertTrue(assertNotNull(listener.messages.poll(5, TimeUnit.SECONDS)).contains("new-message"))
                socket.abort()
            }

        } finally {
            Files.deleteIfExists(file)
            Files.delete(directory)
        }
    }
}

fun generateTestCertificate(file: java.nio.file.Path) {
    Files.deleteIfExists(file)
    val keytool = ProcessBuilder("${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-alias", "api",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
        "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", file.toString(),
        "-storepass", "changeit", "-keypass", "changeit", "-noprompt").redirectErrorStream(true).start()
    assertTrue(keytool.waitFor(30, TimeUnit.SECONDS))
    assertEquals(0, keytool.exitValue(), keytool.inputStream.bufferedReader().readText())
}

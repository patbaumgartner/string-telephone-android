package com.patbaumgartner.stringtelephone

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.network.tls.certificates.generateCertificate
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Proves the companion API serves https/wss when TLS is configured while the
 * plain http/ws connector keeps working (both secured and unsecured).
 */
class TlsIntegrationTest {
    private lateinit var dbFile: java.nio.file.Path
    private var engine: ApplicationEngine? = null
    private var httpsPort = 0
    private var httpPort = 0
    private lateinit var keyStore: KeyStore

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempDirectory("st-tls-test").resolve("test.db")
        val db = Database(dbFile.toString())
        val users = Users(db)
        val wsHub = WsHub()
        val messages = Messages(db) { record -> wsHub.broadcastNewMessage(record) }
        val adminTokenHash = sha256Hex("admin-test-token-1234567890")
        keyStore = generateCertificate(
            algorithm = "SHA256withRSA",
            keyAlias = TEST_KEY_ALIAS,
            keyPassword = TEST_KEYSTORE_PASSWORD,
            jksPassword = TEST_KEYSTORE_PASSWORD,
            keySizeInBits = 2048,
        )
        val tls = ServerTls(
            port = 0,
            keyStore = keyStore,
            keyStorePassword = TEST_KEYSTORE_PASSWORD,
            keyAlias = TEST_KEY_ALIAS,
            keyPassword = TEST_KEYSTORE_PASSWORD,
        )
        val server = embeddedServer(
            Netty,
            configure = {
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                secureConnector(tls)
            },
        ) {
            stModule(users, wsHub, messages, adminTokenHash)
        }.start(wait = false)
        engine = server.engine
        runBlocking {
            val connectors = awaitConnectors(server.engine)
            httpPort = connectors.first { it.type == ConnectorType.HTTP }.port
            httpsPort = connectors.first { it.type == ConnectorType.HTTPS }.port
        }
    }

    @AfterTest
    fun tearDown() {
        engine?.stop(1000, 2000)
        engine = null
    }

    private suspend fun awaitConnectors(engine: ApplicationEngine): List<io.ktor.server.engine.EngineConnectorConfig> {
        repeat(50) {
            val connectors = runCatching { engine.resolvedConnectors() }.getOrDefault(emptyList())
            if (connectors.size >= 2) return connectors
            delay(50)
        }
        error("server never resolved both HTTP and HTTPS connectors")
    }

    private fun tlsClient(): HttpClient {
        val trustManager = trustManagerFor(keyStore.getCertificate(TEST_KEY_ALIAS))
        return HttpClient(CIO) {
            engine {
                https {
                    this.trustManager = trustManager
                }
            }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(WebSockets)
        }
    }

    private fun trustManagerFor(certificate: java.security.cert.Certificate): X509TrustManager {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("test", certificate)
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trustStore) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    @Test
    fun `https health works and plain http stays available`() = runBlocking {
        val client = tlsClient()
        val secure = client.get("https://127.0.0.1:$httpsPort/health")
        assertEquals(HttpStatusCode.OK, secure.status)
        assertTrue(secure.bodyAsText().contains("ok"))

        val plain = client.get("http://127.0.0.1:$httpPort/health")
        assertEquals(HttpStatusCode.OK, plain.status)
        client.close()
    }

    @Test
    fun `wss stream delivers new-message events`() = runBlocking {
        val client = tlsClient()
        val aliceToken = createUser(client, "alicetls")
        val bobToken = createUser(client, "bobtls")

        val bobWs = client.webSocketSession("wss://127.0.0.1:$httpsPort/v1/ws?token=$bobToken")
        val received = mutableListOf<String>()
        val job = launch {
            for (frame in bobWs.incoming) {
                if (frame is Frame.Text) received.add(frame.readText())
            }
        }
        delay(300)

        val sendResp = client.post("https://127.0.0.1:$httpsPort/v1/conversations/bobtls/messages") {
            header("Authorization", "Bearer $aliceToken")
            header("Content-Type", "application/json")
            setBody(
                Json.encodeToString(
                    SendMessageRequest.serializer(),
                    SendMessageRequest("bobtls", "hello over wss", "tls-key-1"),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, sendResp.status)

        delay(700)
        assertTrue(received.isNotEmpty(), "Bob should have received a frame over wss")
        assertTrue(received.first().contains("new-message"))
        job.cancel()
        bobWs.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.NORMAL, "bye"))
        client.close()
    }

    private suspend fun createUser(client: HttpClient, username: String): String {
        val response = client.post("https://127.0.0.1:$httpsPort/v1/admin/create-user") {
            header("Authorization", "Bearer admin-test-token-1234567890")
            header("Content-Type", "application/json")
            setBody(
                Json.encodeToString(
                    CreateUserRequest.serializer(),
                    CreateUserRequest(username, username),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.decodeFromString<CreateResponse>(response.bodyAsText()).apiToken
    }
}

private const val TEST_KEY_ALIAS = "test"
private const val TEST_KEYSTORE_PASSWORD = "changeit"

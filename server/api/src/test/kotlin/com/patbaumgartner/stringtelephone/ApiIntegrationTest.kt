package com.patbaumgartner.stringtelephone

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class ApiIntegrationTest {
    private lateinit var dbFile: java.nio.file.Path
    private var engine: ApplicationEngine? = null
    private var port: Int = 0

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempDirectory("st-test").resolve("test.db")
        val db = Database(dbFile.toString())
        val users = Users(db)
        val wsHub = WsHub()
        val messages = Messages(db) { record -> wsHub.broadcastNewMessage(record) }
        val adminTokenHash = sha256Hex("admin-test-token-1234567890")
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            stModule(users, wsHub, messages, adminTokenHash)
        }.start(wait = false)
        engine = server.engine
        port = runBlocking {
            runCatching { server.engine.resolvedConnectors().first().port }.getOrDefault(18084)
        }
    }

    @AfterTest
    fun tearDown() {
        engine?.stop(1000, 2000)
        engine = null
    }

    private val baseUrl get() = "http://127.0.0.1:$port"

    /** WebSocket URLs must use the ws:// scheme; http:// would skip the upgrade. */
    private val wsBaseUrl get() = "ws://127.0.0.1:$port"

    private fun adminClient() = io.ktor.client.HttpClient {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    @Test
    fun `create user, use token, send and read messages`() = runBlocking {
        val client = adminClient()
        val createResp = client.post("$baseUrl/v1/admin/create-user") {
            header("Authorization", "Bearer admin-test-token-1234567890")
            header("Content-Type", "application/json")
            setBody(Json.encodeToString(CreateUserRequest.serializer(), CreateUserRequest("alice", "Alice")))
        }
        assertEquals(HttpStatusCode.Created, createResp.status)
        val alice = Json.decodeFromString<CreateResponse>(createResp.bodyAsText())

        val bobResp = client.post("$baseUrl/v1/admin/create-user") {
            header("Authorization", "Bearer admin-test-token-1234567890")
            header("Content-Type", "application/json")
            setBody(Json.encodeToString(CreateUserRequest.serializer(), CreateUserRequest("bob", "Bob")))
        }
        assertEquals(HttpStatusCode.Created, bobResp.status)

        val meResp = client.get("$baseUrl/v1/me") {
            header("Authorization", "Bearer ${alice.apiToken}")
        }
        assertEquals(HttpStatusCode.OK, meResp.status)
        assertTrue(meResp.bodyAsText().contains("alice"))

        val sendResp = client.post("$baseUrl/v1/conversations/bob/messages") {
            header("Authorization", "Bearer ${alice.apiToken}")
            header("Content-Type", "application/json"); setBody(Json.encodeToString(SendMessageRequest.serializer(), SendMessageRequest("bob", "hello bob", "k1")))
        }
        assertEquals(HttpStatusCode.Created, sendResp.status)

        val dupResp = client.post("$baseUrl/v1/conversations/bob/messages") {
            header("Authorization", "Bearer ${alice.apiToken}")
            header("Content-Type", "application/json"); setBody(Json.encodeToString(SendMessageRequest.serializer(), SendMessageRequest("bob", "hello bob", "k1")))
        }
        assertEquals(HttpStatusCode.Created, dupResp.status)

        val pageResp = client.get("$baseUrl/v1/conversations/bob/messages") {
            header("Authorization", "Bearer ${alice.apiToken}")
        }
        assertEquals(HttpStatusCode.OK, pageResp.status)
        assertTrue(pageResp.bodyAsText().contains("hello bob"))

        val unauth = client.get("$baseUrl/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)

        val badToken = client.get("$baseUrl/v1/me") {
            header("Authorization", "Bearer st_nope")
        }
        assertEquals(HttpStatusCode.Unauthorized, badToken.status)

        val revokedToken = client.post("$baseUrl/v1/admin/revoke-user") {
            header("Authorization", "Bearer admin-test-token-1234567890")
            header("Content-Type", "application/json")
            setBody(Json.encodeToString(RevokeRequest.serializer(), RevokeRequest("bob")))
        }
        assertEquals(HttpStatusCode.OK, revokedToken.status)

        val bobToken = Json.decodeFromString<CreateResponse>(bobResp.bodyAsText())
        val revokedAccess = client.get("$baseUrl/v1/me") {
            header("Authorization", "Bearer ${bobToken.apiToken}")
        }
        assertEquals(HttpStatusCode.Unauthorized, revokedAccess.status)
        client.close()
    }

    @Test
    fun `websocket receives new-message event when peer sends a message`() = runBlocking {
        val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(io.ktor.client.plugins.websocket.WebSockets)
        }

        val createAlice = httpClient.post("$baseUrl/v1/admin/create-user") {
            header("Authorization", "Bearer admin-test-token-1234567890")
            header("Content-Type", "application/json")
            setBody(Json.encodeToString(CreateUserRequest.serializer(), CreateUserRequest("alicews", "Alice")))
        }
        assertEquals(HttpStatusCode.Created, createAlice.status)
        val aliceToken = Json.decodeFromString<CreateResponse>(createAlice.bodyAsText()).apiToken

        val createBob = httpClient.post("$baseUrl/v1/admin/create-user") {
            header("Authorization", "Bearer admin-test-token-1234567890")
            header("Content-Type", "application/json")
            setBody(Json.encodeToString(CreateUserRequest.serializer(), CreateUserRequest("bobws", "Bob")))
        }
        assertEquals(HttpStatusCode.Created, createBob.status)
        val bobToken = Json.decodeFromString<CreateResponse>(createBob.bodyAsText()).apiToken

        val bobWs = httpClient.webSocketSession("$wsBaseUrl/v1/ws?token=$bobToken")
        val bobReceived = mutableListOf<String>()
        val bobJob = launch {
            for (frame in bobWs.incoming) {
                if (frame is Frame.Text) bobReceived.add(frame.readText())
            }
        }
        delay(300)

        val sendResp = httpClient.post("$baseUrl/v1/conversations/bobws/messages") {
            header("Authorization", "Bearer $aliceToken")
            header("Content-Type", "application/json")
            setBody(Json.encodeToString(SendMessageRequest.serializer(), SendMessageRequest("bobws", "hello from alice via ws", "ws-key-1")))
        }
        assertEquals(HttpStatusCode.Created, sendResp.status)

        delay(700)
        assertTrue(bobReceived.isNotEmpty(), "Bob should have received a WebSocket frame")
        assertTrue(bobReceived.first().contains("new-message"), "Frame type should be new-message")

        bobJob.cancel()
        bobWs.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.NORMAL, "bye"))
        httpClient.close()
    }

    @Test
    fun `websocket rejects invalid token`() = runBlocking {
        val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }
        val session = httpClient.webSocketSession("$wsBaseUrl/v1/ws?token=st_invalid")
        val closeReason = kotlinx.coroutines.withTimeoutOrNull(5_000) { session.closeReason.await() }
        assertEquals(
            io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY.code,
            closeReason?.code,
            "The server should close an invalid-token socket with 1008",
        )
        httpClient.close()
    }
}

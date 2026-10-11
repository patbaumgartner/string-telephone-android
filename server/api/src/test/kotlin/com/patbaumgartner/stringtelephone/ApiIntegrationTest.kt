package com.patbaumgartner.stringtelephone

import java.net.URI
import java.net.http.WebSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*

class ApiIntegrationTest {
    private lateinit var server: RunningServer

    @BeforeTest fun setUp() { server = RunningServer() }
    @AfterTest fun tearDown() { server.close() }

    @Test
    fun `admin requires username password and CSRF while Android requires its own bearer token`() {
        assertEquals(302, server.get("/admin/").statusCode())
        assertEquals(403, server.post("/admin/login", "username=operator&password=wrong", csrf = false, form = true).statusCode())
        val page = server.get("/admin/login")
        assertFalse(page.body().contains("maxlength"))
        server.csrf = csrfToken(page.body())
        val wrong = server.post("/admin/login", "username=operator&password=wrong&_csrf=${server.csrf}", form = true)
        assertEquals(302, wrong.statusCode())
        assertTrue(wrong.headers().firstValue("Location").orElse("").contains("error"))
        server.login()
        assertEquals(200, server.get("/admin/").statusCode())
        assertEquals(403, server.post("/v1/admin/create-user", """{"username":"alice","displayName":"Alice"}""", csrf = false).statusCode())
        assertEquals(401, server.get("/v1/me").statusCode())
        assertEquals(401, server.get("/v1/me", "wrong").statusCode())
        assertTrue(server.get("/admin/").headers().firstValue("Cache-Control").orElse("").contains("no-store"))
    }

    @Test
    fun `create account automatically provisions separate long SIP credentials and supports Android message contracts`() {
        server.login()
        val alice = server.create("alice", "Alice", TEST_PASSPHRASE)
        val bob = server.create("bob", "Bob")
        assertTrue(alice.getValue("ready").jsonPrimitive.boolean)
        assertEquals(TEST_PASSPHRASE, alice.getValue("sipSecret").jsonPrimitive.content)
        val token = alice.getValue("apiToken").jsonPrimitive.content
        assertTrue(token != TEST_PASSPHRASE)
        assertEquals(sipDigest("alice", TEST_PASSPHRASE), server.pbx.applied.first { it.username == "alice" }.digest)
        assertEquals("alice", Json.parseToJsonElement(server.get("/v1/me", token).body()).jsonObject.getValue("username").jsonPrimitive.content)
        val contacts = server.get("/v1/contacts", token)
        assertTrue(contacts.body().contains("bob"))
        val body = """{"recipient":"bob","content":"hello bob","clientKey":"k1","sender":"mallory"}"""
        val first = server.post("/v1/conversations/bob/messages", body, token)
        assertEquals(201, first.statusCode(), first.body())
        val record = Json.parseToJsonElement(first.body()).jsonObject
        assertEquals("alice", record.getValue("sender").jsonPrimitive.content)
        val duplicate = server.post("/v1/conversations/bob/messages", body, token)
        assertEquals(record.getValue("id"), Json.parseToJsonElement(duplicate.body()).jsonObject.getValue("id"))
        assertTrue(server.get("/v1/conversations/bob/messages", token).body().contains("hello bob"))
        assertEquals(400, server.get("/v1/conversations/bob/messages?cursor=bad", token).statusCode())
        assertEquals(400, server.post("/v1/conversations/bob/messages", """{"recipient":"alice","content":"hello","clientKey":"k2"}""", token).statusCode())
        assertEquals(413, server.post("/v1/conversations/bob/messages", """{"recipient":"bob","content":"${"x".repeat(8193)}","clientKey":"k3"}""", token).statusCode())
        assertEquals(404, server.post("/v1/conversations/nobody/messages", """{"recipient":"nobody","content":"x","clientKey":"k4"}""", token).statusCode())
        assertEquals(200, server.get("/v1/me", bob.getValue("apiToken").jsonPrimitive.content).statusCode())
    }

    @Test
    fun `websocket receives Android envelope and disabling account disconnects API websocket and PBX calls`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content
        val listener = SocketListener()
        val socket = server.http.newWebSocketBuilder().header("Authorization", "Bearer $bob")
            .buildAsync(URI("${server.base.replaceFirst("http", "ws")}/v1/ws"), listener).join()
        val response = server.post("/v1/conversations/bob/messages", """{"recipient":"bob","content":"live","clientKey":"ws-1"}""", alice)
        assertEquals(201, response.statusCode())
        val envelope = Json.parseToJsonElement(assertNotNull(listener.messages.poll(5, TimeUnit.SECONDS))).jsonObject
        assertEquals("new-message", envelope.getValue("type").jsonPrimitive.content)
        val payload = Json.parseToJsonElement(envelope.getValue("payload").jsonPrimitive.content).jsonObject
        assertEquals("live", payload.getValue("content").jsonPrimitive.content)
        assertEquals(200, server.post("/v1/admin/revoke-user", """{"username":"bob"}""").statusCode())
        assertEquals(401, server.get("/v1/me", bob).statusCode())
        assertEquals(1008, listener.closed.poll(5, TimeUnit.SECONDS))
        assertTrue("bob" in server.pbx.disconnected)
        assertFalse(server.pbx.applied.any { it.username == "bob" })
        socket.abort()
    }

    @Test
    fun `invalid websocket credentials close with 1008`() {
        val listener = SocketListener()
        val socket = server.http.newWebSocketBuilder().header("Authorization", "Bearer wrong")
            .buildAsync(URI("${server.base.replaceFirst("http", "ws")}/v1/ws"), listener).join()
        assertEquals(1008, listener.closed.poll(5, TimeUnit.SECONDS))
        socket.abort()
    }

    @Test
    fun `reset ends calls invalidates old API token and updates PBX digest and reenable works`() {
        server.login()
        val old = server.create("alice", "Alice")
        val reset = server.post("/v1/admin/reset-user", """{"username":"alice","sipPassword":"$TEST_PASSPHRASE"}""")
        assertEquals(200, reset.statusCode())
        val newToken = Json.parseToJsonElement(reset.body()).jsonObject.getValue("apiToken").jsonPrimitive.content
        assertEquals(401, server.get("/v1/me", old.getValue("apiToken").jsonPrimitive.content).statusCode())
        assertEquals(200, server.get("/v1/me", newToken).statusCode())
        assertTrue("alice" in server.pbx.disconnected)
        assertEquals(sipDigest("alice", TEST_PASSPHRASE), server.pbx.applied.single().digest)
        assertEquals(200, server.post("/v1/admin/revoke-user", """{"username":"alice"}""").statusCode())
        assertEquals(401, server.get("/v1/me", newToken).statusCode())
        assertEquals(200, server.post("/v1/admin/set-enabled", """{"username":"alice","enabled":true}""").statusCode())
        assertEquals(200, server.get("/v1/me", newToken).statusCode())
    }

    @Test
    fun `PBX outage is explicit pending creation retains one time credentials and retry reconciles desired state`() {
        server.login()
        server.pbx.available = false
        val created = server.post("/v1/admin/create-user", """{"username":"alice","displayName":"Alice"}""")
        assertEquals(202, created.statusCode())
        val credential = Json.parseToJsonElement(created.body()).jsonObject
        assertFalse(credential.getValue("ready").jsonPrimitive.boolean)
        val token = credential.getValue("apiToken").jsonPrimitive.content
        assertEquals(401, server.get("/v1/me", token).statusCode())
        val page = server.get("/admin/")
        assertTrue(page.body().contains("Synchronization required"))
        server.pbx.available = true
        assertEquals(200, server.post("/v1/admin/pbx/reconcile", "{}").statusCode())
        assertEquals(200, server.get("/v1/me", token).statusCode())
        server.pbx.available = false
        assertEquals(503, server.post("/v1/admin/revoke-user", """{"username":"alice"}""").statusCode())
        assertEquals(401, server.get("/v1/me", token).statusCode())
        server.pbx.available = true
        assertEquals(200, server.post("/v1/admin/pbx/reconcile", "{}").statusCode())
        assertTrue(server.pbx.applied.isEmpty())
    }

    @Test
    fun `settings are validated persisted and applied and rendered names are escaped`() {
        server.login()
        server.create("alice", "<script>alert(1)</script>")
        assertTrue(server.get("/admin/").body().contains("&lt;script&gt;"))
        val settings = """{"codecs":"ulaw,alaw","ringSeconds":45,"maxContacts":1,"externalAddress":"203.0.113.5","localNetwork":"192.168.0.0/24","symmetricMedia":true}"""
        assertEquals(200, server.post("/v1/admin/pbx/settings", settings).statusCode())
        assertEquals(45, server.pbx.settings.ringSeconds)
        assertEquals(400, server.post("/v1/admin/pbx/settings", settings.replace("203.0.113.5", "203.0.113.5\\n[bad]")).statusCode())
        assertEquals(400, server.post("/v1/admin/create-user", """{"username":"../escape","displayName":"Bad"}""").statusCode())
        assertEquals(409, server.post("/v1/admin/create-user", """{"username":"alice","displayName":"Alice"}""").statusCode())
        val html = server.post("/admin/accounts", "username=bob&displayName=Bob&sipPassword=$TEST_PASSPHRASE", form = true)
        assertEquals(200, html.statusCode())
        assertTrue(html.body().contains("Save these credentials now"))
        assertTrue(html.body().contains(TEST_PASSPHRASE))
        assertFalse(server.get("/admin/").body().contains(TEST_PASSPHRASE))
    }
}

class SocketListener : WebSocket.Listener {
    val messages = LinkedBlockingQueue<String>()
    val closed = LinkedBlockingQueue<Int>()
    private val text = StringBuilder()
    override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
        text.append(data)
        if (last) { messages.add(text.toString()); text.setLength(0) }
        webSocket.request(1)
        return null
    }
    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): java.util.concurrent.CompletionStage<*>? {
        closed.add(statusCode)
        return null
    }
}

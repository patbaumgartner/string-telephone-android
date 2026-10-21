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
    fun `message cursor pages include every message with the same timestamp`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        server.create("bob", "Bob")

        listOf("first", "second", "third").forEachIndexed { index, content ->
            val response = server.post(
                "/v1/conversations/bob/messages",
                """{"recipient":"bob","content":"$content","clientKey":"page-$index"}""",
                alice,
            )
            assertEquals(201, response.statusCode())
        }

        server.context.getBean(org.springframework.jdbc.core.simple.JdbcClient::class.java)
            .sql("UPDATE messages SET created_at=? WHERE sender=? AND recipient=?")
            .params(1_700_000_000_000, "alice", "bob")
            .update()

        val firstPage = Json.parseToJsonElement(
            server.get("/v1/conversations/bob/messages?limit=2", alice).body(),
        ).jsonObject
        val cursor = firstPage.getValue("nextCursor").jsonPrimitive.content
        val secondPage = Json.parseToJsonElement(
            server.get("/v1/conversations/bob/messages?limit=2&cursor=$cursor", alice).body(),
        ).jsonObject
        val ids = (firstPage.getValue("messages").jsonArray + secondPage.getValue("messages").jsonArray)
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }

        assertEquals(3, ids.size)
        assertEquals(3, ids.toSet().size)
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
        val envelope = listener.next("new-message")
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
    fun `contacts report SIP and realtime presence and connected users receive presence events`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content
        server.pbx.deviceStates["bob"] = "Unavailable"
        val offline = contact(alice, "bob")
        assertEquals("offline", offline.getValue("presence").jsonPrimitive.content)
        assertNotNull(offline.getValue("presenceUpdatedAtEpochMs").jsonPrimitive.longOrNull)

        val aliceEvents = SocketListener()
        val aliceSocket = connect(alice, aliceEvents)
        val bobSocket = connect(bob, SocketListener())
        aliceEvents.nextPresence("bob", "online")
        assertEquals("online", contact(alice, "bob").getValue("presence").jsonPrimitive.content)

        bobSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join()
        aliceEvents.nextPresence("bob", "offline")
        aliceSocket.abort()
    }

    private fun connect(token: String, listener: SocketListener): WebSocket =
        server.http.newWebSocketBuilder().header("Authorization", "Bearer $token")
            .buildAsync(URI("${server.base.replaceFirst("http", "ws")}/v1/ws"), listener).join()

    private fun contact(token: String, username: String): JsonObject =
        Json.parseToJsonElement(server.get("/v1/contacts", token).body()).jsonObject.getValue("contacts").jsonArray
            .map { it.jsonObject }.single { it.getValue("username").jsonPrimitive.content == username }

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
    fun `re-enabling does not depend on a reachable PBX to restore API access`() {
        server.login()
        val created = server.create("alice", "Alice")
        assertEquals(200, server.post("/v1/admin/revoke-user", """{"username":"alice"}""").statusCode())
        val token = created.getValue("apiToken").jsonPrimitive.content
        assertEquals(401, server.get("/v1/me", token).statusCode())
        val revoked = server.db.sql("SELECT revoked_at FROM users WHERE username='alice'")
            .query(Long::class.javaObjectType).optional()
        assertNotNull(revoked.orElse(null))
        // The account is enabled again while the PBX is down: the deliberate
        // state must be committed, not deferred to a reconcile that cannot run.
        server.pbx.available = false
        assertEquals(503, server.post("/v1/admin/set-enabled", """{"username":"alice","enabled":true}""").statusCode())
        server.pbx.available = true
        assertEquals(
            1,
            server.db.sql("SELECT active FROM users WHERE username='alice'").query(Int::class.java).single(),
        )
        assertNull(
            server.db.sql("SELECT revoked_at FROM users WHERE username='alice'")
                .query(Long::class.javaObjectType).optional().orElse(null),
        )
        assertEquals(200, server.get("/v1/me", token).statusCode())
    }

    @Test
    fun `ready reports database reachability while health stays a process check`() {
        assertEquals(200, server.get("/health").statusCode())
        val ready = server.get("/ready")
        assertEquals(200, ready.statusCode())
        assertTrue(ready.body().contains("\"database\":\"up\""))
        assertEquals(200, server.get("/actuator/health").statusCode())
    }

    @Test
    fun `read receipts are mutual and only the recipient can mark a message read`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content
        val carol = server.create("carol", "Carol").getValue("apiToken").jsonPrimitive.content
        val aliceEvents = SocketListener()
        val aliceSocket = connect(alice, aliceEvents)

        val sent = server.post("/v1/conversations/bob/messages", """{"recipient":"bob","content":"hello bob","clientKey":"r1"}""", alice)
        assertEquals(201, sent.statusCode(), sent.body())
        val id = Json.parseToJsonElement(sent.body()).jsonObject.getValue("id").jsonPrimitive.content

        // Unread until the recipient opens the conversation.
        val before = messageOf(server.get("/v1/conversations/bob/messages", alice), id)
        assertNull(before["readAt"])

        // The author cannot mark the message read on the recipient's behalf.
        assertEquals(404, server.post("/v1/conversations/bob/read", """{"upToMessageId":"$id"}""", alice).statusCode())

        // A third party may not name a message of someone else's conversation...
        assertEquals(404, server.post("/v1/conversations/bob/read", """{"upToMessageId":"$id"}""", carol).statusCode())
        // ...and a blind mark touches no message of this conversation.
        val stranger = server.post("/v1/conversations/bob/read", "{}", carol)
        assertEquals(200, stranger.statusCode(), stranger.body())
        assertEquals(0, Json.parseToJsonElement(stranger.body()).jsonObject.getValue("messageIds").jsonArray.size)
        assertNull(messageOf(server.get("/v1/conversations/bob/messages", alice), id)["readAt"])

        // The recipient marks it read; the author learns about it over the websocket.
        // The peer is the other party, so the recipient addresses the author.
        val mark = server.post("/v1/conversations/alice/read", """{"upToMessageId":"$id"}""", bob)
        assertEquals(200, mark.statusCode(), mark.body())
        val marked = Json.parseToJsonElement(mark.body()).jsonObject
        assertEquals(listOf(id), marked.getValue("messageIds").jsonArray.map { it.jsonPrimitive.content })
        val readAt = marked.getValue("readAt").jsonPrimitive.long
        assertTrue(readAt > 0)

        val event = aliceEvents.next("message-read")
        val payload = Json.parseToJsonElement(event.getValue("payload").jsonPrimitive.content).jsonObject
        assertEquals(listOf(id), payload.getValue("messageIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(readAt, payload.getValue("readAt").jsonPrimitive.long)

        // The author sees the receipt in the conversation history.
        assertEquals(readAt, messageOf(server.get("/v1/conversations/bob/messages", alice), id).getValue("readAt").jsonPrimitive.long)

        // Marking again is idempotent and never moves the receipt.
        val again = server.post("/v1/conversations/alice/read", """{"upToMessageId":"$id"}""", bob)
        assertEquals(200, again.statusCode())
        assertEquals(0, Json.parseToJsonElement(again.body()).jsonObject.getValue("messageIds").jsonArray.size)
        assertEquals(readAt, messageOf(server.get("/v1/conversations/bob/messages", alice), id).getValue("readAt").jsonPrimitive.long)
        aliceSocket.abort()
    }

    @Test
    fun `a read mark without a message id reads the whole conversation and the server owns the identity`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content
        val ids = (1..3).map { index ->
            val response = server.post("/v1/conversations/bob/messages", """{"recipient":"bob","content":"m$index","clientKey":"bulk-$index"}""", alice)
            assertEquals(201, response.statusCode())
            Json.parseToJsonElement(response.body()).jsonObject.getValue("id").jsonPrimitive.content
        }
        val mark = server.post("/v1/conversations/alice/read", "{}", bob)
        assertEquals(200, mark.statusCode(), mark.body())
        assertEquals(ids, Json.parseToJsonElement(mark.body()).jsonObject.getValue("messageIds").jsonArray.map { it.jsonPrimitive.content })
        for (id in ids) {
            assertNotNull(messageOf(server.get("/v1/conversations/bob/messages", alice), id)["readAt"])
        }
        // The recipient's own messages stay unread: the receipt is about what the reader read.
        val reply = server.post("/v1/conversations/alice/messages", """{"recipient":"alice","content":"hi alice","clientKey":"bulk-reply"}""", bob)
        assertEquals(201, reply.statusCode())
        val replyId = Json.parseToJsonElement(reply.body()).jsonObject.getValue("id").jsonPrimitive.content
        assertNull(messageOf(server.get("/v1/conversations/alice/messages", bob), replyId)["readAt"])
    }

    private fun messageOf(response: java.net.http.HttpResponse<String>, id: String): JsonObject =
        Json.parseToJsonElement(response.body()).jsonObject.getValue("messages").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }

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

    /** Next envelope of [type]; other event types (e.g. presence) are skipped. */
    fun next(type: String): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val remaining = deadline - System.nanoTime()
            val frame = assertNotNull(messages.poll(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS), "no $type event")
            val envelope = Json.parseToJsonElement(frame).jsonObject
            if (envelope.getValue("type").jsonPrimitive.content == type) return envelope
        }
    }

    /** Waits for a presence event matching [username] and [presence]; the periodic poll may repeat states. */
    fun nextPresence(username: String, presence: String) {
        while (true) {
            val payload = Json.parseToJsonElement(next("presence").getValue("payload").jsonPrimitive.content).jsonObject
            if (payload.getValue("username").jsonPrimitive.content == username &&
                payload.getValue("presence").jsonPrimitive.content == presence
            ) return
        }
    }
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

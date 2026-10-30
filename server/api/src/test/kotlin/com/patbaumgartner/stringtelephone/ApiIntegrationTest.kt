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
    fun `last seen is stamped on final disconnect cleared on reconnect and carried by contacts and presence frames`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content

        // Never connected: contacts omit last-seen entirely.
        assertFalse(contact(alice, "bob").jsonObject.containsKey("lastSeenEpochMs"))
        // Bob is SIP-unreachable, so once his stream closes the derived
        // presence is offline (the FakePbx defaults endpoints to registered).
        server.pbx.deviceStates["bob"] = "Unavailable"

        val bobEvents = SocketListener()
        val bobSocket = connect(bob, bobEvents)
        // Connected now: still no last-seen.
        assertFalse(contact(alice, "bob").jsonObject.containsKey("lastSeenEpochMs"))

        // Alice listens for the offline push her contact list will reflect.
        val aliceEvents = SocketListener()
        val aliceSocket = connect(alice, aliceEvents)

        bobSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join()
        val lastSeen = assertLastSeenAppears(alice, "bob")
        assertTrue(lastSeen > 0, "last-seen must be a positive epoch ms")

        // The offline presence frame carries the same stamp (push, not just poll).
        val offline = aliceEvents.nextPresence("bob", "offline")
        assertEquals(lastSeen, offline.getValue("lastSeenAt").jsonPrimitive.long)

        // Reconnecting clears it again.
        val bobSocket2 = connect(bob, SocketListener())
        waitUntilLastSeenVanishes(alice, "bob")
        aliceSocket.abort()
        bobSocket2.abort()
    }

    @Test
    fun `read receipts fan out to both sides so the reader's other devices converge`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content

        // Alice (author) and a second bob device both listen.
        val aliceEvents = SocketListener()
        val aliceSocket = connect(alice, aliceEvents)
        val bob2Events = SocketListener()
        val bob2Socket = connect(bob, bob2Events)
        val bob1Socket = connect(bob, SocketListener()) // the device that will mark read

        assertEquals(201, server.post("/v1/conversations/bob/messages", """{"recipient":"bob","content":"read me","clientKey":"multi-1"}""", alice).statusCode())
        // The marking device must have received the message before marking.
        val envelope = Json.parseToJsonElement(aliceEvents.next("new-message").getValue("payload").jsonPrimitive.content).jsonObject
        val messageId = envelope.getValue("id").jsonPrimitive.content
        bob2Events.next("new-message")

        // Bob's first device marks read; both the author AND bob's second device get the frame.
        assertEquals(200, server.post("/v1/conversations/alice/read", """{"upToMessageId":"$messageId"}""", bob).statusCode())
        val authorFrame = Json.parseToJsonElement(aliceEvents.next("message-read").getValue("payload").jsonPrimitive.content).jsonObject
        assertEquals(messageId, authorFrame.getValue("messageIds").jsonArray.single().jsonPrimitive.content)
        val otherDeviceFrame = Json.parseToJsonElement(bob2Events.next("message-read").getValue("payload").jsonPrimitive.content).jsonObject
        assertEquals(messageId, otherDeviceFrame.getValue("messageIds").jsonArray.single().jsonPrimitive.content)
        aliceSocket.abort(); bob2Socket.abort()
    }

    private fun assertLastSeenAppears(token: String, username: String): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            // The key is absent until the disconnect is stamped; poll, don't throw.
            val value = contact(token, username).jsonObject["lastSeenEpochMs"]?.jsonPrimitive?.longOrNull
            if (value != null && value > 0) return value
            Thread.sleep(100)
        }
        error("last-seen never appeared for $username")
    }

    private fun waitUntilLastSeenVanishes(token: String, username: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val value = contact(token, username).jsonObject["lastSeenEpochMs"]?.jsonPrimitive?.longOrNull
            if (value == null) return
            Thread.sleep(100)
        }
        error("last-seen never cleared for $username")
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
    fun `message search finds matches only inside the authenticated conversation`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content
        val carol = server.create("carol", "Carol").getValue("apiToken").jsonPrimitive.content

        // Alice ↔ Bob conversation: the only place "pineapple" appears.
        assertEquals(201, server.post("/v1/conversations/bob/messages", """{"recipient":"bob","content":"pineapple pizza tonight?","clientKey":"s-1"}""", alice).statusCode())
        assertEquals(201, server.post("/v1/conversations/alice/messages", """{"recipient":"alice","content":"always, bring pineapple","clientKey":"s-2"}""", bob).statusCode())
        // Alice ↔ Carol: the same words must NOT leak into carol's search scope.
        assertEquals(201, server.post("/v1/conversations/carol/messages", """{"recipient":"carol","content":"the weather is nice","clientKey":"s-3"}""", alice).statusCode())

        // Case-insensitive match across both directions of the pair.
        val found = Json.parseToJsonElement(
            server.get("/v1/conversations/bob/search?q=PINEAPPLE", alice).body(),
        ).jsonObject.getValue("messages").jsonArray
        assertEquals(2, found.size, "both directions of the pair must match, case-insensitively")

        // No match: empty page.
        val none = Json.parseToJsonElement(
            server.get("/v1/conversations/bob/search?q=kiwi", alice).body(),
        ).jsonObject
        assertEquals(0, none.getValue("messages").jsonArray.size)

        // Carol searching her own conversation with alice finds nothing of Bob's.
        val carolsView = Json.parseToJsonElement(
            server.get("/v1/conversations/alice/search?q=pineapple", carol).body(),
        ).jsonObject
        assertEquals(0, carolsView.getValue("messages").jsonArray.size, "another pair's conversation must not leak")

        // A word that only exists in alice ↔ carol stays there.
        val weather = Json.parseToJsonElement(
            server.get("/v1/conversations/carol/search?q=weather", alice).body(),
        ).jsonObject.getValue("messages").jsonArray
        assertEquals(1, weather.size)

        // Blank queries are rejected; unknown peers 404.
        assertEquals(400, server.get("/v1/conversations/bob/search?q=%20", alice).statusCode())
        assertEquals(404, server.get("/v1/conversations/stranger/search?q=x", alice).statusCode())

        // Tombstones carry no content and never match.
        assertEquals(400, server.get("/v1/conversations/bob/search", alice).statusCode(), "missing q must be a bad request")
    }

    @Test
    fun `message search pagination walks the whole conversation with cursors`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        server.create("bob", "Bob")

        repeat(3) { index ->
            assertEquals(201, server.post(
                "/v1/conversations/bob/messages",
                """{"recipient":"bob","content":"needle number $index","clientKey":"p-$index"}""",
                alice,
            ).statusCode())
        }

        val firstPage = Json.parseToJsonElement(
            server.get("/v1/conversations/bob/search?q=needle&limit=2", alice).body(),
        ).jsonObject
        assertEquals(2, firstPage.getValue("messages").jsonArray.size)
        val cursor = firstPage.getValue("nextCursor").jsonPrimitive.content

        val secondPage = Json.parseToJsonElement(
            server.get("/v1/conversations/bob/search?q=needle&limit=2&cursor=$cursor", alice).body(),
        ).jsonObject
        val ids = (firstPage.getValue("messages").jsonArray + secondPage.getValue("messages").jsonArray)
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(3, ids.size)
        assertEquals(3, ids.toSet().size, "no message may appear on both pages")
    }

    @Test
    fun `editing is author-only updates content and fans out a message-edited event`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content

        val sent = server.post("/v1/conversations/bob/messages", """{"recipient":"bob","content":"helo bob","clientKey":"e-1"}""", alice)
        assertEquals(201, sent.statusCode(), sent.body())
        val id = Json.parseToJsonElement(sent.body()).jsonObject.getValue("id").jsonPrimitive.content

        // Bob (not the author) may not edit.
        assertEquals(403, server.put("/v1/conversations/alice/messages/$id", """{"content":"hacked"}""", bob).statusCode())

        // The author edits; the response is the updated record.
        val edited = server.put("/v1/conversations/bob/messages/$id", """{"content":"hello bob"}""", alice)
        assertEquals(200, edited.statusCode(), edited.body())
        val record = Json.parseToJsonElement(edited.body()).jsonObject
        assertEquals("hello bob", record.getValue("content").jsonPrimitive.content)
        val editedAt = record.getValue("editedAt").jsonPrimitive.long
        assertTrue(editedAt > 0, "editedAt must be stamped")

        // History reflects the edit.
        val history = Json.parseToJsonElement(server.get("/v1/conversations/bob/messages", alice).body()).jsonObject
        val row = history.getValue("messages").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
        assertEquals("hello bob", row.getValue("content").jsonPrimitive.content)
        assertEquals(editedAt, row.getValue("editedAt").jsonPrimitive.long)

        // Both participants receive the message-edited event with the new content.
        val aliceEvents = SocketListener()
        val aliceSocket = connect(alice, aliceEvents)
        val bobEvents = SocketListener()
        val bobSocket = connect(bob, bobEvents)
        assertEquals(200, server.put("/v1/conversations/bob/messages/$id", """{"content":"hello bob, again"}""", alice).statusCode())
        for (listener in listOf(aliceEvents, bobEvents)) {
            val envelope = listener.next("message-edited")
            val payload = Json.parseToJsonElement(envelope.getValue("payload").jsonPrimitive.content).jsonObject
            assertEquals(id, payload.getValue("messageId").jsonPrimitive.content)
            assertEquals("hello bob, again", payload.getValue("content").jsonPrimitive.content)
            assertEquals("bob", payload.getValue("peer").jsonPrimitive.content)
        }
        aliceSocket.abort()
        bobSocket.abort()

        // An empty edit is rejected like an empty send.
        assertEquals(400, server.put("/v1/conversations/bob/messages/$id", """{"content":""}""", alice).statusCode())
    }

    @Test
    fun `editing a tombstoned message conflicts and deleting after editing wins`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        server.create("bob", "Bob")

        val sent = server.post("/v1/conversations/bob/messages", """{"recipient":"bob","content":"to be removed","clientKey":"e-2"}""", alice)
        assertEquals(201, sent.statusCode())
        val id = Json.parseToJsonElement(sent.body()).jsonObject.getValue("id").jsonPrimitive.content

        // Edit first, then delete: the tombstone wins and carries no content.
        assertEquals(200, server.put("/v1/conversations/bob/messages/$id", """{"content":"still there?"}""", alice).statusCode())
        assertEquals(200, server.delete("/v1/conversations/bob/messages/$id", alice).statusCode())

        // Editing a tombstone is a 409: deletes take precedence.
        assertEquals(409, server.put("/v1/conversations/bob/messages/$id", """{"content":"revive"}""", alice).statusCode())

        // The tombstone has no content and no revived editedAt bump.
        val row = Json.parseToJsonElement(server.get("/v1/conversations/bob/messages", alice).body()).jsonObject
            .getValue("messages").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
        assertEquals("", row.getValue("content").jsonPrimitive.content)
        assertNotNull(row.getValue("deletedAt").jsonPrimitive.longOrNull)
    }

    @Test
    fun `an edit preserves an existing read receipt instead of un-reading the message`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content

        val sent = server.post("/v1/conversations/bob/messages", """{"recipient":"bob","content":"read me then edit me","clientKey":"e-3"}""", alice)
        assertEquals(201, sent.statusCode())
        val id = Json.parseToJsonElement(sent.body()).jsonObject.getValue("id").jsonPrimitive.content

        // Bob reads the message.
        assertEquals(200, server.post("/v1/conversations/alice/read", """{"upToMessageId":"$id"}""", bob).statusCode())

        // Alice edits: the read receipt must survive the edit (ADR-0010).
        assertEquals(200, server.put("/v1/conversations/bob/messages/$id", """{"content":"edited after read"}""", alice).statusCode())
        val row = Json.parseToJsonElement(server.get("/v1/conversations/bob/messages", alice).body()).jsonObject
            .getValue("messages").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
        assertNotNull(row.getValue("readAt").jsonPrimitive.longOrNull, "an edit must not un-read a read message")
        assertEquals("edited after read", row.getValue("content").jsonPrimitive.content)
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

    /** Waits for a presence event matching [username] and [presence]; returns its payload. */
    fun nextPresence(username: String, presence: String): JsonObject {
        while (true) {
            val payload = Json.parseToJsonElement(next("presence").getValue("payload").jsonPrimitive.content).jsonObject
            if (payload.getValue("username").jsonPrimitive.content == username &&
                payload.getValue("presence").jsonPrimitive.content == presence
            ) return payload
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

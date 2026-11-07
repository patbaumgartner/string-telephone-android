package com.patbaumgartner.stringtelephone

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpResponse
import java.net.http.WebSocket
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

/**
 * Group media messages (Phase 21, the deferred ADR-0012 piece): a member
 * uploads media scoped to the group (`groupId` instead of `peer`), sends it as
 * an IMAGE or VOICE group message, and every member may fetch the bytes over
 * `/v1/media/{id}` — membership is the authorisation. Non-members get 404 on
 * upload/send and 403 on fetch, and cross-bindings (pairwise media in a group
 * send, another member's media) are rejected.
 */
class GroupMediaIntegrationTest {
    private lateinit var server: RunningServer

    @BeforeTest fun setUp() { server = RunningServer() }
    @AfterTest fun tearDown() { server.close() }

    /** Opaque audio bytes: the server validates the content type, not the codec. */
    private fun oggBytes(): ByteArray = ByteArray(2048) { (it % 251).toByte() }

    private fun pngBytes(width: Int = 320, height: Int = 200): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(30, 90, 160)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun createGroup(token: String, name: String, members: List<String> = emptyList()): String {
        val body = """{"name":"$name","members":[${members.joinToString(",") { "\"$it\"" }}]}"""
        val response = server.post("/v1/groups", body, token)
        assertEquals(201, response.statusCode(), response.body())
        return Json.parseToJsonElement(response.body()).jsonObject.getValue("id").jsonPrimitive.content
    }

    private fun uploadGroup(token: String, groupId: String, fileName: String, contentType: String, bytes: ByteArray): HttpResponse<ByteArray> =
        server.postMultipartGroup("/v1/media", groupId, fileName, contentType, bytes, token)

    private fun groupMessage(
        token: String,
        groupId: String,
        clientKey: String,
        type: String? = null,
        mediaId: String? = null,
        durationMs: Int? = null,
        waveform: String? = null,
        content: String = "",
    ): HttpResponse<String> {
        val fields = buildList {
            add("\"content\":\"$content\"")
            add("\"clientKey\":\"$clientKey\"")
            type?.let { add("\"type\":\"$it\"") }
            mediaId?.let { add("\"mediaId\":\"$it\"") }
            durationMs?.let { add("\"durationMs\":$it") }
            waveform?.let { add("\"waveform\":\"$it\"") }
        }
        return server.post("/v1/groups/$groupId/messages", fields.joinToString(",", "{", "}"), token)
    }

    private fun messageOf(response: HttpResponse<String>, id: String): JsonObject =
        Json.parseToJsonElement(response.body()).jsonObject.getValue("messages").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }

    private fun connect(token: String, listener: SocketListener): WebSocket =
        server.http.newWebSocketBuilder().header("Authorization", "Bearer $token")
            .buildAsync(URI("${server.base.replaceFirst("http", "ws")}/v1/ws"), listener).join()

    @Test
    fun `a member sends IMAGE and VOICE group messages that every member reads and fetches`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content
        val dave = server.create("dave", "Dave").getValue("apiToken").jsonPrimitive.content
        val groupId = createGroup(alice, "Hikers", listOf("bob"))

        // Alice uploads an image and a recording scoped to the group.
        val imageUpload = uploadGroup(alice, groupId, "hike.png", "image/png", pngBytes())
        assertEquals(201, imageUpload.statusCode(), String(imageUpload.body()))
        val imageMedia = Json.parseToJsonElement(String(imageUpload.body())).jsonObject
        val imageId = imageMedia.getValue("id").jsonPrimitive.content
        assertEquals(groupId, imageMedia.getValue("groupId").jsonPrimitive.content)
        assertEquals(JsonNull, imageMedia["peer"], "group media has no pairwise peer")
        assertEquals(320, imageMedia.getValue("width").jsonPrimitive.int)
        assertEquals(200, imageMedia.getValue("height").jsonPrimitive.int)

        val voiceUpload = uploadGroup(alice, groupId, "voice.ogg", "audio/ogg", oggBytes())
        assertEquals(201, voiceUpload.statusCode(), String(voiceUpload.body()))
        val voiceId = Json.parseToJsonElement(String(voiceUpload.body())).jsonObject.getValue("id").jsonPrimitive.content

        // Bob listens for the realtime fan-out before alice sends.
        val bobEvents = SocketListener()
        val bobSocket = connect(bob, bobEvents)

        val imageSent = groupMessage(alice, groupId, "img-1", type = "IMAGE", mediaId = imageId)
        assertEquals(201, imageSent.statusCode(), imageSent.body())
        val imageMessage = Json.parseToJsonElement(imageSent.body()).jsonObject
        assertEquals("IMAGE", imageMessage.getValue("type").jsonPrimitive.content)
        assertEquals(imageId, imageMessage.getValue("mediaId").jsonPrimitive.content)
        assertEquals(groupId, imageMessage.getValue("groupId").jsonPrimitive.content)
        assertEquals(320, imageMessage.getValue("width").jsonPrimitive.int)

        val voiceSent = groupMessage(alice, groupId, "voice-1", type = "VOICE", mediaId = voiceId, durationMs = 4200, waveform = "10,80,40")
        assertEquals(201, voiceSent.statusCode(), voiceSent.body())
        val voiceMessage = Json.parseToJsonElement(voiceSent.body()).jsonObject
        assertEquals("VOICE", voiceMessage.getValue("type").jsonPrimitive.content)
        assertEquals(voiceId, voiceMessage.getValue("mediaId").jsonPrimitive.content)
        assertEquals(4200, voiceMessage.getValue("durationMs").jsonPrimitive.int)
        assertEquals("10,80,40", voiceMessage.getValue("waveform").jsonPrimitive.content)

        // The fan-out reaches bob with the media fields intact. The insert
        // callback and the endpoint both fan out, so frames may repeat —
        // match each sent message by id instead of assuming frame order.
        for (sent in listOf(imageMessage, voiceMessage)) {
            val sentId = sent.getValue("id").jsonPrimitive.content
            while (true) {
                val payload = Json.parseToJsonElement(bobEvents.next("group-message").getValue("payload").jsonPrimitive.content).jsonObject
                if (payload.getValue("id").jsonPrimitive.content != sentId) continue
                assertEquals(sent.getValue("type").jsonPrimitive.content, payload.getValue("type").jsonPrimitive.content)
                assertEquals(sent.getValue("mediaId").jsonPrimitive.content, payload.getValue("mediaId").jsonPrimitive.content)
                assertEquals(groupId, payload.getValue("groupId").jsonPrimitive.content)
                break
            }
        }

        // Both members see the media messages in the group history.
        for (token in listOf(alice, bob)) {
            val history = server.get("/v1/groups/$groupId/messages", token)
            assertEquals(200, history.statusCode(), history.body())
            val imageRow = messageOf(history, imageMessage.getValue("id").jsonPrimitive.content)
            assertEquals("IMAGE", imageRow.getValue("type").jsonPrimitive.content)
            assertEquals(imageId, imageRow.getValue("mediaId").jsonPrimitive.content)
            val voiceRow = messageOf(history, voiceMessage.getValue("id").jsonPrimitive.content)
            assertEquals("VOICE", voiceRow.getValue("type").jsonPrimitive.content)
            assertEquals(4200, voiceRow.getValue("durationMs").jsonPrimitive.int)
            assertEquals("10,80,40", voiceRow.getValue("waveform").jsonPrimitive.content)
        }

        // Every member may fetch the original bytes and the thumbnail.
        for (token in listOf(alice, bob)) {
            assertEquals(200, server.get("/v1/media/$imageId", token).statusCode())
            assertEquals(200, server.get("/v1/media/$imageId/thumbnail", token).statusCode())
            assertEquals(200, server.get("/v1/media/$voiceId", token).statusCode())
        }

        // An outsider is not a member: fetch is forbidden, never a hint.
        assertEquals(403, server.get("/v1/media/$imageId", dave).statusCode())
        assertEquals(403, server.get("/v1/media/$imageId/thumbnail", dave).statusCode())
        assertEquals(403, server.get("/v1/media/$voiceId", dave).statusCode())

        bobSocket.abort()
    }

    @Test
    fun `group media paths reject non-members and cross-bindings`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content
        val dave = server.create("dave", "Dave").getValue("apiToken").jsonPrimitive.content
        val groupId = createGroup(alice, "Hikers", listOf("bob"))
        val otherGroupId = createGroup(bob, "Ramen", listOf("alice"))

        // A non-member cannot upload into the group (404, like every group path).
        assertEquals(404, uploadGroup(dave, groupId, "x.png", "image/png", pngBytes(64, 64)).statusCode())
        // Nor send group messages at all.
        assertEquals(404, groupMessage(dave, groupId, "dave-1", type = "IMAGE", mediaId = "whatever").statusCode())
        assertEquals(404, groupMessage(dave, groupId, "dave-2", content = "hello").statusCode())

        // Upload requires exactly one of peer / groupId.
        assertEquals(400, server.postMultipartFieldsBad(alice).statusCode())

        // Alice uploads pairwise media (peer-scoped) and group media.
        val pairwise = server.postMultipart("/v1/media", "bob", "pair.png", "image/png", pngBytes(64, 64), alice)
        assertEquals(201, pairwise.statusCode(), String(pairwise.body()))
        val pairwiseId = Json.parseToJsonElement(String(pairwise.body())).jsonObject.getValue("id").jsonPrimitive.content
        assertEquals(JsonNull, Json.parseToJsonElement(String(pairwise.body())).jsonObject["groupId"], "pairwise media has no group")
        val groupImage = uploadGroup(alice, groupId, "g.png", "image/png", pngBytes(64, 64))
        assertEquals(201, groupImage.statusCode(), String(groupImage.body()))
        val groupImageId = Json.parseToJsonElement(String(groupImage.body())).jsonObject.getValue("id").jsonPrimitive.content
        // Media scoped to a different group also cannot be used here.
        val otherImage = uploadGroup(bob, otherGroupId, "o.png", "image/png", pngBytes(64, 64))
        assertEquals(201, otherImage.statusCode(), String(otherImage.body()))
        val otherImageId = Json.parseToJsonElement(String(otherImage.body())).jsonObject.getValue("id").jsonPrimitive.content
        // Bob's own upload in the group cannot be sent by alice.
        val bobImage = uploadGroup(bob, groupId, "b.png", "image/png", pngBytes(64, 64))
        assertEquals(201, bobImage.statusCode(), String(bobImage.body()))
        val bobImageId = Json.parseToJsonElement(String(bobImage.body())).jsonObject.getValue("id").jsonPrimitive.content
        // A voice upload for the audio checks.
        val audio = uploadGroup(alice, groupId, "a.ogg", "audio/ogg", oggBytes())
        assertEquals(201, audio.statusCode(), String(audio.body()))
        val audioId = Json.parseToJsonElement(String(audio.body())).jsonObject.getValue("id").jsonPrimitive.content

        // Pairwise media in a group send is forbidden.
        assertEquals(403, groupMessage(alice, groupId, "x-1", type = "IMAGE", mediaId = pairwiseId).statusCode())
        // Media scoped to another group is forbidden.
        assertEquals(403, groupMessage(alice, groupId, "x-2", type = "IMAGE", mediaId = otherImageId).statusCode())
        // Another member's upload is forbidden even in the right group.
        assertEquals(403, groupMessage(alice, groupId, "x-3", type = "IMAGE", mediaId = bobImageId).statusCode())
        // Unknown media is 404; a missing mediaId is 400.
        assertEquals(404, groupMessage(alice, groupId, "x-4", type = "IMAGE", mediaId = "no-such-media").statusCode())
        assertEquals(400, groupMessage(alice, groupId, "x-5", type = "IMAGE").statusCode())

        // VOICE validation mirrors the pairwise rules.
        val pngGroup = uploadGroup(alice, groupId, "v.png", "image/png", pngBytes(64, 64))
        val pngGroupId = Json.parseToJsonElement(String(pngGroup.body())).jsonObject.getValue("id").jsonPrimitive.content
        assertEquals(400, groupMessage(alice, groupId, "x-6", type = "VOICE", mediaId = pngGroupId, durationMs = 1000, waveform = "1,2").statusCode(), "voice needs audio media")
        assertEquals(400, groupMessage(alice, groupId, "x-7", type = "VOICE", mediaId = audioId, durationMs = null, waveform = "1,2").statusCode(), "duration required")
        assertEquals(400, groupMessage(alice, groupId, "x-8", type = "VOICE", mediaId = audioId, durationMs = 0, waveform = "1,2").statusCode())
        assertEquals(400, groupMessage(alice, groupId, "x-9", type = "VOICE", mediaId = audioId, durationMs = 600_001, waveform = "1,2").statusCode())
        assertEquals(400, groupMessage(alice, groupId, "x-10", type = "VOICE", mediaId = audioId, durationMs = 1000, waveform = null).statusCode(), "waveform required")

        // A resend with the same client key is idempotent: same message id.
        val first = groupMessage(alice, groupId, "img-ok", type = "IMAGE", mediaId = groupImageId)
        assertEquals(201, first.statusCode(), first.body())
        val second = groupMessage(alice, groupId, "img-ok", type = "IMAGE", mediaId = groupImageId)
        assertEquals(201, second.statusCode(), second.body())
        assertEquals(
            Json.parseToJsonElement(first.body()).jsonObject.getValue("id").jsonPrimitive.content,
            Json.parseToJsonElement(second.body()).jsonObject.getValue("id").jsonPrimitive.content,
        )
    }

    /** The multipart upload with neither peer nor groupId is a 400. */
    private fun RunningServer.postMultipartFieldsBad(bearer: String): HttpResponse<ByteArray> {
        val boundary = "----stit-none" + System.nanoTime()
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.png\"\r\n" +
            "Content-Type: image/png\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val body = java.io.ByteArrayOutputStream().apply { write(head); write(pngBytes(8, 8)); write(tail) }.toByteArray()
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI("$base/v1/media")).timeout(java.time.Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header("Authorization", "Bearer $bearer")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }
}

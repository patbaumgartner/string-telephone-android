package com.patbaumgartner.stringtelephone

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

/**
 * Image messaging (Phase 6) against a running server: upload, the IMAGE
 * message variant, participant-checked downloads and cross-user protection.
 */
class ImageMessagingIntegrationTest {
    private lateinit var server: RunningServer

    @BeforeTest fun setUp() { server = RunningServer() }
    @AfterTest fun tearDown() { server.close() }

    /** A small real PNG so the server decodes actual dimensions. */
    private fun pngBytes(width: Int = 320, height: Int = 200): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0, 121, 163)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun messageOf(response: java.net.http.HttpResponse<String>, id: String): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(response.body()).jsonObject.getValue("messages").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }

    @Test
    fun `an uploaded image becomes an IMAGE message with id and dimensions`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content

        val upload = server.postMultipart("/v1/media", "bob", "it.png", "image/png", pngBytes(), alice)
        assertEquals(201, upload.statusCode(), String(upload.body()))
        val media = Json.parseToJsonElement(String(upload.body())).jsonObject
        val mediaId = media.getValue("id").jsonPrimitive.content
        assertEquals(320, media.getValue("width").jsonPrimitive.int)
        assertEquals(200, media.getValue("height").jsonPrimitive.int)

        val sent = server.post(
            "/v1/conversations/bob/messages",
            """{"recipient":"bob","clientKey":"img-1","type":"IMAGE","mediaId":"$mediaId"}""",
            alice,
        )
        assertEquals(201, sent.statusCode(), sent.body())
        val sentMessage = Json.parseToJsonElement(sent.body()).jsonObject
        assertEquals("IMAGE", sentMessage.getValue("type").jsonPrimitive.content)
        assertEquals(mediaId, sentMessage.getValue("mediaId").jsonPrimitive.content)
        assertEquals(320, sentMessage.getValue("width").jsonPrimitive.int)
        // Both sides see the same shape in their view of the conversation.
        for ((token, peer) in listOf(alice to "bob", bob to "alice")) {
            val history = server.get("/v1/conversations/$peer/messages", token)
            assertEquals(200, history.statusCode(), history.body())
            val record = messageOf(history, sentMessage.getValue("id").jsonPrimitive.content)
            assertEquals("IMAGE", record.getValue("type").jsonPrimitive.content)
            assertEquals(mediaId, record.getValue("mediaId").jsonPrimitive.content)
            assertEquals(320, record.getValue("width").jsonPrimitive.int)
        }
    }

    @Test
    fun `only participants may fetch the bytes and a media message cannot reference someone else's upload`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content
        val carol = server.create("carol", "Carol").getValue("apiToken").jsonPrimitive.content

        val upload = server.postMultipart("/v1/media", "bob", "it.png", "image/png", pngBytes(), alice)
        assertEquals(201, upload.statusCode(), String(upload.body()))
        val mediaId = Json.parseToJsonElement(String(upload.body())).jsonObject.getValue("id").jsonPrimitive.content

        // A stranger must not fetch the bytes; a bogus token is simply unauthorized.
        assertEquals(403, server.get("/v1/media/$mediaId", carol).statusCode())
        assertEquals(403, server.get("/v1/media/$mediaId/thumbnail", carol).statusCode())
        assertEquals(401, server.get("/v1/media/$mediaId", "wrong-token").statusCode())

        // A media message may only reference the author's own upload for this peer.
        assertEquals(403, server.post(
            "/v1/conversations/alice/messages",
            """{"recipient":"alice","clientKey":"steal-1","type":"IMAGE","mediaId":"$mediaId"}""",
            carol,
        ).statusCode())
        assertEquals(400, server.post(
            "/v1/conversations/bob/messages",
            """{"recipient":"bob","clientKey":"img-bad","type":"IMAGE"}""",
            alice,
        ).statusCode())
        assertEquals(404, server.post(
            "/v1/conversations/bob/messages",
            """{"recipient":"bob","clientKey":"img-2","type":"IMAGE","mediaId":"does-not-exist"}""",
            alice,
        ).statusCode())

        // The participants can download original and thumbnail.
        assertTrue(server.get("/v1/media/$mediaId", alice).statusCode() == 200)
        assertTrue(server.get("/v1/media/$mediaId/thumbnail", alice).statusCode() == 200)
    }
}

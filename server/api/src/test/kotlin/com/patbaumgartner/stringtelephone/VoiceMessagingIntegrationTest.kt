package com.patbaumgartner.stringtelephone

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

/**
 * Voice messaging (Phase 11) against a running server: the VOICE message
 * variant carries the recording's duration and waveform, both visible to both
 * participants, and the endpoint rejects anything that is not a bounded audio
 * message with a numeric waveform.
 */
class VoiceMessagingIntegrationTest {
    private lateinit var server: RunningServer

    @BeforeTest fun setUp() { server = RunningServer() }
    @AfterTest fun tearDown() { server.close() }

    /** Opaque audio bytes: the server validates the content type, not the codec. */
    private fun oggBytes(): ByteArray = ByteArray(2048) { (it % 251).toByte() }

    /** A small real PNG, for the "voice must be audio" case. */
    private fun pngBytes(width: Int = 64, height: Int = 64): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0, 175, 225)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun messageOf(response: java.net.http.HttpResponse<String>, id: String): JsonObject =
        Json.parseToJsonElement(response.body()).jsonObject.getValue("messages").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }

    private fun upload(token: String, fileName: String, contentType: String, bytes: ByteArray): String {
        val upload = server.postMultipart("/v1/media", "bob", fileName, contentType, bytes, token)
        assertEquals(201, upload.statusCode(), String(upload.body()))
        return Json.parseToJsonElement(String(upload.body())).jsonObject.getValue("id").jsonPrimitive.content
    }

    private fun voice(
        token: String,
        mediaId: String,
        clientKey: String,
        durationMs: Int? = 4200,
        waveform: String? = "10,80,40",
    ): java.net.http.HttpResponse<String> {
        val fields = buildList {
            add(""""recipient":"bob"""")
            add(""""clientKey":"$clientKey"""")
            add(""""type":"VOICE"""")
            add(""""mediaId":"$mediaId"""")
            durationMs?.let { add(""""durationMs":$it""") }
            waveform?.let { add(""""waveform":"$it"""") }
        }
        return server.post("/v1/conversations/bob/messages", fields.joinToString(",", "{", "}"), token)
    }

    @Test
    fun `an uploaded recording becomes a VOICE message with duration and waveform`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        val bob = server.create("bob", "Bob").getValue("apiToken").jsonPrimitive.content
        val mediaId = upload(alice, "voice.ogg", "audio/ogg", oggBytes())

        val sent = voice(alice, mediaId, "voice-1")
        assertEquals(201, sent.statusCode(), sent.body())
        val sentMessage = Json.parseToJsonElement(sent.body()).jsonObject
        assertEquals("VOICE", sentMessage.getValue("type").jsonPrimitive.content)
        assertEquals(mediaId, sentMessage.getValue("mediaId").jsonPrimitive.content)
        assertEquals(4200, sentMessage.getValue("durationMs").jsonPrimitive.int)
        assertEquals("10,80,40", sentMessage.getValue("waveform").jsonPrimitive.content)

        // Both sides see the same voice fields in their view of the conversation.
        for ((token, peer) in listOf(alice to "bob", bob to "alice")) {
            val history = server.get("/v1/conversations/$peer/messages", token)
            assertEquals(200, history.statusCode(), history.body())
            val record = messageOf(history, sentMessage.getValue("id").jsonPrimitive.content)
            assertEquals("VOICE", record.getValue("type").jsonPrimitive.content)
            assertEquals(mediaId, record.getValue("mediaId").jsonPrimitive.content)
            assertEquals(4200, record.getValue("durationMs").jsonPrimitive.int)
            assertEquals("10,80,40", record.getValue("waveform").jsonPrimitive.content)
        }
    }

    @Test
    fun `a VOICE message must reference audio media with a bounded duration and a numeric waveform`() {
        server.login()
        val alice = server.create("alice", "Alice").getValue("apiToken").jsonPrimitive.content
        server.create("bob", "Bob")
        val audioId = upload(alice, "voice.ogg", "audio/ogg", oggBytes())
        val imageId = upload(alice, "it.png", "image/png", pngBytes())

        // Not audio, even though it is the author's own upload for this peer.
        assertEquals(400, voice(alice, imageId, "voice-image").statusCode())
        // Duration is required and bounded to 1..600000 ms.
        assertEquals(400, voice(alice, audioId, "voice-no-duration", durationMs = null).statusCode())
        assertEquals(400, voice(alice, audioId, "voice-zero", durationMs = 0).statusCode())
        assertEquals(400, voice(alice, audioId, "voice-too-long", durationMs = 600_001).statusCode())
        // The waveform is required and must contain numbers.
        assertEquals(400, voice(alice, audioId, "voice-no-waveform", waveform = null).statusCode())
        assertEquals(400, voice(alice, audioId, "voice-text-waveform", waveform = "abc").statusCode())
        // Amplitudes are clamped to 0..100 and the sample count is capped at 64.
        val clamped = voice(alice, audioId, "voice-clamped", waveform = "-5,999,50")
        assertEquals(201, clamped.statusCode(), clamped.body())
        assertEquals("0,100,50", Json.parseToJsonElement(clamped.body()).jsonObject.getValue("waveform").jsonPrimitive.content)

        val many = (0 until 70).joinToString(",") { (it * 3).toString() }
        val capped = voice(alice, audioId, "voice-capped", waveform = many)
        assertEquals(201, capped.statusCode(), capped.body())
        val stored = Json.parseToJsonElement(capped.body()).jsonObject.getValue("waveform").jsonPrimitive.content
        assertEquals(64, stored.split(",").size, stored)
    }
}

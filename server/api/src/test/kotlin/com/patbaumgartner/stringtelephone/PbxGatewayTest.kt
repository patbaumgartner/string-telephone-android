package com.patbaumgartner.stringtelephone

import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class PbxGatewayTest {
    @Test
    fun `production configuration requires TLS and SRTP without a UDP transport`() {
        val account = PbxAccount("alice", sipDigest("alice", TEST_PASSPHRASE))
        val production = AsteriskConfig.endpoints(
            listOf(account),
            PbxSettings(externalAddress = "203.0.113.10", localNetwork = "127.0.0.0/8"),
            requireTls = true,
        )
        assertContains(production, "transport=transport-tls")
        assertContains(production, "media_encryption=sdes")
        assertContains(production, "media_encryption_optimistic=no")
        assertContains(production, "media_address=203.0.113.10")
        assertFalse(production.contains("[transport-udp]"))
        val development = AsteriskConfig.endpoints(listOf(account), PbxSettings())
        assertContains(development, "[transport-udp]")
        assertContains(development, "media_encryption_optimistic=yes")
        assertFalse(development.contains("media_address="))
    }

    @Test
    fun `video codec follows the audio preference order and can be disabled`() {
        val account = PbxAccount("alice", sipDigest("alice", TEST_PASSPHRASE))
        val withVideo = AsteriskConfig.endpoints(listOf(account), PbxSettings())
        assertContains(withVideo, "allow=opus,ulaw,vp8\n")
        val withoutVideo = AsteriskConfig.endpoints(listOf(account), PbxSettings(video = "none"))
        assertContains(withoutVideo, "allow=opus,ulaw\n")
        assertFalse(withoutVideo.contains("vp8"))
        assertFailsWith<IllegalArgumentException> { PbxSettings(video = "h264").validate() }
    }

    @Test
    fun `invalid manager credentials fail before any provisioning or network access`() {
        val directory = java.nio.file.Path.of("unused-ami-configuration")
        for (password in listOf("short", "$TEST_PASSPHRASE\ninjected")) {
            assertFailsWith<IllegalArgumentException> {
                AsteriskGateway(directory, "localhost", 5038, "fixture", password)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            AsteriskGateway(directory, "localhost", 5038, "fixture\ninjected", TEST_PASSPHRASE)
        }
    }

    @Test
    fun `AMI no endpoints response means empty inventory rather than failed reconciliation`() {
        ami("No endpoints found") { gateway, _ ->
            assertEquals(PbxStatus(emptyList(), 0), gateway.status())
        }
    }

    @Test
    fun `other AMI errors remain explicit failures`() {
        ami("Permission denied") { gateway, _ ->
            assertFailsWith<PbxUnavailable> { gateway.status() }
        }
    }

    @Test
    fun `missing or changed managed files are detected and restored by applying desired configuration`() {
        ami("No endpoints found") { gateway, directory ->
            val settings = PbxSettings()
            assertFalse(gateway.isCurrent(emptyList(), settings))
            gateway.apply(emptyList(), settings)
            assertTrue(gateway.isCurrent(emptyList(), settings))
            Files.writeString(directory.resolve("pjsip-managed.conf"), "; drifted\n")
            assertFalse(gateway.isCurrent(emptyList(), settings))
            gateway.apply(emptyList(), settings)
            assertTrue(gateway.isCurrent(emptyList(), settings))
        }
    }

    private fun ami(endpointMessage: String, block: (PbxGateway, java.nio.file.Path) -> Unit) {
        val directory = Files.createTempDirectory("st-ami-test")
        val listener = ServerSocket(0)
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                while (!listener.isClosed) {
                    listener.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        val output = socket.getOutputStream().bufferedWriter()
                        fun send(value: String) { output.write(value); output.flush() }
                        fun read(): Map<String, String> {
                            val fields = linkedMapOf<String, String>()
                            while (true) {
                                val line = input.readLine() ?: error("Client closed AMI early")
                                if (line.isEmpty()) return fields
                                val parts = line.split(": ", limit = 2)
                                fields[parts[0]] = parts[1]
                            }
                        }
                        send("Asterisk Call Manager/11.0.0\r\n")
                        assertEquals("Login", read()["Action"])
                        send("Response: Success\r\nMessage: Authentication accepted\r\n\r\n")
                        val request = read()
                        val id = request.getValue("ActionID")
                        when (request["Action"]) {
                            "PJSIPShowEndpoints" -> send("Response: Error\r\nActionID: $id\r\nMessage: $endpointMessage\r\n\r\n")
                            "CoreShowChannels" -> send("Response: Success\r\nActionID: $id\r\n\r\nEvent: CoreShowChannelsComplete\r\nActionID: $id\r\n\r\n")
                            "Command" -> send("Response: Follows\r\nActionID: $id\r\nOutput: Reload complete\r\n\r\n")
                            else -> error("Unexpected AMI action")
                        }
                    }
                }
            } catch (error: java.net.SocketException) {
                if (!listener.isClosed) failure.set(error)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        try {
            block(AsteriskGateway(directory, "127.0.0.1", listener.localPort, "fixture", TEST_PASSPHRASE), directory)
        } finally {
            listener.close()
            worker.join(5_000)
            Files.list(directory).use { files -> files.forEach { Files.delete(it) } }
            Files.delete(directory)
        }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError("AMI fixture failed", it) }
    }
}

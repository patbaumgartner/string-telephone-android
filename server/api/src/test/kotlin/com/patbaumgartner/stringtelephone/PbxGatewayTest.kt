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
    fun `dialplan gains one generic confbridge pattern independent of groups`() {
        val account = PbxAccount("alice", sipDigest("alice", TEST_PASSPHRASE))
        val dialplan = AsteriskConfig.dialplan(listOf(account), PbxSettings())
        // ADR-0014 rule 3: one static pattern; the bridge id is the extension.
        assertContains(dialplan, "exten => _[c][o][n][f]-.,1,Confbridge(\${EXTEN})")
        assertContains(dialplan, "same => n,Hangup()")
        // Per-group PBX state does not exist: renaming nothing to do with groups
        // leaves the pattern identical.
        val other = AsteriskConfig.dialplan(emptyList(), PbxSettings())
        assertContains(other, "exten => _[c][o][n][f]-.,1,Confbridge(\${EXTEN})")
    }

    @Test
    fun `confbridge lifecycle verifies kick and members over the AMI fixture`() {
        // A live bridge (as if a member's INVITE created it) with alice on it.
        ami("No endpoints found", initialBridge = "conf-abc") { gateway, _ ->
            gateway.openConference("conf-abc", withVideo = true) // validation only; bridge is PBX-side
            gateway.openConference("conf-abc", withVideo = true) // idempotent re-open
            assertEquals(listOf("alice"), gateway.conferenceMembers("conf-abc"))
            gateway.destroyConference("conf-abc") // kicks everyone; empty bridge destroys itself
            assertEquals(emptyList(), gateway.conferenceMembers("conf-abc"))
        }
    }

    @Test
    fun `an unknown bridge reads as no members instead of a PBX error`() {
        ami("No endpoints found", initialBridge = null) { gateway, _ ->
            assertEquals(emptyList(), gateway.conferenceMembers("conf-never-existed"))
        }
    }

    @Test
    fun `a bridge id with AMI metacharacters is rejected before any network access`() {
        val directory = java.nio.file.Path.of("unused-bridge-validation")
        val gateway = AsteriskGateway(directory, "127.0.0.1", 5038, "fixture", TEST_PASSPHRASE)
        for (bridgeId in listOf("conf-x\r\nAction: Login", "conf-x;drop", "", "conf x")) {
            assertFailsWith<IllegalArgumentException> { gateway.openConference(bridgeId, withVideo = false) }
            assertFailsWith<IllegalArgumentException> { gateway.destroyConference(bridgeId) }
        }
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

    private fun ami(endpointMessage: String, block: (PbxGateway, java.nio.file.Path) -> Unit) =
        ami(endpointMessage, null, block)

    private fun ami(endpointMessage: String, initialBridge: String?, block: (PbxGateway, java.nio.file.Path) -> Unit) {
        val directory = Files.createTempDirectory("st-ami-test")
        val listener = ServerSocket(0)
        val failure = AtomicReference<Throwable>()
        var bridge: String? = initialBridge
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
                            "CoreShowChannels" -> send(
                                if (bridge == null) "Response: Success\r\nActionID: $id\r\n\r\nEvent: CoreShowChannelsComplete\r\nActionID: $id\r\n\r\n"
                                else "Response: Success\r\nActionID: $id\r\n\r\n" +
                                    "Event: CoreShowChannel\r\nActionID: $id\r\nChannel: PJSIP/alice-000001\r\nApplication: Confbridge\r\nApplicationData: $bridge\r\n\r\n" +
                                    "Event: CoreShowChannelsComplete\r\nActionID: $id\r\n\r\n",
                            )
                            "Command" -> send(
                                if (request["Command"]?.startsWith("confbridge kick") == true) {
                                    bridge = null
                                    "Response: Success\r\nActionID: $id\r\nOutput: Kicked alice\r\n\r\n"
                                } else {
                                    "Response: Follows\r\nActionID: $id\r\nOutput: Reload complete\r\n\r\n"
                                },
                            )
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

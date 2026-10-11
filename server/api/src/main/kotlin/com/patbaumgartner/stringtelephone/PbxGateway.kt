package com.patbaumgartner.stringtelephone

import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

data class EndpointStatus(val username: String, val state: String, val channels: Int)
data class PbxStatus(val endpoints: List<EndpointStatus>, val activeChannels: Int)

class PbxUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface PbxGateway {
    fun apply(accounts: List<PbxAccount>, settings: PbxSettings)
    fun isCurrent(accounts: List<PbxAccount>, settings: PbxSettings): Boolean
    fun disconnect(username: String)
    fun status(): PbxStatus
}

class AsteriskGateway(
    private val directory: Path,
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val requireTls: Boolean = false,
) : PbxGateway {
    init {
        require(username.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) {
            "PBX_AMI_USERNAME must be 1 to 64 letters, numbers, underscores or hyphens"
        }
        require(password.matches(Regex("[a-zA-Z0-9_.~-]{24,1024}"))) {
            "PBX_AMI_PASSWORD must be 24 to 1024 letters, numbers, underscores, dots, tildes or hyphens"
        }
    }

    override fun apply(accounts: List<PbxAccount>, settings: PbxSettings) = boundary {
        Files.createDirectories(directory)
        configuration(accounts, settings).forEach { (name, content) -> atomicWrite(name, content) }
        command("pjsip reload")
        command("dialplan reload")
        val expected = accounts.map { it.username }.toSet()
        repeat(20) {
            if (status().endpoints.map { it.username }.toSet() == expected) return@boundary
            Thread.sleep(100)
        }
        throw PbxUnavailable("PBX endpoint verification failed. Retry reconciliation and check Asterisk logs.")
    }

    override fun isCurrent(accounts: List<PbxAccount>, settings: PbxSettings): Boolean = boundary {
        configuration(accounts, settings).all { (name, content) ->
            val file = directory.resolve(name)
            Files.isRegularFile(file) && Files.readString(file) == content
        } && status().endpoints.map { it.username }.toSet() == accounts.map { it.username }.toSet()
    }

    private fun configuration(accounts: List<PbxAccount>, settings: PbxSettings) = mapOf(
        "pjsip-managed.conf" to AsteriskConfig.endpoints(accounts, settings, requireTls),
        "extensions-managed.conf" to AsteriskConfig.dialplan(accounts, settings),
        "pjsip-network.conf" to if (settings.externalAddress.isEmpty()) "; No NAT overrides.\n" else
            "external_media_address=${settings.externalAddress}\nexternal_signaling_address=${settings.externalAddress}\nlocal_net=${settings.localNetwork}\n",
    )

    override fun disconnect(username: String) = boundary {
        validateUsername(username)
        action(mapOf("Action" to "CoreShowChannels"), "CoreShowChannelsComplete")
            .filter { it["Event"] == "CoreShowChannel" }
            .mapNotNull { it["Channel"] }
            .filter { it.startsWith("PJSIP/") && it.removePrefix("PJSIP/").substringBeforeLast("-") == username }
            .forEach { channel ->
                action(mapOf("Action" to "Hangup", "Channel" to channel, "Cause" to "16"))
            }
        repeat(20) {
            val remaining = action(mapOf("Action" to "CoreShowChannels"), "CoreShowChannelsComplete")
                .any { it["Channel"]?.let { channel -> channel.startsWith("PJSIP/") && channel.removePrefix("PJSIP/").substringBeforeLast("-") == username } == true }
            if (!remaining) return@boundary
            Thread.sleep(50)
        }
        throw PbxUnavailable("PBX has not confirmed call disconnection for $username. Reconciliation will retry.")
    }

    override fun status(): PbxStatus = boundary {
        val endpoints = action(mapOf("Action" to "PJSIPShowEndpoints"), "EndpointListComplete")
            .filter { it["Event"] == "EndpointList" }
            .map { EndpointStatus(it.getValue("ObjectName"), it["DeviceState"] ?: "Unknown", it["ActiveChannels"]?.toIntOrNull() ?: 0) }
        val channels = action(mapOf("Action" to "CoreShowChannels"), "CoreShowChannelsComplete")
            .count { it["Event"] == "CoreShowChannel" }
        PbxStatus(endpoints, channels)
    }

    private fun atomicWrite(name: String, content: String) {
        val temporary = Files.createTempFile(directory, ".managed-", ".tmp")
        try {
            Files.writeString(temporary, content)
            Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
            Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun command(command: String) {
        val output = action(mapOf("Action" to "Command", "Command" to command))
            .flatMap { it.values }.joinToString("\n")
        if (listOf("No such command", "Unable to", "Error", "failed").any { output.contains(it, ignoreCase = true) }) {
            throw PbxUnavailable("PBX rejected its managed configuration. Check Asterisk logs and retry.")
        }
    }

    private fun action(fields: Map<String, String>, completion: String? = null): List<Map<String, String>> {
        fields.values.forEach { require(!it.contains('\r') && !it.contains('\n')) { "Invalid AMI value" } }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 3_000)
            socket.soTimeout = 5_000
            val input = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream().bufferedWriter()
            if (input.readLine()?.startsWith("Asterisk Call Manager/") != true) throw PbxUnavailable("Invalid PBX manager greeting")
            send(output, mapOf("Action" to "Login", "Username" to username, "Secret" to password, "Events" to "off"))
            if (frame(input)["Response"] != "Success") throw PbxUnavailable("PBX manager authentication failed")
            val id = UUID.randomUUID().toString()
            send(output, fields + ("ActionID" to id))
            val frames = mutableListOf<Map<String, String>>()
            repeat(10_000) {
                val response = frame(input)
                if (response["ActionID"] != id) return@repeat
                if (response["Response"] == "Error") {
                    // Asterisk reports an empty endpoint collection as an AMI error, not a list.
                    if (fields["Action"] == "PJSIPShowEndpoints" && response["Message"] == "No endpoints found") return emptyList()
                    throw PbxUnavailable("PBX manager rejected ${fields["Action"]}")
                }
                frames += response
                if (completion == null || response["Event"] == completion) return frames
            }
            throw PbxUnavailable("PBX manager response limit exceeded")
        }
    }

    private fun send(output: BufferedWriter, fields: Map<String, String>) {
        fields.forEach { (key, value) ->
            require(value.none { it == '\r' || it == '\n' }) { "Invalid AMI field" }
            output.write("$key: $value\r\n")
        }
        output.write("\r\n")
        output.flush()
    }

    private fun frame(input: BufferedReader): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var size = 0
        while (true) {
            val line = input.readLine() ?: throw PbxUnavailable("PBX manager closed the connection")
            size += line.length
            if (size > 1024 * 1024) throw PbxUnavailable("PBX manager frame too large")
            if (line.isEmpty()) return result
            val separator = line.indexOf(":")
            if (separator > 0) {
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1).trim()
                result[key] = result[key]?.let { "$it\n$value" } ?: value
            }
        }
    }

    private fun <T> boundary(block: () -> T): T = try {
        block()
    } catch (error: java.io.IOException) {
        throw PbxUnavailable("PBX is unreachable or its managed configuration directory is not writable. Check the PBX service and retry.", error)
    }
}

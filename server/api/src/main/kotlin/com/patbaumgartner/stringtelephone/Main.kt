package com.patbaumgartner.stringtelephone

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import java.io.File

fun main() {
    val apiPort = System.getenv("API_PORT")?.toIntOrNull() ?: DEFAULT_API_PORT
    val dbPath = System.getenv("API_DB") ?: "data/stringtelephone.db"
    val adminToken = System.getenv("API_ADMIN_TOKEN") ?: throw IllegalStateException("API_ADMIN_TOKEN is required")
    require(adminToken.length >= 24) { "API_ADMIN_TOKEN must be at least 24 characters" }
    val tls = ServerTls.fromEnv()

    File(dbPath).parentFile?.mkdirs()
    val db = Database(dbPath)
    val users = Users(db)
    val wsHub = WsHub()
    val messages = Messages(db) { record -> wsHub.broadcastNewMessage(record) }

    embeddedServer(
        Netty,
        configure = {
            // Unsecured http/ws always stays available; TLS is additive.
            connector {
                host = "0.0.0.0"
                port = apiPort
            }
            tls?.let { secureConnector(it) }
        },
    ) {
        stModule(users, wsHub, messages, sha256Hex(adminToken))
    }.start(wait = true)
}

/**
 * Routes shared by the real server and the integration tests so the tested
 * surface cannot drift from the deployed one.
 */
fun Application.stModule(users: Users, wsHub: WsHub, messages: Messages, adminTokenHash: String) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(WebSockets)
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
        adminRoutes(users, adminTokenHash)
        apiRoutes(users, messages)
        wsRoutes(wsHub, users)
    }
}

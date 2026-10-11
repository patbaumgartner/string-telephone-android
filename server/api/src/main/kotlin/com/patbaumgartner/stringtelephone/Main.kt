package com.patbaumgartner.stringtelephone

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import java.io.File

fun main() {
    val port = System.getenv("API_PORT")?.toIntOrNull() ?: 8080
    val dbPath = System.getenv("API_DB") ?: "data/stringtelephone.db"
    val adminToken = System.getenv("API_ADMIN_TOKEN") ?: throw IllegalStateException("API_ADMIN_TOKEN is required")
    require(adminToken.length >= 24) { "API_ADMIN_TOKEN must be at least 24 characters" }

    File(dbPath).parentFile?.mkdirs()
    val db = Database(dbPath)
    val users = Users(db)
    val wsHub = WsHub()
    val messages = Messages(db) { record -> wsHub.broadcastNewMessage(record) }

    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(WebSockets)
        routing {
            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
            adminRoutes(users, sha256Hex(adminToken))
            apiRoutes(users, messages)
            wsRoutes(wsHub, users)
        }
    }.start(wait = true)
}

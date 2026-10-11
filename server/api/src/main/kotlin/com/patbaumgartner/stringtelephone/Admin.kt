package com.patbaumgartner.stringtelephone

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun bearerToken(call: ApplicationCall): String? {
    val header = call.request.headers["Authorization"] ?: return null
    if (!header.startsWith("Bearer ")) return null
    return header.removePrefix("Bearer ").trim().ifEmpty { null }
}

fun isAdminToken(token: String?, adminTokenHash: String): Boolean {
    if (token == null) return false
    return sha256Hex(token) == adminTokenHash
}

fun Route.adminRoutes(users: Users, adminTokenHash: String) {
    route("/v1/admin") {
        post("/create-user") {
            val token = bearerToken(call)
            if (!isAdminToken(token, adminTokenHash)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                return@post
            }
            val req = call.receive<CreateUserRequest>()
            if (req.username.isBlank() || !req.username.matches(Regex("[a-z][a-z0-9-]{0,31}"))) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid username"))
                return@post
            }
            if (req.displayName.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("displayName required"))
                return@post
            }
            if (users.find(req.username) != null) {
                call.respond(HttpStatusCode.Conflict, ErrorDto("username taken"))
                return@post
            }
            val (user, newToken) = users.create(req.username, req.displayName)
            call.respond(HttpStatusCode.Created, CreateResponse(user.username, user.displayName, newToken))
        }
        post("/revoke-user") {
            val token = bearerToken(call)
            if (!isAdminToken(token, adminTokenHash)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                return@post
            }
            val req = call.receive<RevokeRequest>()
            val ok = users.revoke(req.username)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound, RevokeResponse(req.username, ok))
        }
        get("/list-users") {
            val token = bearerToken(call)
            if (!isAdminToken(token, adminTokenHash)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                return@get
            }
            call.respond(UserListDto(users.list().map { UserDto(it.username, it.displayName, it.active) }))
        }
    }
}

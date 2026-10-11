package com.patbaumgartner.stringtelephone

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.*

fun main(args: Array<String>) {
    val expected = mapOf("list-users" to 1, "create-user" to 3, "revoke-user" to 2, "enable-user" to 2, "reset-user" to 2, "reconcile" to 1)
    if (args.isEmpty() || expected[args[0]] != args.size) {
        println("Usage: stringtelephone-provision list-users | create-user <username> <display-name> | revoke-user <username> | enable-user <username> | reset-user <username> | reconcile")
        kotlin.system.exitProcess(2)
    }
    try {
        val admin = AdminClient(
            System.getenv("API_URL") ?: "http://localhost:8080",
            System.getenv("ADMIN_USERNAME").orEmpty(),
            System.getenv("ADMIN_PASSWORD").orEmpty(),
        )
        admin.login()
        when (args[0]) {
            "list-users" -> {
                val result = admin.request("list-users").jsonObject.getValue("users").jsonArray
                result.forEach { element ->
                    val user = element.jsonObject
                    println("${user.getValue("username").jsonPrimitive.content}\t${user.getValue("displayName").jsonPrimitive.content}\tactive=${user.getValue("active")}")
                }
            }
            "create-user", "reset-user" -> {
                val body = buildJsonObject {
                    put("username", args[1])
                    if (args[0] == "create-user") put("displayName", args[2])
                }
                val result = admin.request(args[0], body).jsonObject
                println("api_token=${result.getValue("apiToken").jsonPrimitive.content}")
                println("sip_secret=${result.getValue("sipSecret").jsonPrimitive.content}")
                if (!result.getValue("ready").jsonPrimitive.boolean) {
                    System.err.println("PBX provisioning is pending. Save these credentials and reconcile before using the account.")
                    kotlin.system.exitProcess(1)
                }
                println("PBX configured and verified")
            }
            "revoke-user" -> {
                admin.request("revoke-user", buildJsonObject { put("username", args[1]) })
                println("revoked ${args[1]}; API, SIP and calls disconnected")
            }
            "enable-user" -> {
                admin.request("set-enabled", buildJsonObject { put("username", args[1]); put("enabled", true) })
                println("enabled ${args[1]}")
            }
            "reconcile" -> {
                admin.request("pbx/reconcile", buildJsonObject {})
                println("PBX configured and verified")
            }
        }
    } catch (error: IllegalArgumentException) {
        System.err.println(error.message)
        kotlin.system.exitProcess(1)
    } catch (error: java.io.IOException) {
        System.err.println("Administration request failed. Check API_URL and server availability.")
        kotlin.system.exitProcess(1)
    }
}

private class AdminClient(private val base: String, private val username: String, private val password: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build()
    private var csrf = ""

    init {
        val uri = URI(base)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1"))) {
            "API_URL must use HTTPS except on loopback"
        }
        require(username.isNotBlank() && password.isNotBlank()) { "ADMIN_USERNAME and ADMIN_PASSWORD are required" }
    }

    fun login() {
        csrf = token(send("/admin/login").body())
        val form = mapOf("username" to username, "password" to password, "_csrf" to csrf)
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
        val login = send("/admin/login", form, "application/x-www-form-urlencoded")
        require(login.statusCode() == 302 && !login.headers().firstValue("Location").orElse("").contains("error")) {
            "Admin sign-in failed. Check ADMIN_USERNAME and ADMIN_PASSWORD."
        }
        val page = send("/admin/")
        require(page.statusCode() == 200) { "Unable to load administration after sign-in" }
        csrf = token(page.body())
    }

    fun request(action: String, body: JsonObject? = null): JsonElement {
        val response = send("/v1/admin/$action", body?.toString(), "application/json")
        require(response.statusCode() in 200..299) { "Administration request failed (${response.statusCode()}). Check the admin dashboard and server logs." }
        return Json.parseToJsonElement(response.body())
    }

    private fun send(path: String, body: String? = null, contentType: String = ""): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("$base$path")).timeout(Duration.ofSeconds(30))
        if (body != null) builder.header("Content-Type", contentType).header("X-CSRF-TOKEN", csrf)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun token(page: String): String =
        Regex("""name="_csrf"[^>]*value="([^"]+)"""").find(page)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Server did not return a CSRF token")
}

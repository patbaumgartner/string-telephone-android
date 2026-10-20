package com.patbaumgartner.stringtelephone

import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

const val TEST_PASSPHRASE = "fixture-robin-maple-meadow-lantern-pebble-willow-scarlet-1234"

class FakePbx : PbxGateway {
    @Volatile var available = true
    var applied = emptyList<PbxAccount>()
    var settings = PbxSettings()
    val disconnected = mutableListOf<String>()
    /** Asterisk DeviceState per endpoint; registered endpoints default to "Not in use". */
    val deviceStates = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun checkAvailable() { if (!available) throw PbxUnavailable("Test PBX is unreachable") }
    override fun apply(accounts: List<PbxAccount>, settings: PbxSettings) {
        checkAvailable()
        AsteriskConfig.endpoints(accounts, settings)
        applied = accounts
        this.settings = settings
    }
    override fun disconnect(username: String) { checkAvailable(); disconnected += username }
    override fun isCurrent(accounts: List<PbxAccount>, settings: PbxSettings): Boolean {
        checkAvailable()
        return applied == accounts && this.settings == settings
    }
    override fun status(): PbxStatus {
        checkAvailable()
        return PbxStatus(applied.map { EndpointStatus(it.username, deviceStates[it.username] ?: "Not in use", 0) }, 0)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakePbxConfiguration {
    @Bean @Primary fun fakePbx() = FakePbx()
}

class RunningServer(extra: Map<String, String> = emptyMap(), val http: HttpClient = HttpClient.newBuilder()
    .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).connectTimeout(Duration.ofSeconds(5)).build()) : AutoCloseable {
    val context = SpringApplicationBuilder(StringTelephoneServer::class.java, FakePbxConfiguration::class.java).run(
        *(mapOf("server.port" to "0", "server.shutdown" to "immediate",
            "st.admin.username" to "operator", "st.admin.password" to TEST_PASSPHRASE, "st.pbx.password" to TEST_PASSPHRASE,
            "logging.level.root" to "ERROR") + TestPostgres.springArgs() + extra).map { "--${it.key}=${it.value}" }.toTypedArray(),
    )
    val base = "http://localhost:${context.environment.getProperty("local.server.port")}"
    val pbx = context.getBean(FakePbx::class.java)
    var csrf = ""

    fun get(path: String, bearer: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("$base$path")).timeout(Duration.ofSeconds(15))
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun post(path: String, body: String, bearer: String? = null, csrf: Boolean = true, form: Boolean = false): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("$base$path")).timeout(Duration.ofSeconds(15))
            .header("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (csrf && this.csrf.isNotEmpty()) builder.header("X-CSRF-TOKEN", this.csrf)
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun login() {
        csrf = csrfToken(get("/admin/login").body())
        val response = post("/admin/login", "username=operator&password=${URLEncoder.encode(TEST_PASSPHRASE, Charsets.UTF_8)}&_csrf=$csrf", form = true)
        assertEquals(302, response.statusCode())
        val page = get("/admin/")
        assertEquals(200, page.statusCode(), page.body())
        csrf = csrfToken(page.body())
    }

    fun create(username: String, displayName: String, sipPassword: String? = null): JsonObject {
        val body = buildJsonObject {
            put("username", username); put("displayName", displayName)
            sipPassword?.let { put("sipPassword", it) }
        }
        val response = post("/v1/admin/create-user", body.toString())
        assertEquals(201, response.statusCode(), response.body())
        return Json.parseToJsonElement(response.body()).jsonObject
    }

    override fun close() {
        context.close()
    }
}

fun csrfToken(html: String): String =
    Regex("""name="_csrf"[^>]*value="([^"]+)"""").find(html)?.groupValues?.get(1) ?: error("CSRF token missing from rendered form")

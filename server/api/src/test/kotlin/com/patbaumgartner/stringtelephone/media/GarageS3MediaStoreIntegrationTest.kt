package com.patbaumgartner.stringtelephone.media

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.io.ByteArrayInputStream

class GarageS3MediaStoreIntegrationTest {

    companion object {
        private const val ADMIN_TOKEN = "it-admin-token-1234567890"
        private val RPC_SECRET = "0".repeat(64)
        private const val BUCKET = "st-media-it"

        val garage: GenericContainer<*> = GenericContainer(DockerImageName.parse("dxflrs/garage:v1.0.1"))
            .withExposedPorts(3900, 3903)
            .withEnv("GARAGE_RPC_SECRET", RPC_SECRET)
            .withEnv("GARAGE_ADMIN_TOKEN", ADMIN_TOKEN)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("garage-it.toml"),
                "/etc/garage.toml",
            )
            .waitingFor(Wait.forLogMessage(".*Admin API server listening.*", 1))

        private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        fun admin(path: String, method: String = "GET", body: String? = null): String {
            val builder = HttpRequest.newBuilder(URI("http://localhost:${garage.getMappedPort(3903)}$path"))
                .header("Authorization", "Bearer $ADMIN_TOKEN")
                .timeout(Duration.ofSeconds(30))
            if (body != null) {
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            }
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) {
                "admin $method $path failed (${response.statusCode()}): ${response.body()}"
            }
            return response.body()
        }

        private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

        lateinit var accessKey: String
        lateinit var secretKey: String

        @BeforeClass
        @JvmStatic
        fun startAndBootstrap() {
            garage.start()
            val status = json(admin("/v1/status"))
            val nodeId = status.jsonObject.getValue("node").jsonPrimitive.content
            admin("/v1/layout", "POST", """[{"id":"$nodeId","role":"garage","capacity":1073741824,"zone":"dc1","tags":["it"]}]""")
            admin("/v1/layout/apply", "POST", """{"version":1}""")
            repeat(30) {
                val v = json(admin("/v1/layout")).jsonObject.getValue("version").jsonPrimitive.int
                if (v >= 1) return@repeat
                Thread.sleep(500)
            }
            require(json(admin("/v1/layout")).jsonObject.getValue("version").jsonPrimitive.int >= 1) {
                "Garage layout was not applied"
            }

            admin("/v1/bucket", "POST", """{"globalAlias":"$BUCKET"}""")

            val keyJson = json(admin("/v1/key", "POST", """{"name":"it-key"}"""))
            accessKey = keyJson.jsonObject.getValue("accessKeyId").jsonPrimitive.content
            secretKey = keyJson.jsonObject.getValue("secretAccessKey").jsonPrimitive.content
            val bucketId = json(admin("/v1/bucket?alias=$BUCKET")).jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content
            admin(
                "/v1/bucket/allow?bucket=$bucketId&id=$accessKey",
                "POST",
                """{"bucketId":"$bucketId","accessKeyId":"$accessKey","permissions":{"read":true,"write":true,"owner":false}}""",
            )
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            garage.stop()
        }
    }

    private fun newStore(): S3MediaStore {
        val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
        val client = S3Client.builder()
            .endpointOverride(URI.create("http://localhost:${garage.getMappedPort(3900)}"))
            .region(Region.of("garage"))
            .credentialsProvider(credentials)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
        return S3MediaStore(client, BUCKET)
    }

    @Test
    fun `put get and delete round trip through a real S3 endpoint`() {
        val store = newStore()
        val key = "media/${UUID.randomUUID()}/original"
        val payload = "hello from a real object store".toByteArray()
        val contentType = "text/plain"

        store.put(key, contentType, payload.size.toLong(), ByteArrayInputStream(payload))

        val fetched = store.get(key)
        assertEquals(contentType, fetched.contentType)
        assertEquals(payload.size.toLong(), fetched.contentLength)
        assertTrue(payload.contentEquals(fetched.body.readBytes()))
        fetched.body.close()

        store.delete(key)

        assertFailsWith<Exception> { store.get(key) }
    }

    @Test
    fun `a second put overwrites and a get returns the latest bytes`() {
        val store = newStore()
        val key = "media/${UUID.randomUUID()}/original"
        val first = "first".toByteArray()
        val second = "second-payload".toByteArray()

        store.put(key, "text/plain", first.size.toLong(), ByteArrayInputStream(first))
        store.put(key, "text/plain", second.size.toLong(), ByteArrayInputStream(second))

        val fetched = store.get(key)
        assertTrue(second.contentEquals(fetched.body.readBytes()))
        fetched.body.close()

        store.delete(key)
    }
}

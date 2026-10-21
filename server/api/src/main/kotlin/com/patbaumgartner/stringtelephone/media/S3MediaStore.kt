package com.patbaumgartner.stringtelephone.media

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream
import java.net.URI

class S3MediaStore(
    private val client: S3Client,
    private val bucket: String,
) : MediaStore {
    override fun put(key: String, contentType: String, contentLength: Long, body: InputStream) {
        val request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(contentLength)
            .build()
        client.putObject(request, RequestBody.fromInputStream(body, contentLength))
    }

    override fun get(key: String): MediaObject {
        val request = GetObjectRequest.builder().bucket(bucket).key(key).build()
        val response = client.getObject(request)
        return MediaObject(
            contentType = response.response().contentType() ?: "application/octet-stream",
            contentLength = response.response().contentLength() ?: -1,
            body = response,
        )
    }

    override fun delete(key: String) {
        val request = DeleteObjectRequest.builder().bucket(bucket).key(key).build()
        client.deleteObject(request)
    }

    companion object {
        fun create(properties: MediaStoreProperties): S3MediaStore {
            val credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey, properties.secretKey),
            )
            val client = S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint))
                .region(Region.of(properties.region))
                .credentialsProvider(credentials)
                .serviceConfiguration(
                    S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccess)
                        .build(),
                )
                .build()
            return S3MediaStore(client, properties.bucket)
        }
    }
}

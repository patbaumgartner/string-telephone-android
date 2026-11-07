package com.patbaumgartner.stringtelephone

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream

@RestController
class MediaApi(
    private val users: Users,
    private val media: MediaService,
    private val groups: Groups,
) {
    private fun principal(request: HttpServletRequest): UserRecord =
        bearerToken(request.getHeader("Authorization"))?.let(users::findByToken)
            ?: throw ApiFailure(HttpStatus.UNAUTHORIZED, "unauthorized")

    /**
     * Uploads a media blob. Exactly one conversation reference is required:
     * `peer` for a pairwise upload or `groupId` for a group upload (Phase 21).
     * Group uploads check membership like every other group path — a
     * non-member sees 404, never a hint that the group exists.
     */
    @PostMapping("/v1/media", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        request: HttpServletRequest,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("peer", required = false) peer: String?,
        @RequestParam("groupId", required = false) groupId: String?,
    ): ResponseEntity<MediaRecordDto> {
        val me = principal(request)
        val cleanPeer = peer?.takeIf { it.isNotBlank() }
        val cleanGroup = groupId?.takeIf { it.isNotBlank() }
        if ((cleanPeer == null) == (cleanGroup == null)) {
            throw ApiFailure(HttpStatus.BAD_REQUEST, "exactly one of peer or groupId is required")
        }
        if (cleanGroup != null) {
            val group = groups.find(cleanGroup) ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such group")
            if (!groups.isMember(cleanGroup, me.username)) throw ApiFailure(HttpStatus.NOT_FOUND, "no such group")
        }
        val bytes = file.bytes
        val contentType = file.contentType ?: "application/octet-stream"
        val record = media.upload(me.username, cleanPeer, contentType, bytes, cleanGroup)
        return ResponseEntity.status(HttpStatus.CREATED).body(MediaRecordDto.from(record))
    }

    @GetMapping("/v1/media/{id}/thumbnail")
    fun thumbnail(request: HttpServletRequest, @PathVariable id: String): ResponseEntity<org.springframework.core.io.InputStreamResource> {
        val me = principal(request)
        val obj = media.authorizeAndGetThumbnail(id, me.username)
        val headers = HttpHeaders()
        headers.contentType = MediaType.parseMediaType(obj.contentType)
        if (obj.contentLength >= 0) headers.contentLength = obj.contentLength
        headers.cacheControl = org.springframework.http.CacheControl.noStore().headerValue
        return ResponseEntity.ok().headers(headers).body(org.springframework.core.io.InputStreamResource(obj.body))
    }

    @GetMapping("/v1/media/{id}")
    fun get(request: HttpServletRequest, @PathVariable id: String): ResponseEntity<org.springframework.core.io.InputStreamResource> {
        val me = principal(request)
        val obj = media.authorizeAndGet(id, me.username)
        val headers = HttpHeaders()
        headers.contentType = MediaType.parseMediaType(obj.contentType)
        if (obj.contentLength >= 0) headers.contentLength = obj.contentLength
        headers.cacheControl = org.springframework.http.CacheControl.noStore().headerValue
        return ResponseEntity.ok().headers(headers).body(org.springframework.core.io.InputStreamResource(obj.body))
    }
}

data class MediaRecordDto(
    val id: String,
    val owner: String,
    /** Pairwise peer; null for group media (Phase 21). */
    val peer: String? = null,
    /** Group the media belongs to; null for pairwise media (Phase 21). */
    val groupId: String? = null,
    val contentType: String,
    val byteSize: Long,
    val sha256: String,
    val createdAt: Long,
    val width: Int? = null,
    val height: Int? = null,
) {
    companion object {
        fun from(r: MediaRecord) = MediaRecordDto(r.id, r.owner, r.peer, r.groupId, r.contentType, r.byteSize, r.sha256, r.createdAt, r.width, r.height)
    }
}

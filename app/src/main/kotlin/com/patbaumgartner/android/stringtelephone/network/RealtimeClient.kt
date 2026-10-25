package com.patbaumgartner.android.stringtelephone.network

import com.patbaumgartner.android.stringtelephone.core.RealtimeEvent
import com.patbaumgartner.android.stringtelephone.core.parseRealtimeEvent
import com.patbaumgartner.android.stringtelephone.core.webSocketUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The `/v1/ws` endpoint expressed as an OkHttp request URL. [webSocketUrl]
 * yields `ws://`/`wss://`; OkHttp only accepts `http://`/`https://` and performs
 * the websocket upgrade itself, so the scheme is mapped here.
 */
internal fun okHttpWebSocketUrl(baseUrl: String): String =
    webSocketUrl(baseUrl)
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")

internal fun authenticatedWebSocketRequest(baseUrl: String, token: String): Request =
    Request.Builder()
        .url(okHttpWebSocketUrl(baseUrl))
        .header("Authorization", "Bearer $token")
        .build()

/**
 * Realtime message stream over the companion API's `/v1/ws` websocket.
 *
 * Reconnects with capped exponential backoff until [disconnect], so the socket
 * survives network changes while signed in. Frames are decoded by
 * [parseRealtimeEvent] in `core`, keeping the wire format out of the app.
 */
class RealtimeClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    http: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RealtimeStream {
    init {
        requireAllowedServerUrl(baseUrl)
    }

    private val http = http.withTransportSecurity()
    private val _events = MutableSharedFlow<RealtimeEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    @Volatile private var stopped = false
    private var attempts = 0

    /** Opens the socket (idempotent-ish; a second call restarts it). */
    override fun connect() {
        stopped = false
        open()
    }

    /** Stops reconnecting and closes the socket; safe to call repeatedly. */
    override fun disconnect() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(CLOSE_NORMAL, "bye")
        socket = null
    }

    private fun open() {
        val token = tokenProvider() ?: return
        val request = authenticatedWebSocketRequest(baseUrl, token)
        socket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempts = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            parseRealtimeEvent(text)?.let { _events.tryEmit(it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val delayMs = (BASE_BACKOFF_MS shl attempts.coerceAtMost(MAX_BACKOFF_SHIFT))
            .coerceAtMost(MAX_BACKOFF_MS)
        attempts += 1
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!stopped) open()
        }
    }

    /** Sends the ephemeral typing signal; best effort, no delivery guarantee. */
    override fun sendTyping(typing: Boolean) {
        socket?.send("""{"type":"typing","payload":{"typing":$typing}}""")
    }

    private companion object {
        const val CLOSE_NORMAL = 1000
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_SHIFT = 5
    }
}

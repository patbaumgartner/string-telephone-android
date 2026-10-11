package com.patbaumgartner.android.stringtelephone.network

import com.patbaumgartner.android.stringtelephone.core.RealtimeEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * Realtime server-push channel boundary. The repository depends on this rather
 * than the OkHttp-backed [RealtimeClient] so the message fan-out can be tested
 * against a fake without opening a socket.
 */
interface RealtimeStream {
    val events: SharedFlow<RealtimeEvent>

    /** Starts (or restarts) the connection. */
    fun connect()

    /** Stops reconnecting and closes the connection; idempotent. */
    fun disconnect()
}

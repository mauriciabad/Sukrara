// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.transport

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** What a relay socket reports. Delivered on any thread; the pool hands it to its own dispatcher. */
sealed interface SocketEvent {
    data class Message(val text: String) : SocketEvent

    /** The socket is gone (closed by either side or failed); [reason] is for the status line, never user data. */
    data class Closed(val reason: String) : SocketEvent
}

/** One text WebSocket. [send] returns false when the socket can't take it (closed or its queue is full). */
interface RelaySocket {
    fun send(text: String): Boolean

    fun cancel()
}

/** Opens relay sockets. Production: [OkHttpWebSockets]; tests: a fake that never touches the network. */
fun interface WebSocketFactory {
    fun connect(url: String, onEvent: (SocketEvent) -> Unit): RelaySocket
}

/**
 * Relay sockets over OkHttp. Only `wss://` URLs reach it (RelayPool filters). OkHttp pings every [PING_SECONDS] and
 * fails a socket whose pong doesn't come back, which ends a half-open connection without a watchdog of our own.
 */
class OkHttpWebSockets(base: OkHttpClient) : WebSocketFactory {
    private val client = base.newBuilder().pingInterval(PING_SECONDS, TimeUnit.SECONDS).build()

    override fun connect(url: String, onEvent: (SocketEvent) -> Unit): RelaySocket {
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = onEvent(SocketEvent.Message(text))

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    onEvent(SocketEvent.Closed("closed $code"))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    onEvent(SocketEvent.Closed(t.javaClass.simpleName))
            },
        )
        return object : RelaySocket {
            override fun send(text: String) = socket.send(text)

            override fun cancel() = socket.cancel()
        }
    }

    private companion object {
        const val PING_SECONDS = 60L
        const val NORMAL_CLOSURE = 1000
    }
}

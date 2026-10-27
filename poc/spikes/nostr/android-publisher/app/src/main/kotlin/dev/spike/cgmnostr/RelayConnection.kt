// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmnostr

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray

/** A signed event ready to send. [d] = chunk id for addressable events (null for live). */
class Outgoing(
    val id: String,
    val eventJson: String,
    val d: String?,
    val createdAt: Long,
    val originMs: Long,   // when the reading reached the phone (live latency)
) {
    val message = """["EVENT",$eventJson]"""
}

/**
 * One relay, one lazily-opened WebSocket, one paced writer.
 *
 *  - priority + pacing: a live event is sent the moment it is enqueued (at most one per reading, ~1/min), so it
 *    never waits behind a chunk; chunks are paced ≥ [MIN_GAP_MS] after the previous EVENT on this socket, so a
 *    backfill is never a burst (damus bans bursts). A newer version of a chunk replaces the queued one (same `d`);
 *  - liveness without pings: every EVENT must be OK'd within [OK_TIMEOUT_MS] or the socket is dropped and the
 *    chunk re-queued. With one reading per minute that is enough to detect dead sockets, and it means no
 *    WebSocket ping timer waking the radio;
 *  - reconnect is lazy (on the next send) with exponential backoff, so a dead relay causes no wake-up loop;
 *  - an OK=false containing rate-limit/banned/blocked pauses the relay for [PAUSE_MS] and drops its live queue.
 */
class RelayConnection(val url: String, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val lock = Any()
    private val live = ArrayDeque<Outgoing>()
    private val chunks = LinkedHashMap<String, Outgoing>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pending = ConcurrentHashMap<String, Outgoing>()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var pausedUntil = 0L
    private var backoffMs = BACKOFF_MIN_MS
    @Volatile private var lastSendMs = 0L

    init {
        Status.relay(url) { it }
        scope.launch { writer() }
    }

    fun enqueue(o: Outgoing) {
        synchronized(lock) {
            if (o.d == null) live.addLast(o) else { chunks.remove(o.d); chunks[o.d] = o }
        }
        showQueued()
        wake.trySend(Unit)
    }

    private fun showQueued() = Status.relay(url) { it.copy(queued = synchronized(lock) { live.size + chunks.size }) }

    fun close() {
        ws?.close(1000, "bye")
        ws = null
    }

    /** Live events are due immediately; a chunk only once [MIN_GAP_MS] has passed since the last send. */
    private fun next(nowMs: Long): Outgoing? = synchronized(lock) {
        live.removeFirstOrNull()
            ?: if (nowMs >= lastSendMs + MIN_GAP_MS) chunks.keys.firstOrNull()?.let { chunks.remove(it) } else null
    }

    private fun hasChunks() = synchronized(lock) { chunks.isNotEmpty() }

    /** Put a chunk back unless a newer version was queued meanwhile; live events go back to the front. */
    private fun requeue(o: Outgoing) = synchronized(lock) {
        if (o.d == null) live.addFirst(o) else if (!chunks.containsKey(o.d)) chunks[o.d] = o
    }

    private suspend fun writer() {
        while (true) {
            val o = next(System.currentTimeMillis())
            showQueued()
            if (o == null) {
                // nothing due: sleep until the next chunk slot, or until something (e.g. a live event) is enqueued
                val wait = lastSendMs + MIN_GAP_MS - System.currentTimeMillis()
                if (hasChunks() && wait > 0) withTimeoutOrNull(wait) { wake.receive() } else wake.receive()
                continue
            }
            val now = System.currentTimeMillis()
            if (now < pausedUntil) {
                if (o.d == null) { drop(o, "relay paused") } else { requeue(o); delay(pausedUntil - now) }
                continue
            }
            if (o.d == null && now / 1000 - o.createdAt > LIVE_MAX_AGE_S) { drop(o, "stale"); continue }

            val sock = connect()
            if (sock == null) {
                requeue(o)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                continue
            }
            backoffMs = BACKOFF_MIN_MS
            pending[o.id] = o
            if (!sock.send(o.message)) {
                pending.remove(o.id)
                requeue(o)
                dropSocket(sock, "send refused")
                continue
            }
            lastSendMs = System.currentTimeMillis()
            scope.launch {
                delay(OK_TIMEOUT_MS)
                if (pending.remove(o.id) != null) {
                    Status.relay(url) { it.copy(fail = it.fail + 1, lastError = "no OK within ${OK_TIMEOUT_MS / 1000} s") }
                    dropSocket(sock, "no OK")
                    if (o.d != null) { requeue(o); wake.trySend(Unit) }
                }
            }
        }
    }

    private fun drop(o: Outgoing, why: String) {
        Status.relay(url) { it.copy(dropped = it.dropped + 1) }
        Status.log("${host()} dropped ${if (o.d == null) "live" else "chunk"} event ($why)")
    }

    private fun dropSocket(sock: WebSocket, why: String) {
        sock.cancel()
        if (ws === sock) ws = null
        Status.relay(url) { it.copy(connected = false, lastError = why) }
    }

    private suspend fun connect(): WebSocket? {
        ws?.let { return it }
        val opened = CompletableDeferred<Boolean>()
        val req = Request.Builder().url(url).header("User-Agent", "cgm-nostr-android/0.1").build()
        val sock = http.newWebSocket(req, Listener(opened))
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { opened.await() } ?: false
        if (!ok) { sock.cancel(); return null }
        ws = sock
        Status.relay(url) { it.copy(connected = true, connects = it.connects + 1) }
        return sock
    }

    private inner class Listener(val opened: CompletableDeferred<Boolean>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(true) }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { JSONArray(text) }.getOrNull() ?: return
            when (msg.optString(0)) {
                "OK" -> onOk(msg.optString(1), msg.optBoolean(2), msg.optString(3))
                "NOTICE" -> {
                    Status.relay(url) { it.copy(lastError = "NOTICE ${msg.optString(1).take(80)}") }
                    Status.log("${host()} NOTICE ${msg.optString(1).take(80)}")
                }
                "AUTH" -> Unit   // NIP-42 challenge: we only write public events, ignore
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            gone(webSocket, "closed by relay ($code ${reason.take(40)})")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened.complete(false)
            gone(webSocket, "${t.javaClass.simpleName}: ${t.message?.take(80)}${response?.let { " HTTP ${it.code}" } ?: ""}")
        }

        private fun gone(webSocket: WebSocket, why: String) {
            if (ws === webSocket) ws = null
            Status.relay(url) { it.copy(connected = false, lastError = why) }
            Status.log("${host()} socket: $why")
        }
    }

    private fun onOk(id: String, accepted: Boolean, message: String) {
        val o = pending.remove(id) ?: return
        val now = System.currentTimeMillis()
        if (accepted || message.startsWith("duplicate:")) {
            Status.relay(url) { if (o.d == null) it.copy(ok = it.ok + 1, lastLiveOkMs = now - o.originMs) else it.copy(ok = it.ok + 1) }
            return
        }
        Status.relay(url) { it.copy(fail = it.fail + 1, lastError = message.take(100)) }
        Status.log("${host()} rejected ${if (o.d == null) "live" else "chunk"}: ${message.take(80)}")
        if (RATE_LIMITED.containsMatchIn(message)) {
            pausedUntil = now + PAUSE_MS
            val droppedLive = synchronized(lock) { live.size.also { live.clear() } }
            if (o.d != null) requeue(o)
            Status.relay(url) { it.copy(pausedUntilMs = pausedUntil, dropped = it.dropped + droppedLive) }
            Status.log("${host()} paused 10 min (rate limit), dropped $droppedLive live")
        }
    }

    private fun host() = url.removePrefix("wss://")

    companion object {
        const val MIN_GAP_MS = 2_000L
        const val OK_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val BACKOFF_MIN_MS = 5_000L
        const val BACKOFF_MAX_MS = 5 * 60_000L
        const val PAUSE_MS = 10 * 60_000L
        const val LIVE_MAX_AGE_S = 120L
        private val RATE_LIMITED = Regex("rate-limit|banned|blocked", RegexOption.IGNORE_CASE)
    }
}

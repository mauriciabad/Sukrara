// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmshare.core

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.Event

/**
 * Write lanes. LIVE leaves immediately; CONTROL (gift wraps, roster, backup) and CHUNK are paced ≥ 2 s apart per
 * relay, CONTROL first. In CONTROL/CHUNK a newer event with the same key (the `d` tag) replaces a queued one.
 */
enum class Lane { LIVE, CONTROL, CHUNK }

class Outgoing(
    val id: String,
    val eventJson: String,
    val lane: Lane,
    val key: String,
    val createdAt: Long,
    val originMs: Long,   // when the reading reached the phone (live latency)
) {
    val message = """["EVENT",$eventJson]"""

    companion object {
        fun of(ev: Event, lane: Lane, key: String? = null, originMs: Long = System.currentTimeMillis()): Outgoing {
            val id = ev.id().toHex()
            return Outgoing(id, ev.asJson(), lane, key ?: id, ev.createdAt().asSecs().toLong(), originMs)
        }
    }
}

data class RelayStats(
    val url: String,
    val connected: Boolean = false,
    val ok: Int = 0,
    val fail: Int = 0,
    val dropped: Int = 0,
    val rx: Int = 0,
    val lastRxMs: Long = 0,
    val lastError: String? = null,
    val pausedUntilMs: Long = 0,
    val lastLiveOkMs: Long? = null,
    val connects: Int = 0,
    val queued: Int = 0,
) {
    val host: String get() = url.removePrefix("wss://").removeSuffix("/")
}

/** A persistent REQ. [filters] is re-evaluated on every (re)send so `since` stays fresh after a reconnect. */
class Subscription(val filters: () -> List<JSONObject>, val onEvent: (JSONObject, String) -> Unit)

interface RelayHost {
    fun stats(url: String, f: (RelayStats) -> RelayStats)
    fun log(msg: String)
    /** NIP-42: a signed kind-22242 event JSON, or null to not authenticate. */
    fun authEvent(url: String, challenge: String): String?
}

/**
 * One relay, one WebSocket, one paced writer (from the PoC 3 publisher) plus subscriptions and one-shot queries.
 *
 *  - pacing: live events go out the moment they're enqueued; control/chunk events ≥ [MIN_GAP_MS] after the
 *    previous EVENT on this socket, so a backfill or a rotation is never a burst (damus bans bursts);
 *  - liveness without pings: every EVENT must be OK'd within [OK_TIMEOUT_MS] or the socket is dropped. Read-only
 *    sockets (followers) get their liveness from the engine (the stream is 1 event/min, silence ⇒ reconnect), so
 *    there is no WebSocket ping timer waking the radio;
 *  - write-only sockets reconnect lazily (on the next send); sockets with subscriptions are kept open by
 *    [keeper] with exponential backoff, and every subscription is re-sent after a reconnect;
 *  - OK=false containing rate-limit/banned/blocked pauses the relay for [PAUSE_MS] and drops its live queue;
 *  - NIP-42: on `CLOSED auth-required:` we answer the relay's AUTH challenge once per connection and re-send.
 */
class RelayConnection(
    val url: String,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val host: RelayHost,
) {
    private val lock = Any()
    private val live = ArrayDeque<Outgoing>()
    private val control = LinkedHashMap<String, Outgoing>()
    private val chunks = LinkedHashMap<String, Outgoing>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val keep = Channel<Unit>(Channel.CONFLATED)
    private val pending = ConcurrentHashMap<String, Outgoing>()
    private val subs = ConcurrentHashMap<String, Subscription>()
    private val queries = ConcurrentHashMap<String, Query>()
    private val connectMutex = Mutex()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var pausedUntil = 0L
    @Volatile private var lastSendMs = 0L
    @Volatile private var challenge: String? = null
    @Volatile private var authSent = false
    @Volatile private var authId: String? = null
    @Volatile private var closed = false
    private var backoffMs = BACKOFF_MIN_MS

    private class Query {
        val events: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        val done = CompletableDeferred<Unit>()
    }

    init {
        host.stats(url) { it }
        scope.launch { writer() }
        scope.launch { keeper() }
    }

    val isConnected get() = ws != null

    fun enqueue(o: Outgoing) {
        synchronized(lock) {
            when (o.lane) {
                Lane.LIVE -> live.addLast(o)
                Lane.CONTROL -> { control.remove(o.key); control[o.key] = o }
                Lane.CHUNK -> { chunks.remove(o.key); chunks[o.key] = o }
            }
        }
        showQueued()
        wake.trySend(Unit)
    }

    fun subscribe(id: String, sub: Subscription) {
        subs[id] = sub
        val sock = ws
        if (sock != null) sendReq(sock, id, sub) else keep.trySend(Unit)
    }

    fun unsubscribe(id: String) {
        if (subs.remove(id) != null) ws?.send("""["CLOSE","$id"]""")
    }

    /** One-shot REQ until EOSE (or [timeoutMs]); then CLOSE. Empty if the relay is unreachable. */
    suspend fun query(filters: List<JSONObject>, timeoutMs: Long): List<JSONObject> {
        val sock = connect() ?: return emptyList()
        val id = "q" + java.lang.Long.toHexString(System.nanoTime())
        val q = Query()
        queries[id] = q
        sock.send(reqMessage(id, filters))
        withTimeoutOrNull(timeoutMs) { q.done.await() }
        queries.remove(id)
        sock.send("""["CLOSE","$id"]""")
        return q.events.toList()
    }

    /** Drop the socket; subscriptions come back via [keeper]. */
    fun reconnect() {
        ws?.let { dropSocket(it, "reconnect requested") }
    }

    fun close() {
        closed = true
        subs.clear()
        ws?.close(1000, "bye")
        ws = null
        host.stats(url) { it.copy(connected = false) }
    }

    private fun showQueued() =
        host.stats(url) { it.copy(queued = synchronized(lock) { live.size + control.size + chunks.size }) }

    private fun reqMessage(id: String, filters: List<JSONObject>): String {
        val a = JSONArray().put("REQ").put(id)
        filters.forEach { a.put(it) }
        return a.toString()
    }

    private fun sendReq(sock: WebSocket, id: String, sub: Subscription) {
        sock.send(reqMessage(id, sub.filters()))
    }

    // ------------------------------------------------------------------------------------------------ writer

    private fun next(nowMs: Long): Outgoing? = synchronized(lock) {
        live.removeFirstOrNull() ?: if (nowMs >= lastSendMs + MIN_GAP_MS) {
            control.keys.firstOrNull()?.let { control.remove(it) } ?: chunks.keys.firstOrNull()?.let { chunks.remove(it) }
        } else null
    }

    private fun hasPaced() = synchronized(lock) { control.isNotEmpty() || chunks.isNotEmpty() }

    private fun requeue(o: Outgoing) = synchronized(lock) {
        when (o.lane) {
            Lane.LIVE -> live.addFirst(o)
            Lane.CONTROL -> if (!control.containsKey(o.key)) control[o.key] = o
            Lane.CHUNK -> if (!chunks.containsKey(o.key)) chunks[o.key] = o
        }
    }

    private suspend fun writer() {
        while (!closed) {
            val o = next(System.currentTimeMillis())
            showQueued()
            if (o == null) {
                val wait = lastSendMs + MIN_GAP_MS - System.currentTimeMillis()
                if (hasPaced() && wait > 0) withTimeoutOrNull(wait) { wake.receive() } else wake.receive()
                continue
            }
            val now = System.currentTimeMillis()
            if (now < pausedUntil) {
                if (o.lane == Lane.LIVE) drop(o, "relay paused") else { requeue(o); delay(pausedUntil - now) }
                continue
            }
            if (o.lane == Lane.LIVE && now / 1000 - o.createdAt > LIVE_MAX_AGE_S) { drop(o, "stale"); continue }

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
                    host.stats(url) { it.copy(fail = it.fail + 1, lastError = "no OK within ${OK_TIMEOUT_MS / 1000} s") }
                    dropSocket(sock, "no OK")
                    if (o.lane != Lane.LIVE) { requeue(o); wake.trySend(Unit) }
                }
            }
        }
    }

    private fun drop(o: Outgoing, why: String) {
        host.stats(url) { it.copy(dropped = it.dropped + 1) }
        host.log("${hostName()} dropped ${o.lane.name.lowercase()} event ($why)")
    }

    // ------------------------------------------------------------------------------------------------ connection

    private suspend fun keeper() {
        var backoff = BACKOFF_MIN_MS
        while (!closed) {
            keep.receive()
            while (!closed && subs.isNotEmpty() && ws == null) {
                if (connect() != null) { backoff = BACKOFF_MIN_MS; break }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        }
    }

    private fun dropSocket(sock: WebSocket, why: String) {
        sock.cancel()
        if (ws === sock) ws = null
        host.stats(url) { it.copy(connected = false, lastError = why) }
        keep.trySend(Unit)
    }

    private suspend fun connect(): WebSocket? = connectMutex.withLock {
        ws?.let { return it }
        if (closed) return null
        val opened = CompletableDeferred<Boolean>()
        val req = Request.Builder().url(url).header("User-Agent", "cgm-share-android/0.1").build()
        challenge = null
        authSent = false
        val sock = http.newWebSocket(req, Listener(opened))
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { opened.await() } ?: false
        if (!ok) {
            sock.cancel()
            host.stats(url) { it.copy(lastError = it.lastError ?: "connect timeout") }
            return null
        }
        ws = sock
        host.stats(url) { it.copy(connected = true, connects = it.connects + 1) }
        subs.forEach { (id, s) -> sendReq(sock, id, s) }
        return sock
    }

    /** Answer the AUTH challenge once per connection; subscriptions are re-sent when the relay OKs it. */
    private fun tryAuth(sock: WebSocket) {
        if (authSent) return
        val ch = challenge ?: return
        val ev = host.authEvent(url, ch) ?: return
        authSent = true
        authId = runCatching { JSONObject(ev).getString("id") }.getOrNull()
        sock.send("""["AUTH",$ev]""")
        host.log("${hostName()} NIP-42 AUTH sent")
    }

    private inner class Listener(val opened: CompletableDeferred<Boolean>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(true) }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { JSONArray(text) }.getOrNull() ?: return
            when (msg.optString(0)) {
                "EVENT" -> {
                    val id = msg.optString(1)
                    val ev = msg.optJSONObject(2) ?: return
                    host.stats(url) { it.copy(rx = it.rx + 1, lastRxMs = System.currentTimeMillis()) }
                    val sub = subs[id]
                    if (sub != null) {
                        runCatching { sub.onEvent(ev, url) }.onFailure { host.log("${hostName()} handler: $it") }
                    } else queries[id]?.events?.add(ev)
                }
                "EOSE" -> {
                    host.stats(url) { it.copy(lastRxMs = System.currentTimeMillis()) }
                    queries[msg.optString(1)]?.done?.complete(Unit)
                }
                "CLOSED" -> {
                    val id = msg.optString(1)
                    val reason = msg.optString(2)
                    host.log("${hostName()} CLOSED $id: ${reason.take(80)}")
                    if (reason.contains("auth-required")) tryAuth(webSocket)
                    queries[id]?.done?.complete(Unit)
                }
                "AUTH" -> challenge = msg.optString(1)
                "OK" -> if (msg.optString(1) == authId) {
                    host.log("${hostName()} NIP-42 AUTH ${if (msg.optBoolean(2)) "accepted" else "rejected: ${msg.optString(3).take(60)}"}")
                    if (msg.optBoolean(2)) subs.forEach { (sid, s) -> sendReq(webSocket, sid, s) }
                } else onOk(msg.optString(1), msg.optBoolean(2), msg.optString(3))
                "NOTICE" -> {
                    host.stats(url) { it.copy(lastError = "NOTICE ${msg.optString(1).take(80)}") }
                    host.log("${hostName()} NOTICE ${msg.optString(1).take(80)}")
                }
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
            host.stats(url) { it.copy(connected = false, lastError = why) }
            host.log("${hostName()} socket: $why")
            queries.values.forEach { it.done.complete(Unit) }
            keep.trySend(Unit)
        }
    }

    private fun onOk(id: String, accepted: Boolean, message: String) {
        val o = pending.remove(id) ?: return
        val now = System.currentTimeMillis()
        if (accepted || message.startsWith("duplicate:")) {
            host.stats(url) { if (o.lane == Lane.LIVE) it.copy(ok = it.ok + 1, lastLiveOkMs = now - o.originMs) else it.copy(ok = it.ok + 1) }
            return
        }
        host.stats(url) { it.copy(fail = it.fail + 1, lastError = message.take(100)) }
        host.log("${hostName()} rejected ${o.lane.name.lowercase()}: ${message.take(80)}")
        if (RATE_LIMITED.containsMatchIn(message)) {
            pausedUntil = now + PAUSE_MS
            val droppedLive = synchronized(lock) { live.size.also { live.clear() } }
            if (o.lane != Lane.LIVE) requeue(o)
            host.stats(url) { it.copy(pausedUntilMs = pausedUntil, dropped = it.dropped + droppedLive) }
            host.log("${hostName()} paused 10 min (rate limit), dropped $droppedLive live")
        }
    }

    private fun hostName() = url.removePrefix("wss://")

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

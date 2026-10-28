// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.transport

import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.UnsignedEvent
import app.mauri.sukrara.protocol.Wire
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Reconnect delays: 2 s doubling to 5 min, each scaled by a random 50–100 % so many phones don't reconnect in the same
 * instant (Mac `Backoff`, same numbers). Reset once a relay sends anything, or by [RelayTransport.reconnectNow].
 */
data class Backoff(val baseSeconds: Double = 2.0, val capSeconds: Double = 300.0) {
    fun delayMs(attempt: Int, jitter: Double): Long = (min(capSeconds, baseSeconds * 2.0.pow(min(attempt, 20))) * jitter * 1000).toLong()
}

/**
 * The relay connection (mirrors the Mac `RelayPool`): one WebSocket per relay, reconnect with [Backoff] (a suspended
 * delay, never a busy loop), subscriptions and an outbox re-sent after every reconnect (gift wraps must reach relays
 * that were down; a newer live / addressable event replaces the older one), NIP-42 AUTH as [authKey] when a relay
 * closes a REQ with `auth-required`. All state lives on one serial dispatcher. Events are not deduped here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayPool(
    relays: List<String>,
    /** The key relays' gift wraps are addressed to (owner: I), asked for when a relay wants NIP-42 AUTH. */
    private val authKey: () -> KeyPair?,
    private val crypto: NostrCrypto,
    private val sockets: WebSocketFactory,
    dispatcher: CoroutineDispatcher,
    private val nowSeconds: () -> Long,
    private val backoff: Backoff = Backoff(),
    private val jitter: () -> Double = { 0.5 + Math.random() / 2 },
) : RelayTransport {
    private val urls = Wire.secureRelays(relays).distinct()
    private val serial = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private class Connection(val job: Job) {
        var socket: RelaySocket? = null
        var open = false
        var challenge: String? = null
        var authEventId: String? = null
        var authPending = false
    }

    private val connections = mutableMapOf<String, Connection>()
    private val subscriptions = linkedMapOf<String, List<RelayFilter>>()
    private val outbox = linkedMapOf<String, Pair<SignedEvent, Long>>()

    private val _inbound = MutableSharedFlow<RelayInbound>(extraBufferCapacity = INBOUND_BUFFER)
    override val inbound: SharedFlow<RelayInbound> = _inbound.asSharedFlow()

    private val _status = MutableStateFlow(urls.associateWith { RelayStatus(connected = false) })
    override val status: StateFlow<Map<String, RelayStatus>> = _status.asStateFlow()

    override fun start() {
        scope.launch { urls.filter { connections[it] == null }.forEach(::launchRelay) }
    }

    override fun stop() {
        scope.launch {
            connections.values.forEach { it.job.cancel(); it.socket?.cancel() }
            connections.clear()
            _status.value = urls.associateWith { RelayStatus(connected = false) }
        }
    }

    /** Releases the pool for good. */
    fun close() {
        connections.values.forEach { it.socket?.cancel() }
        scope.cancel()
    }

    override fun reconnectNow() {
        scope.launch {
            connections.filterValues { !it.open }.keys.forEach { url ->
                connections.remove(url)?.let { it.job.cancel(); it.socket?.cancel() }
                launchRelay(url)
            }
        }
    }

    override fun subscribe(id: String, filters: List<RelayFilter>) {
        scope.launch {
            if (subscriptions[id] == filters) return@launch
            subscriptions[id] = filters
            broadcast(req(id, filters))
        }
    }

    override fun close(id: String) {
        scope.launch { if (subscriptions.remove(id) != null) broadcast(JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(id))).toString()) }
    }

    override fun publish(event: SignedEvent) {
        scope.launch {
            val now = nowSeconds()
            outbox.entries.removeAll { now - it.value.second > OUTBOX_SECONDS }
            val key = outboxKey(event)
            outbox.remove(key)
            outbox[key] = event to now
            broadcast(eventMessage(event))
        }
    }

    override suspend fun fetch(filters: List<RelayFilter>, timeoutMs: Long): FetchResult {
        val id = "q-" + UUID.randomUUID().toString().take(8)
        val events = linkedMapOf<String, SignedEvent>()
        val answered = mutableSetOf<String>()
        val done = Channel<Unit>(Channel.CONFLATED)
        val collector = scope.launch {
            inbound.onSubscription { subscribe(id, filters) }.collect { m ->
                when {
                    m is RelayInbound.Event && m.subscription == id -> events.putIfAbsent(m.event.id, m.event)
                    m is RelayInbound.EndOfStored && m.subscription == id -> answered += m.relay
                    m is RelayInbound.Closed && m.subscription == id -> answered += m.relay
                }
                if (settled(answered)) done.trySend(Unit)
            }
        }
        // a relay that fails also settles the query (its status changes, not inbound)
        val failures = scope.launch { status.collect { if (settled(answered)) done.trySend(Unit) } }
        withTimeoutOrNull(timeoutMs) { done.receive() }
        return withContext(serial) {
            collector.cancel()
            failures.cancel()
            close(id)
            FetchResult(events.values.toList(), answered.size)
        }
    }

    /**
     * Every relay has answered the query (EOSE / CLOSED) or has failed since it was asked: the newest copy may sit on
     * a relay that connects late, so one quick relay never ends a query alone.
     */
    private fun settled(answered: Set<String>): Boolean = urls.all { url ->
        host(url) in answered || status.value[url]?.let { !it.connected && it.lastError != null } == true
    }

    private fun launchRelay(url: String) {
        lateinit var connection: Connection
        connection = Connection(scope.launch { run(url, connection) })
        connections[url] = connection
    }

    private suspend fun run(url: String, connection: Connection) {
        var attempt = 0
        while (true) {
            val attemptBefore = attempt
            // Bounded: a relay that floods faster than we handle loses its socket instead of filling memory.
            val events = Channel<SocketEvent>(INBOUND_QUEUE)
            val flooded = AtomicBoolean(false)
            val socket = sockets.connect(url) {
                if (events.trySend(it).isFailure && !events.isClosedForSend) {
                    flooded.set(true)
                    events.close()
                }
            }
            connection.socket = socket
            connection.open = false
            connection.challenge = null
            connection.authEventId = null
            connection.authPending = false
            resend(socket)
            var reason: String? = null
            for (e in events) {
                if (flooded.get()) break
                when (e) {
                    is SocketEvent.Message -> {
                        if (e.text.length > MAX_MESSAGE_CHARS) {
                            reason = TOO_BIG
                            break
                        }
                        if (!connection.open) {
                            connection.open = true
                            attempt = 0
                            setStatus(url, RelayStatus(connected = true))
                        }
                        handle(e.text, url, connection, socket)
                    }
                    is SocketEvent.Closed -> {
                        reason = e.reason
                        break
                    }
                }
            }
            // the first of the relay's close, a too-big message or the flood wins (a late flood callback never
            // overwrites an earlier reason)
            val why = reason ?: if (flooded.get()) FLOODED else "closed"
            // a relay that answers and then floods / oversends doesn't win back the 2 s backoff each time
            if (why == TOO_BIG || why == FLOODED) attempt = attemptBefore
            events.close()
            socket.cancel()
            connection.socket = null
            connection.open = false
            setStatus(url, RelayStatus(connected = false, lastError = why, retryAttempt = attempt + 1))
            delay(backoff.delayMs(attempt, jitter()))
            attempt++
        }
    }

    private suspend fun handle(text: String, url: String, connection: Connection, socket: RelaySocket) {
        val msg = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
        fun str(i: Int) = (msg.getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content
        val relay = host(url)
        when (str(0)) {
            "EVENT" -> {
                val sub = str(1) ?: return
                val ev = (msg.getOrNull(2) as? JsonObject)?.let { SignedEvent.parse(it.toString()) } ?: return
                _inbound.emit(RelayInbound.Event(ev, sub, relay))
            }
            "EOSE" -> str(1)?.let { _inbound.emit(RelayInbound.EndOfStored(it, relay)) }
            "AUTH" -> {
                connection.challenge = str(1)
                if (connection.authPending) authenticate(url, connection, socket)
            }
            "CLOSED" -> {
                val sub = str(1).orEmpty()
                val reason = str(2).orEmpty()
                if (reason.startsWith("auth-required") && authKey() != null) {
                    connection.authPending = true
                    authenticate(url, connection, socket)
                } else {
                    _inbound.emit(RelayInbound.Closed(sub, reason, relay))
                }
            }
            "OK" -> {
                val id = str(1) ?: return
                val accepted = (msg.getOrNull(2) as? JsonPrimitive)?.booleanOrNull ?: false
                if (id == connection.authEventId) {
                    if (accepted) resend(socket)
                } else {
                    _inbound.emit(RelayInbound.Ok(id, accepted, relay))
                }
            }
        }
    }

    /** NIP-42: kind 22242 with the relay URL and the challenge, signed by [authKey]; once per connection. */
    private fun authenticate(url: String, connection: Connection, socket: RelaySocket) {
        val key = authKey() ?: return
        val challenge = connection.challenge ?: return
        if (connection.authEventId != null) return
        val ev = crypto.sign(key, UnsignedEvent(KIND_AUTH, "", listOf(listOf("relay", url), listOf("challenge", challenge)), nowSeconds()))
        connection.authEventId = ev.id
        connection.authPending = false
        socket.send("[\"AUTH\",${ev.toJson()}]")
    }

    private fun resend(socket: RelaySocket) {
        subscriptions.forEach { (id, filters) -> socket.send(req(id, filters)) }
        outbox.values.forEach { (ev, _) -> socket.send(eventMessage(ev)) }
    }

    private fun broadcast(text: String) = connections.values.forEach { it.socket?.send(text) }

    private fun setStatus(url: String, s: RelayStatus) = _status.update { it + (url to s) }

    private fun req(id: String, filters: List<RelayFilter>) =
        "[\"REQ\",${JsonPrimitive(id)},${filters.joinToString(",") { it.toJson() }}]"

    private fun eventMessage(ev: SignedEvent) = "[\"EVENT\",${ev.toJson()}]"

    /** A newer live event or addressable event (same author and `d`) replaces the older one in the outbox. */
    private fun outboxKey(ev: SignedEvent): String = when {
        ev.kind == Wire.KIND_LIVE -> "live:${ev.pubkey}"
        ev.kind == Wire.KIND_DATA && ev.tag("d") != null -> "d:${ev.pubkey}:${ev.tag("d")}"
        else -> ev.id
    }

    private fun host(url: String) = url.removePrefix("wss://").substringBefore('/')

    internal companion object {
        private const val KIND_AUTH = 22242
        private const val OUTBOX_SECONDS = 3600L
        private const val INBOUND_BUFFER = 1024

        /** Socket events waiting for [run]; more than this means the relay floods us and its socket is dropped. */
        const val INBOUND_QUEUE = 4096

        /**
         * The longest relay message kept (in chars, so at least 1 MiB of UTF-8, like the Mac's URLSession default).
         * Our events are a few KB; anything this big is a broken or hostile relay and its socket is dropped. OkHttp
         * has already buffered the message by then (it has no incoming limit); dropping it keeps the backoff growing (no reset), so a relay that repeats it is
         * retried less and less often.
         */
        const val MAX_MESSAGE_CHARS = 1 shl 20

        /** Status-line reasons (never relay content). */
        const val TOO_BIG = "message too big"
        const val FLOODED = "too many messages"
    }
}

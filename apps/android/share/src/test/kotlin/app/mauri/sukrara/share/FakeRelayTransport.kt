// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share

import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.share.transport.FetchResult
import app.mauri.sukrara.share.transport.RelayFilter
import app.mauri.sukrara.share.transport.RelayInbound
import app.mauri.sukrara.share.transport.RelayStatus
import app.mauri.sukrara.share.transport.RelayTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An in-memory relay for tests: records what is published, serves [stored] to [fetch], and lets a test push events
 * to a subscription. Never opens a socket. [answering] = how many relays answer a fetch (0 = offline).
 */
class FakeRelayTransport(var answering: Int = 1) : RelayTransport {
    val published = mutableListOf<SignedEvent>()
    val stored = mutableListOf<SignedEvent>()
    val subscriptions = mutableMapOf<String, List<RelayFilter>>()
    var started = false

    private val _inbound = MutableSharedFlow<RelayInbound>(extraBufferCapacity = 256)
    override val inbound: SharedFlow<RelayInbound> = _inbound
    override val status: StateFlow<Map<String, RelayStatus>> = MutableStateFlow(mapOf("wss://fake.relay" to RelayStatus(connected = true)))

    override fun start() {
        started = true
    }

    override fun stop() {
        started = false
    }

    override fun subscribe(id: String, filters: List<RelayFilter>) {
        subscriptions[id] = filters
    }

    override fun close(id: String) {
        subscriptions.remove(id)
    }

    override fun publish(event: SignedEvent) {
        published += event
    }

    override fun reconnectNow() = Unit

    override suspend fun fetch(filters: List<RelayFilter>, timeoutMs: Long): FetchResult =
        if (answering == 0) FetchResult(emptyList(), 0) else FetchResult(stored.filter { e -> filters.any { it.matches(e) } }, answering)

    /** A relay delivers [event] on subscription [id]. */
    suspend fun deliver(id: String, event: SignedEvent) = _inbound.emit(RelayInbound.Event(event, id, "fake.relay"))

    suspend fun ok(eventId: String) = _inbound.emit(RelayInbound.Ok(eventId, true, "fake.relay"))
}

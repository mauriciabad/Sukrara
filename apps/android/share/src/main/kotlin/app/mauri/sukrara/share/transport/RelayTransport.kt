// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.transport

import app.mauri.sukrara.protocol.SignedEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A NIP-01 REQ filter (only the fields the protocol uses). Same shape as the Mac `RelayFilter`. */
data class RelayFilter(
    val kinds: List<Int>? = null,
    val authors: List<String>? = null,
    val p: List<String>? = null,
    val d: List<String>? = null,
    val c: List<String>? = null,
    val since: Long? = null,
) {
    /** Whether a relay would deliver [event] for this filter (the fake relay uses it). */
    fun matches(event: SignedEvent): Boolean {
        if (kinds != null && event.kind !in kinds) return false
        if (authors != null && event.pubkey !in authors) return false
        if (since != null && event.createdAt < since) return false
        return listOf("p" to p, "d" to d, "c" to c).all { (name, values) ->
            values == null || event.tags.any { it.size > 1 && it[0] == name && it[1] in values }
        }
    }

    fun toJson(): String = buildJsonObject {
        kinds?.let { put("kinds", JsonArray(it.map(::JsonPrimitive))) }
        authors?.let { put("authors", strings(it)) }
        p?.let { put("#p", strings(it)) }
        d?.let { put("#d", strings(it)) }
        c?.let { put("#c", strings(it)) }
        since?.let { put("since", it) }
    }.toString()

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
}

/** What relays send back, tagged with the relay host. */
sealed interface RelayInbound {
    val relay: String

    data class Event(val event: SignedEvent, val subscription: String, override val relay: String) : RelayInbound

    /** End of stored events for a subscription on one relay. */
    data class EndOfStored(val subscription: String, override val relay: String) : RelayInbound

    /** A relay's answer to one of our publishes. */
    data class Ok(val eventId: String, val accepted: Boolean, override val relay: String) : RelayInbound

    /** The relay closed a subscription (auth-required is handled inside the pool). */
    data class Closed(val subscription: String, val reason: String, override val relay: String) : RelayInbound
}

/** One relay as Settings › Sharing shows it. Failures stay here; they are never thrown into the engines. */
data class RelayStatus(val connected: Boolean, val lastError: String? = null, val retryAttempt: Int = 0)

/**
 * The relay side of the protocol. [RelayPool] talks to real relays; tests use `FakeRelayTransport` and never open a
 * socket to a real relay.
 */
interface RelayTransport {
    /** Everything relays send. Hot: collect it before subscribing. */
    val inbound: Flow<RelayInbound>

    /** Per relay URL. */
    val status: StateFlow<Map<String, RelayStatus>>

    /** Connects every relay; idempotent. */
    fun start()

    /** Closes every socket; [start] may be called again later. */
    fun stop()

    /** Opens or replaces subscription [id]; re-sent after every reconnect. */
    fun subscribe(id: String, filters: List<RelayFilter>)

    fun close(id: String)

    /** Sends to every relay now and again after reconnects for an hour (or until replaced by a newer version). */
    fun publish(event: SignedEvent)

    /** The network came back: reconnect now instead of waiting out the backoff. */
    fun reconnectNow()

    /**
     * One-shot query: the events every connected relay stores for [filters], collected until each relay sent EOSE or
     * [timeoutMs] passed. [FetchResult.answered] = how many relays sent EOSE.
     */
    suspend fun fetch(filters: List<RelayFilter>, timeoutMs: Long): FetchResult
}

data class FetchResult(val events: List<SignedEvent>, val answered: Int)

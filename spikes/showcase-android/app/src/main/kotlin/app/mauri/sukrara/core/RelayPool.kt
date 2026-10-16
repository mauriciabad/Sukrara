// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.nostrdevkit.sdk.Event
import org.nostrdevkit.sdk.EventBuilder
import org.nostrdevkit.sdk.Keys
import org.nostrdevkit.sdk.Kind

/**
 * The relay [Transport]: a set of relays with the same writes and subscriptions on each. Events from all relays are
 * delivered with `via = "relay:<host>"`, so the receiver can record *where* a reading came from.
 */
class RelayPool(
    urls: List<String>,
    private val logger: (String) -> Unit,
    private val authKeys: () -> Keys?,
) : RelayHost, Transport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().build()   // pingInterval = 0 (default): no ping timer
    private val conns = ConcurrentHashMap<String, RelayConnection>()
    private val subs = ConcurrentHashMap<String, Subscription>()
    private val _stats = MutableStateFlow<Map<String, RelayStats>>(emptyMap())
    val stats: StateFlow<Map<String, RelayStats>> = _stats

    init { setRelays(urls) }

    val urls: List<String> get() = conns.keys.sorted()

    /** Add new relays (with all current subscriptions) and close the ones no longer listed. */
    @Synchronized
    fun setRelays(urls: List<String>) {
        val want = urls.map { it.trim().removeSuffix("/") }.filter { it.startsWith("wss://") }.distinct()
        (conns.keys - want.toSet()).forEach { u ->
            conns.remove(u)?.close()
            _stats.update { it - u }
        }
        want.filter { !conns.containsKey(it) }.forEach { u ->
            val c = RelayConnection(u, http, scope, this)
            conns[u] = c
            subs.forEach { (id, s) -> c.subscribe(id, s) }
        }
    }

    override fun publish(ev: Event, lane: Lane, key: String?) {
        val o = Outgoing.of(ev, lane, key)
        conns.values.forEach { it.enqueue(o) }
    }

    override fun subscribe(id: String, filters: () -> List<JSONObject>, onEvent: (JSONObject, String) -> Unit) {
        val s = Subscription(filters) { ev, url -> onEvent(ev, via(url)) }
        subs[id] = s
        conns.values.forEach { it.subscribe(id, s) }
    }

    override fun unsubscribe(id: String) {
        subs.remove(id)
        conns.values.forEach { it.unsubscribe(id) }
    }

    override fun hasSubscription(id: String) = subs.containsKey(id)

    /** One-shot query on every relay in parallel; union of events (first relay wins for duplicates). */
    override suspend fun fetch(filters: List<JSONObject>, timeoutMs: Long): List<Pair<JSONObject, String>> = coroutineScope {
        conns.values.map { c -> async { c.query(filters, timeoutMs).map { it to via(c.url) } } }
            .awaitAll().flatten().distinctBy { it.first.optString("id") }
    }

    override fun reconnect() = conns.values.forEach { it.reconnect() }

    override fun close() {
        conns.values.forEach { it.close() }
        conns.clear()
        scope.cancel()
    }

    /** `via` for events from this transport: `relay:<host>`. */
    private fun via(url: String) = "relay:" + url.removePrefix("wss://").removeSuffix("/")

    override fun stats(url: String, f: (RelayStats) -> RelayStats) =
        _stats.update { s -> s + (url to f(s[url] ?: RelayStats(url))) }

    override fun log(msg: String) = logger(msg)

    /** Event id → called each time a relay stores it (e.g. a follower's treatment: *sending* → *sent*). */
    @Volatile var onAccepted: ((String) -> Unit)? = null

    override fun accepted(url: String, id: String) { onAccepted?.invoke(id) }

    override fun authEvent(url: String, challenge: String): String? {
        val k = authKeys() ?: return null
        return EventBuilder(Kind(22242u), "")
            .tags(listOf(Proto.tag("relay", url), Proto.tag("challenge", challenge)))
            .finalize(k).asJson()
    }
}

// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmshare.core

import org.json.JSONObject
import org.nostrdevkit.sdk.Event

/**
 * Anything that carries signed v2 events: Nostr relays today ([RelayPool]), Bluetooth LE next (same events,
 * including access requests and key deliveries for nearby pairing). A transport only moves events; it never decides
 * what is trusted. Every incoming event goes to the engine's `onEvent(eventJson, via)` — the single place where
 * the acceptance rules (signature, seal signer, roster membership, epoch, decryption) are applied — tagged with
 * `via` = where it came from (`relay:<host>`, later `ble:<peer>`), which is stored with each reading.
 */
interface Transport {
    /** Fire-and-forget; the transport handles pacing / retries / replacement by [key] (the `d` tag). */
    fun publish(ev: Event, lane: Lane, key: String? = null)

    /** Persistent interest in events matching NIP-01 [filters]; [onEvent] gets `(event JSON, via)`. */
    fun subscribe(id: String, filters: () -> List<JSONObject>, onEvent: (JSONObject, String) -> Unit)

    fun unsubscribe(id: String)

    fun hasSubscription(id: String): Boolean

    /** One-shot query: whatever the transport can deliver now, as `(event JSON, via)`. */
    suspend fun fetch(filters: List<JSONObject>, timeoutMs: Long = 10_000): List<Pair<JSONObject, String>>

    /** Liveness hint from the engine ("nothing for a while"): reconnect if that means anything to this transport. */
    fun reconnect()

    fun close()
}

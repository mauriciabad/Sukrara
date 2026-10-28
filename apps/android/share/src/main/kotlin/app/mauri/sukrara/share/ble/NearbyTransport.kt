// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.ble.BleMessages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the follower's Bluetooth path reports. */
sealed interface NearbyEvent {
    data class Event(val event: SignedEvent) : NearbyEvent

    /**
     * A phone's INFO: ours, another wearer's, or not known yet (no `sid` before the first key). [phone] names the
     * connection it came on, so leaving it never hits a phone connected since. [pairing] = its *Pair nearby* offer.
     */
    data class Info(val owner: BleMessages.Owner, val phone: String, val pairing: BleMessages.Pairing? = null) : NearbyEvent
}

/**
 * The follower's Bluetooth path to the wearer's phone (`AndroidNearbyCentral`; tests use a fake). Used once: [start]
 * then [stop]. Events are verified by the engine exactly like relay events; nothing here is trusted.
 */
interface NearbyTransport {
    /** One collector (the follower engine). */
    val events: Flow<NearbyEvent>

    val status: StateFlow<NearbyStatus>

    fun start()

    /** Terminal: disconnects and ends [events]. */
    fun stop()

    /** The follower record changed (first key, new epoch): HELLO proofs and INFO checks use it. */
    fun update(identity: NearbyLink.Identity)

    /** Writes [event] to the wearer's phone: false when no phone proved it's the wearer's or the write failed. */
    suspend fun send(event: SignedEvent): Boolean

    /** Avoids [phone] (an [NearbyEvent.Info.phone]) for [seconds], dropping it if it's still the one connected. */
    fun leave(phone: String, seconds: Long)

    /** Bluetooth turned on / Nearby devices allowed: look again now. */
    fun refresh()
}

/** Makes the Bluetooth path for a follow (null = no Bluetooth on this phone). */
fun interface NearbyTransportFactory {
    fun create(identity: NearbyLink.Identity): NearbyTransport?
}

// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.ble.BleMessages
import app.mauri.sukrara.share.owner.OwnerNearby
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** The wearer's Bluetooth server without a radio: records what the engine hands it. */
class FakeNearbyServer : OwnerNearby {
    override val status = MutableStateFlow<NearbyStatus>(NearbyStatus.Stopped)
    override val links = MutableStateFlow<List<NearbyServerCore.Named>>(emptyList())
    var host: OwnerNearby.Host? = null
    val published = mutableListOf<SignedEvent>()
    val forwarded = mutableListOf<Pair<SignedEvent, String>>()
    val forgotten = mutableListOf<String>()

    override fun start(host: OwnerNearby.Host) {
        this.host = host
    }

    override fun stop() {
        host = null
    }

    override fun refresh() = Unit

    override fun publish(event: SignedEvent) {
        published += event
    }

    override fun forward(event: SignedEvent, except: String) {
        forwarded += event to except
    }

    override fun forget(follower: String) {
        forgotten += follower
    }
}

/** The follower's Bluetooth path without a radio: the test plays the wearer's phone. */
class FakeNearbyTransport(var identity: NearbyLink.Identity) : NearbyTransport {
    private val channel = Channel<NearbyEvent>(Channel.UNLIMITED)
    override val events: Flow<NearbyEvent> = channel.receiveAsFlow()
    override val status = MutableStateFlow<NearbyStatus>(NearbyStatus.Stopped)
    var started = false
    var stopped = false
    val sent = mutableListOf<SignedEvent>()
    val left = mutableListOf<Long>()
    var accepts = true

    override fun start() {
        started = true
        status.value = NearbyStatus.Searching
    }

    override fun stop() {
        stopped = true
        channel.close()
    }

    override fun update(identity: NearbyLink.Identity) {
        this.identity = identity
    }

    override suspend fun send(event: SignedEvent): Boolean {
        sent += event
        return accepts
    }

    override fun leave(seconds: Long) {
        left += seconds
    }

    override fun refresh() = Unit

    fun deliver(event: SignedEvent) = channel.trySend(NearbyEvent.Event(event))

    fun info(owner: BleMessages.Owner) {
        if (owner == BleMessages.Owner.Ours) status.value = NearbyStatus.Connected(1)
        channel.trySend(NearbyEvent.Info(owner))
    }
}

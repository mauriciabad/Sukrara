// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import app.mauri.sukrara.protocol.event.AlertPayload
import app.mauri.sukrara.protocol.event.SensorLife
import app.mauri.sukrara.protocol.event.SettingsPayload
import app.mauri.sukrara.protocol.event.TreatmentPayload
import app.mauri.sukrara.protocol.event.WireReading
import app.mauri.sukrara.protocol.follow.FollowResult
import app.mauri.sukrara.protocol.follow.FollowerState
import app.mauri.sukrara.protocol.follow.Item
import app.mauri.sukrara.protocol.follow.StreamFollower

/**
 * What a follower engine stores from [StreamFollower]'s accepted items — the minimal reducer the fixtures' expected
 * state is written against (chunk 25 does the same into Room): first reading per `t` wins, the newest live event's
 * sensor, treatments merged by `(u, o)` unless overridden, newest settings, every accepted alert.
 */
class FollowerModel(crypto: NostrCrypto, initial: FollowerState) {
    val follower = StreamFollower(crypto, initial)
    val readings = sortedMapOf<Long, WireReading>()
    var sensor: SensorLife? = null
    val treatments = linkedMapOf<String, TreatmentPayload>()
    var settings: SettingsPayload? = null
    val alerts = mutableListOf<AlertPayload>()

    fun feed(ev: SignedEvent, now: Long): FollowResult {
        val r = follower.onEvent(ev, now)
        if (r is FollowResult.Accepted) apply(r.item)
        return r
    }

    private fun apply(item: Item) {
        when (item) {
            is Item.Chunk -> item.chunk.readings.forEach { readings.putIfAbsent(it.t, it) }
            is Item.Live -> {
                item.live.reading?.let { readings.putIfAbsent(it.t, it) }
                if (item.sensorChanged) sensor = item.live.sensor
            }
            is Item.Treatment -> {
                val held = treatments[item.treatment.id]
                if (held == null || item.override || item.treatment.newerThan(held)) treatments[item.treatment.id] = item.treatment
            }
            is Item.Settings -> settings = item.settings
            is Item.Alert -> alerts += item.alert
            else -> Unit
        }
    }
}

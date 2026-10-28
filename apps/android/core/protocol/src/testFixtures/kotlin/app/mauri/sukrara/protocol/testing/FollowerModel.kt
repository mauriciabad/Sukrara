// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.testing

import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.event.AlertPayload
import app.mauri.sukrara.protocol.event.SensorLife
import app.mauri.sukrara.protocol.event.SettingsPayload
import app.mauri.sukrara.protocol.event.TreatmentPayload
import app.mauri.sukrara.protocol.event.WireReading
import app.mauri.sukrara.protocol.follow.FollowResult
import app.mauri.sukrara.protocol.follow.FollowerState
import app.mauri.sukrara.protocol.follow.Item
import app.mauri.sukrara.protocol.follow.StreamFollower
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a follower engine stores from [StreamFollower]'s accepted items — the minimal reducer the fixtures' expected
 * state is written against (the follower engine does the same into Room): first reading per `t` wins, the newest live event's
 * sensor, treatments merged by `(u, o)` unless overridden, newest settings, every accepted alert.
 */
class FollowerModel(private val crypto: NostrCrypto, initial: FollowerState) {
    var follower = StreamFollower(crypto, initial)
        private set
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

    /**
     * An app restart: the follower keeps its persisted state and the stored data but not the event ids it has seen nor
     * its rekey throttle (the Mac keeps those in memory only), so relay replays are judged again by the rules alone.
     */
    fun restart() {
        follower = StreamFollower(crypto, follower.state.copy(seen = emptyMap(), rekeyAt = emptyMap()))
    }

    /** The state as the sync fixtures' `expected` JSON (contracts/protocol/sync/README.md; the Mac compares the same). */
    fun stateJson(): JsonObject {
        val s = follower.state
        return buildJsonObject {
            put("status", s.status.name.lowercase())
            put("epochs", buildJsonArray { s.epochs.values.map { it.n }.sorted().forEach { add(JsonPrimitive(it)) } })
            put("devices", buildJsonArray { s.devices.forEach { add(JsonPrimitive(it)) } })
            put("writers", buildJsonArray { s.writers.forEach { add(JsonPrimitive(it)) } })
            put("readings", buildJsonArray { readings.values.forEach { r -> add(buildJsonArray { add(JsonPrimitive(r.t)); add(JsonPrimitive(r.sgv)); add(r.rate?.let(::JsonPrimitive) ?: JsonNull) }) } })
            put("sensor", sensor?.let { buildJsonObject { put("ss", it.start); put("se", it.end); put("sw", it.warmupEnd) } } ?: JsonNull)
            put(
                "treatments",
                buildJsonArray {
                    treatments.values.sortedBy { it.id }.forEach {
                        add(buildJsonObject { put("id", it.id); put("k", it.kind); put("a", it.amount); put("u", it.u); put("o", it.origin); put("del", it.deleted) })
                    }
                },
            )
            put("settings", settings?.let { buildJsonObject { put("u", it.u); put("graph", it.graph); put("alerts", it.alerts) } } ?: JsonNull)
            put("alerts", alerts.size)
        }
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

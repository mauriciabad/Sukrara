// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettingsCodec
import app.mauri.sukrara.alerts.SoundSettings
import app.mauri.sukrara.core.data.settings.FollowerSettingsRepository
import app.mauri.sukrara.core.data.settings.MirroredGraph
import app.mauri.sukrara.core.data.settings.SettingsFrom
import app.mauri.sukrara.core.testing.Contracts
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.share.owner.usecase.SettingsMirror
import app.mauri.sukrara.share.owner.usecase.WearerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Android wearer's side of *Settings from: Wearer*: what [WearerSettingsSource] gives the owner engine, after
 * [SettingsMirror.select], is exactly the settings event of contracts/protocol/sync/settings-mirror.json for the same
 * values, and a follower reading it gets the wearer's values back.
 */
class WearerSettingsSourceTest {
    private val expected =
        Json
            .parseToJsonElement(Contracts.file("protocol/sync/settings-mirror.json").readText())
            .jsonObject
            .getValue("expected")
            .jsonObject
            .getValue("settings")
            .jsonObject
    private val graph = expected.getValue("graph").jsonObject
    private val alerts = AlertSettingsCodec.decode(expected.getValue("alerts").toString())!!

    /** The wearer's own settings set to the fixture's values (with per-device extras that must stay home). */
    private suspend fun wearerPhone(): InMemorySettingsRepository =
        InMemorySettingsRepository().also { MirroredGraph.copyInto(it, FollowerSettingsBridge.MIRRORED_GRAPH, SettingsFrom.Wearer, graph) }

    private fun sent(w: WearerSettings): JsonObject =
        SettingsMirror.select(w).let { JsonObject(mapOf("graph" to it.graph, "alerts" to it.alerts)) }

    @Test
    fun `the wearer publishes the fixture's settings event content`() =
        runTest {
            val own = wearerPhone()
            val local =
                alerts
                    .copy(
                        sounds = SoundSettings(pack = "soft"),
                    ).withRule(AlertKind.NO_DATA, alerts.rule(AlertKind.NO_DATA).copy(threshold = 45.0))
            val w = WearerSettingsSource(own, flowOf(local)).settings().first()
            assertEquals(JsonObject(mapOf("graph" to graph, "alerts" to expected.getValue("alerts"))), sent(w))
        }

    @Test
    fun `a follower on Wearer reads the wearer's values back`() =
        runTest {
            val w = WearerSettingsSource(wearerPhone(), flowOf(alerts)).settings().first()
            val follower =
                FollowerSettingsRepository(
                    InMemorySettingsRepository(),
                    flowOf(SettingsFrom.Wearer),
                    flowOf(SettingsMirror.select(w).graph),
                )
            for (spec in FollowerSettingsBridge.MIRRORED_GRAPH) {
                assertEquals(spec.id, wearerPhone().flow(spec).first(), follower.flow(spec).first())
            }
            val back = AlertSettingsCodec.decode(SettingsMirror.select(w).alerts.toString())!!
            for (kind in AlertKind.entries - AlertKind.NO_DATA) {
                assertEquals(kind.wire, alerts.rule(kind).copy(alarm = false), back.rule(kind).copy(alarm = false))
            }
        }

    @Test
    fun `a new value goes out when a mirrored row changes, never for the same content`() =
        runTest {
            val own = wearerPhone()
            val alertFlow = MutableStateFlow(alerts)
            val seen = mutableListOf<JsonObject>()
            backgroundScope.launch { WearerSettingsSource(own, alertFlow).settings().collect { seen += sent(it) } }
            runCurrent()
            // the same values again (a rewrite, an equal alert object): nothing new
            own.set(GraphSettings.Bands[1], graph.getValue("low").toString().toDouble())
            alertFlow.value = alerts.copy()
            runCurrent()
            assertEquals(1, seen.size)
            own.set(GraphSettings.Bands[1], 72.0)
            runCurrent()
            alertFlow.update { it.withRule(AlertKind.HIGH, it.rule(AlertKind.HIGH).copy(threshold = 190.0)) }
            runCurrent()
            assertEquals(3, seen.size)
            assertEquals(
                "72",
                seen[1]
                    .getValue("graph")
                    .jsonObject
                    .getValue("low")
                    .toString(),
            )
            assertEquals(
                "190",
                seen[2]
                    .getValue("alerts")
                    .jsonObject
                    .getValue("alerts")
                    .jsonObject
                    .getValue("high")
                    .jsonObject
                    .getValue("mgdl")
                    .toString(),
            )
        }
}

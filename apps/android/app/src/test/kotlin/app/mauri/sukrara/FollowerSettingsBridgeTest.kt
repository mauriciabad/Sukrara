// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertSource
import app.mauri.sukrara.alerts.QuietRule
import app.mauri.sukrara.alerts.SoundSettings
import app.mauri.sukrara.core.data.settings.MirroredGraph
import app.mauri.sukrara.core.data.settings.SettingsFrom
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.monitor.alerts.AlertMirror
import app.mauri.sukrara.protocol.event.SettingsPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which value wins per *Settings from* for a follower's alerts and graph, and what always stays this phone's. */
class FollowerSettingsBridgeTest {
    private val wearerAlerts =
        Json
            .parseToJsonElement(
                """{"alerts":{"low":{"on":true,"mgdl":80,"repeatMin":10},"high":{"on":false,"mgdl":200,"repeatMin":0}},
                "muteAfterCarbs":{"on":false,"min":30}}""",
            ).jsonObject
    private val payload = SettingsPayload(1_800_000_000, JsonObject(mapOf("low" to JsonPrimitive(80))), wearerAlerts)

    /** This phone's own alert settings: different from the defaults and from the wearer everywhere it matters. */
    private val mine =
        AlertSettings()
            .withRule(AlertKind.LOW, AlertSettings().rule(AlertKind.LOW).copy(threshold = 65.0, alarm = true))
            .withRule(AlertKind.NO_DATA, AlertSettings().rule(AlertKind.NO_DATA).copy(threshold = 40.0))
            .copy(afterCarbs = QuietRule(true, 45), sounds = SoundSettings(pack = "soft"))

    private class Alerts(initial: AlertSettings) : FollowerSettingsBridge.AlertsAccess {
        val state = MutableStateFlow(initial)
        override val settings: Flow<AlertSettings> = state

        override suspend fun update(transform: (AlertSettings) -> AlertSettings) = state.update(transform)
    }

    private fun bridge(
        own: InMemorySettingsRepository,
        alerts: Alerts,
        role: Role? = Role.Follower,
        wearer: SettingsPayload? = payload,
    ) = FollowerSettingsBridge(own, alerts, MutableStateFlow(role), MutableStateFlow(wearer), MutableStateFlow("Ana"))

    @Test
    fun `wearer mode runs the wearer's mirrored rules, everything else stays this phone's`() {
        val used = AlertMirror.effective(mine, FollowerSettingsBridge.mirroredAlerts(SettingsFrom.Wearer, payload))
        assertEquals(80.0, used.rule(AlertKind.LOW).threshold, 0.0)
        assertEquals(10, used.rule(AlertKind.LOW).repeatMin)
        assertFalse(used.rule(AlertKind.HIGH).on)
        assertFalse(used.afterCarbs.on)
        // a key the wearer didn't send is its default, not this phone's
        assertEquals(AlertSettings().rule(AlertKind.VERY_LOW), used.rule(AlertKind.VERY_LOW))
        // never mirrored: alarm volume, no data, sounds
        assertTrue(used.rule(AlertKind.LOW).alarm)
        assertEquals(40.0, used.rule(AlertKind.NO_DATA).threshold, 0.0)
        assertEquals("soft", used.sounds.pack)
    }

    @Test
    fun `wearer mode before anything arrives runs the defaults, not this phone's own`() {
        val used = AlertMirror.effective(mine, FollowerSettingsBridge.mirroredAlerts(SettingsFrom.Wearer, null))
        assertEquals(AlertSettings().rule(AlertKind.LOW).threshold, used.rule(AlertKind.LOW).threshold, 0.0)
        assertEquals(40.0, used.rule(AlertKind.NO_DATA).threshold, 0.0)
    }

    @Test
    fun `default mode runs the defaults, custom this phone's own`() {
        val default = AlertMirror.effective(mine, FollowerSettingsBridge.mirroredAlerts(SettingsFrom.Default, payload))
        assertEquals(AlertSettings().rule(AlertKind.LOW).threshold, default.rule(AlertKind.LOW).threshold, 0.0)
        assertEquals(AlertSettings().afterCarbs, default.afterCarbs)
        assertTrue(default.rule(AlertKind.LOW).alarm)
        assertNull(FollowerSettingsBridge.mirroredAlerts(SettingsFrom.Custom, payload))
        assertEquals(mine, AlertMirror.effective(mine, null))
    }

    @Test
    fun `the choice drives the alert runtime and the graph`() =
        runTest {
            val own = InMemorySettingsRepository().apply { set(GraphSettings.Bands[1], 65.0) }
            val b = bridge(own, Alerts(mine))
            // Wearer is the default choice
            assertEquals(80.0, b.settings.flow(GraphSettings.Bands[1]).first(), 0.0)
            assertEquals(
                80.0,
                b.alertMirror
                    .first()!!
                    .rule(AlertKind.LOW)
                    .threshold,
                0.0,
            )
            b.choose(SettingsFrom.Default)
            assertEquals(70.0, b.settings.flow(GraphSettings.Bands[1]).first(), 0.0)
            b.choose(SettingsFrom.Custom)
            assertEquals(65.0, b.settings.flow(GraphSettings.Bands[1]).first(), 0.0)
            assertNull(b.alertMirror.first())
            assertEquals(SettingsFrom.Custom, b.state.first()!!.from)
            assertEquals("Ana", b.state.first()!!.wearerName)
        }

    @Test
    fun `the wearer's phone always uses its own and offers no choice`() =
        runTest {
            val own =
                InMemorySettingsRepository().apply {
                    set(GraphSettings.Bands[1], 65.0)
                    set(SettingsFrom.Stored, SettingsFrom.Default.wire)
                }
            val b = bridge(own, Alerts(mine), role = Role.Wearer)
            assertEquals(65.0, b.settings.flow(GraphSettings.Bands[1]).first(), 0.0)
            assertNull(b.alertMirror.first())
            assertNull(b.state.first())
        }

    @Test
    fun `copy and reset write into this phone's own values`() =
        runTest {
            val own = InMemorySettingsRepository()
            val alerts = Alerts(mine)
            val b = bridge(own, alerts)
            assertTrue(b.copyWearer())
            assertEquals(80.0, own.flow(GraphSettings.Bands[1]).first(), 0.0)
            assertEquals(
                80.0,
                alerts.state.value
                    .rule(AlertKind.LOW)
                    .threshold,
                0.0,
            )
            assertTrue(
                alerts.state.value
                    .rule(AlertKind.LOW)
                    .alarm,
            )
            assertEquals(
                40.0,
                alerts.state.value
                    .rule(AlertKind.NO_DATA)
                    .threshold,
                0.0,
            )
            b.resetToDefault()
            assertEquals(70.0, own.flow(GraphSettings.Bands[1]).first(), 0.0)
            assertEquals(
                AlertSettings().rule(AlertKind.LOW).threshold,
                alerts.state.value
                    .rule(AlertKind.LOW)
                    .threshold,
                0.0,
            )
            assertEquals("soft", alerts.state.value.sounds.pack)
            assertFalse(bridge(own, alerts, wearer = null).copyWearer())
        }

    @Test
    fun `the alerts' source follows the choice`() =
        runTest {
            val own = InMemorySettingsRepository()
            val alerts = Alerts(mine.copy(source = AlertSource.OWN))
            val b = bridge(own, alerts)
            backgroundScope.launch { b.keepAlertSource() }
            runCurrent()
            assertEquals(AlertSource.WEARER, alerts.state.value.source)
            b.choose(SettingsFrom.Custom)
            runCurrent()
            assertEquals(AlertSource.OWN, alerts.state.value.source)
        }

    @Test
    fun `copy and reset cover every mirrored graph setting of the schema`() {
        assertEquals(MirroredGraph.ids, FollowerSettingsBridge.MIRRORED_GRAPH.map { it.id }.toSet())
    }
}

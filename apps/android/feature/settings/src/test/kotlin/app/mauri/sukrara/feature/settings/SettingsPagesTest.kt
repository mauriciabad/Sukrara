// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Which schema settings these pages reach, and the alert / range edits they make. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPagesTest {
    /** Android settings the P0 pages show (Alerts, an alert's page, Ranges, Graph, the review). */
    private val shown =
        setOf(
            "graph.band.veryLow", "graph.band.low", "graph.band.tight", "graph.band.high", "graph.band.veryHigh",
            "graph.band.extremelyHigh", "graph.hours", "graph.ringCount", "graph.ringMarks", "graph.side", "graph.columnCrown",
            "graph.gridHours", "alerts.on", "alerts.kind.on", "alerts.threshold.very_low", "alerts.threshold.low",
            "alerts.threshold.high", "alerts.threshold.very_high", "alerts.threshold.extremely_high", "alerts.threshold.falling_fast",
            "alerts.threshold.rising_fast", "alerts.threshold.no_data", "alerts.kind.repeat", "alerts.kind.alarm", "alerts.kind.sound",
            "alerts.muteAfterInsulin.on", "alerts.muteAfterInsulin.min", "alerts.muteFallingHigh.on", "alerts.muteFallingHigh.min",
            "alerts.muteAfterCarbs.on", "alerts.muteAfterCarbs.min", "alerts.actions.muteMin", "alerts.actions.relaxMin",
            "alerts.actions.backMgdl", "alerts.actions.backMin", "alerts.sounds.pack", "alerts.sounds.shuffle",
            "alerts.thresholdsConfirmedAt",
            // Settings › Sensor (the app joins it to the sensor module)
            "source.source",
        )

    /** Shown by another chunk's screen (sensor 18, sharing / setup 24, home 16, pens 19, integrations 26, widgets 20). */
    private val elsewhere =
        setOf(
            "sensor.countdownHours", "sharing.nostr", "sharing.bleFollowers", "home.uploadBanner",
            "sensor.reminders", "widget.overrides", "statusbar.chip", "statusbar.icon",
        ) + SettingsSpec.SETTINGS.map { it.id }.filter { it.startsWith("pens.") || it.startsWith("apps.") } +
            setOf("sensorCalendar.on", "sensorCalendar.calendarId", "healthConnect.on")

    /** Not in V1's settings pages yet (P1: FEATURES.md "Not in V1"), or internal format / state with no row. */
    private val pending =
        setOf(
            "graph.hours.place", "graph.slots", "graph.ringRange", "graph.ringOrder", "graph.marks", "graph.lines", "graph.lastPlace",
            "graph.lastElse", "graph.sensorTime.place", "follower.settingsFrom", "alerts.source", "alerts.v",
            "statusbar.systemIcons", "statusbar.hook", "statusbar.hookPosition", "statusbar.hookColor", "statusbar.hideNotifIcon",
            "lock.widget", "lock.background", "lock.appearance", "lock.treatmentTime",
            // mmol/L in these pages: issue c17 (thresholds and ranges stay mg/dL here)
            "general.unit",
        )

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `every Android schema setting is shown here, by another screen, or listed as pending`() {
        val android = SettingsSpec.SETTINGS.filter { it.android.isNotEmpty() }.map { it.id }.toSet()
        assertEquals(emptySet<String>(), android - shown - elsewhere - pending)
        assertEquals(emptySet<String>(), (shown + pending) - android)
        assertEquals(emptySet<String>(), shown intersect pending)
    }

    @Test
    fun `every number the pages show has its schema format`() {
        for (kind in AlertKind.entries) assertNotNull(SchemaPrefs.thresholdSpec(kind))
        for (which in QuietKind.entries) assertNotNull(SchemaPrefs.spec(AlertEdits.quietSpecId(which)))
        for (band in SchemaPrefs.Bands) assertEquals(band.default, SchemaPrefs.bandSpec(band).default, 0.0)
        listOf("alerts.actions.muteMin", "alerts.actions.relaxMin", "alerts.actions.backMgdl", "alerts.actions.backMin", "alerts.kind.repeat")
            .forEach { assertNotNull(SchemaPrefs.spec(it)) }
    }

    @Test
    fun `a band must stay between its neighbours`() {
        val bands = listOf(54.0, 70.0, 140.0, 180.0, 250.0, 300.0)
        assertNull(BandOrder.conflict(bands, 1, 65.0))
        assertEquals(54.0 to 140.0, BandOrder.conflict(bands, 1, 54.0))
        assertEquals(250.0 to null, BandOrder.conflict(bands, 5, 240.0))
        assertNull(BandOrder.conflict(bands, 0, 30.0))
    }

    @Test
    fun `a level threshold can't cross its neighbours`() {
        val s = app.mauri.sukrara.alerts.AlertSettings()
        assertNull(AlertEdits.thresholdConflict(s, AlertKind.LOW, 65.0))
        assertEquals(null to 70.0, AlertEdits.thresholdConflict(s, AlertKind.VERY_LOW, 80.0))
        assertEquals(180.0 to 300.0, AlertEdits.thresholdConflict(s, AlertKind.VERY_HIGH, 160.0))
        assertNull(AlertEdits.thresholdConflict(s, AlertKind.NO_DATA, 5.0))
    }

    @Test
    fun `alert edits change only their field and keep values in format`() {
        val s0 = app.mauri.sukrara.alerts.AlertSettings()
        val s1 = AlertEdits.repeat(AlertKind.HIGH, 0.0)(s0)
        assertEquals(0, s1.rule(AlertKind.HIGH).repeatMin)
        assertEquals(s0.rule(AlertKind.LOW), s1.rule(AlertKind.LOW))
        val s2 = AlertEdits.sound(AlertKind.LOW, "soft")(s1)
        assertEquals("soft", s2.sounds.soundOf(AlertKind.LOW))
        assertEquals(s0.sounds.pack, AlertEdits.sound(AlertKind.LOW, null)(s2).sounds.soundOf(AlertKind.LOW))
        val s3 = AlertEdits.quiet(QuietKind.AfterCarbs) { it.copy(on = false) }(s0)
        assertFalse(s3.afterCarbs.on)
        assertTrue(s3.afterInsulin.on)
    }

    @Test
    fun `mute all until turned off and unmute go to the alert runtime`() {
        val controls = RecordingControls()
        val deps =
            object : SettingsDeps {
                override val settings = InMemorySettingsRepository()
                override val alertSettings = FakeAlertSettingsStore()
                override val alertControls = controls
                override val alertState = flowOf(AlertUiState())
                override val readiness = FakeReadiness()
                override val appVersion = "0.90.0"
                override val prerelease = true
                override val releases = ReleasesSource { Result.success(emptyList()) }
                override val clock = FakeClock()
            }
        val vm = AlertsViewModel(deps)
        vm.muteAll(0.0)
        vm.muteAll(120.0)
        vm.unmuteAll()
        vm.unmute(AlertKind.VERY_LOW)
        vm.remindAsUsual(AlertKind.HIGH)
        assertEquals(listOf("muteAll null", "muteAll 120", "unmuteAll", "unmute very_low", "remindAsUsual high"), controls.calls)
    }
}

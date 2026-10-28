// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.notify

import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.testing.Contracts
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.stats.toPoint
import app.mauri.sukrara.monitor.LiveNotification
import app.mauri.sukrara.monitor.R
import app.mauri.sukrara.monitor.notify.LiveNotificationContent.Icon
import app.mauri.sukrara.monitor.notify.LiveNotificationContent.Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The glucose notification's content (surface notification-text) from the hub state and the card's face rule. */
class LiveNotificationContentTest {
    private val now = Instant.parse("2026-01-15T12:00:00Z")

    private fun state(
        mgdl: Int? = 138,
        ageS: Long = 30,
        rate: Double? = 1.2,
        sensor: SensorState = SensorState.Active(null),
        role: Role? = Role.Wearer,
        started: Boolean = true,
        firing: Set<String> = emptySet(),
    ): GlucoseState {
        val reading = mgdl?.let { Reading(now.minusSeconds(ageS), it, rate, SourceId.Libre3) }
        return GlucoseState.Initial.copy(
            role = role,
            latest = reading,
            recent = listOfNotNull(reading),
            sensor = sensor,
            alerts = AlertUiState(firingKinds = firing),
            monitoringStartedAt = if (started) now - Duration.ofHours(1) else null,
        )
    }

    /** Chip on and allowed unless said otherwise. */
    private fun content(
        state: GlucoseState,
        prefs: NotificationPrefs = NotificationPrefs(chip = true, promotionAllowed = true),
    ) = LiveNotificationContent.from(state, now, LiveNotification::face, prefs)

    @Test
    fun `chip on and allowed is a Live Update with the arrow icon`() {
        val c = content(state())
        assertEquals(LiveNotificationContent.Layout.LIVE_UPDATE, c.layout)
        assertEquals(Icon.TREND, c.icon)
        assertNull(c.cardKey)
    }

    @Test
    fun `chip off is the colorized card with the number icon and its card key`() {
        val c = content(state(), NotificationPrefs(chip = false, promotionAllowed = true, numberIcon = true, cardKey = "k"))
        assertEquals(LiveNotificationContent.Layout.CARD, c.layout)
        assertNull(c.chip)
        assertEquals(Icon.NUMBER, c.icon)
        assertEquals("138", c.look?.text)
        assertEquals(138, c.mgdl)
        assertEquals("k", c.cardKey)
    }

    @Test
    fun `chip wanted but Live Updates refused falls back to the card`() {
        val c = content(state(), NotificationPrefs(chip = true, promotionAllowed = false, numberIcon = false))
        assertEquals(LiveNotificationContent.Layout.CARD, c.layout)
        assertNull(c.chip)
        assertEquals(Icon.TREND, c.icon)
    }

    @Test
    fun `a struck value keeps the number icon struck, from the face`() {
        val c = content(state(ageS = 600), NotificationPrefs(chip = false, numberIcon = true))
        assertEquals(Icon.NUMBER, c.icon)
        assertTrue(c.look!!.strike)
    }

    @Test
    fun `states without a value are text whatever the settings`() {
        for (prefs in listOf(NotificationPrefs(chip = false), NotificationPrefs(chip = true, promotionAllowed = true))) {
            assertEquals(LiveNotificationContent.Layout.TEXT, content(state(mgdl = null), prefs).layout)
            assertEquals(LiveNotificationContent.Layout.TEXT, content(state(sensor = SensorState.Ended), prefs).layout)
            assertEquals(LiveNotificationContent.Layout.TEXT, content(state(ageS = 25 * 60), prefs).layout)
        }
    }

    @Test
    fun `a fresh value shows value and arrow in the title and the chip, no time`() {
        val c = content(state())
        assertEquals(UiText(R.string.monitor_notif_value, listOf("138", "↗")), c.title)
        assertEquals(UiText(R.string.monitor_chip_value, listOf("138")), c.chip)
        assertEquals(Icon.TREND, c.icon)
        assertEquals(1.2, c.trendRate!!, 0.0)
        assertNull(c.readingAt)
        assertEquals(false, c.strike)
    }

    @Test
    fun `a late value shows the reading time`() {
        val c = content(state(ageS = 3 * 60))
        assertEquals(now.minusSeconds(180), c.readingAt)
        assertEquals(UiText(R.string.monitor_notif_reading_at, listOf(now.minusSeconds(180))), c.text)
    }

    @Test
    fun `a stale value is struck and the chip shows its time, never a bare number`() {
        val c = content(state(ageS = 10 * 60))
        assertTrue(c.strike)
        assertEquals(UiText(R.string.monitor_chip_stale, listOf(now.minusSeconds(600))), c.chip)
        assertEquals(Icon.NO_TREND, c.icon)
    }

    @Test
    fun `from 20 minutes no value but no readings since`() {
        val c = content(state(ageS = 25 * 60))
        assertEquals(R.string.monitor_notif_no_readings_since, c.title.res)
        assertNull(c.chip)
        assertEquals(Icon.NO_READINGS, c.icon)
    }

    @Test
    fun `LO and HI come from the face`() {
        assertEquals("LO", content(state(mgdl = 39)).title.args[0])
        assertEquals("HI", content(state(mgdl = 450)).title.args[0])
    }

    @Test
    fun `before the first reading it says not monitoring with set up sensor`() {
        val c = content(state(mgdl = null, started = false))
        assertEquals(UiText(R.string.monitor_notif_not_monitoring), c.title)
        assertEquals(Target.SensorSetup, c.tap)
        assertEquals(
            R.string.monitor_action_set_up_sensor,
            c.actions
                .single()
                .label.res,
        )
        assertNull(c.chip)
    }

    @Test
    fun `setup not done is not monitoring too`() {
        assertEquals(UiText(R.string.monitor_notif_not_monitoring), content(state(role = null)).title)
    }

    @Test
    fun `the tap opens quick add treatment, carbs focused while a low fires`() {
        assertEquals(Target.QuickTreatment(focusCarbs = false), content(state()).tap)
        assertEquals(Target.QuickTreatment(focusCarbs = true), content(state(firing = setOf("low"))).tap)
        assertEquals(Target.QuickTreatment(focusCarbs = true), content(state(firing = setOf("very_low"))).tap)
        assertEquals(Target.OpenApp, content(state()).actions.single().target)
    }

    @Test
    fun `sensor ended asks the wearer to replace it`() {
        val c = content(state(sensor = SensorState.Ended))
        assertEquals(R.string.monitor_notif_sensor_ended, c.title.res)
        assertEquals(Target.SensorSetup, c.tap)
        assertEquals(Icon.SENSOR_ENDED, c.icon)
    }

    @Test
    fun `bluetooth off is said under the value`() {
        val c = content(state(sensor = SensorState.BluetoothOff))
        assertEquals(UiText(R.string.monitor_notif_bluetooth_off), c.text)
    }

    @Test
    fun `equal states give equal contents (the notifier skips re-posting them)`() {
        assertEquals(content(state()), content(state()))
    }

    @Test
    fun `the notification takes the renderer's face for its surface`() {
        val s = state(ageS = 10 * 60)
        assertEquals(
            GlucoseCard.face(s.latest?.toPoint(), now.epochSecond, null, CardSurface.NOTIFICATION_TEXT),
            LiveNotification.face(s, now),
        )
    }

    /** Same rate, same arrow: the title writes the fixture's arrow (contracts/graph/trend), the chip's icon draws it. */
    @Test
    fun `the title's arrow is the renderer's for every rate of the trend fixture`() {
        val cases =
            Json
                .parseToJsonElement(Contracts.file("graph/trend/trend.json").readText())
                .jsonObject
                .getValue("cases")
                .jsonArray
        assertTrue(cases.isNotEmpty())
        cases.map { it.jsonObject }.forEach { c ->
            val rate = c.getValue("rate").jsonPrimitive.doubleOrNull
            val want =
                c
                    .getValue("expect")
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content
            val name = c.getValue("name").jsonPrimitive.content
            val content = content(state(rate = rate))
            assertEquals(name, UiText(R.string.monitor_notif_value, listOf("138", want)), content.title)
            assertEquals(name, rate, content.trendRate)
        }
    }

    @Test
    fun `a wearer with followers gets the followers and nearby sub text in every state, a follower never`() {
        val line = SharingLine(followers = 2, nearby = 1)
        val prefs = NotificationPrefs(chip = true, promotionAllowed = true, sharing = line)
        assertEquals(line, content(state(), prefs).sharing)
        assertEquals(line, content(state(mgdl = null), prefs).sharing)
        assertEquals(line, content(state(sensor = SensorState.Ended), prefs).sharing)
        assertNull(content(state(role = Role.Follower), prefs).sharing)
        assertNull(content(state(), NotificationPrefs()).sharing)
        assertNull(content(state(role = null), prefs).sharing)
        // a sharing identity without followers says nothing (no "0 followers" on the lock screen)
        assertNull(content(state(), prefs.copy(sharing = SharingLine(0, 0))).sharing)
    }
}

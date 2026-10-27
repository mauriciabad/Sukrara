// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.notify

import app.mauri.sukrara.alerts.AlertGroup
import app.mauri.sukrara.monitor.alerts.AlertNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelsTest {
    private val ids = Channels.ALL.map { it.id }

    @Test
    fun `every V1 channel id exists exactly once`() {
        val expected =
            listOf("glucose", "status", "requests", "sensor", "sensor.countdown", "pen.results", "pen.check") +
                Channels.ALERT_KINDS.map { "alert.$it" } + Channels.ALERT_KINDS.map { "alert_alarm.$it" }
        assertEquals(expected.sorted(), ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the alert kinds are the contract's`() {
        assertEquals(
            listOf("very_low", "low", "high", "very_high", "extremely_high", "falling_fast", "rising_fast", "no_data"),
            Channels.ALERT_KINDS,
        )
    }

    @Test
    fun `alerts never share a group with other notifications`() {
        Channels.ALL.forEach { spec ->
            when (spec.kind) {
                Channels.Kind.ALERT -> assertEquals(spec.id, Channels.Group.ALERTS, spec.group)
                Channels.Kind.ALARM -> assertEquals(spec.id, Channels.Group.ALERTS_ALARM, spec.group)
                Channels.Kind.INFO -> assertEquals(spec.id, Channels.Group.INFO, spec.group)
            }
        }
        assertEquals(listOf("alerts", "alerts_alarm", "info"), Channels.Group.entries.map { it.id })
    }

    @Test
    fun `alert channels are high importance, the glucose one is silent`() {
        Channels.ALL.filter { it.kind != Channels.Kind.INFO }.forEach { assertEquals(it.id, 4, it.importance) }
        assertEquals(2, Channels.ALL.single { it.id == Channels.GLUCOSE }.importance)
    }

    @Test
    fun `notification ids never collide and alerts start at 1000`() {
        val alertIds = listOf(NotificationIds.ALERTS_SUMMARY) + AlertGroup.entries.map(AlertNotifier::idOf)
        assertEquals((1000..1003).toList(), alertIds)
        assertTrue(NotificationIds.INFO_SUMMARY > alertIds.max())
        val fixed =
            listOf(
                NotificationIds.GLUCOSE,
                NotificationIds.MONITORING_STOPPED,
                NotificationIds.SENSOR,
                NotificationIds.SENSOR_COUNTDOWN,
                NotificationIds.PEN_RESULTS,
                NotificationIds.PEN_CHECK,
                NotificationIds.REQUESTS,
            )
        assertEquals(fixed.size, fixed.toSet().size)
        assertTrue(fixed.all { it < NotificationIds.ALERTS_BASE })
    }
}

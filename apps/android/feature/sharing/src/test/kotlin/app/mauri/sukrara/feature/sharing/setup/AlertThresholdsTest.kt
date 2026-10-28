// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertThresholdsTest {
    private val t = AlertThresholds(AlertSettings())

    @Test
    fun `shows the runtime's values`() {
        assertEquals(55.0, t.value(ThresholdField.VeryLow), 0.0)
        assertEquals(70.0, t.value(ThresholdField.Low), 0.0)
        assertEquals(180.0, t.value(ThresholdField.High), 0.0)
        assertEquals(250.0, t.value(ThresholdField.VeryHigh), 0.0)
        assertEquals(300.0, t.value(ThresholdField.ExtremelyHigh), 0.0)
        assertEquals(AlertSettings().rule(AlertKind.NO_DATA).threshold, t.value(ThresholdField.NoData), 0.0)
        assertTrue(t.ordered)
    }

    @Test
    fun `editing keeps on, repeat and alarm of the rule`() {
        val edited = t.with(ThresholdField.Low, 75.0)
        assertEquals(75.0, edited.value(ThresholdField.Low), 0.0)
        val before = AlertSettings().rule(AlertKind.LOW)
        assertEquals(before.copy(threshold = 75.0), edited.settings.rule(AlertKind.LOW))
    }

    @Test
    fun `out of format values snap into the setting's range`() {
        assertEquals(50.0, t.with(ThresholdField.Low, 10.0).value(ThresholdField.Low), 0.0)
    }

    @Test
    fun `levels out of order are flagged`() {
        assertFalse(t.with(ThresholdField.Low, 50.0).with(ThresholdField.VeryLow, 60.0).ordered)
        assertFalse(t.with(ThresholdField.High, 260.0).ordered)
        assertFalse(t.with(ThresholdField.VeryHigh, 300.0).ordered)
    }

    @Test
    fun `the step reviews the same kinds as Settings, in its order`() {
        assertEquals(
            listOf(AlertKind.VERY_LOW, AlertKind.LOW, AlertKind.HIGH, AlertKind.VERY_HIGH, AlertKind.EXTREMELY_HIGH, AlertKind.NO_DATA),
            ThresholdField.entries.map { it.kind },
        )
    }
}

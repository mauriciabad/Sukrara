// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SensorFeedbackTest {
    @Test
    fun `the issue names the family, app, Android and phone only`() {
        val url = SensorFeedback.issueUrl("Dexcom G7 / ONE+", SensorFeedback.Device("1.2.3", "16 (API 36)", "Google Pixel 10 Pro"))
        assertEquals(
            "https://github.com/mauriciabad/sukrara/issues/new?template=sensor-feedback.yml" +
                "&title=Sensor%20feedback%3A%20Dexcom%20G7%20%2F%20ONE%2B&labels=sensor-feedback" +
                "&sensor=Dexcom%20G7%20%2F%20ONE%2B&app-version=1.2.3&android=16%20%28API%2036%29&phone=Google%20Pixel%2010%20Pro",
            url,
        )
        assertFalse(url.substringAfter('?').contains('+'))
    }
}

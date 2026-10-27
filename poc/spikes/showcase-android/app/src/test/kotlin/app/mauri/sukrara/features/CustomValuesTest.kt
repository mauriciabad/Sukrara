// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphPlace
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Custom values typed in a number dialog survive the settings file (export → import), not only the presets. */
class CustomValuesTest {
    @Test fun featureIntervalsRoundTrip() {
        val x = FeatureState(nostrIntervalMin = 7, libreviewIntervalMin = 42)
        assertEquals(x, FeatureState.fromJson(JSONObject(x.toJson().toString())))
    }

    @Test fun alertNumbersRoundTrip() {
        val d = AlertSettings()
        val x = d.copy(kinds = d.kinds + (Kind.LOW to d.cfg(Kind.LOW).copy(value = 68.0, repeatMin = 7)) +
            (Kind.FALLING_FAST to d.cfg(Kind.FALLING_FAST).copy(value = 1.7)), muteInsulinMin = 75, muteFallingMin = 25, muteCarbsMin = 12)
        assertEquals(x, AlertSettings.fromJson(JSONObject(x.toJson().toString())))
    }

    @Test fun graphWindowsAndRingPeriodsRoundTrip() {
        val d = GraphConfig()
        val x = d.copy(defaultHours = 5, placeHours = mapOf(GraphPlace.WIDGETS to 36),
            slots = d.slots.toMutableList().also { it[0] = it[0].copy(windowS = 10 * 86400L) })
        val back = GraphConfig.fromJson(JSONObject(x.toJson().toString()))
        assertEquals(5, back.defaultHours)
        assertEquals(36, back.placeHours[GraphPlace.WIDGETS])
        assertEquals(10 * 86400L, back.slots[0].windowS)
    }
}

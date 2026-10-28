// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import app.mauri.sukrara.core.model.DurationWords
import app.mauri.sukrara.settings.SettingsSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Minute settings read through THE duration formatter: Mute all's choices are "1 h", "2 h", never "60 min". */
class MinutesTextTest {
    private val words = DurationWords("%1\$dm", "%1\$dh", "%1\$dh %2\$dm", "%1\$dd", "%1\$d min", "%1\$d h", "%1\$d h %2\$d min")

    @Test
    fun muteAllPresetsReadInHours() {
        val spec = SettingsSpec.MUTE_ALL
        val labels = spec.presets.filter { spec.specialOf(it) == null }.map { minutesText(spec, it, words) }
        assertEquals(listOf("30 min", "1 h", "2 h", "4 h", "8 h"), labels)
        assertEquals("1 h 30 min", minutesText(spec, 90.0, words))
    }

    @Test
    fun otherUnitsKeepTheirOwnLabel() {
        assertNull(minutesText(SettingsSpec.THRESHOLD_LOW, 70.0, words))
        assertNull(minutesText(SettingsSpec.GRAPH_HOURS, 6.0, words))
    }
}

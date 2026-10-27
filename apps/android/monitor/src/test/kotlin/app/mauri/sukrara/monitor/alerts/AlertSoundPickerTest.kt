// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.SoundSettings
import app.mauri.sukrara.core.testing.Contracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file an alert plays, and that every file it can pick exists in contracts/alerts/sounds/android/raw. */
class AlertSoundPickerTest {
    @Test fun samePickWhileTheNotificationOpensTheUriTwiceThenANewVariant() {
        val p = AlertSoundPicker()
        val s = SoundSettings(pack = "quest", shuffle = true)
        val first = p.pick(AlertKind.LOW, s, 0)
        assertEquals(first, p.pick(AlertKind.LOW, s, 1_000))
        assertNotEquals(first, p.pick(AlertKind.LOW, s, 10_000))
    }

    @Test fun noShuffleSystemAndUnknownPack() {
        val p = AlertSoundPicker()
        assertEquals("soft_high_1", p.pick(AlertKind.HIGH, SoundSettings(pack = "soft", shuffle = false), 0))
        assertEquals(AlertSoundPicker.SYSTEM, p.pick(AlertKind.LOW, SoundSettings(pack = "system"), 0))
        assertEquals(AlertSoundPicker.SILENCE, p.pick(AlertKind.NO_DATA, SoundSettings(pack = "none"), 0))
    }

    @Test fun everyPickableFileExists() {
        val raw = Contracts.root.resolve("alerts/sounds/android/raw")
        val names = raw.listFiles().orEmpty().map { it.nameWithoutExtension }.toSet()
        assertTrue(AlertSoundPicker.SILENCE in names)
        for (pack in AlertSoundPicker.PACKS) for (k in AlertKind.entries) for (n in 1..AlertSoundPicker.VARIANTS) {
            assertTrue("${pack}_${k.wire}_$n missing", "${pack}_${k.wire}_$n" in names)
        }
    }
}

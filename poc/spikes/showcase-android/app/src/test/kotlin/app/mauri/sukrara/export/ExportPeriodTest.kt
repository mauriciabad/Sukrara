// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.export

import app.mauri.sukrara.treatments.Treatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Export data → Period → All time: from the oldest reading or (live) entry on the phone to now. */
class ExportPeriodTest {
    private val now = 1_800_000_000L

    @Test fun allTimeStartsAtTheOldestData() {
        val readings = listOf(GlucoseCsv.Row(now - 5000, 100, null), GlucoseCsv.Row(now - 9000, 110, null))
        assertEquals(now - 9000, Export.oldest(readings, emptyList()))
        assertEquals(now - 9000 to now + 1, Export.range(Export.ALL, now, Export.oldest(readings, emptyList())))
    }

    @Test fun deletedEntriesDontCount() {
        val old = Treatment("a", "carbs", 10.0, now - 20000, u = 1, del = true, o = "phone")
        val kept = Treatment("b", "carbs", 10.0, now - 15000, u = 1, o = "phone")
        assertEquals(now - 15000, Export.oldest(emptyList(), listOf(old, kept)))
    }

    @Test fun nothingOnThePhone() {
        assertNull(Export.oldest(emptyList(), emptyList()))
        assertEquals(now to now + 1, Export.range(Export.ALL, now, null))
    }
}

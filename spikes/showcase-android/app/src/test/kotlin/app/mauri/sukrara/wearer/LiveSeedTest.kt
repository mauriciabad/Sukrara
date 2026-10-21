// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.wearer

import app.mauri.sukrara.core.Reading
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Validation 21, item 5: after a restart the glucose notification said "waiting for glucose…" although a reading was
 * stored (widgets and the app showed it): the role's state starts from the stored newest reading.
 */
class LiveSeedTest {
    @Test fun seedFillsAnEmptyStateAndNeverReplacesANewerReading() {
        Live.status.value = WearerStatus()
        Live.seed(Reading(1_000, 120, null, "db"))
        assertEquals(1_000L, Live.status.value.last?.t)
        Live.reading(Reading(1_300, 125, 0.2, "live"))
        Live.seed(Reading(1_000, 120, null, "db"))
        assertEquals(1_300L, Live.status.value.last?.t)
        Live.seed(null)
        assertEquals(1_300L, Live.status.value.last?.t)
        Live.status.value = WearerStatus()
    }
}

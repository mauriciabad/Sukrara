// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanStartBudgetTest {
    @Test fun startsAreCountedAcrossScannersAndFreedAfterTheWindow() {
        val budget = ScanStartBudget(limit = 4, windowMs = 30_000)
        // the follow link and Find phones nearby take from the same budget
        repeat(4) { assertEquals(0L, budget.take(1_000L + it)) }
        // the fifth waits until the oldest start leaves the window (+ a margin), and isn't counted
        assertEquals(30_100L - 5_000L, budget.take(6_000))
        assertEquals(30_100L - 5_000L, budget.take(6_000))
        assertEquals(0L, budget.take(31_001))
    }
}

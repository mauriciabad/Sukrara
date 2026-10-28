// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported to Kotlin from xDrip's insulin/opennov/data/SaveCompleted.isPrimingDose + SaveCompletedTest (GPL-3.0), JamOrHam.
package app.mauri.novopen.opennov.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * xDrip's priming rule, kept as a reference for docs/29-novopen.md (Sukrara uses its own engine, DESIGN §4): a dose
 * ≤ [doseThreshold] u is priming when a non-zero dose of the same pen follows within [timeThresholdMs], unless a
 * zeroed (already-priming) dose precedes it within that window.
 */
object XdripPriming {
    data class Dose(val id: Int, val timestamp: Long, var insulin: Double, val notes: String)

    fun isPrimingDose(cache: List<Dose>, dose: Dose, doseThreshold: Double, timeThresholdMs: Long): Boolean {
        if (dose.insulin > doseThreshold) return false
        val t = dose.timestamp
        if (cache.any { it.timestamp < t && it.timestamp > t - timeThresholdMs && it.id != dose.id && it.insulin == 0.0 }) return false
        for (next in cache) {
            if (next.timestamp > t && next.timestamp < t + timeThresholdMs && next.id != dose.id && next.notes == dose.notes && next.insulin > 0) return true
            if (next.timestamp > t + timeThresholdMs) break
        }
        return false
    }
}

class XdripPrimingTest {
    /** Ten 1 u doses 1 ms apart (as xDrip's test). */
    private fun cache() = List(10) { XdripPriming.Dose(it, 1_000_000L + it, 1.0, "test") }

    private fun verify(expected: Set<Int>, doseThreshold: Double, timeThresholdMs: Long) {
        val cache = cache()
        cache.forEachIndexed { i, d ->
            val priming = XdripPriming.isPrimingDose(cache, d, doseThreshold, timeThresholdMs)
            if (priming) d.insulin = 0.0
            assertEquals("dose $i", i in expected, priming)
        }
    }

    @Test fun isPrimingDoseTest1() = verify(setOf(0, 3, 6), 2.0, 3)

    @Test fun isPrimingDoseTest2() = verify(setOf(0), 2.0, 30)
}

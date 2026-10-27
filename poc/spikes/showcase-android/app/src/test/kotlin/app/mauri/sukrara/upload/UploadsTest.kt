// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.upload

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces Tidepool and Nightscout share (upload/Uploads.kt). */
class UploadsTest {
    @Test fun rangesRoundTrip() {
        val xs = listOf(5L, 1, 2, 3, 9, 10, 4, 20)
        val r = SentRanges.ranges(xs)
        assertEquals(listOf(listOf(1L, 5L), listOf(9L, 10L), listOf(20L, 20L)), r.map { it.toList() })
        assertEquals(xs.toSortedSet(), SentRanges.expand(r))
    }

    @Test fun backoff() {
        assertEquals(0, UploadBackoff.ms(0, 60_000, 3_600_000))
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 60, 60).map { it * 60_000 }, (1..8).map { UploadBackoff.ms(it, 60_000, 3_600_000) })
        assertEquals(15 * 60_000L, UploadBackoff.ms(40, 30_000, 15 * 60_000))
        repeat(50) { val j = UploadBackoff.ms(1, 30_000, 900_000, Random(it)); assertTrue(j in 24_000L..36_000L) }
    }
}

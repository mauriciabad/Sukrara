// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.status

import org.junit.Assert.assertEquals
import org.junit.Test

class PostGateTest {
    private var now = 1_000_000L
    private val due = mutableListOf<Pair<Long, () -> Unit>>()
    private val posted = mutableListOf<Pair<Long, String>>()
    private val gate = PostGate(1_000, { now }, { d, run -> due += (now + d) to run })

    private fun submit(key: String, force: Boolean = false) = gate.submit(key, force) { posted += now to key }

    /** Advances the clock, running the scheduled flushes in order. */
    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = due.minByOrNull { it.first }?.takeIf { it.first <= end } ?: break
            due.remove(next)
            now = next.first
            next.second()
        }
        now = end
    }

    @Test fun firstChangePostsAtOnce() {
        submit("a")
        assertEquals(listOf(now to "a"), posted)
    }

    @Test fun sameContentIsNotPostedAgain() {
        submit("a"); advance(5_000); submit("a"); advance(5_000); submit("a")
        assertEquals(1, posted.size)
    }

    @Test fun forceRepostsSameContent() {
        submit("a"); advance(5_000); submit("a", force = true)
        assertEquals(2, posted.size)
    }

    @Test fun burstIsConflatedToOnePostPerSecondLatestWins() {
        val t0 = now
        submit("r1")                                       // the reading: at once
        repeat(16) { advance(50); submit("h$it") }        // a backfill: 16 history packets in 0.8 s
        advance(2_000)
        assertEquals(listOf(t0 to "r1", t0 + 1_000 to "h15"), posted)
    }

    @Test fun neverMoreThanOnePerSecond() {
        repeat(200) { advance(37); submit("v$it") }
        advance(2_000)
        posted.zipWithNext().forEach { (a, b) -> assert(b.first - a.first >= 1_000) { "$a then $b" } }
        assertEquals("v199", posted.last().second)
    }

    @Test fun pendingBackToPostedContentIsDropped() {
        submit("a"); advance(100); submit("b"); advance(100); submit("a")
        advance(2_000)
        assertEquals(1, posted.size)
    }

    @Test fun newValueAfterQuietGapIsNotDelayed() {
        submit("a"); advance(1_000); submit("b")
        assertEquals(listOf("a", "b"), posted.map { it.second })
        assertEquals(now, posted.last().first)
    }

    @Test fun forceInsideTheGapIsKeptWhenConflated() {
        submit("a"); advance(100); submit("a", force = true); submit("a")
        advance(2_000)
        assertEquals(2, posted.size)
    }
}

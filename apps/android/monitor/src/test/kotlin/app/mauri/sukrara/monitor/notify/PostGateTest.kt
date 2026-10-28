// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class PostGateTest {
    private var now = 10_000L
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val posts = mutableListOf<String>()
    private val gate = PostGate<String>(1_000, { now }) { delay, run -> scheduled += (now + delay) to run }

    private fun submit(
        key: String,
        force: Boolean = false,
    ) = gate.submit(key, force) { posts += key }

    private fun runDue() {
        val due = scheduled.filter { it.first <= now }
        scheduled.removeAll(due)
        due.forEach { it.second() }
    }

    @Test
    fun `the first change posts at once, the same content is skipped`() {
        submit("a")
        submit("a")
        assertEquals(listOf("a"), posts)
    }

    @Test
    fun `a burst is folded into one post at the end of the gap, the latest winning`() {
        submit("a")
        now += 100
        submit("b")
        submit("c")
        assertEquals(listOf("a"), posts)
        now += 900
        runDue()
        assertEquals(listOf("a", "c"), posts)
    }

    @Test
    fun `a cancelled pending post never lands`() {
        submit("a")
        now += 100
        submit("b")
        gate.cancelPending()
        now += 900
        runDue()
        assertEquals(listOf("a"), posts)
    }

    @Test
    fun `forced posts repeat the same content`() {
        submit("a")
        now += 2_000
        submit("a", force = true)
        assertEquals(listOf("a", "a"), posts)
    }

    @Test
    fun `a clock set back never delays a post longer than the gap`() {
        submit("a")
        now -= 3_600_000
        submit("b")
        assertEquals(1_000L, scheduled.single().first - now)
    }
}

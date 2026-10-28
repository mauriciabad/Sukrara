// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.graph

import app.mauri.sukrara.graph.GraphText
import app.mauri.sukrara.graph.RingSpec
import app.mauri.sukrara.graph.stats.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The ring-order list's edits: drag targets, moves, and the stored order they produce. */
class ReorderTest {
    private val order = RingSpec.DEFAULT_RING_ORDER

    @Test fun `a move shifts the rows between`() {
        assertEquals(listOf("b", "c", "a", "d"), Reorder.moved(listOf("a", "b", "c", "d"), 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), Reorder.moved(listOf("a", "b", "c", "d"), 3, 0))
    }

    @Test fun `a move out of range or onto itself changes nothing`() {
        val l = listOf(1, 2, 3)
        assertEquals(l, Reorder.moved(l, 1, 1))
        assertEquals(l, Reorder.moved(l, -1, 2))
        assertEquals(l, Reorder.moved(l, 0, 3))
    }

    @Test fun `half a row or more crosses a neighbour, never past the ends`() {
        assertEquals(2, Reorder.target(2, 0.4f, 6))
        assertEquals(3, Reorder.target(2, 0.5f, 6))
        assertEquals(1, Reorder.target(2, -0.6f, 6))
        assertEquals(0, Reorder.target(2, -9f, 6))
        assertEquals(5, Reorder.target(2, 9f, 6))
    }

    @Test fun `every reordering is a valid stored ring order`() {
        for (from in order.indices) {
            for (to in order.indices) {
                val moved = Reorder.moved(order, from, to)
                assertNotNull(GraphText.parseRingOrder(GraphText.ringOrderText(moved)))
                assertEquals(moved, GraphText.parseRingOrder(GraphText.ringOrderText(moved)))
            }
        }
        assertEquals(Band.VERY_HIGH, Reorder.moved(order, 5, 0).first())
    }
}

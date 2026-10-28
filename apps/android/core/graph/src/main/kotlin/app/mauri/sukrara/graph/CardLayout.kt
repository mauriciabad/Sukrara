// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.RectF

/**
 * What one [GlucoseCard.draw] laid out: every element by name, its box and what it shows ([El.info]; "(no room)" =
 * decided but left out for room, "[…]" = how it was placed). The cross-surface layout test (contracts/graph/layout)
 * and the screen-reader summary read it; drawing ignores it. [exact] boxes don't depend on the font.
 */
class CardLayout(
    val surface: CardSurface,
    val part: CardPart,
    val style: CardStyle,
    val w: Float,
    val h: Float,
    val d: Float,
) {
    class El(
        val name: String,
        val box: RectF?,
        val info: String,
        val exact: Boolean,
    )

    private val list = ArrayList<El>()
    val elements: List<El> get() = list

    fun add(
        name: String,
        box: RectF? = null,
        info: String = "",
        exact: Boolean = false,
    ) {
        list += El(name, box?.let(::RectF), info, exact)
    }

    fun all(name: String) = list.filter { it.name == name }

    fun first(name: String) = list.firstOrNull { it.name == name }

    companion object {
        /** [px] in dp to the nearest 0.5, as the report writes it on both platforms ("33.5", "40"). */
        fun dp(
            px: Float,
            d: Float,
        ): String {
            val v = Math.round(px / d * 2) / 2.0
            return if (v == Math.floor(v)) "${v.toLong()}" else "$v"
        }
    }
}

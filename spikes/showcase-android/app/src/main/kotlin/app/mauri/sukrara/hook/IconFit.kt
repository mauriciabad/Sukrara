// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.hook

/**
 * The status bar's icon row, as SystemUI fits it (Android 17 `EllipsizingRow.measureIcons`), and how the module makes
 * glucose win the space: plain Kotlin so the tests can run SystemUI's algorithm on widths.
 *
 * SystemUI fits greedily from the priority end (the right with [fromEnd], its status bar); the first icon that doesn't
 * fit becomes the overflow dot and everything behind it is hidden; if the dot then has no room, visible icons are
 * dropped from the far side. The module ([SystemUiHook.hookIconFit]) hands SystemUI the row with our icons moved to the
 * priority end ([permutation]), lets SystemUI's own code fit it, then puts the visible icons back in their on-screen
 * order and keeps our icons (number + trend, or the single "no readings" / "sensor ended" icon) together ([repair]):
 * both shown, or both folded into the one dot.
 */
object IconFit {
    /** What SystemUI decided: indices shown (in on-screen order) and the icon drawn as the dot (null = everything fits). */
    data class Fit(val visible: List<Int>, val overflow: Int?)

    /** SystemUI's `measureIcons` (with its FillDirection), on the measured widths. */
    fun fit(widths: List<Int>, maxWidth: Int, spacing: Int, dotSpace: Int, fromEnd: Boolean = true): Fit {
        var remaining = maxWidth
        val visible = ArrayList<Int>()
        var overflow: Int? = null
        for (i in if (fromEnd) widths.indices.reversed() else widths.indices) {
            val need = if (visible.isEmpty()) widths[i] else spacing + widths[i]
            if (remaining < need) { overflow = i; break }
            remaining -= need
            if (fromEnd) visible.add(0, i) else visible.add(i)
        }
        if (overflow != null) {
            var forDot = if (visible.isEmpty()) dotSpace else dotSpace + spacing
            while (remaining < forDot && visible.isNotEmpty()) {
                val dropped = if (fromEnd) visible.removeAt(0) else visible.removeAt(visible.size - 1)
                overflow = dropped
                remaining += widths[dropped]
                if (visible.isEmpty()) forDot = dotSpace else remaining += spacing
            }
        }
        return Fit(visible, overflow)
    }

    /** The row's width for [fit] (SystemUI's `calculateDimensions`). */
    fun width(widths: List<Int>, fit: Fit, spacing: Int, dotSpace: Int): Int {
        val gaps = (fit.visible.size - 1).coerceAtLeast(0) + if (fit.overflow != null && fit.visible.isNotEmpty()) 1 else 0
        return (if (fit.overflow != null) dotSpace else 0) + fit.visible.sumOf { widths[it] } + gaps * spacing
    }

    /**
     * The order to hand SystemUI: `perm[k]` = the original index at position k. Ours (in their own order) go to the
     * priority end; null when there's nothing to move.
     */
    fun permutation(n: Int, ours: Set<Int>, fromEnd: Boolean = true): IntArray? {
        if (ours.isEmpty() || ours.size == n) return null
        val rest = (0 until n).filter { it !in ours }
        val mine = (0 until n).filter { it in ours }
        val perm = if (fromEnd) rest + mine else mine + rest
        return if (perm == (0 until n).toList()) null else perm.toIntArray()
    }

    /**
     * SystemUI's [fit] of the permuted row (indices into it) → what to show: visible back in on-screen order, and our
     * icons as one unit: if only part of them fit (the row can't hold them even alone), they all fold into the dot.
     * [oursPermuted] = our positions in the permuted row.
     */
    fun repair(fit: Fit, perm: IntArray, oursPermuted: Set<Int>): Fit {
        val shown = fit.visible.count { it in oursPermuted }
        var visible = fit.visible
        var overflow = fit.overflow
        if (shown in 1 until oursPermuted.size) {
            visible = visible.filter { it !in oursPermuted }
            if (overflow == null || overflow !in oursPermuted) overflow = oursPermuted.min()
        }
        return Fit(visible.sortedBy { perm[it] }, overflow)
    }

    /** What the user sees with our icons first in line, in original indices (the tests' view of the module). */
    fun prioritized(widths: List<Int>, ours: Set<Int>, maxWidth: Int, spacing: Int, dotSpace: Int, fromEnd: Boolean = true): Fit {
        val perm = permutation(widths.size, ours, fromEnd) ?: (0 until widths.size).toList().toIntArray()
        val f = repair(fit(perm.map { widths[it] }, maxWidth, spacing, dotSpace, fromEnd), perm,
            perm.indices.filter { perm[it] in ours }.toSet())
        return Fit(f.visible.map { perm[it] }, f.overflow?.let { perm[it] })
    }

    /**
     * The card's visible gap between the number's ink and the trend arrow's ink, as a share of the digits' height (the
     * user, 2026-10-22: number and arrow closer, as on the card). Derived from the renderer
     * ([app.mauri.sukrara.graph.ValueGlyphs] CAP / MARK_GAP / MARK_BOX / ARROW_IN_BOX / the arrow's outline) and the
     * card's font, averaged over the digits and the 5° arrow angles; IconFitTest recomputes it.
     */
    const val CARD_GAP_OF_DIGIT = 0.27f

    /** The status-bar number drawables' digit height over their height (tools/gen-slot-drawables.swift: 12.4 of 15 dp). */
    const val DIGIT_OF_ICON = 12.4f / 15f

    /**
     * How many px the module pulls the trend icon towards the number: from SystemUI's [spacing] between status icons
     * to the card's gap at icons [iconHeight] px tall (the drawables have no padding on those sides). Never apart.
     */
    fun pull(spacing: Int, iconHeight: Int): Int =
        (spacing - Math.round(CARD_GAP_OF_DIGIT * DIGIT_OF_ICON * iconHeight)).coerceAtLeast(0)

    /**
     * Where SystemUI's placement puts the shown icons (x per entry of [fit].visible, the dot first at 0 when there is
     * one) and the row's width, with everything after the icon [pullAfter] moved [pull] px left: how the module brings
     * our trend next to our number while every other gap stays SystemUI's (the row is that much narrower).
     */
    fun place(widths: List<Int>, fit: Fit, spacing: Int, dotSpace: Int, pullAfter: Int? = null, pull: Int = 0): Pair<List<Int>, Int> {
        var x = if (fit.overflow != null) dotSpace + if (fit.visible.isNotEmpty()) spacing else 0 else 0
        val xs = fit.visible.mapIndexed { k, i ->
            val at = x
            x += widths[i] + (if (k < fit.visible.size - 1) spacing else 0) - (if (i == pullAfter && k < fit.visible.size - 1) pull else 0)
            at
        }
        val pulled = pullAfter != null && fit.visible.indexOf(pullAfter).let { it >= 0 && it < fit.visible.size - 1 }
        return xs to width(widths, fit, spacing, dotSpace) - if (pulled) pull else 0
    }

    /**
     * The order to show SystemUI's row of [slots] in (indices into it), or null to keep it: our slots together in the
     * fixed order [ours] (number, then trend: after a System UI restart system_server hands them back in hash order,
     * trend first), after [target] ([insertAfter]) or, without a target (or an unknown one), where the first of ours is.
     */
    fun arrange(slots: List<String?>, ours: List<String>, target: String?, order: List<String>): List<Int>? {
        val mine = ours.mapNotNull { s -> slots.indexOf(s).takeIf { it >= 0 } }
        if (mine.isEmpty()) return null
        val rest = slots.indices.filter { it !in mine }
        val at = target?.takeIf { it.isNotEmpty() }?.let { insertAfter(rest.map { slots[it] }, it, order) }
            ?: rest.count { it < mine.min() }
        val arranged = rest.take(at) + mine + rest.drop(at)
        return arranged.takeIf { it != slots.indices.toList() }
    }

    /**
     * Where "after [target]" puts our icons in SystemUI's row of visible icons ([slots], ours already taken out): right
     * after [target]; when it's hidden, where it would be: after the last visible icon that comes no later than it in
     * the platform's slot order [order] (`config_statusBarIcons`; slots not in it, like other apps' icons, come first,
     * as SystemUI puts them). Null = the target is unknown: leave the row as SystemUI made it.
     */
    fun insertAfter(slots: List<String?>, target: String, order: List<String>): Int? {
        val at = slots.indexOf(target)
        if (at >= 0) return at + 1
        val rank = order.indexOf(target).takeIf { it >= 0 } ?: return null
        return slots.indexOfLast { order.indexOf(it) <= rank } + 1
    }
}

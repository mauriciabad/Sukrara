// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.status

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The root status bar's slot icons ([RootStatusBar.iconPlan]: a number drawable `sb_n…` / `sb_s…` / `sb_lo` / `sb_hi`
 * and, right of it, a trend drawable `sb_t…`) are laid out by SystemUI with no gap between them, so the gap between the
 * digits and the arrow / "no trend" ring is the drawables' own free space: the number's on its right plus the trend's
 * on its left. Without it they touched ("118→", 2026-10-21; the cross-surface tests only saw the icon *names*).
 * spikes/statusbar-android/tools/gen-slot-drawables.swift writes them; this reads the geometry back from the XML.
 */
class SlotDrawablesTest {
    private val dir: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "spikes/showcase-android/app/src/main/res/drawable") }.first { it.isDirectory }

    /** The ink's left and right x (dp) and the drawable's width, from its vector XML. */
    private fun ink(f: File): Triple<Double, Double, Double> {
        val x = f.readText()
        val width = Regex("""android:viewportWidth="([0-9.]+)"""").find(x)!!.groupValues[1].toDouble()
        // the group's shift applies to the paths inside it only (a struck number's bar is outside)
        val group = Regex("""<group android:translateX="(-?[0-9.]+)">(.*?)</group>""", RegexOption.DOT_MATCHES_ALL).find(x)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (p in Regex("""<path ([^>]*)/>""").findAll(x)) {
            val attrs = p.groupValues[1]
            val data = Regex("""android:pathData="([^"]*)"""").find(attrs)!!.groupValues[1]
            val stroke = Regex("""android:strokeWidth="([0-9.]+)"""").find(attrs)?.groupValues?.get(1)?.toDouble() ?: 0.0
            val xs = if (data.contains('A')) {
                // the ring: "M x0,y A r,r 0 1,0 x1,y A …": its two ends across the middle
                Regex("""[MA][^MAZ]*?(-?[0-9.]+),(-?[0-9.]+)(?=[A-Z]|$)""").findAll(data).map { it.groupValues[1].toDouble() }.toList()
            } else Regex("""(-?[0-9.]+),(-?[0-9.]+)""").findAll(data).map { it.groupValues[1].toDouble() }.toList() +
                // the struck bar's horizontal edges
                Regex("""H(-?[0-9.]+)""").findAll(data).map { it.groupValues[1].toDouble() }.toList()
            val shift = if (group != null && p.range.first in group.range) group.groupValues[1].toDouble() else 0.0
            lo = minOf(lo, xs.min() + shift - stroke / 2); hi = maxOf(hi, xs.max() + shift + stroke / 2)
        }
        return Triple(lo, hi, width)
    }

    @Test fun numberAndTrendKeepAGap() {
        val files = dir.listFiles()!!.filter { it.name.startsWith("sb_") && it.name.endsWith(".xml") }
        val (trends, numbers) = files.partition { it.name.startsWith("sb_t") }
        assertTrue("no slot drawables found in $dir", trends.isNotEmpty() && numbers.isNotEmpty())
        val numberRight = numbers.minOf { f -> ink(f).let { it.third - it.second } }
        val trendLeft = trends.associate { f -> f.name to ink(f).first }
        val worst = trendLeft.minBy { it.value }
        val gap = numberRight + worst.value
        assertTrue("number → trend gap %.2f dp (numbers' free right side %.2f, %s's free left side %.2f); want ≥ 1.2 dp"
            .format(gap, numberRight, worst.key, worst.value), gap >= 1.2)
    }
}
